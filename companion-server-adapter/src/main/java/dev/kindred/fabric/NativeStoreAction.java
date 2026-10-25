package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Puts things away in a store of its own (E-43), as a player does at its chest: opened with a use click, every stack
 * of the items it puts away shift-clicked in, and shut. Or, moving what is left of a store it no longer trusts, every
 * stack in it shift-clicked out into its inventory. What it saw in the chest as it opened it is kept: its look. Exactly
 * what left one side arrives in the other, recorded in the durable ledger; nothing else of either moves. Only its own
 * stores: the storage its administrator assigned it, or a chest of a base it built.
 */
final class NativeStoreAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos chestPos;
    /** The items it puts away, all of each; none when it takes everything out. */
    private final Set<String> items;
    private final boolean takeOut;
    /** Whether the chest is still a store of its own. */
    private final BooleanSupplier own;

    private Map<String, Integer> seen = Map.of(), moved = Map.of();
    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, opened;
    private CompletableFuture<ActionRecord> flush;

    NativeStoreAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                      ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos chestPos,
                      Set<String> items, boolean takeOut, BooleanSupplier own) {
        if (takeOut != items.isEmpty()) throw new IllegalArgumentException("Put items away, or take everything out");
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.chestPos = chestPos.toImmutable();
        this.items = Set.copyOf(items);
        this.takeOut = takeOut;
        this.own = own;
    }

    private boolean inReach() {
        return body.entity().getEyePos().squaredDistanceTo(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) <= 20.25;
    }

    private String denial() {
        return RoamingEdits.ownStoreDenial(body.entity(), chestPos, own.getAsBoolean(), policy.get());
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded store cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (!inReach()) return Step.blocked("The chest is out of reach", true);
            String denial = denial();
            if (denial != null) return Step.blocked("Storage policy denied: " + denial, true);
            String key = "block:" + worldId + ":" + player.getServerWorld().getRegistryKey().getValue() + ":" + chestPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_store",
                    "store=" + chestPos.toShortString() + (takeOut ? "; everything taken out" : "; put away " + String.join(",", new java.util.TreeSet<>(items)))),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable store acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Store close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!valid()) {
            result = Step.blocked("The chest or the inventory changed before it was opened; nothing moved", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        NativePlayerBody.Looted used;
        try {
            opened = true;
            used = body.useContainer(lease.get(), chestPos, null, this::out, this::in);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Store interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (used == null) {
            if (before.matches(body)) {
                result = Step.blocked("The chest would not open", true);
                admission.noEffect(before.description() + "; nothing moved", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Store conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        var after = InventoryEvidence.capture(body);
        var chestChange = change(used.opened(), used.shut());
        var held = change(List.of(before.stacks()), List.of(after.stacks()));
        // What the chest gained the inventory lost, or what it lost the inventory gained: one side's change is the other's.
        var opposite = new LinkedHashMap<String, Integer>();
        held.forEach((key, count) -> opposite.put(key, -count));
        if (!chestChange.equals(opposite) || chestChange.values().stream().anyMatch(n -> takeOut ? n > 0 : n < 0)) {
            unknown = true;
            result = Step.blocked("Store conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        seen = counts(used.opened());
        var byItem = new LinkedHashMap<String, Integer>();
        chestChange.forEach((key, count) -> byItem.merge(key.substring(0, key.indexOf('|')), Math.abs(count), Integer::sum));
        moved = Map.copyOf(byItem);
        String words = words(byItem);
        KindredMod.LOGGER.info("{} {} its chest at {}: {}", player.getName().getString(), takeOut ? "emptied" : "put away in",
                chestPos.toShortString(), byItem.isEmpty() ? "nothing" : words);
        result = Step.completed(Map.of("moved", words, "count", String.valueOf(byItem.values().stream().mapToInt(Integer::intValue).sum())));
        admission.verify(after.description() + "; " + (byItem.isEmpty() ? "nothing moved" : words + (takeOut ? " taken out of" : " put in"))
                + " the chest at " + chestPos.toShortString(), "Native store verified");
        return waiting();
    }

    /** Taking everything out: every slot of the chest. */
    private List<Integer> out(List<ItemStack> contents) {
        if (!takeOut) return List.of();
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < contents.size(); i++) if (!contents.get(i).isEmpty()) slots.add(i);
        return slots;
    }

    /** Putting away: every slot of the inventory holding an item it puts away. */
    private List<Integer> in(List<ItemStack> held) {
        if (takeOut) return List.of();
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < held.size(); i++) if (!held.get(i).isEmpty() && items.contains(Valuables.id(held.get(i)))) slots.add(i);
        return slots;
    }

    private static String key(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + "|" + stack.getComponents();
    }

    /** How each item changed from {@code was} to {@code now}, by item and components; what did not change left out. */
    private static Map<String, Integer> change(List<ItemStack> was, List<ItemStack> now) {
        var out = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : now) if (!stack.isEmpty()) out.merge(key(stack), stack.getCount(), Integer::sum);
        for (ItemStack stack : was) if (!stack.isEmpty()) out.merge(key(stack), -stack.getCount(), Integer::sum);
        out.values().removeIf(count -> count == 0);
        return out;
    }

    private static Map<String, Integer> counts(List<ItemStack> stacks) {
        var out = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : stacks) if (!stack.isEmpty()) out.merge(Valuables.id(stack), stack.getCount(), Integer::sum);
        return Map.copyOf(out);
    }

    private static String words(Map<String, Integer> items) {
        var parts = new ArrayList<String>();
        items.forEach((id, count) -> parts.add(count + " " + id.substring(id.indexOf(':') + 1).replace('_', ' ')));
        return String.join(", ", parts);
    }

    /** What was in the chest as it opened it, item by item: its look. */
    Map<String, Integer> seen() { return seen; }

    /** What it moved, item by item, once verified. */
    Map<String, Integer> moved() { return moved; }

    boolean takesOut() { return takeOut; }

    BlockPos chest() { return chestPos; }

    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive() || !inReach() || !before.matches(body) || denial() != null) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.TAKE
                && chestPos.equals(target);
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
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native store verified");
            } else if (!opened && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing moved", "Store cancelled before the chest was opened");
            } else {
                unknown = true;
                admission.uncertain("Store cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
