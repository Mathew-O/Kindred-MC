package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.chronicle.Chronicle;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.model.Strategist;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * In plain terms: a GameTest of the Chronicle on a real server: an AI player's day in the World Record (a death and a
 * journal line, and someone else's event beside them) becomes its page, cited from the Record; a restart reads the page
 * back and no event is ever told in two pages; with a model, its page is kept when it checks out and the plain list kept when it cites
 * an event that does not exist.
 *
 * Technical: F-33b's fixture. The day is written at once with {@code writeDay} rather than waiting a game day; the model is
 * a stub through {@code strategistForFixtures}, so nothing calls the network. The Record is read back from the database
 * after the final stop.
 */
public final class NativeChronicleGameTests implements FabricGameTest {
    /** A model that tells each line it is given in one sentence, citing it; or cites an event that does not exist. */
    static final class Stub implements Strategist {
        final boolean invent;
        int asked;

        Stub(boolean invent) { this.invent = invent; }

        @Override
        public CompletableFuture<Page> chronicle(String request) {
            asked++;
            var m = Pattern.compile("^\\[(\\d+)] [^:]+: (.*)$", Pattern.MULTILINE).matcher(request);
            var cites = new ArrayList<Long>();
            var text = new StringBuilder();
            while (m.find()) {
                cites.add(Long.parseLong(m.group(1)));
                text.append(text.isEmpty() ? "" : " ").append("It happened that ").append(m.group(2)).append('.');
            }
            if (invent) cites.add(999_999L);
            return CompletableFuture.completedFuture(new Page(Optional.of(text.toString()), cites, 300, 0, 60, 5, Optional.empty()));
        }

        @Override
        public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
            return CompletableFuture.completedFuture(new Outcome(Optional.empty(), 0, 0, 0, 0, Optional.of("a fixture's stub gives no advice")));
        }

        @Override public String provider() { return "Fixture"; }
        @Override public String model() { return "stub"; }
        @Override public String pace() { return "at once"; }
        @Override public void close() { }
    }

    /**
     * F-33e: past 10,000 pages (a month at 8 players, about day 17), a restart reads every page back, not the first
     * 10,000: it resumes after the last page's {@code through}, keeps each player's and the world's newest pages, and
     * writes no day again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chronicleManyPages", tickLimit = 2400)
    public void pastTenThousandPagesARestartResumesAfterTheLastAndKeepsTheNewest(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[1];
        int days = 12_000, worldMonths = 3;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Ready, the Chronicle read back"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Ada established");
                    ada[0] = statuses.getFirst().id();
                    // Ada's pages, one a day, each having read through event 1,000,000 + its day; then the world's months.
                    // Written on the ordered writer, which the stop below waits on.
                    var batch = new ArrayList<WorldEvent>();
                    for (int d = 0; d < days; d++) {
                        var page = new Chronicle.Summary(Chronicle.Tier.PLAYER_DAY, ada[0].toString(), d * Chronicle.DAY, (d + 1) * Chronicle.DAY,
                                "Ada's day " + (d + 1) + ".", List.of(), false);
                        batch.add(new WorldEvent(0, server.getTicks(), System.currentTimeMillis(), "", Chronicle.PAGE, ada[0], ada[0].toString(), "",
                                Chronicle.pageData(page, 1_000_000L + d), WorldEvent.HowKnown.RECORDED));
                        if (batch.size() == 1_000) { f.runtime.record(List.copyOf(batch)); batch.clear(); }
                    }
                    for (int m = 0; m < worldMonths; m++) {
                        long from = (long) m * Chronicle.Tier.WORLD_MONTH.span;
                        var month = new Chronicle.Summary(Chronicle.Tier.WORLD_MONTH, "world", from, from + Chronicle.Tier.WORLD_MONTH.span, "The world's month " + (m + 1) + ".", List.of(), false);
                        batch.add(new WorldEvent(0, server.getTicks(), System.currentTimeMillis(), "", Chronicle.PAGE, null, "world", "",
                                Chronicle.pageData(month, 1_000_000L + days - 1), WorldEvent.HowKnown.RECORDED));
                    }
                    f.runtime.record(List.copyOf(batch));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Restarted, the Chronicle read back"))
                .createAndAdd(() -> {
                    var keeper = f.runtime.chronicleForFixtures();
                    context.assertTrue(keeper.throughForFixtures() == 1_000_000L + days - 1,
                            "Resumes after the last page: through " + keeper.throughForFixtures() + ", not an early page's");
                    var pages = keeper.pages(ada[0]);
                    context.assertTrue(pages.size() == ChronicleKeeper.KEPT && pages.getLast().text().equals("Ada's day " + days + ".")
                            && pages.getFirst().text().equals("Ada's day " + (days - ChronicleKeeper.KEPT + 1) + "."),
                            "Ada's newest " + ChronicleKeeper.KEPT + " pages kept: " + pages.stream().map(Chronicle.Summary::text).toList());
                    var months = keeper.pagesAbove("world");
                    context.assertTrue(months.size() == worldMonths && months.getLast().text().equals("The world's month " + worldMonths + "."),
                            "The world's months past the 10,000th page kept: " + months.stream().map(Chronicle.Summary::text).toList());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .completeIfSuccessful();
    }

    /**
     * F-33f: a day of more than 10,000 events (3 players passed it on day 2 of the keyed runs, journal lines mostly) is
     * read whole for its page, not its first 10,000: an event after them, a death, is on the day's page, and the next
     * page starts after it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chronicleBigDay", tickLimit = 1600)
    public void aDayOfMoreThanTenThousandEventsIsReadWholeForItsPage(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[2];
        int journal = 12_000;
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] wrote = new CompletableFuture[1];
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] deaths = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Ready, the Chronicle read back"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Ada established");
                    ada[0] = statuses.getFirst().nativeId();
                    ada[1] = statuses.getFirst().id();
                    var batch = new ArrayList<WorldEvent>();
                    for (int n = 0; n < journal; n++) {
                        batch.add(WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "journal", ada[0], "goal", "", Map.of("detail", "a step " + n)));
                        if (batch.size() == 1_000) { f.runtime.record(List.copyOf(batch)); batch.clear(); }
                    }
                    // After the 12,000: the day's one death, which every page tells on its own line.
                    f.runtime.record(List.of(WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", "death", ada[0], "Ada", "1,2,3",
                            Map.of("cause", "lava"))));
                })
                .createAndAdd(() -> {
                    deaths[0] = f.runtime.worldEventsForTest(Set.of("death"));
                })
                .createAndAdd(() -> context.assertTrue(deaths[0].isDone() && deaths[0].join().stream().anyMatch(e -> ada[0].equals(e.actor())),
                        "All 12,001 written, the death last"))
                .createAndAdd(() -> wrote[0] = f.runtime.chronicleForFixtures().writeDay(0, Chronicle.DAY))
                .createAndAdd(() -> {
                    context.assertTrue(wrote[0].isDone() && wrote[0].join() == 1, "One page written: " + (wrote[0].isDone() ? wrote[0].join() : "not yet"));
                    long death = deaths[0].join().stream().filter(e -> ada[0].equals(e.actor())).mapToLong(WorldEvent::id).max().orElseThrow();
                    var keeper = f.runtime.chronicleForFixtures();
                    var page = keeper.pages(ada[1]).getLast();
                    context.assertTrue(page.text().contains("Ada died: lava"), "The death after the 10,000th event is on the day's page: " + page.text());
                    context.assertTrue(keeper.throughForFixtures() >= death, "The next page starts after it: through " + keeper.throughForFixtures() + ", the death " + death);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /**
     * The month run's scale (8 players, 30 real days at 72 game days a day): 2,149 game days of pages for 8 players, oak's
     * 307 weeks and the world's 71 months (17,570 pages), then a restart, then 7 more days. The next week is written
     * from those 7 days alone and the next month from the weeks inside its 30 days alone, and every scope keeps its
     * newest 7: the read-back (F-33e) feeds the tiers above correctly at a month's size. The bodies are not connected after
     * the restart, as in the month run until an operator connects each: what they did still makes their players' pages (F-33g).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chronicleMonthScale", tickLimit = 3600)
    public void atAMonthsScaleARestartsNextWeekAndMonthAreWrittenFromTheNewestPages(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3500);
        var server = context.getWorld().getServer();
        UUID[] bodies = new UUID[2], ids = new UUID[2];
        int days = 2_149, weeks = days / 7, months = 71, others = 6, firstNew = days;
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] step = new CompletableFuture[1];
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Dana"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Dana"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Egon"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(st -> st.phase() == StoredNativePlayer.Phase.ESTABLISHED), "Both established");
                    for (int i = 0; i < 2; i++) { bodies[i] = statuses.get(i).nativeId(); ids[i] = statuses.get(i).id(); }
                    // The month so far, as pages: Dana's, Egon's and six other players' days; oak's weeks; the world's months.
                    var actors = new ArrayList<UUID>(List.of(ids));
                    for (int o = 0; o < others; o++) actors.add(UUID.randomUUID());
                    var batch = new ArrayList<WorldEvent>();
                    java.util.function.Consumer<WorldEvent> add = e -> {
                        batch.add(e);
                        if (batch.size() == 1_000) { f.runtime.record(List.copyOf(batch)); batch.clear(); }
                    };
                    for (int d = 0; d < days; d++) {
                        for (UUID a : actors) {
                            var page = new Chronicle.Summary(Chronicle.Tier.PLAYER_DAY, a.toString(), d * Chronicle.DAY, (d + 1) * Chronicle.DAY,
                                    "Day " + (d + 1) + " of " + a.toString().substring(0, 8) + ".", List.of(), false);
                            add.accept(new WorldEvent(0, server.getTicks(), System.currentTimeMillis(), "", Chronicle.PAGE, a, a.toString(), "",
                                    Chronicle.pageData(page, 0), WorldEvent.HowKnown.RECORDED));
                        }
                    }
                    for (int w = 0; w < weeks; w++) {
                        long from = w * Chronicle.Tier.FACTION_WEEK.span;
                        var week = new Chronicle.Summary(Chronicle.Tier.FACTION_WEEK, "faction:oak", from, from + Chronicle.Tier.FACTION_WEEK.span,
                                "Oak's week " + (w + 1) + ".", List.of(), false);
                        add.accept(new WorldEvent(0, server.getTicks(), System.currentTimeMillis(), "", Chronicle.PAGE, null, "faction:oak", "",
                                Chronicle.pageData(week, 0), WorldEvent.HowKnown.RECORDED));
                    }
                    for (int m = 0; m < months; m++) {
                        long from = m * Chronicle.Tier.WORLD_MONTH.span;
                        var month = new Chronicle.Summary(Chronicle.Tier.WORLD_MONTH, "world", from, from + Chronicle.Tier.WORLD_MONTH.span,
                                "The world's month " + (m + 1) + ".", List.of(), false);
                        add.accept(new WorldEvent(0, server.getTicks(), System.currentTimeMillis(), "", Chronicle.PAGE, null, "world", "",
                                Chronicle.pageData(month, 0), WorldEvent.HowKnown.RECORDED));
                    }
                    if (!batch.isEmpty()) f.runtime.record(List.copyOf(batch));
                    f.runtime.stop(); // The stop waits on the ordered writer: every page is written before the restart.
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Restarted, 17,570 pages read back"))
                .createAndAdd(() -> {
                    var keeper = f.runtime.chronicleForFixtures();
                    context.assertTrue(keeper.pages(ids[0]).size() == ChronicleKeeper.KEPT && keeper.pages(ids[0]).getLast().text().startsWith("Day " + days + " "),
                            "Dana's newest pages read back: " + keeper.pages(ids[0]).stream().map(Chronicle.Summary::text).toList());
                    context.assertTrue(keeper.pagesAbove("faction:oak").getLast().text().equals("Oak's week " + weeks + ".")
                            && keeper.pagesAbove("world").getLast().text().equals("The world's month " + months + "."), "The newest week and month read back");
                    f.runtime.setFactionOf(id -> "oak");
                });
        for (int d = 0; d < 7; d++) {
            final int day = firstNew + d, deathsBy = d + 1;
            runner = runner
                    .createAndAdd(() -> f.runtime.record(List.of(
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "death", bodies[0], "Dana", "", Map.of("cause", "lava " + day)),
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "journal", bodies[1], "goal", "", Map.of("detail", "day " + day + " work")))))
                    .createAndAdd(() -> context.assertTrue(f.runtime.ledgers().deaths().getOrDefault(bodies[0], 0) == deathsBy, "Day " + day + " written"))
                    .createAndAdd(() -> step[0] = f.runtime.chronicleForFixtures().writeDay(day * Chronicle.DAY, (day + 1) * Chronicle.DAY))
                    .createAndAdd(() -> context.assertTrue(step[0].isDone() && step[0].join() == 2 && f.runtime.chronicleForFixtures().idle(), "Day " + day + "'s two pages: "
                            + (step[0].isDone() ? step[0].join() : "not done") + ", through " + f.runtime.chronicleForFixtures().throughForFixtures()
                            + ", idle " + f.runtime.chronicleForFixtures().idle()));
        }
        runner.createAndAdd(() -> {
                    // No double paging: the saved id and a connected body's id are the same body, one actor; each of the
                    // new days' events is cited once in each player's pages, and each day's death exactly once.
                    var keeper = f.runtime.chronicleForFixtures();
                    for (UUID id : ids) {
                        var seen = new java.util.HashSet<Long>();
                        for (var p : keeper.pages(id)) for (Long c : p.cites()) context.assertTrue(seen.add(c), "Event " + c + " cited twice in " + id + "'s pages");
                    }
                    long deathsCited = keeper.pages(ids[0]).stream().flatMap(p -> p.cites().stream()).distinct().count();
                    context.assertTrue(keeper.pages(ids[0]).stream().filter(p -> p.text().contains("died: lava")).count() == 7, "Each new day's death on its own day's page");
                    context.assertTrue(deathsCited >= 7, "Dana's 7 deaths cited: " + deathsCited);
                })
                .createAndAdd(() -> step[0] = f.runtime.chronicleForFixtures().writeWeeks(firstNew * Chronicle.DAY))
                .createAndAdd(() -> {
                    context.assertTrue(step[0].isDone() && step[0].join() == 1, "Oak's next week");
                    var oak = f.runtime.chronicleForFixtures().pagesAbove("faction:oak");
                    var week = oak.getLast();
                    context.assertTrue(oak.size() == ChronicleKeeper.KEPT && week.from() == firstNew * Chronicle.DAY, "The newest of 7 kept: " + oak.stream().map(Chronicle.Summary::text).toList());
                    // Each day a condensed line of the week ("day 2150 …" for the first new day); none of the pages before.
                    context.assertTrue(week.text().contains("day " + (firstNew + 1) + " ") && week.text().contains("lava " + (firstNew + 6))
                            && !week.text().contains("Day " + days + " "), "From the 7 new days alone, none before: " + week.text());
                    context.assertTrue(!week.cites().isEmpty(), "The new week cites the new days' events: " + week.cites());
                    step[0] = f.runtime.chronicleForFixtures().writeMonth(2_130 * Chronicle.DAY);
                })
                .createAndAdd(() -> {
                    context.assertTrue(step[0].isDone() && step[0].join() == 1, "The world's next month");
                    var world = f.runtime.chronicleForFixtures().pagesAbove("world");
                    var month = world.getLast();
                    context.assertTrue(world.size() == ChronicleKeeper.KEPT && month.from() == 2_130 * Chronicle.DAY, "The newest of 7 kept: " + world.stream().map(Chronicle.Summary::text).toList());
                    // Its 30 days, 2,130 to 2,160, hold the weeks from days 2,135 and 2,142 ("Oak's week 306" and "307") and the new
                    // one from 2,149; "Oak's week 305" starts on day 2,128, before the month.
                    var newWeek = f.runtime.chronicleForFixtures().pagesAbove("faction:oak").getLast();
                    context.assertTrue(month.text().contains("Oak's week 306.") && month.text().contains("Oak's week 307.") && !month.text().contains("Oak's week 305."),
                            "From the weeks inside its 30 days alone: " + month.text());
                    context.assertTrue(!month.cites().isEmpty() && newWeek.cites().containsAll(month.cites()),
                            "The new week's events cited, nothing else: " + month.cites() + " of " + newWeek.cites());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .completeIfSuccessful();
    }

    /** Ada's day recorded and written as a page: the plain list without a model, read back after a restart, no event twice. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chronicle", tickLimit = 1200)
    public void aPlayersDayBecomesItsPageAndNoEventIsToldTwice(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[1];
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] wrote = new CompletableFuture[2];
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] read = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Ready, the Chronicle read back"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Ada established");
                    ada[0] = statuses.getFirst().nativeId();
                    f.runtime.record(List.of(
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", "death", ada[0], "Ada", "1,2,3", Map.of("cause", "lava")),
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "journal", ada[0], "goal", "", Map.of("detail", "built a crafting table")),
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "death", UUID.randomUUID(), "Someone", "", Map.of("cause", "fall"))));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.ledgers().deaths().getOrDefault(ada[0], 0) == 1, "Written to the Record"))
                .createAndAdd(() -> wrote[0] = f.runtime.chronicleForFixtures().writeDay(0, Chronicle.DAY))
                .createAndAdd(() -> {
                    context.assertTrue(wrote[0].isDone() && wrote[0].join() == 1, "One page written: " + (wrote[0].isDone() ? wrote[0].join() : "not yet"));
                    var pages = f.runtime.chronicleForFixtures().pagesOf("Ada");
                    context.assertTrue(pages.size() == 1, "Ada's page: " + pages);
                    var page = pages.getFirst();
                    context.assertTrue(!page.model() && page.cites().size() >= 2 && page.text().contains("Ada died: lava") && page.text().contains("built a crafting table")
                            && !page.text().contains("Someone"), "The plain list of Ada's day only, its events cited: " + page);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Restarted, the Chronicle read back"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.chronicleForFixtures().pagesOf("Ada").size() == 1, "Ada's page read back from the Record");
                    wrote[1] = f.runtime.chronicleForFixtures().writeDay(Chronicle.DAY, 2 * Chronicle.DAY);
                })
                .createAndAdd(() -> {
                    context.assertTrue(wrote[1].isDone(), "The next day read");
                    // Whatever the restart itself added (its journal lines) may make a page; no event is ever cited twice.
                    var pages = f.runtime.chronicleForFixtures().pagesOf("Ada");
                    var seen = new java.util.HashSet<Long>();
                    for (var p : pages) for (Long id : p.cites()) context.assertTrue(seen.add(id), "Event " + id + " cited twice: " + pages);
                    context.assertTrue(pages.size() == 1 + wrote[1].join(), "Pages: the first read back, then " + wrote[1].join() + " new: " + pages);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .createAndAdd(() -> read[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                        store.allWorldEvents(Set.of(Chronicle.PAGE)).thenCompose(events -> store.closeAsync().thenApply(ignored -> events))))
                .createAndAdd(() -> {
                    context.assertTrue(read[0].isDone() && !read[0].isCompletedExceptionally(), "Record read");
                    var pages = read[0].join();
                    context.assertTrue(!pages.isEmpty() && pages.size() <= 2 && pages.stream().allMatch(p -> p.howKnown() == WorldEvent.HowKnown.RECORDED),
                            "Ada's pages in the Record, the plain list recorded: " + pages);
                    var cited = new java.util.HashSet<String>();
                    for (var p : pages) for (String id : p.data().getOrDefault("cites", "").split(",")) {
                        if (!id.isBlank()) context.assertTrue(cited.add(id), "No event in two pages: " + id);
                    }
                })
                .completeIfSuccessful();
    }

    /** With a model: its page kept when every citation is real; the plain list kept when it invents one. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chronicleModel", tickLimit = 800)
    public void aModelsPageIsKeptOnlyWhenItChecksOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[1];
        var good = new Stub(false);
        var bad = new Stub(true);
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] wrote = new CompletableFuture[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cleo"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Cleo established");
                    ada[0] = statuses.getFirst().nativeId();
                    f.runtime.strategistForFixtures(good);
                    f.runtime.record(WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "death", ada[0], "Cleo", "", Map.of("cause", "lava")));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.ledgers().deaths().getOrDefault(ada[0], 0) == 1, "Written"))
                .createAndAdd(() -> wrote[0] = f.runtime.chronicleForFixtures().writeDay(0, Chronicle.DAY))
                .createAndAdd(() -> {
                    context.assertTrue(wrote[0].isDone() && good.asked == 1, "The model asked once");
                    var pages = f.runtime.chronicleForFixtures().pagesOf("Cleo");
                    context.assertTrue(!pages.isEmpty(), "A page: " + pages);
                    var page = pages.getLast();
                    context.assertTrue(page.model() && page.text().contains("It happened that Cleo died: lava"), "The model's page kept: " + page);
                    f.runtime.strategistForFixtures(bad);
                    f.runtime.record(WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "journal", ada[0], "goal", "", Map.of("detail", "mined coal")));
                })
                .createAndAdd(() -> wrote[1] = f.runtime.chronicleForFixtures().writeDay(Chronicle.DAY, 2 * Chronicle.DAY))
                .createAndAdd(() -> {
                    context.assertTrue(wrote[1].isDone() && bad.asked == 1, "Asked again for the next day");
                    var pages = f.runtime.chronicleForFixtures().pagesOf("Cleo");
                    context.assertTrue(pages.size() == 2, "Two pages: " + pages);
                    var page = pages.getLast();
                    context.assertTrue(!page.model() && page.text().contains("mined coal") && !page.cites().contains(999_999L),
                            "An invented citation refused: the plain list kept: " + page);
                    context.assertTrue(f.runtime.chronicleForFixtures().counts()[0] == 2 && f.runtime.chronicleForFixtures().counts()[1] == 1, "Two pages, one the model's");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /**
     * Ada and Bram of oak, seven days of each: oak's week written from their day pages alone, citing only what they cite;
     * the world's month from oak's week alone; both read back from the Record after a restart.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chronicleWeeks", tickLimit = 2400)
    public void aFactionsWeekAndTheWorldsMonthAreWrittenFromTheTierBelow(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        UUID[] bodies = new UUID[2];
        @SuppressWarnings("unchecked") CompletableFuture<Integer>[] step = new CompletableFuture[1];
        var runner = context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Dana"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Dana"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Egon"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(st -> st.phase() == StoredNativePlayer.Phase.ESTABLISHED), "Both established");
                    for (int i = 0; i < 2; i++) bodies[i] = statuses.get(i).nativeId();
                    f.runtime.setFactionOf(id -> "oak");
                });
        for (int d = 0; d < 7; d++) {
            final int day = d;
            runner = runner
                    .createAndAdd(() -> f.runtime.record(List.of(
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "death", bodies[0], "Dana", "", Map.of("cause", "lava " + day)),
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "journal", bodies[1], "goal", "", Map.of("detail", "day " + day + " work")))))
                    .createAndAdd(() -> context.assertTrue(f.runtime.ledgers().deaths().getOrDefault(bodies[0], 0) == day + 1, "Day " + day + " written"))
                    .createAndAdd(() -> step[0] = f.runtime.chronicleForFixtures().writeDay(day * Chronicle.DAY, (day + 1) * Chronicle.DAY))
                    .createAndAdd(() -> context.assertTrue(step[0].isDone() && f.runtime.chronicleForFixtures().idle(), "Day " + day + "'s pages"));
        }
        runner.createAndAdd(() -> step[0] = f.runtime.chronicleForFixtures().writeWeeks(0))
                .createAndAdd(() -> {
                    context.assertTrue(step[0].isDone() && step[0].join() == 1, "One week, oak's");
                    var week = f.runtime.chronicleForFixtures().pagesAbove("faction:oak");
                    context.assertTrue(week.size() == 1 && week.getFirst().tier() == Chronicle.Tier.FACTION_WEEK, "oak's week: " + week);
                    var cited = new java.util.HashSet<Long>();
                    for (String name : List.of("Dana", "Egon")) for (var p : f.runtime.chronicleForFixtures().pagesOf(name)) cited.addAll(p.cites());
                    context.assertTrue(!week.getFirst().cites().isEmpty() && cited.containsAll(week.getFirst().cites()),
                            "The week cites only what its days cite: " + week.getFirst().cites());
                    context.assertTrue(week.getFirst().text().contains("lava 3") && week.getFirst().text().contains("day 6 work"), "Both members' days told: " + week.getFirst().text());
                    step[0] = f.runtime.chronicleForFixtures().writeMonth(0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(step[0].isDone() && step[0].join() == 1, "The world's month");
                    var month = f.runtime.chronicleForFixtures().pagesAbove("world");
                    var week = f.runtime.chronicleForFixtures().pagesAbove("faction:oak").getFirst();
                    context.assertTrue(month.size() == 1 && week.cites().containsAll(month.getFirst().cites()), "The month from the week alone: " + month);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.chronicleForFixtures().idle(), "Restarted"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.chronicleForFixtures().pagesAbove("faction:oak").size() == 1
                            && f.runtime.chronicleForFixtures().pagesAbove("world").size() == 1, "The week and the month read back from the Record");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .completeIfSuccessful();
    }
}
