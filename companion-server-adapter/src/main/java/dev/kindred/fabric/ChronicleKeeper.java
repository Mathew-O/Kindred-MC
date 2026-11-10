package dev.kindred.fabric;

import dev.kindred.core.chronicle.Chronicle;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.model.ChroniclePrompt;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: writes each AI player's page of the Chronicle once a game day, from what the World Record holds of its
 * day: by the model when there is one and its budget allows, else as the plain list; checked before it is kept; and kept
 * in the Record itself, so a restart loses none. The strategist then reads a player's latest page instead of its whole
 * list of events.
 *
 * Technical: F-33b's nightly run, wired to F-31. Server thread only; the Record is read on the ordered writer
 * ({@link Host#events}) and pages written through {@link Host#record}, never blocking a tick. The batch is everything of
 * {@link Chronicle#DAY_KINDS} after the last page's {@code through}; each event is a player's when its actor is that
 * player's id or body. A page is a {@code chronicle.page} event: actor the player, subject its id; {@code CLAIMED} when a
 * model wrote it, {@code RECORDED} for the plain list. Model pages are checked against the batch's ids and its deaths.
 */
final class ChronicleKeeper {
    /** A page's batch is read at most this many events at a time. */
    static final int BATCH = 10_000;
    /** Pages kept in memory a player, newest last. */
    static final int KEPT = 7;

    /** One AI player as the keeper sees it: its id, its name, every id its events may carry (its own, its body's), its faction ("" for none). */
    record Player(UUID id, String name, Set<UUID> actors, String faction) {
        Player(UUID id, String name, Set<UUID> actors) { this(id, name, actors, ""); }

        Player { faction = faction == null ? "" : faction; }
    }

    /** The runtime's side. */
    interface Host {
        CompletableFuture<List<WorldEvent>> events(Set<String> kinds, long afterId, int limit);

        void record(WorldEvent event);

        List<Player> players();

        long tick();

        /** The strategist's bridge, or null without one. */
        StrategistBridge bridge();

        MinecraftServer server();
    }

    private final Host host;
    private final Map<UUID, List<Chronicle.Summary>> pages = new HashMap<>();
    private final Map<String, UUID> named = new HashMap<>();
    /** The weeks and months, by scope ("faction:<id>", "world"), newest last. */
    private final Map<String, List<Chronicle.Summary>> above = new HashMap<>();
    private long through = -1, day = -1;
    private boolean busy;
    private int written, byModel;

    ChronicleKeeper(Host host) { this.host = host; }

    /** Reads back the pages the Record holds, and where the last one stopped. Call once the Record is open. */
    void start() {
        busy = true;
        host.events(Set.of(Chronicle.PAGE), 0, BATCH).whenComplete((events, failure) -> host.server().execute(() -> {
            long last = 0;
            if (failure == null && events != null) {
                for (var e : events) {
                    Chronicle.fromPage(e).ifPresent(page -> {
                        if (page.tier() == Chronicle.Tier.PLAYER_DAY) keep(e.actor(), page);
                        else keepAbove(page);
                    });
                    try { last = Math.max(last, Long.parseLong(e.data().getOrDefault("through", "0"))); } catch (NumberFormatException ignored) { }
                }
            }
            through = last;
            busy = false;
            KindredMod.LOGGER.info("Chronicle: {} page(s) read back, through event {}", pages.values().stream().mapToInt(List::size).sum(), through);
        }));
    }

    /** A tick's turn: on a new game day, the day before's pages. */
    void tick() {
        if (through < 0 || busy) return;
        long now = host.tick() / Chronicle.DAY;
        if (day < 0) { day = now; return; }
        if (now == day) return;
        long ended = day;
        day = now;
        writeDay(ended * Chronicle.DAY, now * Chronicle.DAY).thenRun(() -> host.server().execute(() -> {
            long weekSpan = Chronicle.Tier.FACTION_WEEK.span / Chronicle.DAY, monthSpan = Chronicle.Tier.WORLD_MONTH.span / Chronicle.DAY;
            if (now % weekSpan == 0) writeWeeks((now - weekSpan) * Chronicle.DAY);
            if (now % monthSpan == 0) writeMonth((now - monthSpan) * Chronicle.DAY);
        }));
    }

    /**
     * Each faction's week from {@code from}: written only from its members' day pages in that week (the tier below),
     * the model's when it checks out, else the plain list; its citations the day pages' own, so every one is a real
     * event. A faction with no member's page that week has none. How many were written.
     */
    CompletableFuture<Integer> writeWeeks(long from) {
        var byFaction = new LinkedHashMap<String, List<Chronicle.Summary>>();
        for (Player p : host.players()) {
            if (p.faction().isEmpty()) continue;
            for (var page : pages(p.id())) {
                if (page.from() >= from && page.until() <= from + Chronicle.Tier.FACTION_WEEK.span) {
                    byFaction.computeIfAbsent(p.faction(), k -> new ArrayList<>()).add(page);
                }
            }
        }
        var writes = new ArrayList<CompletableFuture<Void>>();
        for (var en : byFaction.entrySet()) {
            var below = new ArrayList<>(en.getValue());
            below.sort(java.util.Comparator.comparingLong(Chronicle.Summary::from));
            writes.add(above(Chronicle.Tier.FACTION_WEEK, "faction:" + en.getKey(), "the faction " + en.getKey(), from, below));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).thenApply(v -> writes.size());
    }

    /** The world's month from {@code from}, written only from the faction weeks in it; nothing when there are none. */
    CompletableFuture<Integer> writeMonth(long from) {
        var below = new ArrayList<Chronicle.Summary>();
        for (var en : above.entrySet()) {
            for (var page : en.getValue()) {
                if (page.tier() == Chronicle.Tier.FACTION_WEEK && page.from() >= from && page.until() <= from + Chronicle.Tier.WORLD_MONTH.span) below.add(page);
            }
        }
        if (below.isEmpty()) return CompletableFuture.completedFuture(0);
        below.sort(java.util.Comparator.comparingLong(Chronicle.Summary::from));
        return above(Chronicle.Tier.WORLD_MONTH, "world", "the world", from, below).thenApply(v -> 1);
    }

    /** One page above the players' days: the plain list of the tier below, or the model's page of it when it checks out. */
    private CompletableFuture<Void> above(Chronicle.Tier tier, String scope, String name, long from, List<Chronicle.Summary> below) {
        var plain = Chronicle.plainFrom(tier, scope, from, below);
        if (plain.text().length() > Chronicle.ABOVE_CHARS) {
            plain = new Chronicle.Summary(plain.tier(), plain.scope(), plain.from(), plain.until(), plain.text().substring(0, Chronicle.ABOVE_CHARS - 1) + "…",
                    plain.cites(), false);
        }
        var ids = new HashSet<Long>();
        for (var s : below) ids.addAll(s.cites());
        var bridge = host.bridge();
        var fallback = plain;
        if (bridge == null || !bridge.writesPages()) {
            writeAbove(fallback);
            return CompletableFuture.completedFuture(null);
        }
        var lines = below.stream().limit(Chronicle.MAX_LINES).map(ChroniclePrompt::line).toList();
        String request = ChroniclePrompt.request(tier, name, from, lines);
        var done = new CompletableFuture<Void>();
        bridge.page(new UUID(0, scope.hashCode()), name, request, host.server()).whenComplete((page, failure) -> host.server().execute(() -> {
            Optional<Chronicle.Summary> fromModel = page == null ? Optional.empty()
                    : page.flatMap(p -> Chronicle.fromModel(tier, scope, from, p.text(), p.cites()));
            var kept = Chronicle.keep(fromModel, fallback, ids, Chronicle.Facts.none());
            if (fromModel.isPresent() && kept == fallback) {
                KindredMod.LOGGER.info("{} chronicle: the model's {} refused ({}); the plain list kept", name, tier.name().toLowerCase(java.util.Locale.ROOT),
                        Chronicle.check(fromModel.get(), ids, Chronicle.Facts.none()));
            }
            writeAbove(kept);
            done.complete(null);
        }));
        return done;
    }

    private void writeAbove(Chronicle.Summary page) {
        host.record(new WorldEvent(0, host.tick(), System.currentTimeMillis(), "", Chronicle.PAGE, null, page.scope(), "",
                Chronicle.pageData(page, through), page.model() ? WorldEvent.HowKnown.CLAIMED : WorldEvent.HowKnown.RECORDED));
        keepAbove(page);
        written++;
        if (page.model()) byModel++;
        KindredMod.LOGGER.info("{} chronicle: {} from day {} ({}, {} characters, {} events cited)", page.scope(), page.tier().name().toLowerCase(java.util.Locale.ROOT),
                page.from() / Chronicle.DAY + 1, page.model() ? "the model's" : "the plain list", page.text().length(), page.cites().size());
    }

    private void keepAbove(Chronicle.Summary page) {
        var list = above.computeIfAbsent(page.scope(), k -> new ArrayList<>());
        list.add(page);
        while (list.size() > KEPT) list.removeFirst();
    }

    /** A faction's or the world's latest pages, oldest first ("faction:<id>", "world"). */
    List<Chronicle.Summary> pagesAbove(String scope) { return List.copyOf(above.getOrDefault(scope, List.of())); }

    /** The pages for the span just ended, from the Record's events since the last page. */
    CompletableFuture<Integer> writeDay(long from, long until) {
        var done = new CompletableFuture<Integer>();
        busy = true;
        host.events(Chronicle.DAY_KINDS, through, BATCH).whenComplete((events, failure) -> host.server().execute(() -> {
            if (failure != null || events == null) {
                busy = false;
                KindredMod.LOGGER.warn("Chronicle: the Record could not be read: {}", String.valueOf(failure));
                done.complete(0);
                return;
            }
            var byPlayer = new LinkedHashMap<Player, List<Chronicle.Event>>();
            for (Player p : host.players()) byPlayer.put(p, new ArrayList<>());
            long last = through;
            for (var e : events) {
                last = Math.max(last, e.id());
                for (var en : byPlayer.entrySet()) {
                    if (e.actor() != null && en.getKey().actors().contains(e.actor())) Chronicle.fromRecord(e).ifPresent(en.getValue()::add);
                }
            }
            long batchEnd = last;
            var writes = new ArrayList<CompletableFuture<Void>>();
            for (var en : byPlayer.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                writes.add(page(en.getKey(), from, until, en.getValue(), batchEnd));
            }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).whenComplete((v, f) -> host.server().execute(() -> {
                through = batchEnd;
                busy = false;
                done.complete(writes.size());
            }));
        }));
        return done;
    }

    private CompletableFuture<Void> page(Player player, long from, long until, List<Chronicle.Event> all, long batchEnd) {
        var events = all;
        // What is worth telling, at most Chronicle.MAX_LINES lines, each a real event's id (F-33c's run: ~1,900 events a day).
        int deaths = 0;
        var ids = new HashSet<Long>();
        for (var e : events) {
            ids.add(e.id());
            if ("death".equals(e.kind())) deaths++;
        }
        events = Chronicle.condense(events);
        var plain = Chronicle.plainOf(Chronicle.Tier.PLAYER_DAY, player.id().toString(), from, until, events);
        var facts = new Chronicle.Facts(Map.of(player.name(), deaths), Map.of(), Map.of());
        var bridge = host.bridge();
        if (bridge == null || !bridge.writesPages()) {
            write(player, plain, batchEnd);
            return CompletableFuture.completedFuture(null);
        }
        String request = ChroniclePrompt.request(Chronicle.Tier.PLAYER_DAY, player.name(), from, events.stream().map(ChroniclePrompt::line).toList());
        var done = new CompletableFuture<Void>();
        bridge.page(player.id(), player.name(), request, host.server()).whenComplete((page, failure) -> host.server().execute(() -> {
            Optional<Chronicle.Summary> fromModel = page == null ? Optional.empty()
                    : page.flatMap(p -> Chronicle.fromModel(Chronicle.Tier.PLAYER_DAY, player.id().toString(), from, p.text(), p.cites()))
                            .map(s -> new Chronicle.Summary(s.tier(), s.scope(), from, Math.max(from + 1, until), s.text(), s.cites(), true));
            var kept = Chronicle.keep(fromModel, plain, ids, facts);
            if (fromModel.isPresent() && kept == plain) {
                KindredMod.LOGGER.info("{} chronicle: the model's page refused ({}); the plain list kept", player.name(), Chronicle.check(fromModel.get(), ids, facts));
            }
            write(player, kept, batchEnd);
            done.complete(null);
        }));
        return done;
    }

    private void write(Player player, Chronicle.Summary page, long batchEnd) {
        named.put(player.name(), player.id());
        host.record(new WorldEvent(0, host.tick(), System.currentTimeMillis(), "", Chronicle.PAGE, player.id(), player.id().toString(), "",
                Chronicle.pageData(page, batchEnd), page.model() ? WorldEvent.HowKnown.CLAIMED : WorldEvent.HowKnown.RECORDED));
        keep(player.id(), page);
        written++;
        if (page.model()) byModel++;
        KindredMod.LOGGER.info("{} chronicle: day {} page ({}, {} characters, {} events cited)", player.name(), page.from() / Chronicle.DAY + 1,
                page.model() ? "the model's" : "the plain list", page.text().length(), page.cites().size());
    }

    private void keep(UUID player, Chronicle.Summary page) {
        if (player == null) return;
        var list = pages.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(page);
        while (list.size() > KEPT) list.removeFirst();
    }

    /** A player's latest pages, oldest first. */
    List<Chronicle.Summary> pages(UUID player) { return List.copyOf(pages.getOrDefault(player, List.of())); }

    /** A player's latest pages by its name, for fixtures. */
    List<Chronicle.Summary> pagesOf(String name) {
        for (Player p : host.players()) if (p.name().equals(name)) return pages(p.id());
        UUID id = named.get(name);
        return id == null ? List.of() : pages(id);
    }

    /** Pages kept in memory, all tiers (the month-long cost check). */
    int kept() {
        int n = 0;
        for (var l : pages.values()) n += l.size();
        return n;
    }

    /** Pages written since start, and how many by the model. */
    int[] counts() { return new int[] {written, byModel}; }

    /** Whether it has read back the Record and is not writing now. */
    boolean idle() { return through >= 0 && !busy; }
}
