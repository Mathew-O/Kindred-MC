package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.guide.Trades;
import dev.kindred.core.guide.Worth;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Trades with a villager as a player does (E-45b): its trading screen opened with a use click, its offers seen as the
 * screen shows them, the villager's prices; then what is worth trading by the value model (E-45a), as many times as it
 * plans, each taken from the result slot one at a time; and shut. Or, only looking, opened and shut, its offers seen. A
 * recorded action under the durable ledger: what the inventory lost and gained is exactly what the trades it made ask
 * and yield, item by item, and nothing else of the player's moves. An offer the villager is out of until it restocks
 * yields nothing and is left.
 */
final class NativeTradeAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final UUID villager;
    private final boolean look;
    private final Supplier<Worth.Holding> holding;

    private List<Trades.Offer> seen = List.of();
    private List<String> made = List.of();
    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, opened;
    private CompletableFuture<ActionRecord> flush;

    NativeTradeAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, ClaimRegistry claims, ActionAdmission.Writer writer,
                      Consumer<Runnable> dispatch, UUID villager, boolean look, Supplier<Worth.Holding> holding) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.villager = villager;
        this.look = look;
        this.holding = holding;
    }

    private Optional<VillagerEntity> trader() {
        return body.entity().getServerWorld().getEntity(villager) instanceof VillagerEntity trader && trader.isAlive()
                ? Optional.of(trader) : Optional.empty();
    }

    private boolean inReach() {
        return trader().map(t -> body.entity().canInteractWithEntity(t, 0)).orElse(false);
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded trade cannot replay after cancellation/restart", true);
        if (admission == null) {
            if (trader().isEmpty()) return Step.blocked("No such villager in sight", true);
            if (!inReach()) return Step.blocked("The villager is out of reach", true);
            String key = "entity:" + worldId + ":" + villager;
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_trade",
                    "villager=" + villager + (look ? "; a look" : "; trades as planned")), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable trade acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Trade close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!valid()) {
            result = Step.blocked("The villager or the inventory changed before the trade; nothing traded", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        NativePlayerBody.Traded traded;
        try {
            opened = true;
            traded = body.trade(lease.get(), villager, this::choose);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Trade interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (traded == null) {
            if (before.matches(body)) {
                result = Step.blocked("The villager would not trade", true);
                admission.noEffect(before.description() + "; nothing traded", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Trade conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        var after = InventoryEvidence.capture(body);
        // What the trades it made ask and yield, item by item, is what the inventory lost and gained, and nothing else moved.
        var expected = new LinkedHashMap<String, Integer>();
        var words = new ArrayList<String>();
        for (var entry : traded.made().entrySet()) {
            var offer = traded.offers().get(entry.getKey());
            int times = entry.getValue();
            expected.merge(Valuables.id(offer.getDisplayedFirstBuyItem()), -offer.getDisplayedFirstBuyItem().getCount() * times, Integer::sum);
            if (!offer.getDisplayedSecondBuyItem().isEmpty()) {
                expected.merge(Valuables.id(offer.getDisplayedSecondBuyItem()), -offer.getDisplayedSecondBuyItem().getCount() * times, Integer::sum);
            }
            expected.merge(Valuables.id(offer.getSellItem()), offer.getSellItem().getCount() * times, Integer::sum);
            words.add(Trades.made(Valuables.offer(offer), times));
        }
        expected.values().removeIf(count -> count == 0);
        var change = change(List.of(before.stacks()), List.of(after.stacks()));
        if (!change.equals(expected) || !yielded(traded, expected, after)) {
            unknown = true;
            result = Step.blocked("Trade conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason() + ": expected " + expected + ", saw " + change);
            KindredMod.LOGGER.warn("{} at a villager: trades {} expected {}, saw {}", body.entity().getName().getString(), traded.made(), expected, change);
            return waiting();
        }
        var offers = new ArrayList<Trades.Offer>();
        for (var offer : traded.offers()) offers.add(Valuables.offer(offer));
        seen = List.copyOf(offers);
        made = List.copyOf(words);
        String said = made.isEmpty() ? "nothing traded" : String.join("; ", made);
        KindredMod.LOGGER.info("{} at a villager ({} offers seen): {}", body.entity().getName().getString(), seen.size(), said);
        result = Step.completed(Map.of("made", said, "offers", String.valueOf(seen.size())));
        admission.verify(after.description() + "; " + said + " with the villager " + villager, "Native trade verified");
        return waiting();
    }

    /** What to trade, from the offers it sees: nothing when it only looks; else the plan, sales first, then purchases. */
    private List<int[]> choose(List<net.minecraft.village.TradeOffer> offers) {
        if (look) return List.of();
        var list = new ArrayList<Trades.Offer>();
        for (var offer : offers) list.add(Valuables.offer(offer));
        var held = holding.get();
        var plan = Trades.plan(list, held, held.held().getOrDefault(Trades.EMERALD, 0));
        var steps = new ArrayList<int[]>();
        for (var step : plan.sell()) steps.add(new int[] {list.indexOf(step.deal().offer()), step.times()});
        for (var step : plan.buy()) steps.add(new int[] {list.indexOf(step.deal().offer()), step.times()});
        return steps;
    }

    /**
     * What the trades left it with is in the inventory as the villager offered it, enchantments and all: of each item
     * they yielded, as many as they gained it net (emeralds from a sale spent on a purchase are not still held).
     */
    private static boolean yielded(NativePlayerBody.Traded traded, Map<String, Integer> expected, InventoryEvidence after) {
        for (var entry : traded.made().entrySet()) {
            ItemStack sold = traded.offers().get(entry.getKey()).getSellItem();
            int gained = Math.min(expected.getOrDefault(Valuables.id(sold), 0), sold.getCount() * entry.getValue());
            if (gained <= 0) continue;
            int have = 0;
            for (ItemStack stack : after.stacks()) if (ItemStack.areItemsAndComponentsEqual(stack, sold)) have += stack.getCount();
            if (have < gained) return false;
        }
        return true;
    }

    /** How each item changed from {@code was} to {@code now}, by item; what did not change left out. */
    private static Map<String, Integer> change(List<ItemStack> was, List<ItemStack> now) {
        var out = new LinkedHashMap<String, Integer>();
        for (ItemStack stack : now) if (!stack.isEmpty()) out.merge(Valuables.id(stack), stack.getCount(), Integer::sum);
        for (ItemStack stack : was) if (!stack.isEmpty()) out.merge(Valuables.id(stack), -stack.getCount(), Integer::sum);
        out.values().removeIf(count -> count == 0);
        return out;
    }

    /** The villager's offers as it saw them, once done. */
    List<Trades.Offer> seen() { return seen; }

    /** What it traded, a phrase each ("sold 40 wheat for 2 emeralds"), once done. */
    List<String> made() { return made; }

    boolean looked() { return look; }

    UUID villager() { return villager; }

    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive() || !inReach() || !before.matches(body)) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.TRADE;
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native trade verified");
            } else if (!opened && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing traded", "Trade cancelled before the screen was opened");
            } else {
                unknown = true;
                admission.uncertain("Trade cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
