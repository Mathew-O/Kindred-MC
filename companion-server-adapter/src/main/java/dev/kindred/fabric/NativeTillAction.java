package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Blocks;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native tilling action using the durable action ledger (E-13).
 * Evaluates server edit policy, acquires block claims, tills dirt/grass into farmland
 * using an owned hoe, and verifies tool conservation and farmland creation.
 */
final class NativeTillAction extends NativeRecordedAction {
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
    /** Without an assigned area, till under the server's roaming policy: a field of the player's own (M-06a). */
    private final boolean roam;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private String dimensionKey;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeTillAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                     Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                     ClaimRegistry claims, ActionAdmission.Writer writer,
                     Consumer<Runnable> dispatch, BlockPos target, int slot) {
        this(body, lease, worldId, setup, policy, claims, writer, dispatch, target, slot, false);
    }

    NativeTillAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
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
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Tilling needs an owned hotbar slot 0..8");
        var s = setup.get();
        if (!roam && (s == null || s.area() == null)) throw new IllegalArgumentException("No work area is assigned.");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded tilling cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            if (slot < 0 || slot > 8) {
                return Step.blocked("Tilling needs an owned hotbar slot 0..8", true);
            }
            var player = body.entity();
            var world = player.getServerWorld();
            var stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof HoeItem)) {
                return Step.blocked("Owned hotbar slot does not hold a hoe", true);
            }

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Tilling target is in an unloaded chunk", true);
            }

            var state = world.getBlockState(target);
            if (!state.isOf(Blocks.DIRT) && !state.isOf(Blocks.GRASS_BLOCK)
                    && !state.isOf(Blocks.DIRT_PATH) && !state.isOf(Blocks.COARSE_DIRT)) {
                return Step.blocked("Target block cannot be tilled into farmland", true);
            }
            if (!world.getBlockState(target.up()).isAir() && !world.getBlockState(target.up()).isReplaceable()) {
                return Step.blocked("Space above tilling target is obstructed", true);
            }
            if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
                return Step.blocked("Tilling target is out of reach", true);
            }

            var currentSetup = setup.get();
            if (!roam && (currentSetup == null || currentSetup.area() == null)) {
                return Step.blocked("No work area is assigned.", true);
            }
            setupRevision = currentSetup != null ? currentSetup.revision() : 0;
            dimensionKey = world.getRegistryKey().getValue().toString();
            var decision = decide(currentSetup);
            if (!decision.allowed()) {
                return Step.blocked(decision.reason(), true);
            }

            claimKey = "block:" + worldId + ":" + dimensionKey + ":" + target.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_till",
                            "target=" + target.toShortString() + "; slot=" + slot + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable tilling acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Tilling close outcome uncertain", true);
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
            result = Step.blocked("Tilling preconditions, claim, or policy no longer valid; no soil tilled", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean tilled = body.tillBlock(lease.get(), slot, target);
            var world = body.entity().getServerWorld();
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during tilling; reconcile", true);
            } else if (tilled && world.getBlockState(target).isOf(Blocks.FARMLAND)) {
                var after = InventoryEvidence.capture(body);
                if (verifyConservation(before, after)) {
                    result = Step.completed(Map.of(
                            "tilled", "true",
                            "x", String.valueOf(target.getX()),
                            "y", String.valueOf(target.getY()),
                            "z", String.valueOf(target.getZ())));
                    admission.verify(after.description() + "; " + result.progress(), "Native soil tilling verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Tilling conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body)) {
                result = Step.blocked("Soil tilling produced no effect", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native tilling effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Tilling action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(failure.getMessage());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        if (!world.isChunkLoaded(target)) return false;

        var state = world.getBlockState(target);
        boolean tillable = state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.FARMLAND);
        if (!tillable) return false;
        if (!world.getBlockState(target.up()).isAir() && !world.getBlockState(target.up()).isReplaceable()) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) return false;

        if (before != null && !before.matches(body)) return false;
        var currentSlotStack = player.getInventory().getStack(slot);
        if (currentSlotStack.isEmpty() || !(currentSlotStack.getItem() instanceof HoeItem)) return false;

        var currentSetup = setup.get();
        if ((currentSetup != null ? currentSetup.revision() : 0) != setupRevision) return false;
        if (!roam && (currentSetup == null || currentSetup.area() == null)) return false;
        if (!decide(currentSetup).allowed()) return false;

        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    /** The assigned area's rules for farmland there; roaming without one, the server's roaming policy. */
    private EditPolicy.Decision decide(WorkSetup currentSetup) {
        if (currentSetup != null && currentSetup.area() != null) {
            var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
            return EditPolicy.evaluate(currentSetup, EditPolicy.Kind.PLACE, dimensionKey, point, "minecraft:farmland", policy.get());
        }
        return NativeEdits.farm(body.entity(), target, policy.get());
    }

    private boolean verifyConservation(InventoryEvidence before, InventoryEvidence after) {
        if (before.stacks().length != after.stacks().length) return false;
        for (int i = 0; i < before.stacks().length; i++) {
            ItemStack b = before.stacks()[i];
            ItemStack a = after.stacks()[i];
            if (i == slot) {
                if (a.isEmpty()) {
                    if (!(b.getItem() instanceof HoeItem)) return false;
                } else {
                    if (b.getItem() != a.getItem() || a.getCount() != 1) return false;
                    if (a.getDamage() < b.getDamage()) return false;
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
        if (intent == NativePlayerBody.Intent.TILL) {
            return targetPos == null || target.equals(targetPos);
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native soil tilling verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Tilling cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Tilling cancelled with uncertain outcome");
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
