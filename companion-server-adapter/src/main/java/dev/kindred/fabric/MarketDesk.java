package dev.kindred.fabric;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.Factions;
import dev.kindred.core.market.ItemFacts;
import dev.kindred.core.market.Market;
import dev.kindred.core.market.MarketBook;
import dev.kindred.core.market.MarketTable;
import dev.kindred.core.market.Pricing;
import dev.kindred.core.market.Recipe;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * In plain terms: the market's counter: it describes a player's stacks to the market, quotes them, and settles a sale so
 * that the items and the coins move together or not at all, a disconnect in between leaving both where they were.
 *
 * Technical: The adapter's side of M-06g, on the runtime's thread. A sale is three steps: a {@code market.intent} is
 * written; once it is, the player and the stacks are checked again (gone, changed or offline: a {@code market.cancel},
 * nothing moved), the items are taken in that same tick and the player's state snapshotted for its file, which is
 * written off the server thread ({@link PlayerSaves}); only once the file is in place is the {@code market.sale} with
 * the seller's {@code coins} written, as one transaction. If that write fails the items are given back, since nothing
 * was paid. A purchase writes its coins and {@code market.buy} first, then hands the items over, saves the player the
 * same way and writes {@code market.received}. While a trade is between its first write and its last, the player's
 * further market and auction requests are held ({@link Holds}). A save that fails is a {@code market.uncertain} with
 * the items' contents, for an operator, nothing paid and nothing given back blindly; a crash in between leaves an intent
 * or a purchase the Record holds unfinished, reported the same way at the next start. So a crash can lose a sale's
 * items or a purchase's, never duplicate them, and nothing is ever replayed.
 */
final class MarketDesk {
    static final String INTENT = "market.intent", CANCEL = "market.cancel", RECEIVED = "market.received", UNCERTAIN = "market.uncertain";
    /** The server's own market kinds, registered as an integration would register its own (F-31: event kinds are a registry). */
    static final String KINDS_OWNER = "kindred-server";

    static {
        for (String kind : java.util.List.of(INTENT, CANCEL, RECEIVED, UNCERTAIN)) dev.kindred.core.record.EventKinds.register(kind, KINDS_OWNER);
    }
    static final String HELD = "Your last trade is still being saved; try again in a moment";
    /** Data components the market reads or knowingly ignores; any other changed component makes a stack pending. */
    private static final Set<ComponentType<?>> KNOWN = Set.of(DataComponentTypes.DAMAGE, DataComponentTypes.ENCHANTMENTS,
            DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.POTION_CONTENTS, DataComponentTypes.CONTAINER,
            DataComponentTypes.BUNDLE_CONTENTS, DataComponentTypes.CHARGED_PROJECTILES, DataComponentTypes.CUSTOM_NAME,
            DataComponentTypes.REPAIR_COST, DataComponentTypes.MAP_ID, DataComponentTypes.MAP_DECORATIONS, DataComponentTypes.MAP_POST_PROCESSING,
            DataComponentTypes.WRITTEN_BOOK_CONTENT, DataComponentTypes.WRITABLE_BOOK_CONTENT, DataComponentTypes.PROFILE,
            DataComponentTypes.BANNER_PATTERNS, DataComponentTypes.BASE_COLOR, DataComponentTypes.TRIM, DataComponentTypes.FIREWORKS,
            DataComponentTypes.FIREWORK_EXPLOSION, DataComponentTypes.SUSPICIOUS_STEW_EFFECTS, DataComponentTypes.DYED_COLOR,
            DataComponentTypes.LODESTONE_TRACKER, DataComponentTypes.INSTRUMENT, DataComponentTypes.BLOCK_ENTITY_DATA,
            DataComponentTypes.CONTAINER_LOOT, DataComponentTypes.POT_DECORATIONS, DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER);

    private final MinecraftServer server;
    private final Supplier<Ledgers> ledgers;
    private final Recorder recorder;
    private final Holds holds = new Holds();
    private Pricing pricing;
    private int nextIntent;

    /**
     * Players with a trade between its first write and its last (M-06g, M-06h): their market and auction requests wait.
     * On the runtime's thread.
     */
    static final class Holds {
        private final Set<UUID> held = new java.util.HashSet<>();
        boolean held(UUID player) { return held.contains(player); }
        /** False when the player is already held. */
        boolean hold(UUID player) { return held.add(player); }
        void release(UUID player) { held.remove(player); }
    }

    /** Writes events, then runs one of its callbacks on the runtime's thread. */
    interface Recorder {
        void record(List<WorldEvent> events, Runnable written, Consumer<Throwable> failed);
    }

    MarketDesk(MinecraftServer server, Supplier<Ledgers> ledgers, Recorder recorder) {
        this.server = server;
        this.ledgers = ledgers;
        this.recorder = recorder;
    }

    /** The market priced from the bundled table and the server's own recipes, read once the server has them. */
    Pricing pricing() {
        if (pricing == null) pricing = new Pricing(MarketTable.bundled(), recipes(server));
        return pricing;
    }

    MarketBook book() { return ledgers.get().market(); }

    Holds holds() { return holds; }

    /** The server's recipes as the market sees them: crafting, cooking, stonecutting and smithing upgrades. */
    static List<Recipe> recipes(MinecraftServer server) {
        var out = new ArrayList<Recipe>();
        var lookup = server.getRegistryManager();
        for (var entry : server.getRecipeManager().values()) {
            var recipe = entry.value();
            try {
                ItemStack result;
                List<net.minecraft.recipe.Ingredient> parts;
                if (recipe instanceof net.minecraft.recipe.SmithingTransformRecipe smithing) {
                    var parts3 = (dev.kindred.fabric.mixin.SmithingTransformAccessor) smithing;
                    result = parts3.kindred$result();
                    parts = List.of(parts3.kindred$template(), parts3.kindred$base(), parts3.kindred$addition());
                } else {
                    result = recipe.getResult(lookup);
                    parts = recipe.getIngredients();
                }
                if (result.isEmpty()) continue;
                var ingredients = new ArrayList<Set<String>>();
                for (var part : parts) {
                    if (part.isEmpty()) continue;
                    var options = new java.util.TreeSet<String>();
                    for (var stack : part.getMatchingStacks()) options.add(Registries.ITEM.getId(stack.getItem()).toString());
                    if (!options.isEmpty()) ingredients.add(options);
                }
                if (ingredients.isEmpty()) continue;
                out.add(new Recipe(entry.id().toString(), Registries.ITEM.getId(result.getItem()).toString(), result.getCount(), ingredients));
            } catch (RuntimeException special) {
                // A special recipe (map cloning, firework stars) lists nothing fixed: left out, which only leaves its output unpriced.
            }
        }
        return out;
    }

    /** A stack as the market may know it. */
    static ItemFacts facts(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        int damage = stack.isDamageable() ? stack.getDamage() : 0, max = stack.isDamageable() ? stack.getMaxDamage() : 0;
        var enchantments = new LinkedHashMap<String, Integer>();
        for (var component : List.of(DataComponentTypes.ENCHANTMENTS, DataComponentTypes.STORED_ENCHANTMENTS)) {
            var levels = stack.get(component);
            if (levels == null) continue;
            for (var e : levels.getEnchantmentEntries()) e.getKey().getKey().ifPresent(k -> enchantments.merge(k.getValue().toString(), e.getIntValue(), Math::max));
        }
        String potion = "";
        var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents != null && contents.potion().isPresent()) potion = contents.potion().get().getKey().map(k -> k.getValue().toString()).orElse("");
        boolean holds = false;
        var container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && container.stream().anyMatch(s -> !s.isEmpty())) holds = true;
        var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) holds = true;
        var charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged != null && !charged.isEmpty()) holds = true;
        if (stack.get(DataComponentTypes.BLOCK_ENTITY_DATA) != null || stack.get(DataComponentTypes.CONTAINER_LOOT) != null) holds = true;
        var unknown = new java.util.TreeSet<String>();
        for (var change : stack.getComponentChanges().entrySet()) {
            if (KNOWN.contains(change.getKey())) continue;
            var key = Registries.DATA_COMPONENT_TYPE.getId(change.getKey());
            unknown.add(key == null ? "unknown" : key.toString());
        }
        return new ItemFacts(id, stack.getCount(), damage, max, enchantments, potion, holds, unknown);
    }

    /** What the stacks in {@code slots} of {@code player}'s inventory would pay, grouped by market key; refusals by reason. */
    record Estimate(Map<String, Market.Quote> quotes, Map<Integer, String> refused, long cents) {}

    Estimate estimate(ServerPlayerEntity player, List<Integer> slots) {
        var inventory = player.getInventory();
        var bySlot = new LinkedHashMap<Integer, ItemFacts>();
        var refused = new LinkedHashMap<Integer, String>();
        for (int slot : slots) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            bySlot.put(slot, facts(stack));
        }
        // Stacks of one key sold as one walk down its curve.
        var counts = new LinkedHashMap<String, Integer>();
        var sample = new LinkedHashMap<String, ItemFacts>();
        for (var e : bySlot.entrySet()) {
            var key = dev.kindred.core.market.Canonical.of(e.getValue(), pricing().table());
            if (!key.accepted()) { refused.put(e.getKey(), key.refusal()); continue; }
            counts.merge(key.key(), e.getValue().count(), Integer::sum);
            sample.putIfAbsent(key.key(), e.getValue());
        }
        var quotes = new LinkedHashMap<String, Market.Quote>();
        MarketBook walking = book();
        long total = 0;
        long now = System.currentTimeMillis();
        for (var e : counts.entrySet()) {
            var f = sample.get(e.getKey());
            var all = new ItemFacts(f.id(), e.getValue(), f.damage(), f.maxDamage(), f.enchantments(), f.potion(), false, Set.of());
            var quote = Market.quoteSale(pricing(), walking, all, now);
            if (!quote.accepted()) {
                for (var s : bySlot.entrySet()) if (s.getValue().id().equals(f.id())) refused.put(s.getKey(), quote.refusal());
                continue;
            }
            quotes.put(e.getKey(), quote);
            walking = walking.with(quote.after());
            total += quote.cents();
        }
        return new Estimate(quotes, refused, total);
    }

    /**
     * {@code player} sells what is in {@code slots} (the hand's, or the whole inventory's). {@code done} is told the
     * outcome once it is settled or cancelled.
     */
    void sell(ServerPlayerEntity player, List<Integer> slots, String world, Consumer<String> done) {
        var estimate = estimate(player, slots);
        if (estimate.quotes().isEmpty()) {
            done.accept(estimate.refused().isEmpty() ? "Nothing to sell" : "Refused: " + estimate.refused().values().iterator().next());
            return;
        }
        UUID seller = player.getUuid();
        if (!holds.hold(seller)) { done.accept(HELD); return; }
        String intent = seller.toString().substring(0, 8) + "-" + System.currentTimeMillis() + "-" + (nextIntent++);
        var snapshot = new LinkedHashMap<Integer, ItemStack>();
        for (int slot : slots) {
            var stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && !estimate.refused().containsKey(slot)) snapshot.put(slot, stack.copy());
        }
        var at = new Factions.At(server.getTicks(), System.currentTimeMillis(), world);
        var data = new LinkedHashMap<String, String>();
        data.put("intent", intent);
        data.put("cents", String.valueOf(estimate.cents()));
        data.put("items", String.join(",", estimate.quotes().keySet()));
        recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), world, INTENT, seller, intent, "", data)),
                () -> settle(seller, intent, snapshot, world, released(seller, done)),
                failure -> released(seller, done).accept("The market could not take the sale; nothing moved"));
    }

    private void settle(UUID seller, String intent, Map<Integer, ItemStack> snapshot, String world, Consumer<String> done) {
        var player = server.getPlayerManager().getPlayer(seller);
        var at = new Factions.At(server.getTicks(), System.currentTimeMillis(), world);
        String why = null;
        if (player == null || player.isDisconnected() || player.isRemoved()) why = "the seller left";
        else for (var e : snapshot.entrySet()) {
            if (!ItemStack.areEqual(player.getInventory().getStack(e.getKey()), e.getValue())) { why = "the stacks changed"; break; }
        }
        if (why != null) {
            cancel(seller, intent, why, at);
            done.accept("Sale cancelled (" + why + "); nothing moved");
            return;
        }
        // Prices again now, from the stacks as they are: the quote may have moved since the intent.
        var estimate = estimate(player, List.copyOf(snapshot.keySet()));
        if (estimate.quotes().isEmpty()) {
            cancel(seller, intent, "nothing sellable", at);
            done.accept("Sale cancelled; nothing moved");
            return;
        }
        var taken = new LinkedHashMap<Integer, ItemStack>();
        for (int slot : snapshot.keySet()) {
            if (estimate.refused().containsKey(slot)) continue;
            taken.put(slot, player.getInventory().removeStack(slot));
        }
        // The items gone from the player's own file before any coin is written: a crash now loses them (reported), never
        // leaves them both sold and still held. The file is written off the server thread; the coins wait for it.
        long cents = estimate.cents();
        whenSaved(player, () -> {
            var events = new ArrayList<WorldEvent>();
            var coins = ledgers.get().coins();
            var paid = new Factions.At(server.getTicks(), System.currentTimeMillis(), world);
            for (var quote : estimate.quotes().values()) {
                var sale = Market.sell(quote, coins, seller, intent, paid);
                events.addAll(sale);
                coins = Coins.INSTANCE.apply(coins, sale.get(1));
            }
            recorder.record(events, () -> done.accept("Sold for " + Coins.show(cents) + " coins"), failure -> {
                // Nothing was paid: the stacks go back where they were, and the file is written again with them.
                var back = server.getPlayerManager().getPlayer(seller);
                EvalItemSources.enter(back);
                for (var e : taken.entrySet()) {
                    if (back != null && back.getInventory().getStack(e.getKey()).isEmpty()) back.getInventory().setStack(e.getKey(), e.getValue());
                    else if (back != null) back.getInventory().offerOrDrop(e.getValue());
                    else KindredMod.LOGGER.error("Sale {} failed and its seller left: {} x{} to return by an operator", intent, e.getValue().getItem(), e.getValue().getCount());
                }
                EvalItemSources.exit(back, "market.return");
                if (back != null) save(back);
                cancel(seller, intent, "the sale could not be written", new Factions.At(server.getTicks(), System.currentTimeMillis(), world));
                done.accept("The sale could not be written; the items are back");
            });
        }, failure -> {
            uncertain(seller, intent, world, "the seller's file could not be written", taken.values(), cents);
            done.accept("The sale could not be saved; nothing was paid and an operator is told");
        });
    }

    /**
     * The player's state snapshotted now and its file written off the server thread; then {@code saved}, or
     * {@code failed} with the file still its earlier state, back on the server thread.
     */
    void whenSaved(ServerPlayerEntity player, Runnable saved, Consumer<Throwable> failed) {
        PlayerSaves.save(player).whenComplete((ignored, failure) -> server.execute(() -> {
            if (failure == null) saved.run();
            else failed.accept(failure);
        }));
    }

    /** The player's file written now, in the background, ordered after any trade's save of it. */
    static void save(ServerPlayerEntity player) {
        if (player.isRemoved() || player.getServer().getPlayerManager().getPlayer(player.getUuid()) != player) return;
        PlayerSaves.save(player).whenComplete((ignored, failure) -> {
            if (failure != null) KindredMod.LOGGER.error("The file of {} could not be written after a trade was undone", player.getUuid(), failure);
        });
    }

    /** A trade whose items cannot be shown to be in the player's file: reported for an operator with the items' contents. */
    void uncertain(UUID who, String id, String world, String why, java.util.Collection<ItemStack> items, long cents) {
        var data = new LinkedHashMap<String, String>();
        data.put("why", why);
        data.put("cents", String.valueOf(cents));
        var contents = new ArrayList<String>();
        for (var item : items) contents.add(item.encode(server.getRegistryManager()).toString());
        data.put("items", String.join("\n", contents));
        KindredMod.LOGGER.error("Market {} by {} is uncertain ({}): reconcile its items and coins by hand: {}", id, who, why, contents);
        var at = new Factions.At(server.getTicks(), System.currentTimeMillis(), world);
        recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), world, UNCERTAIN, who, id, "", data)), () -> { },
                failure -> KindredMod.LOGGER.error("Market {} uncertain, and that not written", id, failure));
    }

    /** {@code done}, with the player's hold released first. */
    private Consumer<String> released(UUID player, Consumer<String> done) {
        return text -> {
            holds.release(player);
            done.accept(text);
        };
    }

    private void cancel(UUID seller, String intent, String why, Factions.At at) {
        recorder.record(List.of(WorldEvent.recorded(at.tick(), at.realTimeMs(), at.world(), CANCEL, seller, intent, "", Map.of("why", why))),
                () -> { }, failure -> KindredMod.LOGGER.error("Sale {} cancel not written", intent, failure));
    }

    /** {@code player} buys {@code count} of {@code item}: the coins first, then the items handed over in the same tick. */
    String buy(ServerPlayerEntity player, String item, int count, String world, Consumer<String> done) {
        var quote = Market.quoteBuy(pricing(), book(), item, count, System.currentTimeMillis());
        if (!quote.accepted()) return quote.refusal();
        var itemType = Registries.ITEM.get(net.minecraft.util.Identifier.of(MarketTable.id(item)));
        var at = new Factions.At(server.getTicks(), System.currentTimeMillis(), world);
        var events = Market.buy(quote, ledgers.get().coins(), player.getUuid(), at);
        if (events.isEmpty()) return "Costs " + Coins.show(quote.cents()) + " coins; you have " + Coins.show(ledgers.get().coins().of(player.getUuid()));
        UUID buyer = player.getUuid();
        if (!holds.hold(buyer)) return HELD;
        Consumer<String> finish = released(buyer, done);
        String purchase = buyer.toString().substring(0, 8) + "-" + System.currentTimeMillis() + "-" + (nextIntent++);
        var written = new ArrayList<WorldEvent>();
        for (var e : events.get()) {
            if (!e.kind().equals(dev.kindred.core.market.MarketLedger.BUY)) { written.add(e); continue; }
            var data = new LinkedHashMap<>(e.data());
            data.put("purchase", purchase);
            written.add(WorldEvent.recorded(e.tick(), e.realTimeMs(), e.world(), e.kind(), e.actor(), e.subject(), e.place(), data));
        }
        recorder.record(written, () -> {
            var now = server.getPlayerManager().getPlayer(buyer);
            if (now == null) {
                KindredMod.LOGGER.error("Purchase {} paid by {} who left: {} x{} owed", purchase, buyer, item, count);
                finish.accept("Paid, but the buyer left: an operator hands over the items");
                return;
            }
            var handed = new ArrayList<ItemStack>();
            int left = count;
            EvalItemSources.enter(now);
            while (left > 0) {
                int n = Math.min(left, itemType.getMaxCount());
                handed.add(new ItemStack(itemType, n));
                now.getInventory().offerOrDrop(new ItemStack(itemType, n));
                left -= n;
            }
            EvalItemSources.exit(now, "market.purchase");
            whenSaved(now, () -> {
                var at2 = new Factions.At(server.getTicks(), System.currentTimeMillis(), world);
                recorder.record(List.of(WorldEvent.recorded(at2.tick(), at2.realTimeMs(), world, RECEIVED, buyer, purchase, "", Map.of())),
                        () -> finish.accept("Bought " + count + " " + MarketTable.id(item) + " for " + Coins.show(quote.cents()) + " coins"),
                        failure -> {
                            KindredMod.LOGGER.error("Purchase {} handed over; its receipt not written", purchase, failure);
                            finish.accept("Bought " + count + " " + MarketTable.id(item) + "; its receipt could not be written, an operator is told");
                        });
            }, failure -> {
                uncertain(buyer, purchase, world, "the buyer's file could not be written", handed, quote.cents());
                finish.accept("Bought, but your file could not be saved; an operator is told");
            });
        }, failure -> finish.accept("The purchase could not be written; nothing moved"));
        return null;
    }

    /** A price line for {@code item}: what the market pays and asks for one, and its day's change. */
    String priceLine(String item) {
        String id = MarketTable.id(item);
        long now = System.currentTimeMillis();
        var price = pricing().price(book(), id, now);
        if (price.isEmpty()) return id + ": valuation pending";
        var own = pricing().own(book(), id, now);
        String buy = own.isPresent() && own.get().roots().keySet().stream().allMatch(r -> pricing().root(book(), r).orElseThrow().buyable())
                ? ", asks " + Coins.show(Pricing.buyPrice(own.get().mid())) : ", not sold";
        String change = pricing().root(book(), id).map(r -> String.format(java.util.Locale.ROOT, ", %+.1f%% today", 100 * Market.dayChange(pricing(), book(), id, now))).orElse("");
        return id + ": pays " + Coins.show(Pricing.sellPrice(price.get().mid())) + buy + change;
    }

    /** The five raw materials whose price moved most over the last day, up or down. */
    List<String> movers() {
        long now = System.currentTimeMillis();
        var roots = new ArrayList<>(book().states().keySet());
        roots.removeIf(r -> pricing().root(book(), r).isEmpty());
        roots.sort(java.util.Comparator.comparingDouble((String r) -> -Math.abs(Market.dayChange(pricing(), book(), r, now))));
        var out = new ArrayList<String>();
        for (String r : roots.subList(0, Math.min(5, roots.size()))) out.add(priceLine(r));
        if (out.isEmpty()) out.add("No trades yet");
        return out;
    }
}
