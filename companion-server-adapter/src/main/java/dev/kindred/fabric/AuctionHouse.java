package dev.kindred.fabric;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Factions;
import dev.kindred.core.market.AuctionBook;
import dev.kindred.core.market.Auctions;
import dev.kindred.core.market.Canonical;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * In plain terms: the auction house's counter: it takes a listed item into escrow, settles bids, closes listings when
 * their time is up, fills buy orders, and hands players what they won or what came back, the same for people and AI
 * players.
 *
 * Technical: The adapter's side of M-06h, on the runtime's thread. An item in escrow is the listed stack serialised into
 * the {@code auction.list} event; it is taken from the hand, the player's file written off the server thread
 * ({@link PlayerSaves}), and only then the listing written (a failed listing write gives it back; a failed save is a
 * {@code market.uncertain} for an operator). Closes are found once a second from the ledger itself (the rules read the
 * ledgers with unwritten events folded in, so a close is never asked twice); a restart rebuilds them. A delivery writes
 * {@code auction.delivering} first, then the stack is handed over, the player's file written the same way and
 * {@code auction.delivered} written: a crash in between is reported for an operator, never handed over twice. A player
 * with a trade under way is held ({@link MarketDesk.Holds}) from every request that moves items or coins.
 */
final class AuctionHouse {
    private final MinecraftServer server;
    private final Supplier<Ledgers> ledgers;
    private final MarketDesk.Recorder recorder;
    private final Supplier<FactionSettings> settings;
    private final Supplier<dev.kindred.core.market.Pricing> pricing;
    private final MarketDesk desk;
    private final MarketDesk.Holds holds;
    private final Set<String> delivering = new HashSet<>();
    /** Test-only: run right after a delivery's begin is asked for, before it comes back (a fixture stops the runtime there). */
    private Runnable afterBeginForTest = () -> { };

    void afterBeginForTest(Runnable then) { afterBeginForTest = then == null ? () -> { } : then; }

    /** Whether the runtime is running: a stopped one hands nothing over (the stop barrier). */
    private final java.util.function.BooleanSupplier live;
    private long clockOffset;
    private int next;

    AuctionHouse(MinecraftServer server, Supplier<Ledgers> ledgers, MarketDesk.Recorder recorder, Supplier<FactionSettings> settings,
                 MarketDesk desk, java.util.function.BooleanSupplier live) {
        this.server = server;
        this.live = live;
        this.ledgers = ledgers;
        this.recorder = recorder;
        this.settings = settings;
        this.desk = desk;
        this.pricing = desk::pricing;
        this.holds = desk.holds();
    }

    AuctionBook book() { return ledgers.get().auctions(); }

    long now() { return System.currentTimeMillis() + clockOffset; }

    /** A fixture's clock moved on, so a 48-hour listing can close within a test. */
    void clockForTest(long offsetMs) { clockOffset = offsetMs; }

    private Factions.At at(String world) { return new Factions.At(server.getTicks(), now(), world); }

    /** {@code seller} lists the stack in hand. */
    void list(ServerPlayerEntity seller, long start, long buyNow, int hours, String world, Consumer<String> done) {
        var hand = seller.getMainHandStack();
        if (hand.isEmpty()) { done.accept("Nothing in hand"); return; }
        // A hunt's tracking compass is the hunt's, handed by its rule (M-06k2, D6): never anyone's to sell.
        if (HuntCompass.handed(hand)) { done.accept("A hunt's tracking compass is not yours to sell"); return; }
        var facts = MarketDesk.facts(hand);
        var key = Canonical.of(new dev.kindred.core.market.ItemFacts(facts.id(), facts.count(), facts.damage(), facts.maxDamage(), facts.enchantments(),
                facts.potion(), false, Set.of()), pricing.get().table());
        if (facts.holdsItems()) { done.accept("A container with things in it: empty it first"); return; }
        if (!key.accepted()) { done.accept(key.refusal()); return; }
        String id = "a" + Long.toString(now(), 36) + (next++);
        String item = hand.encode(server.getRegistryManager()).toString();
        var decision = Auctions.list(book(), ledgers.get().coins(), settings.get(), seller.getUuid(), id, key.key(), item, hand.getCount(), start, buyNow, hours, at(world));
        if (!decision.accepted()) { done.accept(decision.reason()); return; }
        UUID who = seller.getUuid();
        if (!holds.hold(who)) { done.accept(MarketDesk.HELD); return; }
        Consumer<String> finish = text -> { holds.release(who); done.accept(text); };
        int slot = seller.getInventory().selectedSlot;
        var taken = seller.getInventory().removeStack(slot);
        var events = withSignal(decision.events(), world);
        // The item gone from the seller's file before the listing holds it in escrow.
        desk.whenSaved(seller, () -> recorder.record(events, () -> finish.accept(decision.reason() + " (" + id + ")"), failure -> {
            var back = server.getPlayerManager().getPlayer(who);
            if (back != null) {
                EvalItemSources.enter(back);
                back.getInventory().offerOrDrop(taken);
                EvalItemSources.exit(back, "auction.return");
                MarketDesk.save(back);
            } else KindredMod.LOGGER.error("Listing {} not written and its seller left: {} to return by an operator", id, taken);
            finish.accept("The listing could not be written; the item is back");
        }), failure -> {
            desk.uncertain(who, id, world, "the seller's file could not be written for a listing", List.of(taken), 0);
            finish.accept("The listing could not be saved; an operator is told");
        });
    }

    /** A bid, or a buy-now with {@code amount} the buy-now price. */
    String bid(ServerPlayerEntity bidder, String id, long amount, String world, Consumer<String> done) {
        if (holds.held(bidder.getUuid())) return MarketDesk.HELD;
        var decision = Auctions.bid(book(), ledgers.get().coins(), settings.get(), bidder.getUuid(), id, amount, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(withSignal(decision.events(), world), () -> done.accept(decision.reason()), failure -> done.accept("The bid could not be written"));
        return null;
    }

    String cancel(ServerPlayerEntity seller, String id, String world, Consumer<String> done) {
        if (holds.held(seller.getUuid())) return MarketDesk.HELD;
        var decision = Auctions.cancel(book(), seller.getUuid(), id, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(decision.events(), () -> done.accept(decision.reason()), failure -> done.accept("Not written"));
        return null;
    }

    String order(ServerPlayerEntity buyer, String item, int count, long each, String world, Consumer<String> done) {
        if (holds.held(buyer.getUuid())) return MarketDesk.HELD;
        String key = dev.kindred.core.market.MarketTable.id(item);
        String id = "o" + Long.toString(now(), 36) + (next++);
        var decision = Auctions.order(ledgers.get().coins(), buyer.getUuid(), id, key, count, each, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(decision.events(), () -> done.accept(decision.reason() + " (" + id + ")"), failure -> done.accept("Not written"));
        return null;
    }

    String cancelOrder(ServerPlayerEntity buyer, String id, String world, Consumer<String> done) {
        if (holds.held(buyer.getUuid())) return MarketDesk.HELD;
        var decision = Auctions.cancelOrder(book(), ledgers.get().coins(), buyer.getUuid(), id, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(decision.events(), () -> done.accept(decision.reason()), failure -> done.accept("Not written"));
        return null;
    }

    /** Once a second: every listing due closed, and every item owed to an online player handed over. */
    void tick() {
        for (var listing : Auctions.due(book(), now())) {
            var decision = Auctions.close(listing, ledgers.get().coins(), settings.get(), at(""));
            recorder.record(withSignal(decision.events(), ""), () -> { }, failure -> KindredMod.LOGGER.error("Close of {} not written", listing.id(), failure));
        }
        for (var delivery : book().owed().values()) {
            if (delivering.contains(delivery.id())) continue;
            var player = server.getPlayerManager().getPlayer(delivery.to());
            if (player == null || !holds.hold(delivery.to())) continue;
            delivering.add(delivery.id());
            var at = at("");
            recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), "", Auctions.DELIVERING, delivery.to(), delivery.id(), "", Map.of())),
                    () -> handOver(delivery),
                    failure -> {
                        delivering.remove(delivery.id());
                        holds.release(delivery.to());
                    });
            afterBeginForTest.run();
        }
    }

    private void handOver(AuctionBook.Delivery delivery) {
        // A delivery's begin written, then the runtime stopped: nothing is handed over by a late callback; the Record
        // holds it begun and not delivered, and the next start reports it for an operator (never handed twice).
        if (!live.getAsBoolean()) {
            KindredMod.LOGGER.warn("Delivery {} begun as the runtime stopped: not handed over; reconcile at the next start", delivery.id());
            return;
        }
        var player = server.getPlayerManager().getPlayer(delivery.to());
        if (player == null) {
            holds.release(delivery.to());
            KindredMod.LOGGER.error("Delivery {} begun and its player left: hand over by an operator", delivery.id());
            return;
        }
        Optional<ItemStack> stack;
        try {
            stack = ItemStack.fromNbt(server.getRegistryManager(), StringNbtReader.parse(delivery.item()));
        } catch (Exception unreadable) {
            stack = Optional.empty();
        }
        if (stack.isEmpty()) {
            holds.release(delivery.to());
            KindredMod.LOGGER.error("Delivery {} holds an unreadable item: reconcile by hand", delivery.id());
            return;
        }
        var handed = stack.get().copy();
        EvalItemSources.enter(player);
        player.getInventory().offerOrDrop(stack.get());
        EvalItemSources.exit(player, "auction.delivery");
        desk.whenSaved(player, () -> {
            var at = at("");
            recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), "", Auctions.DELIVERED, delivery.to(), delivery.id(), "", Map.of("why", delivery.why()))),
                    () -> {
                        delivering.remove(delivery.id());
                        holds.release(delivery.to());
                    }, failure -> {
                        holds.release(delivery.to());
                        KindredMod.LOGGER.error("Delivery {} handed over; its receipt not written", delivery.id(), failure);
                    });
        }, failure -> {
            holds.release(delivery.to());
            desk.uncertain(delivery.to(), delivery.id(), "", "the receiver's file could not be written for a delivery", List.of(handed), 0);
        });
    }

    /** A decision's events with the market's demand signal added where a trade closed and the filter counts it. */
    private List<WorldEvent> withSignal(List<WorldEvent> events, String world) {
        var out = new ArrayList<>(events);
        for (var e : events) {
            if (!e.kind().equals(Auctions.CLOSE) || e.get("buyer", "").isEmpty()) continue;
            var listing = book().listings().get(e.subject());
            if (listing == null) {
                // Listed and filled in the same decision: its listing is in this batch.
                var list = events.stream().filter(x -> x.kind().equals(Auctions.LIST) && x.subject().equals(e.subject())).findFirst().orElse(null);
                if (list == null) continue;
                listing = new AuctionBook.Listing(list.subject(), list.actor(), list.get("key", ""), list.get("item", ""), 0, 0, 0, null, 0);
            }
            int count = events.stream().filter(x -> x.kind().equals(Auctions.LIST) && x.subject().equals(e.subject())).findFirst().map(Auctions::count).orElse(1);
            Auctions.signal(pricing.get(), ledgers.get().market(), book(), listing, UUID.fromString(e.get("buyer", "")), count, at(world)).ifPresent(out::add);
        }
        return out;
    }

    /** The open listings as lines: id, item, count, price, time left. */
    List<String> lines(UUID only) {
        var out = new ArrayList<String>();
        long now = now();
        for (var l : book().listings().values()) {
            if (only != null && !l.seller().equals(only)) continue;
            out.add(l.id() + " " + l.key() + " " + (l.bidder() == null ? "from " + Coins.show(l.start()) : "bid " + Coins.show(l.bid()))
                    + (l.buyNow() > 0 ? ", buy now " + Coins.show(l.buyNow()) : "") + ", " + Math.max(0, (l.closesAt() - now) / 60_000) + " min left");
        }
        for (var o : book().orders().values()) if (only == null || o.buyer().equals(only)) out.add(o.id() + " wants " + o.count() + " " + o.key() + " at " + Coins.show(o.each()));
        if (out.isEmpty()) out.add("Nothing open");
        return out;
    }
}
