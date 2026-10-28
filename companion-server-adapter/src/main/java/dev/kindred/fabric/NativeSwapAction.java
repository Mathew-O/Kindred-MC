package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player swapping two slots of its own inventory, written down first and checked after
 * so every item is still there.
 *
 * Technical: Checkpointed native inventory swap action using the durable action ledger.
 * Admits durably before effect, verifies exact 100% item component and count conservation across all slots.
 */
final class NativeSwapAction extends NativeRecordedAction {
    private final SkillBody operation;
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int sourceSlot;
    private final int targetSlot;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeSwapAction(SkillBody operation, NativePlayerBody body, Supplier<ActionLease> lease,
                     ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     int sourceSlot, int targetSlot) {
        this.operation = operation;
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.sourceSlot = sourceSlot;
        this.targetSlot = targetSlot;
    }

    /** Moves the swap on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded inventory swap cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_swap",
                            "source=" + sourceSlot + "; target=" + targetSlot),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable swap acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Swap close outcome uncertain", true);
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
        if (sourceSlot == targetSlot || sourceSlot < 0 || sourceSlot >= 36 || targetSlot < 0 || targetSlot >= 36) {
            if (admission.take().isEmpty()) return waiting();
            result = Step.blocked("Self-swap is a no-effect request; no transfer issued", true);
            admission.noEffect(before.description() + "; self-swap / invalid range", "Self-swap or invalid slot range");
            return waiting();
        }
        if (!before.matches(body)) {
            result = Step.blocked("Owned inventory changed before swap dispatch; no transfer issued", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            result = operation.tick(args, Optional.empty(), true);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during swap transfer; reconcile", true);
            } else if (result.status() == Status.COMPLETED && !NativePlayerSkills.uncertain(operation)) {
                var after = InventoryEvidence.capture(body);
                if (verifySwapConservation(before, after, sourceSlot, targetSlot)) {
                    admission.verify(after.description() + "; " + result.progress(), "Native slot swap conservation verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Swap conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body) && !NativePlayerSkills.uncertain(operation)) {
                result = Step.blocked(result.reason() == null ? "Swap produced no verified effect" : result.reason(), true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native swap effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Swap action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private static boolean verifySwapConservation(InventoryEvidence before, InventoryEvidence after, int src, int dst) {
        if (before.stacks().length != after.stacks().length) return false;
        ItemStack beforeSrc = before.stacks()[src];
        ItemStack beforeDst = before.stacks()[dst];
        ItemStack afterSrc = after.stacks()[src];
        ItemStack afterDst = after.stacks()[dst];
        if (!ItemStack.areItemsAndComponentsEqual(beforeSrc, afterDst) || beforeSrc.getCount() != afterDst.getCount()) {
            return false;
        }
        if (!ItemStack.areItemsAndComponentsEqual(beforeDst, afterSrc) || beforeDst.getCount() != afterSrc.getCount()) {
            return false;
        }
        for (int i = 0; i < before.stacks().length; i++) {
            if (i == src || i == dst) continue;
            ItemStack b = before.stacks()[i];
            ItemStack a = after.stacks()[i];
            if (!ItemStack.areItemsAndComponentsEqual(b, a) || b.getCount() != a.getCount()) {
                return false;
            }
        }
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && intent == NativePlayerBody.Intent.SWAP;
    }

    @Override
    boolean uncertain() {
        return unknown || NativePlayerSkills.uncertain(operation);
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native inventory swap verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Swap cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Swap cancelled with uncertain outcome");
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
        return operation.settled();
    }
}
