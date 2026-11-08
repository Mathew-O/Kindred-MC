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
 * the {@code auction.list} event; it is taken from the hand, the player saved, then the listing written (a failed write
 * gives it back). Closes are found once a second from the ledger itself (the rules read the ledgers with unwritten
 * events folded in, so a close is never asked twice); a restart rebuilds them. A delivery writes
 * {@code auction.delivering} first, then the stack is handed over, the player saved and {@code auction.delivered}
 * written: a crash in between is reported for an operator, never handed over twice.
 */
final class AuctionHouse {
    private final MinecraftServer server;
    private final Supplier<Ledgers> ledgers;
    private final MarketDesk.Recorder recorder;
    private final Supplier<FactionSettings> settings;
    private final Supplier<dev.kindred.core.market.Pricing> pricing;
    private final Set<String> delivering = new HashSet<>();
    private long clockOffset;
    private int next;

    AuctionHouse(MinecraftServer server, Supplier<Ledgers> ledgers, MarketDesk.Recorder recorder, Supplier<FactionSettings> settings,
                 Supplier<dev.kindred.core.market.Pricing> pricing) {
        this.server = server;
        this.ledgers = ledgers;
        this.recorder = recorder;
        this.settings = settings;
        this.pricing = pricing;
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
        var facts = MarketDesk.facts(hand);
        var key = Canonical.of(new dev.kindred.core.market.ItemFacts(facts.id(), facts.count(), facts.damage(), facts.maxDamage(), facts.enchantments(),
                facts.potion(), false, Set.of()), pricing.get().table());
        if (facts.holdsItems()) { done.accept("A container with things in it: empty it first"); return; }
        if (!key.accepted()) { done.accept(key.refusal()); return; }
        String id = "a" + Long.toString(now(), 36) + (next++);
        String item = hand.encode(server.getRegistryManager()).toString();
        var decision = Auctions.list(book(), ledgers.get().coins(), settings.get(), seller.getUuid(), id, key.key(), item, hand.getCount(), start, buyNow, hours, at(world));
        if (!decision.accepted()) { done.accept(decision.reason()); return; }
        int slot = seller.getInventory().selectedSlot;
        var taken = seller.getInventory().removeStack(slot);
        MarketDesk.save(seller);
        var events = withSignal(decision.events(), world);
        UUID who = seller.getUuid();
        recorder.record(events, () -> done.accept(decision.reason() + " (" + id + ")"), failure -> {
            var back = server.getPlayerManager().getPlayer(who);
            if (back != null) {
                back.getInventory().offerOrDrop(taken);
                MarketDesk.save(back);
            } else KindredMod.LOGGER.error("Listing {} not written and its seller left: {} to return by an operator", id, taken);
            done.accept("The listing could not be written; the item is back");
        });
    }

    /** A bid, or a buy-now with {@code amount} the buy-now price. */
    String bid(ServerPlayerEntity bidder, String id, long amount, String world, Consumer<String> done) {
        var decision = Auctions.bid(book(), ledgers.get().coins(), settings.get(), bidder.getUuid(), id, amount, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(withSignal(decision.events(), world), () -> done.accept(decision.reason()), failure -> done.accept("The bid could not be written"));
        return null;
    }

    String cancel(ServerPlayerEntity seller, String id, String world, Consumer<String> done) {
        var decision = Auctions.cancel(book(), seller.getUuid(), id, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(decision.events(), () -> done.accept(decision.reason()), failure -> done.accept("Not written"));
        return null;
    }

    String order(ServerPlayerEntity buyer, String item, int count, long each, String world, Consumer<String> done) {
        String key = dev.kindred.core.market.MarketTable.id(item);
        String id = "o" + Long.toString(now(), 36) + (next++);
        var decision = Auctions.order(ledgers.get().coins(), buyer.getUuid(), id, key, count, each, at(world));
        if (!decision.accepted()) return decision.reason();
        recorder.record(decision.events(), () -> done.accept(decision.reason() + " (" + id + ")"), failure -> done.accept("Not written"));
        return null;
    }

    String cancelOrder(ServerPlayerEntity buyer, String id, String world, Consumer<String> done) {
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
            if (player == null) continue;
            delivering.add(delivery.id());
            var at = at("");
            recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), "", Auctions.DELIVERING, delivery.to(), delivery.id(), "", Map.of())),
                    () -> handOver(delivery),
                    failure -> delivering.remove(delivery.id()));
        }
    }

    private void handOver(AuctionBook.Delivery delivery) {
        var player = server.getPlayerManager().getPlayer(delivery.to());
        if (player == null) {
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
            KindredMod.LOGGER.error("Delivery {} holds an unreadable item: reconcile by hand", delivery.id());
            return;
        }
        player.getInventory().offerOrDrop(stack.get());
        MarketDesk.save(player);
        var at = at("");
        recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), "", Auctions.DELIVERED, delivery.to(), delivery.id(), "", Map.of("why", delivery.why()))),
                () -> delivering.remove(delivery.id()), failure -> KindredMod.LOGGER.error("Delivery {} handed over; its receipt not written", delivery.id(), failure));
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
