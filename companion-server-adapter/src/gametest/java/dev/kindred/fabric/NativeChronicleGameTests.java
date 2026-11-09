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
}
