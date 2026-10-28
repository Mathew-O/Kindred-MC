package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One step of a pillar, as a player builds one: jump straight up and, once the feet clear the cell they stood in,
 * place an owned block there and land on it. The same edit policy, claim and durable ledger as {@link NativePlaceAction}
 * cover the block, and exactly one block item is consumed. Refused before anything happens when the cell overhead is
 * not open (the jump would hit its head), when nothing solid is underfoot, when the player is no longer in the cell
 * the step was planned from, or when the hotbar slot holds no block.
 */
final class NativePillarAction extends NativeRecordedAction {
    /** Ticks to rise a block on a jump and land again; past this the step has failed. */
    private static final int JUMP_TICKS = 16;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final BlockPos planned;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private BlockPos cell, support;
    private BlockItem blockItem;
    private ItemStack expectedStack;
    private String dimensionKey;
    private long setupRevision;
    private int jumpTicks = -1;
    /** Ticks spent waiting to land before the step begins: one planned mid-jump starts as the feet touch down. */
    private int groundWait;
    /** Longest it waits to land before a step planned mid-jump. */
    private static final int GROUND_WAIT = 12;
    private boolean placed;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativePillarAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                       Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                       Consumer<Runnable> dispatch, BlockPos planned, int slot) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.planned = planned.toImmutable();
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A pillar needs an owned hotbar slot 0..8");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded pillar step cannot replay after cancellation/restart", true);
        if (admission == null) {
            if (!body.entity().isOnGround() && groundWait++ < GROUND_WAIT) return Step.running(Map.of("waiting", "to land"), false);
            Step refused = prepare();
            if (refused != null) return refused;
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable pillar acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Pillar close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (jumpTicks < 0) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!validAuthorityAndPreconditions() || !body.entity().isOnGround()) {
                result = Step.blocked("Pillar preconditions, claim, or policy no longer valid; nothing placed", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            jumpTicks = 0;
        }
        return jump();
    }

    /** Checks and records the step; a refusal before anything is reserved is returned, else null. */
    private Step prepare() {
        var player = body.entity();
        var world = player.getServerWorld();
        var stack = player.getInventory().getStack(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return Step.blocked("Owned hotbar slot does not hold a placeable block", true);
        blockItem = bi;
        cell = player.getBlockPos().toImmutable();
        if (!cell.equals(planned)) return Step.blocked("The player is no longer where the pillar was planned", true);
        support = cell.down();
        if (!world.isChunkLoaded(cell) || !world.isInBuildLimit(cell.up(2))) return Step.blocked("The pillar would leave loaded build space", true);
        if (!player.isOnGround() || world.getBlockState(support).getCollisionShape(world, support).isEmpty()) {
            return Step.blocked("Nothing solid underfoot to build on", true);
        }
        if (!world.getBlockState(cell).isReplaceable() || !world.getFluidState(cell).isEmpty()) return Step.blocked("The cell it stands in is not open", true);
        // After the step the feet are in the cell above and the head in the one above that: both must be open.
        for (BlockPos over : List.of(cell.up(), cell.up(2))) {
            if (!world.getBlockState(over).getCollisionShape(world, over).isEmpty()) return Step.blocked("No head room to jump up a block", true);
        }
        dimensionKey = world.getRegistryKey().getValue().toString();
        expectedStack = stack.copy();
        setupRevision = setup.get() != null ? setup.get().revision() : 0;
        var resource = Registries.BLOCK.getId(blockItem.getBlock()).toString();
        var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.PLACE, cell, resource, policy.get());
        if (!decision.allowed()) return Step.blocked(decision.reason(), true);
        String claimKey = "block:" + worldId + ":" + dimensionKey + ":" + cell.toShortString();
        claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
        if (claim == null) return Step.blocked("Another task holds this resource claim", true);
        before = InventoryEvidence.capture(body);
        admission = new ActionAdmission(
                ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_pillar",
                        "block=" + resource + "; pos=" + cell.toShortString() + "; setupRevision=" + setupRevision),
                before.description(), writer, dispatch);
        return null;
    }

    /** Up, the block placed under the feet once they clear the cell, and down onto it. */
    private Step jump() {
        var player = body.entity();
        var world = player.getServerWorld();
        try {
            ActionLease current = lease.get();
            if (!placed) {
                if (!validAuthorityAndPreconditions()) return settle(false, "Pillar authority or preconditions changed mid-jump");
                // Centred over the cell: hold still and jump on the first tick only.
                if (!body.move(current, 0, 0, jumpTicks == 0, false, player.getYaw())) return settle(false, "Movement authority denied");
                if (player.getY() >= cell.getY() + 1.0 + 1e-3) {
                    placed = body.placeBlock(current, slot, support, Direction.UP) && world.getBlockState(cell).isOf(blockItem.getBlock());
                    if (!placed) return settle(false, "The block could not be placed under the feet");
                }
            } else if (player.isOnGround() && player.getBlockPos().equals(cell.up())) {
                return settle(true, null);
            } else {
                body.move(current, 0, 0, false, false, player.getYaw());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Pillar interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (++jumpTicks > JUMP_TICKS) return settle(placed && world.getBlockState(cell).isOf(blockItem.getBlock()), "The jump did not rise a block");
        return waiting();
    }

    private Step settle(boolean done, String reason) {
        body.stopInputs();
        var world = body.entity().getServerWorld();
        if (done && world.getBlockState(cell).isOf(blockItem.getBlock())) {
            var after = InventoryEvidence.capture(body);
            if (conserved(after, 1)) {
                result = Step.completed(Map.of("block", Registries.BLOCK.getId(blockItem.getBlock()).toString(), "pos", cell.toShortString()));
                admission.verify(after.description() + "; " + result.progress(), "Native pillar step verified");
            } else {
                unknown = true;
                result = Step.blocked("Pillar conservation check failed; reconcile", true);
                admission.uncertain(result.reason());
            }
        } else if (conserved(InventoryEvidence.capture(body), 0) && world.getBlockState(cell).isReplaceable()) {
            result = Step.blocked(reason != null ? reason : "The pillar step produced no effect", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
        } else {
            unknown = true;
            result = Step.blocked("Pillar effect cannot be verified; reconcile", true);
            admission.uncertain(result.reason());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        if (cell == null || blockItem == null || expectedStack == null) return false;
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey) || !world.isChunkLoaded(cell)) return false;
        if (!world.getBlockState(cell).isReplaceable()) return false;
        var currentSlotStack = player.getInventory().getStack(slot);
        if (currentSlotStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(currentSlotStack, expectedStack)) return false;
        var currentSetup = setup.get();
        if ((currentSetup == null ? 0 : currentSetup.revision()) != setupRevision) return false;
        var resource = Registries.BLOCK.getId(blockItem.getBlock()).toString();
        if (!NativeEdits.decide(player, currentSetup, EditPolicy.Kind.PLACE, cell, resource, policy.get()).allowed()) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    /**
     * At most {@code placed} of the block item gone and nothing else lost. A step takes several ticks, and a drop
     * lying where the player jumps (the block it just cleared overhead) is picked up on the way, as for any player.
     */
    private boolean conserved(InventoryEvidence after, int placed) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == blockItem ? placed : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT || intent == NativePlayerBody.Intent.MOVE) return true;
        return intent == NativePlayerBody.Intent.PLACE && !placed && cell != null && cell.equals(target) && validAuthorityAndPreconditions();
    }

    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }

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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native pillar step verified");
            } else if (before != null && conserved(InventoryEvidence.capture(body), 0) && cell != null
                    && body.entity().getServerWorld().getBlockState(cell).isReplaceable()) {
                admission.noEffect(before.description() + "; nothing owned lost", "Pillar step cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Pillar step cancelled with uncertain outcome");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
