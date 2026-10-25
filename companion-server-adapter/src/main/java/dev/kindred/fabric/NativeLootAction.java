package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.guide.Worth;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Loots a structure's container (E-42): a chest, a barrel or a chest minecart nobody has opened since the world made it,
 * opened with a use click as a player opens one, which rolls its loot. What the value model judges worth taking comes
 * out into the inventory, best first, as far as there is room; what is not worth a slot is left in it. Exactly what left
 * the container arrives in the player's inventory, recorded in the durable ledger; nothing else of either moves.
 * Storage anyone has opened is never looted (the server's roaming rule for containers).
 */
final class NativeLootAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos chestPos;
    private final UUID cart;
    private final Supplier<Map<String, Integer>> wanted;
    /** For a raid (E-44): whose storage it is, and whether they are still the player's opponent in its match. */
    private final UUID raidOwner;
    private final java.util.function.BooleanSupplier opponents;
    private Map<String, Integer> took = Map.of();

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, opened;
    private CompletableFuture<ActionRecord> flush;

    NativeLootAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                     ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos chestPos, UUID cart,
                     Supplier<Map<String, Integer>> wanted) {
        this(body, lease, worldId, policy, claims, writer, dispatch, chestPos, cart, wanted, null, () -> false);
    }

    /** A raid of {@code raidOwner}'s storage at {@code chestPos}, in a match, while {@code opponents} holds (E-44). */
    NativeLootAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                     ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos chestPos, UUID cart,
                     Supplier<Map<String, Integer>> wanted, UUID raidOwner, java.util.function.BooleanSupplier opponents) {
        if ((chestPos == null) == (cart == null)) throw new IllegalArgumentException("Loot a container at a place, or a cart");
        this.raidOwner = raidOwner;
        this.opponents = opponents;
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.chestPos = chestPos;
        this.cart = cart;
        this.wanted = wanted;
    }

    private Optional<StorageMinecartEntity> vehicle() {
        if (cart == null) return Optional.empty();
        return body.entity().getServerWorld().getEntity(cart) instanceof StorageMinecartEntity found && found.isAlive()
                ? Optional.of(found) : Optional.empty();
    }

    private boolean unrolled() {
        return cart != null ? vehicle().map(Valuables::unrolled).orElse(false) : Valuables.unrolled(body.entity().getServerWorld(), chestPos);
    }

    private BlockPos where() {
        return cart != null ? vehicle().map(StorageMinecartEntity::getBlockPos).orElse(null) : chestPos;
    }

    private boolean inReach() {
        var player = body.entity();
        if (cart != null) return vehicle().map(found -> player.canInteractWithEntity(found, 0)).orElse(false);
        return player.getEyePos().squaredDistanceTo(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) <= 20.25;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded loot cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            BlockPos at = where();
            if (at == null) return Step.blocked("No such cart in the world", true);
            if (!inReach()) return Step.blocked("The container is out of reach", true);
            String denial = denial(at);
            if (denial != null) return Step.blocked("Container policy denied: " + denial, true);
            String key = cart != null ? "entity:" + worldId + ":" + cart
                    : "block:" + worldId + ":" + player.getServerWorld().getRegistryKey().getValue() + ":" + chestPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_loot",
                    (cart != null ? "cart=" + cart : "container=" + chestPos.toShortString())
                            + (raidOwner != null ? "; raid of opponent " + raidOwner + "'s storage in a match" : "; unopened structure loot")),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable loot acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Loot close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!valid()) {
            result = Step.blocked("The container or the inventory changed before the loot; nothing moved", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        NativePlayerBody.Looted looted;
        try {
            opened = true;
            looted = body.loot(lease.get(), chestPos, cart, this::choose);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Loot interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (looted == null) {
            if (before.matches(body)) {
                result = Step.blocked("The container would not open", true);
                admission.noEffect(before.description() + "; nothing moved", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Loot conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        var after = InventoryEvidence.capture(body);
        var taken = moved(looted.opened(), looted.shut());
        if (taken == null || !taken.equals(gained(before.stacks(), after.stacks()))) {
            unknown = true;
            result = Step.blocked("Loot conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        String words = words(taken);
        var byItem = new LinkedHashMap<String, Integer>();
        taken.forEach((key, count) -> byItem.merge(key.substring(0, key.indexOf('|')), count, Integer::sum));
        took = Map.copyOf(byItem);
        var left = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : looted.shut()) if (!stack.isEmpty()) left.merge(key(stack), stack.getCount(), Integer::sum);
        KindredMod.LOGGER.info("{} {} {}: took {}; left {}", player.getName().getString(), raidOwner != null ? "raided" : "looted",
                cart != null ? "a chest minecart" : "the container at " + chestPos.toShortString(),
                taken.isEmpty() ? "nothing" : words, left.isEmpty() ? "nothing" : words(left));
        result = Step.completed(Map.of("taken", words, "count", String.valueOf(taken.values().stream().mapToInt(Integer::intValue).sum())));
        admission.verify(after.description() + "; " + (taken.isEmpty() ? "opened, nothing in it worth taking" : words + " taken from "
                + (cart != null ? "the cart " + cart : "the container at " + chestPos.toShortString())), "Native loot verified");
        return waiting();
    }

    /**
     * Which of the container's slots to shift-click out, best first: every slot of each item the value model would not
     * leave, judged by all of it there is, as a player sees three flint where a loot table spread them over three slots.
     */
    private List<Integer> choose(List<ItemStack> contents) {
        var holding = Valuables.holding(body.entity(), wanted.get());
        var slots = new LinkedHashMap<String, List<Integer>>();
        var total = new HashMap<String, Integer>();
        var first = new HashMap<String, ItemStack>();
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack.isEmpty()) continue;
            String key = key(stack);
            slots.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            total.merge(key, stack.getCount(), Integer::sum);
            first.putIfAbsent(key, stack);
        }
        var worth = new HashMap<String, Double>();
        var kept = new ArrayList<String>();
        for (String key : slots.keySet()) {
            ItemStack stack = first.get(key);
            var judgement = Worth.judge(new Worth.Stack(Valuables.id(stack), total.get(key), Valuables.enchantments(stack)), holding);
            if (judgement.keep() == Worth.Keep.LEAVE) continue;
            worth.put(key, judgement.worth());
            kept.add(key);
        }
        kept.sort(Comparator.comparingDouble((String key) -> -worth.get(key)));
        var chosen = new ArrayList<Integer>();
        for (String key : kept) chosen.addAll(slots.get(key));
        return chosen;
    }

    private static String key(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()) + "|" + stack.getComponents();
    }

    /** What the container lost, item by item; null if anything in it grew, which a loot never does. */
    private static Map<String, Integer> moved(List<ItemStack> opened, List<ItemStack> shut) {
        var lost = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : opened) if (!stack.isEmpty()) lost.merge(key(stack), stack.getCount(), Integer::sum);
        for (ItemStack stack : shut) if (!stack.isEmpty()) lost.merge(key(stack), -stack.getCount(), Integer::sum);
        for (var count : lost.values()) if (count < 0) return null;
        lost.values().removeIf(count -> count == 0);
        return lost;
    }

    /** What the inventory gained, item by item; anything it lost spoils the comparison. */
    private static Map<String, Integer> gained(ItemStack[] before, ItemStack[] after) {
        var gained = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : after) if (!stack.isEmpty()) gained.merge(key(stack), stack.getCount(), Integer::sum);
        for (ItemStack stack : before) if (!stack.isEmpty()) gained.merge(key(stack), -stack.getCount(), Integer::sum);
        for (var count : gained.values()) if (count < 0) return Map.of("lost", -1);
        gained.values().removeIf(count -> count == 0);
        return gained;
    }

    private static String words(Map<String, Integer> taken) {
        var parts = new ArrayList<String>();
        for (var entry : taken.entrySet()) {
            String id = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            parts.add(entry.getValue() + " " + id.substring(id.indexOf(':') + 1).replace('_', ' '));
        }
        return String.join(", ", parts);
    }

    /** The rule for this container: a structure's, never opened; or for a raid, an opponent's in the match. */
    private String denial(BlockPos at) {
        var player = body.entity();
        return raidOwner == null ? RoamingEdits.lootDenial(player, at, unrolled(), policy.get())
                : RoamingEdits.raidDenial(player, at, opponents.getAsBoolean(), policy.get());
    }

    /** Whose storage a raid is of, or null for a structure's loot. */
    UUID raidOwner() { return raidOwner; }

    /** What it took, item by item ("minecraft:diamond"), once verified. */
    Map<String, Integer> took() { return took; }

    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive() || !inReach() || !before.matches(body)) return false;
        BlockPos at = where();
        if (at == null || denial(at) != null) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.TAKE
                && (cart != null || target == null || target.equals(chestPos));
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native loot verified");
            } else if (!opened && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing moved", "Loot cancelled before the container was opened");
            } else {
                unknown = true;
                admission.uncertain("Loot cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
