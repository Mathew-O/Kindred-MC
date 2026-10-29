package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.passive.SheepEntity;
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
 * In plain terms: an AI player shearing a sheep with its own shears, as a human does: the wool drops beside
 * the sheep to be picked up, and the shears wear a little. A sheep already shorn, or a lamb, is never
 * sheared.
 *
 * Technical: Shears a sheep with the shears in an owned hotbar slot, as a player's use click does (E-45d):
 * the sheep drops its wool beside it, to be picked up as any drop is, and the shears wear by one, under the
 * durable ledger. A sheep without its wool, or a lamb, is never sheared; nothing of the player's is spent but
 * the shears' wear (and the shears, worn out on the click).
 */
final class NativeShearAction extends NativeRecordedAction {
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

    /** Shearing the given sheep with the shears in a hotbar slot. */
    NativeShearAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                      int slot, UUID target) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.target = target;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A sheep is sheared from an owned hotbar slot 0..8");
    }

    private Optional<SheepEntity> sheep() {
        var entity = body.entity().getServerWorld().getEntity(target);
        return entity instanceof SheepEntity sheep && sheep.isAlive() ? Optional.of(sheep) : Optional.empty();
    }

    private boolean sheared() {
        return sheep().map(SheepEntity::isSheared).orElse(false);
    }

    /** Moves the shearing on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded shearing cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            var sheep = sheep();
            if (sheep.isEmpty()) return Step.blocked("No such sheep in sight", true);
            ItemStack held = player.getInventory().getStack(slot);
            if (!held.isOf(Items.SHEARS)) return Step.blocked("The hotbar slot holds no shears", true);
            if (!sheep.get().isShearable()) return Step.blocked("That sheep has no wool to shear", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_shear",
                    "slot=" + slot + "; sheep=" + target), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable shearing acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Shearing close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!used) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            var sheep = sheep();
            if (!before.matches(body) || !player.isAlive() || sheep.isEmpty() || sheared()) {
                result = Step.blocked("The inventory or the sheep changed before it was sheared; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.shear(lease.get(), slot, sheep.get())) return settle("Shearing authority denied or out of reach");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Shearing interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            used = true;
        }
        // Vanilla drops the wool and wears the shears on the click itself.
        if (++waited < 2) return waiting();
        return settle(null);
    }

    private Step settle(String failure) {
        var after = InventoryEvidence.capture(body);
        boolean done = sheared();
        if (!done && conserved(after, 0)) {
            result = Step.blocked(failure != null ? failure : "The sheep was not sheared", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (!done || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Shearing conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        result = Step.completed(Map.of("sheep", target.toString(), "sheared", "true"));
        admission.verify(after.description() + "; the sheep " + target + " sheared, its wool dropped beside it, the shears worn", "Native shearing verified");
        return waiting();
    }

    /** Nothing lost but, at most, {@code shears} worn-out shears. */
    private boolean conserved(InventoryEvidence after, int shears) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == Items.SHEARS ? shears : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || used) return false;
        return intent == NativePlayerBody.Intent.SHEAR || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native shearing verified");
            } else if (!sheared() && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Shearing cancelled before the sheep was sheared");
            } else if (sheared() && conserved(after, 1)) {
                admission.verify(after.description() + "; the sheep " + target + " sheared", "Native shearing verified");
            } else {
                unknown = true;
                admission.uncertain("Shearing cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
