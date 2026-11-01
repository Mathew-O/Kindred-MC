package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player sowing one seed from its own hotbar on farmland, as a human does. It checks it
 * may plant there, uses exactly one seed, and afterwards checks that the crop is planted and nothing else it
 * carries changed.
 *
 * Technical: Checkpointed native crop sowing action using the durable action ledger (E-13).
 * Evaluates server edit policy, acquires block claims, consumes exactly 1 seed item
 * from the owned hotbar slot, and verifies crop placement and item conservation.
 */
final class NativePlantAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;
    private final int slot;
    /** Without an assigned area, sow under the server's roaming policy: a field of the player's own (M-06a). */
    private final boolean roam;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private BlockPos cropPos;
    private Item seedItem;
    private ItemStack expectedStack;
    private String dimensionKey;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    /** Sowing the seed in a hotbar slot at the given spot, inside its assigned area. */
    NativePlantAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                     Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                     ClaimRegistry claims, ActionAdmission.Writer writer,
                     Consumer<Runnable> dispatch, BlockPos target, int slot) {
        this(body, lease, worldId, setup, policy, claims, writer, dispatch, target, slot, false);
    }

    /**
     * As above; when roaming, in a field of its own outside any assigned area, under the server's rules for
     * roaming players.
     */
    NativePlantAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                     Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                     ClaimRegistry claims, ActionAdmission.Writer writer,
                     Consumer<Runnable> dispatch, BlockPos target, int slot, boolean roam) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.target = target.toImmutable();
        this.slot = slot;
        this.roam = roam;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Planting needs an owned hotbar slot 0..8");
        var s = setup.get();
        if (!roam && (s == null || s.area() == null)) throw new IllegalArgumentException("No work area is assigned.");
    }

    /** Moves the planting on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded planting cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            if (slot < 0 || slot > 8) {
                return Step.blocked("Planting needs an owned hotbar slot 0..8", true);
            }
            var player = body.entity();
            var world = player.getServerWorld();
            var stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                return Step.blocked("Owned hotbar slot is empty", true);
            }
            var item = stack.getItem();
            boolean isPlantable = item == Items.WHEAT_SEEDS || item == Items.CARROT
                    || item == Items.POTATO || item == Items.BEETROOT_SEEDS
                    || item == Items.PUMPKIN_SEEDS || item == Items.MELON_SEEDS;
            if (!isPlantable) {
                return Step.blocked("Owned hotbar slot does not hold a plantable seed or crop", true);
            }

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Planting target is in an unloaded chunk", true);
            }

            var farmlandState = world.getBlockState(target);
            if (!farmlandState.isOf(Blocks.FARMLAND)) {
                return Step.blocked("Planting target is not farmland", true);
            }

            cropPos = target.up();
            if (!world.isChunkLoaded(cropPos) || !world.isInBuildLimit(cropPos)) {
                return Step.blocked("Crop location is unloaded or outside build limits", true);
            }
            if (!world.getBlockState(cropPos).isAir() && !world.getBlockState(cropPos).isReplaceable()) {
                return Step.blocked("Space above farmland is already occupied", true);
            }
            if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
                return Step.blocked("Planting target is out of reach", true);
            }

            var currentSetup = setup.get();
            if (!roam && (currentSetup == null || currentSetup.area() == null)) {
                return Step.blocked("No work area is assigned.", true);
            }
            setupRevision = currentSetup != null ? currentSetup.revision() : 0;
            dimensionKey = world.getRegistryKey().getValue().toString();
            seedItem = item;
            expectedStack = stack.copy();

            String cropBlockId = item == Items.CARROT ? "minecraft:carrots" :
                    item == Items.POTATO ? "minecraft:potatoes" :
                    item == Items.BEETROOT_SEEDS ? "minecraft:beetroots" :
                    item == Items.PUMPKIN_SEEDS ? "minecraft:pumpkin_stem" :
                    item == Items.MELON_SEEDS ? "minecraft:melon_stem" :
                    "minecraft:wheat";

            var decision = decide(currentSetup, cropBlockId);
            if (!decision.allowed()) {
                return Step.blocked(decision.reason(), true);
            }

            claimKey = "block:" + worldId + ":" + dimensionKey + ":" + cropPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_plant",
                            "crop=" + cropBlockId + "; pos=" + cropPos.toShortString() + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable planting acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Planting close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) {
                return result;
            }
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!validAuthorityAndPreconditions()) {
            result = Step.blocked("Planting preconditions, claim, or policy no longer valid; no seed sown", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean planted = body.plantCrop(lease.get(), slot, target);
            var world = body.entity().getServerWorld();
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during planting; reconcile", true);
            } else if (planted && (world.getBlockState(cropPos).getBlock() instanceof CropBlock
                    || world.getBlockState(cropPos).isOf(Blocks.PUMPKIN_STEM)
                    || world.getBlockState(cropPos).isOf(Blocks.MELON_STEM))) {
                var after = InventoryEvidence.capture(body);
                if (verifyConservation(before, after, seedItem)) {
                    result = Step.completed(Map.of(
                            "planted", Registries.ITEM.getId(seedItem).toString(),
                            "pos", cropPos.toShortString()));
                    admission.verify(after.description() + "; " + result.progress(), "Native crop sowing verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Planting conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body) && world.getBlockState(cropPos).isAir()) {
                result = Step.blocked("Crop sowing produced no effect", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native planting effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Planting action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(failure.getMessage());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        if (!world.isChunkLoaded(target) || !world.isChunkLoaded(cropPos) || !world.isInBuildLimit(cropPos)) return false;

        if (!world.getBlockState(target).isOf(Blocks.FARMLAND)) return false;
        if (!world.getBlockState(cropPos).isAir() && !world.getBlockState(cropPos).isReplaceable()) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) return false;

        if (before != null && !before.matches(body)) return false;
        var currentSlotStack = player.getInventory().getStack(slot);
        if (currentSlotStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(currentSlotStack, expectedStack)) return false;

        var currentSetup = setup.get();
        if ((currentSetup != null ? currentSetup.revision() : 0) != setupRevision) return false;
        if (!roam && (currentSetup == null || currentSetup.area() == null)) return false;
        String cropBlockId = seedItem == Items.CARROT ? "minecraft:carrots" :
                seedItem == Items.POTATO ? "minecraft:potatoes" :
                seedItem == Items.BEETROOT_SEEDS ? "minecraft:beetroots" :
                seedItem == Items.PUMPKIN_SEEDS ? "minecraft:pumpkin_stem" :
                seedItem == Items.MELON_SEEDS ? "minecraft:melon_stem" :
                "minecraft:wheat";
        if (!decide(currentSetup, cropBlockId).allowed()) return false;

        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    /** The assigned area's rules for the crop there; roaming without one, the server's roaming policy. */
    private EditPolicy.Decision decide(WorkSetup currentSetup, String cropBlockId) {
        if (currentSetup != null && currentSetup.area() != null) {
            var point = new WorkSetup.BlockPoint(cropPos.getX(), cropPos.getY(), cropPos.getZ());
            return EditPolicy.evaluate(currentSetup, EditPolicy.Kind.PLACE, dimensionKey, point, cropBlockId, policy.get());
        }
        return NativeEdits.farm(body.entity(), cropPos, policy.get());
    }

    private boolean verifyConservation(InventoryEvidence before, InventoryEvidence after, Item seedItem) {
        if (before.stacks().length != after.stacks().length) return false;
        for (int i = 0; i < before.stacks().length; i++) {
            ItemStack b = before.stacks()[i];
            ItemStack a = after.stacks()[i];
            if (i == slot) {
                if (b.getItem() != seedItem) return false;
                if (b.getCount() == 1) {
                    if (!a.isEmpty()) return false;
                } else {
                    if (a.getCount() != b.getCount() - 1 || a.getItem() != seedItem) return false;
                }
            } else {
                if (b.getCount() != a.getCount() || !ItemStack.areItemsAndComponentsEqual(b, a)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos targetPos) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT) return true;
        if (intent == NativePlayerBody.Intent.PLANT) {
            return targetPos == null || target.equals(targetPos) || (cropPos != null && cropPos.equals(targetPos));
        }
        return false;
    }

    @Override
    boolean uncertain() {
        return unknown;
    }

    @Override
    ActionRecord snapshot() {
        return admission == null ? null : admission.snapshot();
    }

    @Override
    String phase() {
        return admission == null ? "NOT_PREPARED" : admission.phase().name();
    }

    private Step waiting() {
        return Step.running(Map.of("durableAction", phase()), false);
    }

    @Override
    CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (claim != null) {
            claims.release(claim);
            claim = null;
        }
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native crop sowing verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Planting cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Planting cancelled with uncertain outcome");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override
    public void halt() {
        close();
    }

    @Override
    public boolean settled() {
        return true;
    }
}
