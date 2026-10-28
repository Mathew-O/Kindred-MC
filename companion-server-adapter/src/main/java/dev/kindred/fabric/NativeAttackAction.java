package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native attack action using the durable action ledger.
 * Admits durably before strike, verifies reach and line of sight, executes authentic vanilla attack,
 * and verifies complete 100% item conservation (at most 2 durability loss on the attack weapon, zero slot tampering).
 */
final class NativeAttackAction extends NativeRecordedAction {
    private final SkillBody operation;
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final UUID targetId;
    private final Integer slot;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeAttackAction(SkillBody operation, NativePlayerBody body, Supplier<ActionLease> lease,
                       ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                       UUID targetId, Integer slot) {
        this.operation = operation;
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.targetId = targetId;
        this.slot = slot;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded attack cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_attack",
                            "target=" + targetId + (slot != null ? "; slot=" + slot : "")),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable attack acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Attack close outcome uncertain", true);
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

        var player = body.entity();
        var entity = player.getServerWorld().getEntity(targetId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive() || target.isRemoved()) {
            if (admission.take().isEmpty()) return waiting();
            result = Step.blocked("Target entity not found, removed, or dead; no attack issued", true);
            admission.noEffect(before.description() + "; target absent or dead", "Target absent or dead");
            return waiting();
        }
        if (!player.canInteractWithEntity(target, 0)) {
            if (admission.take().isEmpty()) return waiting();
            result = Step.blocked("Target entity out of reach (more than 3 blocks from the eyes to its hitbox); no attack issued", true);
            admission.noEffect(before.description() + "; target out of reach", "Target out of reach");
            return waiting();
        }
        if (!player.canSee(target)) {
            if (admission.take().isEmpty()) return waiting();
            result = Step.blocked("Target entity not in line of sight; no attack issued", true);
            admission.noEffect(before.description() + "; target obstructed", "Target obstructed");
            return waiting();
        }

        if (!before.matches(body)) {
            result = Step.blocked("Owned inventory changed before attack dispatch; no attack issued", true);
            flush = admission.close();
            return waiting();
        }

        float cooldown = player.getAttackCooldownProgress(0.5f);
        if (cooldown < 0.9f) {
            return Step.running(Map.of("cooldown", String.valueOf(cooldown)), false);
        }

        if (admission.take().isEmpty()) return waiting();
        try {
            int attackSlot = slot != null && slot >= 0 && slot < 9 ? slot : player.getInventory().selectedSlot;
            result = operation.tick(args, Optional.empty(), true);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during attack; reconcile", true);
            } else if (result.status() == Status.COMPLETED && !NativePlayerSkills.uncertain(operation)) {
                var after = InventoryEvidence.capture(body);
                if (verifyAttackConservation(before, after, attackSlot)) {
                    admission.verify(after.description() + "; " + result.progress(), "Native attack item conservation verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Attack item conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body) && !NativePlayerSkills.uncertain(operation)) {
                result = Step.blocked(result.reason() == null ? "Attack produced no effect" : result.reason(), true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native attack effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Attack action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private static boolean verifyAttackConservation(InventoryEvidence before, InventoryEvidence after, int attackSlot) {
        if (before.stacks().length != after.stacks().length) return false;
        for (int i = 0; i < before.stacks().length; i++) {
            var b = before.stacks()[i];
            var a = after.stacks()[i];
            if (i != attackSlot) {
                if (b.getCount() != a.getCount() || !ItemStack.areItemsAndComponentsEqual(b, a)) {
                    return false;
                }
            } else {
                if (b.isEmpty() && a.isEmpty()) continue;
                if (b.getItem() != a.getItem()) return false;
                if (b.isDamageable()) {
                    int damageBefore = b.getDamage();
                    int damageAfter = a.getDamage();
                    // Durability damage on hit is typically 1 (or 2 for non-weapon tools)
                    if (damageAfter < damageBefore || damageAfter > damageBefore + 2) {
                        return false;
                    }
                } else {
                    if (b.getCount() != a.getCount() || !ItemStack.areItemsAndComponentsEqual(b, a)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && NativePlayerSkills.permits(operation, intent, target);
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
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            unknown = true;
            admission.uncertain("Interrupted before attack verification");
        }
        return admission == null ? CompletableFuture.completedFuture(null) : admission.close();
    }

    @Override
    public void halt() {
        halted = true;
        operation.halt();
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            unknown = true;
            admission.uncertain("Halted during attack execution");
        }
    }

    @Override
    public boolean settled() {
        return (admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) && operation.settled();
    }
}
