package dev.kindred.fabric;

import dev.kindred.core.eval.ItemSourceLedger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * In plain terms: during an evaluation run only, a check that every item a player came to hold came from somewhere.
 * Each second the players' inventories are counted; each rise must be paid for by a source the game reported: a drop
 * picked up (one no command made), a screen's craft, smelt, trade or chest take, an item used (a bucket filled, a bowl
 * left by a stew), or one of Kindred's own deliveries (a market purchase, an auction's hand-over, a chest taken from, a
 * rule's hand-out). An item from nowhere is a violation in the run's result. Outside an evaluation run nothing here
 * does anything.
 *
 * Technical: The item-source audit's adapter (M-06k), server thread only. Inert unless {@link #on}: each hook's first
 * statement tests it, with no allocation when off. Drops are tagged by their entity's UUID in a bounded map, never on
 * the stack (a component would stop stacks merging): {@link #spawned} classifies the spawning call by the stack's
 * frames ({@code command} when a command made it, by Brigadier's frames: a production jar keeps those names), {@link #removed} forgets it when the entity is destroyed. A source
 * is a span {@link #enter} .. {@link #exit} around a vanilla entry point or a Kindred delivery: the player's counts are
 * taken at the outermost enter and each rise at its exit is credited to the source; nested spans credit nothing of
 * their own. A refused source ({@code refused:...}) credits nothing, so what it brought in is a violation. Counts are
 * the inventory (main, armour, offhand), the cursor, and any crafting grid of the player's own and open screens, by an
 * item key: the item's id with its components less those that are its state rather than what it is
 * ({@link #STATE}: damage, a crossbow's load, a compass's lodestone, a bundle's contents, repair cost).
 */
public final class EvalItemSources {
    private EvalItemSources() { }

    /** Whether an audit is running: read first by every hook. */
    public static volatile boolean on;

    /** How often the inventories are counted. */
    static final int WINDOW_TICKS = 20;
    /** At most this many drops are remembered; the oldest is forgotten first and counted. */
    static final int ORIGINS = 200_000;

    /** Components that are an item's state, not what it is: a change in them is not a new item. */
    static final Set<ComponentType<?>> STATE = Set.of(DataComponentTypes.DAMAGE, DataComponentTypes.CHARGED_PROJECTILES,
            DataComponentTypes.LODESTONE_TRACKER, DataComponentTypes.BUNDLE_CONTENTS, DataComponentTypes.REPAIR_COST);

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static ItemSourceLedger ledger;
    private static final Map<UUID, String> origins = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
            if (size() <= ORIGINS) return false;
            forgotten++;
            return true;
        }
    };
    private static final Map<String, Long> originCounts = new TreeMap<>();
    private static final Map<UUID, Span> spans = new HashMap<>();
    private static long forgotten, snapshots, snapshotNanos, snapshotNanosMax, untaggedPickups, spawns, spawnNanos, spawnNanosMax;
    private static int ticks;

    private record Span(Map<String, Integer> before, int[] depth) { }

    /** Starts an evaluation run's audit: every player from an empty inventory. */
    public static void start() { start(true); }

    /** Starts an audit; {@code fromEmpty} false for a fixture, whose staged items are each player's baseline. */
    public static void start(boolean fromEmpty) {
        ledger = new ItemSourceLedger(fromEmpty);
        origins.clear();
        originCounts.clear();
        spans.clear();
        forgotten = snapshots = snapshotNanos = snapshotNanosMax = untaggedPickups = spawns = spawnNanos = spawnNanosMax = 0;
        ticks = 0;
        on = true;
    }

    /** Stops it; the report stays readable. */
    public static void stop() { on = false; }

    /** Each server tick while on: every {@link #WINDOW_TICKS} the players are counted and the window ends. */
    public static void tick(MinecraftServer server) {
        if (!on) return;
        if (++ticks % WINDOW_TICKS != 0) return;
        snapshot(server);
    }

    /** Counts every player now and ends the window (a fixture calls it directly). */
    public static void snapshot(MinecraftServer server) {
        if (!on) return;
        long start = System.nanoTime();
        long tick = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ledger.observe(player.getUuid(), player.getGameProfile().getName(), counts(player), tick);
        }
        ledger.endWindow();
        spans.clear();
        long took = System.nanoTime() - start;
        snapshots++;
        snapshotNanos += took;
        snapshotNanosMax = Math.max(snapshotNanosMax, took);
    }

    /** The report so far, with the drops' origins and the snapshot's cost. */
    public static Result result() {
        return new Result(ledger == null ? new ItemSourceLedger().report() : ledger.report(), new TreeMap<>(originCounts), untaggedPickups, forgotten,
                snapshots == 0 ? 0 : snapshotNanos / 1000.0 / snapshots, snapshotNanosMax / 1000.0,
                spawns == 0 ? 0 : spawnNanos / 1000.0 / spawns, spawnNanosMax / 1000.0);
    }

    /** The audit's result: the ledger's report, drops by origin, and in microseconds the cost of a snapshot and of a drop's tagging. */
    public record Result(ItemSourceLedger.Report report, Map<String, Long> origins, long untaggedPickups, long forgottenDrops,
                         double snapshotMicrosMean, double snapshotMicrosMax, double tagMicrosMean, double tagMicrosMax) { }

    /** A drop came into the world: remembered by its UUID with where it came from. */
    public static void spawned(Entity entity) {
        if (!on || !(entity instanceof ItemEntity)) return;
        long start = System.nanoTime();
        String origin = WALKER.walk(frames -> {
            String found = null;
            for (var it = frames.iterator(); it.hasNext(); ) {
                var f = it.next();
                Class<?> c = f.getDeclaringClass();
                // Every command runs through Brigadier, whose names a production jar keeps (Minecraft's own are intermediary there).
                if (c.getName().startsWith("com.mojang.brigadier.")) return "command";
                if (found == null && c != EvalItemSources.class && !net.minecraft.world.World.class.isAssignableFrom(c) && c != Entity.class
                        && c != ItemEntity.class && !c.getName().startsWith("java.")) {
                    found = c.getSimpleName() + "." + f.getMethodName();
                }
            }
            return found == null ? "unknown" : found;
        });
        origins.put(entity.getUuid(), origin);
        originCounts.merge(origin, 1L, Long::sum);
        long took = System.nanoTime() - start;
        spawns++;
        spawnNanos += took;
        spawnNanosMax = Math.max(spawnNanosMax, took);
    }

    /** A drop left the world for good (picked up whole, despawned, destroyed); not on a chunk's unloading. */
    public static void removed(Entity entity, Entity.RemovalReason reason) {
        if (!on || !(entity instanceof ItemEntity) || !reason.shouldDestroy()) return;
        origins.remove(entity.getUuid());
    }

    /** The source a drop's pickup is: {@code refused:command} for a drop a command made, else its origin. */
    public static String pickupSource(Entity drop) {
        String origin = origins.get(drop.getUuid());
        if (origin == null) {
            untaggedPickups++;
            return "refused:untagged";
        }
        return "command".equals(origin) ? "refused:command" : "pickup:" + origin;
    }

    /** The source a screen's click is: Kindred's own screens hand out nothing themselves. */
    public static String screenSource(ScreenHandler handler) {
        if (handler instanceof AuctionScreenHandler || handler instanceof MarketScreenHandler || handler instanceof KindredControlScreenHandler) {
            return "refused:kindred-screen";
        }
        return "screen:" + handler.getClass().getSimpleName();
    }

    /** A source's span begins for {@code player} (the outermost span counts the player). */
    public static void enter(Entity entity) {
        if (!on || !(entity instanceof ServerPlayerEntity player)) return;
        var span = spans.get(player.getUuid());
        if (span != null) {
            span.depth[0]++;
            return;
        }
        spans.put(player.getUuid(), new Span(counts(player), new int[] {1}));
    }

    /** A source's span ends: at the outermost, each rise since its enter is credited to {@code source}. */
    public static void exit(Entity entity, String source) {
        if (!on || !(entity instanceof ServerPlayerEntity player)) return;
        var span = spans.get(player.getUuid());
        if (span == null || --span.depth[0] > 0) return;
        spans.remove(player.getUuid());
        if (source.startsWith("refused:")) return;
        var now = counts(player);
        for (var e : now.entrySet()) {
            int rise = e.getValue() - span.before.getOrDefault(e.getKey(), 0);
            if (rise > 0) ledger.credit(player.getUuid(), e.getKey(), rise, source);
        }
    }

    /** What {@code player} holds, by item key: inventory, cursor, and its own and its open screen's crafting grids. */
    static Map<String, Integer> counts(ServerPlayerEntity player) {
        var out = new HashMap<String, Integer>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) add(out, inventory.getStack(i));
        add(out, player.currentScreenHandler.getCursorStack());
        grids(out, player.playerScreenHandler);
        if (player.currentScreenHandler != player.playerScreenHandler) grids(out, player.currentScreenHandler);
        return out;
    }

    private static void grids(Map<String, Integer> out, ScreenHandler handler) {
        RecipeInputInventory seen = null;
        for (var slot : handler.slots) {
            if (slot.inventory instanceof RecipeInputInventory grid && grid != seen) {
                seen = grid;
                for (int i = 0; i < grid.size(); i++) add(out, grid.getStack(i));
            }
        }
    }

    private static void add(Map<String, Integer> out, ItemStack stack) {
        if (stack.isEmpty()) return;
        out.merge(key(stack), stack.getCount(), Integer::sum);
    }

    /** An item's key: its id, and a hash of its components less its state ({@link #STATE}). */
    static String key(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        var changes = stack.getComponentChanges();
        if (changes.isEmpty()) return id;
        int hash = 0;
        boolean any = false;
        for (var e : changes.entrySet()) {
            if (STATE.contains(e.getKey())) continue;
            hash += e.getKey().hashCode() ^ java.util.Objects.hashCode(e.getValue());
            any = true;
        }
        return any ? id + "#" + Integer.toHexString(hash) : id;
    }
}
