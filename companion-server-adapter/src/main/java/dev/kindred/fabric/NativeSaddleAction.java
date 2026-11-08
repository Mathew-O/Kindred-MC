package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player putting a saddle it found onto a horse it has tamed, from its own hotbar, as a human
 * does. Exactly one saddle leaves its inventory and the horse then wears it; a horse not yet tamed, or one saddled
 * already, is left alone.
 *
 * Technical: Saddles a tamed horse with the saddle in an owned hotbar slot, as a player's use click with one does
 * (M-01a): exactly one saddle is spent under the durable ledger and the horse is then saddled. Saddles are found
 * (chests, fishing, trades), never crafted, and never made here. Nothing else of the player's is touched.
 */
final class NativeSaddleAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final UUID target;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown, used;
    private int waited;
    private CompletableFuture<ActionRecord> flush;

    NativeSaddleAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                       int slot, UUID target) {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A horse is saddled from an owned hotbar slot 0..8");
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.target = target;
    }

    private Optional<AbstractHorseEntity> horse() {
        var entity = body.entity().getServerWorld().getEntity(target);
        return entity instanceof AbstractHorseEntity horse && horse.isAlive() ? Optional.of(horse) : Optional.empty();
    }

    private boolean saddled() {
        return horse().map(AbstractHorseEntity::isSaddled).orElse(false);
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded saddling cannot replay after cancellation/restart", true);
        if (admission == null) {
            var horse = horse();
            if (horse.isEmpty()) return Step.blocked("No such horse in sight", true);
            if (!body.entity().getInventory().getStack(slot).isOf(Items.SADDLE)) return Step.blocked("The hotbar slot holds no saddle", true);
            if (!horse.get().isTame() || horse.get().isBaby()) return Step.blocked("That horse is not tamed", true);
            if (horse.get().isSaddled()) return Step.blocked("That horse is saddled already", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_saddle",
                    "slot=" + slot + "; horse=" + target), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable saddling acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Saddling close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!used) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            var horse = horse();
            if (!before.matches(body) || !body.entity().isAlive() || horse.isEmpty() || saddled()) {
                result = Step.blocked("The inventory or the horse changed before it was saddled; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.saddle(lease.get(), slot, horse.get())) return settle("Saddling authority denied or out of reach");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Saddling interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            used = true;
        }
        // Vanilla takes the saddle onto the horse on the click itself.
        if (++waited < 2) return waiting();
        return settle(null);
    }

    private Step settle(String failure) {
        var after = InventoryEvidence.capture(body);
        int spent = count(before) - count(after);
        boolean on = saddled();
        if (spent == 0 && !on && conserved(after, 0)) {
            result = Step.blocked(failure != null ? failure : "The horse did not take the saddle", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent != 1 || !on || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Saddling conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        result = Step.completed(Map.of("horse", target.toString(), "saddled", "true"));
        admission.verify(after.description() + "; one saddle onto the horse " + target, "Native saddling verified");
        return waiting();
    }

    private int count(InventoryEvidence evidence) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(Items.SADDLE)) total += stack.getCount();
        return total;
    }

    /** At most {@code spent} saddles gone and nothing else lost. */
    private boolean conserved(InventoryEvidence after, int spent) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == Items.SADDLE ? spent : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || used) return false;
        return intent == NativePlayerBody.Intent.RIDE || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            var after = InventoryEvidence.capture(body);
            int spent = count(before) - count(after);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native saddling verified");
            } else if (spent == 0 && !saddled() && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Saddling cancelled before the horse took it");
            } else if (spent == 1 && saddled() && conserved(after, 1)) {
                admission.verify(after.description() + "; one saddle onto the horse " + target, "Native saddling verified");
            } else {
                unknown = true;
                admission.uncertain("Saddling cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
