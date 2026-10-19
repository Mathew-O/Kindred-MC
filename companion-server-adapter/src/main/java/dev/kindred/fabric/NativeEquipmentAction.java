package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One native equipment operation, admitted durably before effect and verified before completion. */
final class NativeEquipmentAction extends NativeRecordedAction {
    private final SkillBody operation;
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeEquipmentAction(SkillBody operation, NativePlayerBody body, Supplier<ActionLease> lease,
                          ActionAdmission.Writer writer, Consumer<Runnable> dispatch) {
        this.operation = operation; this.body = body; this.lease = lease; this.writer = writer; this.dispatch = dispatch;
    }

    @Override public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("Recorded equipment cannot replay after cancellation/restart", true);
        if (admission == null) {
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_equip", "owned source slot=" + args.get("slot")),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable equipment acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Equipment close outcome uncertain", true); }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!before.matches(body)) {
            result = Step.blocked("Owned inventory changed before equipment dispatch; no transfer issued", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            result = operation.tick(args, Optional.empty(), true);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during equipment transfer; reconcile", true);
            } else if (result.status() == Status.COMPLETED && !NativePlayerSkills.uncertain(operation)) {
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native slot conservation verified");
            } else if (before.matches(body) && !NativePlayerSkills.uncertain(operation)) {
                result = Step.blocked(result.reason() == null ? "Equipment produced no verified effect" : result.reason(), true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native equipment effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Equipment action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    boolean permits(NativePlayerBody.Intent intent, net.minecraft.util.math.BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && NativePlayerSkills.permits(operation, intent);
    }
    boolean uncertain() { return unknown || NativePlayerSkills.uncertain(operation); }
    ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    private Step waiting() { return Step.running(Map.of("durableAction", phase()), false); }

    CompletableFuture<ActionRecord> close() {
        halted = true;
        operation.halt();
        if (flush == null) flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return operation.settled(); }
}
