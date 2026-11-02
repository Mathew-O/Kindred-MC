package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.passive.TameableEntity;
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
 * A pet made or named with the player's own items, as a player's use click does (E-37, E-36): {@code tame} gives a wild
 * wolf one of the bones in a hotbar slot, which vanilla takes, taming it to the player one time in three; {@code name}
 * uses a name tag already bearing a name on a pet of the player's own, which vanilla takes, naming the pet. Exactly the
 * one item is spent under the durable ledger, and nothing else of the player's is touched; whether the wolf took to it
 * is the game's. Another player's pet, or a wolf not wild, is never touched.
 */
final class NativePetAction extends NativeRecordedAction {
    enum Mode { TAME, NAME }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Mode mode;
    private final int slot;
    private final UUID target;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Item item;
    private String name;
    private Step result;
    private boolean halted, unknown, used;
    private int waited;
    private CompletableFuture<ActionRecord> flush;

    NativePetAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                    Mode mode, int slot, UUID target) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.mode = mode;
        this.slot = slot;
        this.target = target;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A pet is tamed or named from an owned hotbar slot 0..8");
    }

    private Optional<TameableEntity> pet() {
        var entity = body.entity().getServerWorld().getEntity(target);
        return entity instanceof TameableEntity pet && pet.isAlive() ? Optional.of(pet) : Optional.empty();
    }

    private boolean ours(TameableEntity pet) {
        return pet.isTamed() && body.entity().getUuid().equals(pet.getOwnerUuid());
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded taming or naming cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            var pet = pet();
            if (pet.isEmpty()) return Step.blocked("No such animal in sight", true);
            ItemStack held = player.getInventory().getStack(slot);
            if (mode == Mode.TAME) {
                if (!(pet.get() instanceof net.minecraft.entity.passive.WolfEntity) || !held.isOf(Items.BONE)) {
                    return Step.blocked("Only a wolf, and with a bone in that slot", true);
                }
                if (pet.get().isTamed() || pet.get().isBaby() || ((net.minecraft.entity.passive.WolfEntity) pet.get()).hasAngerTime()) {
                    return Step.blocked("That wolf is not a wild one at peace", true);
                }
            } else {
                if (!held.isOf(Items.NAME_TAG) || held.get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME) == null) {
                    return Step.blocked("No name tag bearing a name in that slot", true);
                }
                if (!ours(pet.get())) return Step.blocked("Only a pet of its own is named", true);
                name = held.getName().getString();
            }
            item = held.getItem();
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_pet",
                    "mode=" + mode.name().toLowerCase(java.util.Locale.ROOT) + "; slot=" + slot + "; animal=" + target), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable pet action acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Pet action close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!used) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            var pet = pet();
            if (!before.matches(body) || !player.isAlive() || pet.isEmpty() || mode == Mode.TAME && pet.get().isTamed()) {
                result = Step.blocked("The inventory or the animal changed first; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.feed(lease.get(), slot, pet.get())) return settle("Use authority denied or out of reach");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            used = true;
        }
        // Vanilla takes the item and tames or names on the click itself.
        if (++waited < 2) return waiting();
        return settle(null);
    }

    private Step settle(String failure) {
        var after = InventoryEvidence.capture(body);
        int spent = count(before) - count(after);
        // The animal as it is now, alive or just dead: a name or an owner it took stays on it.
        var entity = body.entity().getServerWorld().getEntity(target);
        var pet = entity instanceof TameableEntity tameable ? Optional.of(tameable) : Optional.<TameableEntity>empty();
        if (spent == 0 && conserved(after, 0)) {
            result = Step.blocked(failure != null ? failure : "The animal did not take it", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        boolean named = mode == Mode.NAME && pet.isPresent() && pet.get().hasCustomName() && name.equals(pet.get().getCustomName().getString());
        boolean tamed = mode == Mode.TAME && pet.isPresent() && ours(pet.get());
        if (spent != 1 || !conserved(after, 1) || mode == Mode.NAME && !named
                || mode == Mode.TAME && pet.isPresent() && pet.get().isTamed() && !tamed) {
            unknown = true;
            result = Step.blocked("Pet action conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        if (mode == Mode.NAME) {
            result = Step.completed(Map.of("animal", target.toString(), "name", name));
            admission.verify(after.description() + "; a name tag used on its pet " + target + ", now called " + name, "Native naming verified");
        } else {
            result = Step.completed(Map.of("animal", target.toString(), "tamed", String.valueOf(tamed)));
            admission.verify(after.description() + "; one bone given to the wolf " + target + (tamed ? ", now its own" : ", which did not take to it yet"),
                    "Native taming verified");
        }
        return waiting();
    }

    private int count(InventoryEvidence evidence) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    /** At most {@code spent} of the item gone and nothing else lost. */
    private boolean conserved(InventoryEvidence after, int spent) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == item ? spent : 0)) return false;
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
                admission.verify(after.description() + "; " + result.progress(), "Native pet action verified");
            } else if (spent == 0 && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Cancelled before the click");
            } else if (spent == 1 && conserved(after, 1)) {
                admission.verify(after.description() + "; one " + net.minecraft.registry.Registries.ITEM.getId(item).getPath().replace('_', ' ')
                        + " used on " + target, "Native pet action verified");
            } else {
                unknown = true;
                admission.uncertain("Cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
