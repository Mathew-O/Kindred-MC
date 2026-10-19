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
 * Recorded native crop harvest (E-13). The mature crop is broken through the ordinary survival break path, so
 * its own loot table decides the yield; the player then walks over its attributable drops and, when asked,
 * replants with an owned seed through normal item use. Nothing is added to the inventory except by pickup.
 */
final class NativeHarvestAction extends NativeRecordedAction {
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
    private final boolean replant;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private String dimensionKey;
    private String cropBlockId;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;
    private long priorReceipt;
    private boolean broken;
    private int collectTicks;
    private final java.util.Set<UUID> preExistingDrops = new java.util.HashSet<>();
    private final java.util.Set<UUID> trackedDrops = new java.util.HashSet<>();
    private boolean replantAttempted;
    private static final int COLLECT_TICKS = 80;

    NativeHarvestAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                        Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                        ClaimRegistry claims, ActionAdmission.Writer writer,
                        Consumer<Runnable> dispatch, BlockPos target, int slot, boolean replant) {
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
        this.replant = replant;
        var s = setup.get();
        if (s == null || s.area() == null) throw new IllegalArgumentException("No work area is assigned.");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded harvest cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            var player = body.entity();
            var world = player.getServerWorld();

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Harvest target is in an unloaded chunk", true);
            }

            var cropState = world.getBlockState(target);
            if (!(cropState.getBlock() instanceof CropBlock crop) || !crop.isMature(cropState)) {
                return Step.blocked("Target block is not a mature crop ready for harvest", true);
            }
            if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
                return Step.blocked("Harvest target is out of reach", true);
            }

            var currentSetup = setup.get();
            if (currentSetup == null || currentSetup.area() == null) {
                return Step.blocked("No work area is assigned.", true);
            }
            var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
            if (!currentSetup.area().contains(point)) {
                return Step.blocked("Harvest target is outside assigned work area", true);
            }

            setupRevision = currentSetup.revision();
            dimensionKey = world.getRegistryKey().getValue().toString();
            cropBlockId = Registries.BLOCK.getId(cropState.getBlock()).toString();

            var breakDecision = EditPolicy.evaluate(currentSetup, EditPolicy.Kind.BREAK, dimensionKey, point, cropBlockId, policy.get());
            if (!breakDecision.allowed()) {
                return Step.blocked(breakDecision.reason(), true);
            }

            claimKey = "block:" + worldId + ":" + dimensionKey + ":" + target.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_harvest",
                            "crop=" + cropBlockId + "; pos=" + target.toShortString() + "; replant=" + replant + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable harvest acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Harvest close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) {
                return result;
            }
            return waiting();
        }
        if (admission.phase() == ActionAdmission.Phase.READY) {
            if (!validAuthorityAndPreconditions()) {
                result = Step.blocked("Harvest preconditions, claim, or policy no longer valid; no harvest performed", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            var world = body.entity().getServerWorld();
            priorReceipt = body.miningReceipt().map(NativePlayerBody.MiningReceipt::sequence).orElse(0L);
            for (var drop : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(target).expand(3.0), d -> true)) {
                preExistingDrops.add(drop.getUuid());
            }
            boolean started;
            try {
                started = body.harvestCrop(lease.get(), slot, target);
            } catch (RuntimeException failure) {
                started = false;
            }
            if (!started) {
                if (before.matches(body) && cropIntact()) {
                    result = Step.blocked("Native harvest controls denied; nothing was broken", true);
                    admission.noEffect(before.description() + "; crop intact", result.reason());
                } else {
                    unknown = true;
                    result = Step.blocked("Native harvest effect cannot be verified; reconcile", true);
                    admission.uncertain(result.reason());
                }
            }
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.ISSUED) return waiting();
        var player = body.entity();
        var world = player.getServerWorld();
        if (!broken) {
            if (breakReceipt()) {
                broken = true;
                body.stopInputs();
                for (var drop : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(target).expand(3.0), d -> true)) {
                    if (!preExistingDrops.contains(drop.getUuid())) trackedDrops.add(drop.getUuid());
                }
            } else if (cropIntact()) {
                return waiting();
            } else {
                // The crop changed without our attributable break; no drops of ours exist to claim.
                body.stopInputs();
                result = Step.blocked("Crop changed before an attributable break; nothing harvested", true);
                if (before.matches(body)) admission.noEffect(before.description() + "; crop changed externally", result.reason());
                else { unknown = true; admission.uncertain(result.reason()); }
                return waiting();
            }
        }
        // Walk into ordinary pickup range of our own drops; pickup itself is vanilla collision.
        net.minecraft.entity.ItemEntity next = null;
        for (UUID id : trackedDrops) {
            if (world.getEntity(id) instanceof net.minecraft.entity.ItemEntity drop && drop.isAlive()) { next = drop; break; }
        }
        if (next != null && collectTicks++ < COLLECT_TICKS) {
            double dx = next.getX() - player.getX();
            double dz = next.getZ() - player.getZ();
            var ahead = net.minecraft.util.math.BlockPos.ofFloored(player.getX() + Math.signum(dx) * 0.6, player.getY(), player.getZ() + Math.signum(dz) * 0.6);
            if (dx * dx + dz * dz > 0.36 && world.getFluidState(ahead).isEmpty()) {
                body.move(lease.get(), 0.6f, 0, false, false, (float) Math.toDegrees(Math.atan2(-dx, dz)));
            } else {
                body.stopInputs();
            }
            return waiting();
        }
        body.stopInputs();
        if (replant && !replantAttempted) {
            replantAttempted = true;
            int seedSlot = seedHotbarSlot();
            BlockPos farmland = target.down();
            if (seedSlot >= 0 && world.getBlockState(farmland).isOf(Blocks.FARMLAND) && world.getBlockState(target).isAir()) {
                body.plantCrop(lease.get(), seedSlot, farmland);
            }
        }
        boolean replanted = world.getBlockState(target).getBlock() instanceof CropBlock;
        var after = InventoryEvidence.capture(body);
        result = Step.completed(Map.of(
                "harvested", cropBlockId,
                "pos", target.toShortString(),
                "replanted", String.valueOf(replanted)));
        admission.verify(after.description() + "; nativeReceipt=" + priorReceipt + "+; dropsTracked=" + trackedDrops.size()
                + "; " + result.progress(), "Native crop break with vanilla loot and collision pickup");
        return waiting();
    }

    private boolean breakReceipt() {
        return body.miningReceipt().filter(r -> r.sequence() > priorReceipt && r.position().equals(target)).isPresent();
    }

    private boolean cropIntact() {
        var state = body.entity().getServerWorld().getBlockState(target);
        return state.getBlock() instanceof CropBlock crop && crop.isMature(state);
    }

    /** Hotbar slot holding the item that replants this crop, or -1. */
    private int seedHotbarSlot() {
        var seed = switch (cropBlockId) {
            case "minecraft:carrots" -> net.minecraft.item.Items.CARROT;
            case "minecraft:potatoes" -> net.minecraft.item.Items.POTATO;
            case "minecraft:beetroots" -> net.minecraft.item.Items.BEETROOT_SEEDS;
            case "minecraft:wheat" -> net.minecraft.item.Items.WHEAT_SEEDS;
            default -> null;
        };
        if (seed == null) return -1;
        for (int s = 0; s < 9; s++) if (body.entity().getInventory().getStack(s).isOf(seed)) return s;
        return -1;
    }

    private boolean validAuthorityAndPreconditions() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        if (!world.isChunkLoaded(target)) return false;

        var state = world.getBlockState(target);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMature(state)) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) return false;

        var currentSetup = setup.get();
        if (currentSetup == null || currentSetup.revision() != setupRevision) return false;
        var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
        var decision = EditPolicy.evaluate(currentSetup, EditPolicy.Kind.BREAK, dimensionKey, point, cropBlockId, policy.get());
        if (!decision.allowed()) return false;

        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos targetPos) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT) return true;
        if (intent == NativePlayerBody.Intent.MINE) return !broken && target.equals(targetPos);
        if (intent == NativePlayerBody.Intent.MOVE) return broken && !replantAttempted;
        if (intent == NativePlayerBody.Intent.PLANT) return broken && replantAttempted && target.equals(targetPos);
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native crop harvest verified");
            } else if (broken) {
                // Our attributable break happened; anything gained since came from vanilla pickup of its drops.
                admission.verify(InventoryEvidence.capture(body).description() + "; collection interrupted",
                        "Native crop break observed; drop collection interrupted");
            } else if (before != null && before.matches(body) && cropIntact()) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Harvest cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Harvest cancelled with uncertain outcome");
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
