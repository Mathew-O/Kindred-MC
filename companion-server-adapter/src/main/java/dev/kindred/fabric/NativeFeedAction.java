package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player feeding an animal the food it breeds on, from its own hotbar, as a human does.
 * Exactly one piece of food is used and the animal is then ready to breed; an animal that is young, already
 * in love or still resting is not fed.
 *
 * Technical: Feeds an animal the food it breeds on from an owned hotbar slot, as a player's use click does
 * (M-01a): exactly one of the food is spent under the durable ledger and the animal is then in love. An
 * animal not ready to breed (a young one, one in love already or still cooling down) is never fed; nothing
 * else of the player's is touched.
 */
final class NativeFeedAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final UUID target;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Item food;
    private Step result;
    private boolean halted, unknown, used;
    private int waited;
    private CompletableFuture<ActionRecord> flush;

    /** Feeding the given animal from a hotbar slot. */
    NativeFeedAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     int slot, UUID target) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.target = target;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("An animal is fed from an owned hotbar slot 0..8");
    }

    private Optional<AnimalEntity> animal() {
        var entity = body.entity().getServerWorld().getEntity(target);
        return entity instanceof AnimalEntity animal && animal.isAlive() ? Optional.of(animal) : Optional.empty();
    }

    private boolean inLove() {
        return animal().map(AnimalEntity::isInLove).orElse(false);
    }

    /** Moves the feeding on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded feeding cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            var animal = animal();
            if (animal.isEmpty()) return Step.blocked("No such animal in sight", true);
            ItemStack held = player.getInventory().getStack(slot);
            if (held.isEmpty() || !animal.get().isBreedingItem(held)) return Step.blocked("The hotbar slot holds nothing that animal eats", true);
            if (animal.get().isBaby() || animal.get().getBreedingAge() != 0 || animal.get().isInLove()) {
                return Step.blocked("That animal is not ready to breed", true);
            }
            food = held.getItem();
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_feed",
                    "slot=" + slot + "; animal=" + target), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable feeding acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Feeding close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!used) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            var animal = animal();
            if (!before.matches(body) || !player.isAlive() || animal.isEmpty() || inLove()) {
                result = Step.blocked("The inventory or the animal changed before it was fed; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.feed(lease.get(), slot, animal.get())) return settle("Feeding authority denied or out of reach");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Feeding interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            used = true;
        }
        // Vanilla takes the food and sets the animal in love on the click itself.
        if (++waited < 2) return waiting();
        return settle(null);
    }

    private Step settle(String failure) {
        var after = InventoryEvidence.capture(body);
        int spent = count(before) - count(after);
        boolean fed = inLove();
        if (spent == 0 && !fed && conserved(after, 0)) {
            result = Step.blocked(failure != null ? failure : "The animal did not take the food", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent != 1 || !fed || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Feeding conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        result = Step.completed(Map.of("animal", target.toString(), "inLove", "true"));
        admission.verify(after.description() + "; one " + name() + " fed to the animal " + target + ", now in love", "Native feeding verified");
        return waiting();
    }

    private String name() {
        return net.minecraft.registry.Registries.ITEM.getId(food).getPath().replace('_', ' ');
    }

    private int count(InventoryEvidence evidence) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(food)) total += stack.getCount();
        return total;
    }

    /** At most {@code eaten} of the food gone and nothing else lost. */
    private boolean conserved(InventoryEvidence after, int eaten) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == food ? eaten : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || used) return false;
        return intent == NativePlayerBody.Intent.FEED || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
                admission.verify(after.description() + "; " + result.progress(), "Native feeding verified");
            } else if (spent == 0 && !inLove() && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Feeding cancelled before the animal ate");
            } else if (spent == 1 && inLove() && conserved(after, 1)) {
                admission.verify(after.description() + "; one " + name() + " fed to the animal " + target, "Native feeding verified");
            } else {
                unknown = true;
                admission.uncertain("Feeding cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
