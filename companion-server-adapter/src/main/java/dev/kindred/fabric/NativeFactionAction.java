package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: an AI player's request in a faction world (founding or joining a faction, claiming or capturing the
 * chunk it stands in, selling its spare blocks), made only when it is its turn to act, written down first, and never
 * made again after a stop or a restart.
 *
 * Technical: The {@code native_faction} skill (M-06i). It is admitted through the action ledger like every recorded
 * skill, under the player's lease. At dispatch it hands the request to {@code act}, which goes through the same
 * {@code FactionsService} or {@code MarketDesk} calls as a person's command, with every rule checked there again. The
 * outcome is a request answered or refused. The events it writes are the World Record's, and the market's own intent
 * and save protocol carries a sale. Nothing moves between the ledger's DISPATCHED and the request, so a stop before
 * dispatch closes the action with no effect.
 */
final class NativeFactionAction extends NativeRecordedAction {
    /** The request made at dispatch: the reply's line, and whether it was accepted. */
    interface Act {
        Reply run();
    }

    record Reply(boolean accepted, String line) { }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final String kind;
    private final String target;
    private final Act act;

    private ActionAdmission admission;
    private Step result;
    private boolean halted, unknown, asked;
    private CompletableFuture<ActionRecord> flush;

    NativeFactionAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                        String kind, String target, Act act) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.kind = kind;
        this.target = target;
        this.act = act;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A faction request is never made again after a stop or restart", true);
        if (admission == null) {
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_faction", "kind=" + kind + "; target=" + target),
                    "faction request " + kind, writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable faction request acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Faction request close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (admission.take().isEmpty()) return waiting();
        try {
            asked = true;
            var reply = act.run();
            if (reply.accepted()) {
                result = Step.completed(Map.of("kind", kind, "result", reply.line()));
                admission.verify(reply.line(), "Faction request answered");
            } else {
                result = Step.blocked(reply.line(), true);
                admission.noEffect("refused", reply.line());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Faction request interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) { return false; }

    @Override
    boolean uncertain() { return unknown; }

    @Override
    ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }

    @Override
    String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }

    private Step waiting() { return Step.running(Map.of("durableAction", phase()), false); }

    @Override
    CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            if (result != null && result.status() == Status.COMPLETED && !unknown) admission.verify("faction request answered", "Faction request verified");
            else if (!asked) admission.noEffect("not asked", "Faction request cancelled before it was made");
            else {
                unknown = true;
                admission.uncertain("Faction request cancelled with uncertain outcome");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override
    public void halt() { close(); }

    @Override
    public boolean settled() { return true; }
}
