package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Tosses owned items toward a nearby human teammate (QOL-05), or with no recipient drops them at the player's feet. */
final class NativeTossAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int sourceSlot;
    private final int count;
    private final UUID targetPlayerId;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeTossAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer,
                     Consumer<Runnable> dispatch, int sourceSlot, int count, UUID targetPlayerId) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.sourceSlot = sourceSlot;
        this.count = count;
        this.targetPlayerId = targetPlayerId;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded toss cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            if (sourceSlot < 0 || sourceSlot >= 36 || count <= 0) {
                return Step.blocked("Invalid toss slot or count", true);
            }
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_toss",
                            "slot=" + sourceSlot + "; count=" + count + "; target=" + targetPlayerId),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable toss acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Toss close outcome uncertain", true);
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
        if (!before.matches(body)) {
            result = Step.blocked("Owned inventory changed before toss dispatch; no transfer issued", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();

        try {
            var player = body.entity();
            var world = player.getServerWorld();
            var target = targetPlayerId == null ? null : world.getPlayerByUuid(targetPlayerId);
            if (targetPlayerId == null) {
                // Dropping surplus where the player stands, as a person clears a full inventory.
                if (body.discard(lease.get(), sourceSlot, count) && verifyTossConservation(before, InventoryEvidence.capture(body), sourceSlot, count)) {
                    result = Step.completed(Map.of("dropped", String.valueOf(count), "slot", String.valueOf(sourceSlot)));
                    admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native drop conservation verified");
                } else if (before.matches(body)) {
                    result = Step.blocked("Drop produced no verified effect", true);
                    admission.noEffect(before.description(), result.reason());
                } else {
                    unknown = true;
                    result = Step.blocked("Drop conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (!(target instanceof ServerPlayerEntity human) || !human.isAlive()) {
                result = Step.blocked("Target player is not available", true);
                admission.noEffect(before.description(), result.reason());
            } else if (player.squaredDistanceTo(human) > 16.0) {
                result = Step.blocked("Target player is out of range", true);
                admission.noEffect(before.description(), result.reason());
            } else if (body.tossTowardPlayer(lease.get(), sourceSlot, count, human)) {
                var after = InventoryEvidence.capture(body);
                if (verifyTossConservation(before, after, sourceSlot, count)) {
                    result = Step.completed(Map.of(
                            "tossed", String.valueOf(count),
                            "slot", String.valueOf(sourceSlot),
                            "target", targetPlayerId.toString()));
                    admission.verify(after.description() + "; " + result.progress(), "Native toss conservation verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Toss conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body)) {
                result = Step.blocked("Toss produced no verified effect", true);
                admission.noEffect(before.description(), result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native toss effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Toss action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private static boolean verifyTossConservation(InventoryEvidence before, InventoryEvidence after, int slot, int tossed) {
        if (before.stacks().length != after.stacks().length) return false;
        int totalBefore = 0;
        int totalAfter = 0;
        for (int i = 0; i < before.stacks().length; i++) {
            var b = before.stacks()[i];
            var a = after.stacks()[i];
            totalBefore += b.getCount();
            totalAfter += a.getCount();
            if (i == slot) {
                if (!ItemStack.areItemsAndComponentsEqual(b, a) && !a.isEmpty()) return false;
                if (b.getCount() - a.getCount() != tossed) return false;
            } else if (b.getCount() != a.getCount() || !ItemStack.areItemsAndComponentsEqual(b, a)) {
                return false;
            }
        }
        return totalBefore - totalAfter == tossed;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && intent == NativePlayerBody.Intent.TOSS;
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
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(InventoryEvidence.capture(body).description(), "Native toss verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description(), "Toss cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Toss cancelled with uncertain outcome");
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
