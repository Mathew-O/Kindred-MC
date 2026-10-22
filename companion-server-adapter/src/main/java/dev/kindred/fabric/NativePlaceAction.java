package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.item.BlockItem;
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
 * Checkpointed native block placement action using the durable action ledger.
 * Evaluates server edit policy, acquires block claims, consumes exactly one block item
 * from the owned hotbar slot, and verifies placement and item conservation.
 */
final class NativePlaceAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;
    private final Direction side;
    private final int slot;
    private final NativePlayerBody.PlaceLook aim;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private BlockPos placedPos;
    private BlockItem blockItem;
    private boolean targetWasReplaceable;
    private String dimensionKey;
    private ItemStack expectedStack;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativePlaceAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                      Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                      ClaimRegistry claims, ActionAdmission.Writer writer,
                      Consumer<Runnable> dispatch, BlockPos target, Direction side, int slot) {
        this(body, lease, worldId, setup, policy, claims, writer, dispatch, target, side, slot, null);
    }

    /** With {@code aim}, the look and the point clicked are the planner's, so the block takes the state it was planned in. */
    NativePlaceAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                      Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                      ClaimRegistry claims, ActionAdmission.Writer writer,
                      Consumer<Runnable> dispatch, BlockPos target, Direction side, int slot, NativePlayerBody.PlaceLook aim) {
        this.aim = aim;
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.target = target.toImmutable();
        this.side = side != null ? side : Direction.UP;
        this.slot = slot;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Placement needs an owned hotbar slot 0..8");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded placement cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            if (slot < 0 || slot > 8) {
                return Step.blocked("Placement needs an owned hotbar slot 0..8", true);
            }
            var player = body.entity();
            var world = player.getServerWorld();
            var stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) {
                return Step.blocked("Owned hotbar slot does not hold a placeable block", true);
            }
            blockItem = bi;

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Placement target is in an unloaded chunk", true);
            }

            targetWasReplaceable = world.getBlockState(target).isReplaceable();
            if (targetWasReplaceable) {
                placedPos = target;
            } else {
                placedPos = target.offset(side);
            }
            dimensionKey = world.getRegistryKey().getValue().toString();
            expectedStack = stack.copy();
            setupRevision = setup.get() != null ? setup.get().revision() : 0;

            if (!world.isChunkLoaded(placedPos) || !world.isInBuildLimit(placedPos)) {
                return Step.blocked("Placement location is unloaded or outside build limits", true);
            }
            if (!world.getBlockState(placedPos).isReplaceable()) {
                return Step.blocked("Placement location is already occupied by a non-replaceable block", true);
            }
            if (player.getEyePos().squaredDistanceTo(placedPos.getX() + 0.5, placedPos.getY() + 0.5, placedPos.getZ() + 0.5) > 25.0) {
                return Step.blocked("Placement target is out of reach", true);
            }

            var point = new WorkSetup.BlockPoint(placedPos.getX(), placedPos.getY(), placedPos.getZ());
            var resource = Registries.BLOCK.getId(blockItem.getBlock()).toString();
            var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.PLACE, placedPos, resource, policy.get());
            if (!decision.allowed()) {
                return Step.blocked(decision.reason(), true);
            }

            claimKey = "block:" + worldId + ":" + dimensionKey + ":" + placedPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_place",
                            "block=" + resource + "; pos=" + placedPos.toShortString() + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable place acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Place close outcome uncertain", true);
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
            result = Step.blocked("Placement preconditions, claim, or policy no longer valid; no block placed", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean placed = body.placeBlock(lease.get(), slot, target, side, aim);
            var world = body.entity().getServerWorld();
            var state = world.getBlockState(placedPos);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during block placement; reconcile", true);
            } else if (placed && (state.isOf(blockItem.getBlock()) || state.getBlock().asItem() == blockItem)) {
                // The block an item places may be another of its blocks: a torch on a wall is a wall torch.
                var after = InventoryEvidence.capture(body);
                if (verifyConservation(before, after, blockItem)) {
                    result = Step.completed(Map.of(
                            "block", Registries.BLOCK.getId(state.getBlock()).toString(),
                            "state", net.minecraft.command.argument.BlockArgumentParser.stringifyBlockState(state),
                            "pos", placedPos.toShortString()));
                    admission.verify(after.description() + "; " + result.progress(), "Native block placement verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Placement conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body) && world.getBlockState(placedPos).isReplaceable()) {
                result = Step.blocked("Block placement produced no effect or was obstructed", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native placement effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Placement action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        if (placedPos == null || blockItem == null || expectedStack == null) return false;
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        if (!world.isChunkLoaded(target) || !world.isChunkLoaded(placedPos) || !world.isInBuildLimit(placedPos)) return false;

        // Verify target replaceability has not drifted away from the planned destination
        boolean currentTargetReplaceable = world.getBlockState(target).isReplaceable();
        if (currentTargetReplaceable != targetWasReplaceable) return false;
        BlockPos currentResolved = currentTargetReplaceable ? target : target.offset(side);
        if (!currentResolved.equals(placedPos)) return false;

        if (!world.getBlockState(placedPos).isReplaceable()) return false;
        if (player.getEyePos().squaredDistanceTo(placedPos.getX() + 0.5, placedPos.getY() + 0.5, placedPos.getZ() + 0.5) > 25.0) return false;

        if (before != null && !before.matches(body)) return false;
        var currentSlotStack = player.getInventory().getStack(slot);
        if (currentSlotStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(currentSlotStack, expectedStack)) return false;

        var currentSetup = setup.get();
        if ((currentSetup == null ? 0 : currentSetup.revision()) != setupRevision) return false;
        var resource = Registries.BLOCK.getId(blockItem.getBlock()).toString();
        var decision = NativeEdits.decide(player, currentSetup, EditPolicy.Kind.PLACE, placedPos, resource, policy.get());
        if (!decision.allowed()) return false;

        if (claim == null || !claims.holdsAll(claim, System.currentTimeMillis())) return false;
        return true;
    }

    private boolean verifyConservation(InventoryEvidence before, InventoryEvidence after, BlockItem blockItem) {
        if (before.stacks().length != after.stacks().length) return false;
        for (int i = 0; i < before.stacks().length; i++) {
            ItemStack b = before.stacks()[i];
            ItemStack a = after.stacks()[i];
            if (i == slot) {
                if (!ItemStack.areItemsAndComponentsEqual(b, expectedStack)) return false;
                if (b.getCount() == 1) {
                    if (!a.isEmpty()) return false;
                } else {
                    if (a.getCount() != b.getCount() - 1 || !ItemStack.areItemsAndComponentsEqual(a, expectedStack)) {
                        return false;
                    }
                }
            } else {
                if (!ItemStack.areItemsAndComponentsEqual(b, a) || b.getCount() != a.getCount()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT) return true;
        if (intent == NativePlayerBody.Intent.PLACE) {
            return target != null && target.equals(placedPos) && validAuthorityAndPreconditions();
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native block placement verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Placement cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Placement cancelled with uncertain outcome");
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
