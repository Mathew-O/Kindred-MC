package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a GameTest of the month-long cost check's gauge: events written to the World Record are counted by
 * kind with their size, and the day's line says how big the database is on disk and what the webs, the Wire and the
 * Chronicle hold, and what the Wire refused, by why and where, and nothing of what it said.
 *
 * Technical: The gauge only reads; this checks its counts against what was written and that the database's size is
 * measured (positive) off the server thread.
 */
public final class NativeCostGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "cost", tickLimit = 600)
    public void theCostLineCountsWhatTheRecordWrote(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        var server = context.getWorld().getServer();
        @SuppressWarnings("unchecked") CompletableFuture<String>[] line = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.record(List.of(
                        WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM, null, "red",
                                ClaimLedger.chunk("minecraft:overworld", 90, 90), Map.of()),
                        WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM, null, "red",
                                ClaimLedger.chunk("minecraft:overworld", 91, 90), Map.of()),
                        WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "", "journal", null, "goal", "", Map.of("detail", "x".repeat(100))))))
                .createAndAdd(() -> context.assertTrue(f.runtime.ledgers().claims().size() == 2, "Written"))
                .createAndAdd(() -> {
                    // F-32d: the player's web holds three facts it was told, one of them told again, which ends the first.
                    f.established(context);
                    var web = f.runtime.webs().of(f.id);
                    long now = server.getTicks();
                    for (String item : List.of("item:iron_ingot", "item:bread", "item:iron_ingot")) {
                        web.add("faction:oak", dev.kindred.core.knowledge.KnowledgeWeb.Verb.HAS_SPARE, item, "4", dev.kindred.core.wire.HowKnown.TOLD,
                                List.of(1L), now, dev.kindred.core.wire.Visibility.FACTION, "oak");
                    }
                    line[0] = f.runtime.logCost(1);
                })
                .createAndAdd(() -> {
                    context.assertTrue(line[0].isDone(), "Measured");
                    String s = line[0].join();
                    context.assertTrue(s.contains("claim=2/") && s.contains("journal=1/"), "Counted by kind: " + s);
                    String name = f.runtime.nativeStatuses(Actor.console()).getFirst().name();
                    // Three held; the iron told first ended at this tick, so the bound may drop it once it lapses: two kept for good.
                    context.assertTrue(s.contains("; webs by player, held/kept for good {" + name + "=3/2};"), "Each AI's web by name: " + s);
                    var bytes = java.util.regex.Pattern.compile("the database (\\d+) bytes on disk").matcher(s);
                    context.assertTrue(bytes.find() && Long.parseLong(bytes.group(1)) > 0, "The database measured: " + s);
                    context.assertTrue(s.contains("the world's 2;") || s.contains("the world's 2 "), "The world's web holds the two claims: " + s);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /**
     * A message the Wire refuses shows in the day's line as a count by reason and channel, and nothing of what it said
     * (F-34e): a refusal there would have shown at once that Echo's join was never told (F-34d).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "costRefused", tickLimit = 600)
    public void aRefusedMessageIsCountedInTheCostLineAndNothingItSaid(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        @SuppressWarnings("unchecked") CompletableFuture<String>[] line = new CompletableFuture[2];
        String[] refused = new String[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> line[0] = f.runtime.logCost(1))
                .createAndAdd(() -> {
                    context.assertTrue(line[0].isDone(), "Measured before");
                    context.assertTrue(line[0].join().contains("Wire none refused since start;"), "Nothing refused yet: " + line[0].join());
                    // From no player the server knows, on a faction's channel, with a fact meant for that faction alone.
                    var facts = new java.util.LinkedHashMap<String, dev.kindred.core.wire.Fact>();
                    facts.put("what", dev.kindred.core.wire.Fact.of("the vault behind the waterfall", dev.kindred.core.wire.Visibility.FACTION));
                    var message = dev.kindred.core.wire.Message.of("faction.need", java.util.UUID.randomUUID(),
                            dev.kindred.core.wire.Scope.faction("oak"), facts, List.of());
                    var d = f.runtime.wire().send(message);
                    context.assertTrue(!d.sent(), "Refused: " + d);
                    refused[0] = d.refused().orElseThrow().name() + " on " + message.scope().channel().id() + " 1";
                    line[1] = f.runtime.logCost(1);
                })
                .createAndAdd(() -> {
                    context.assertTrue(line[1].isDone(), "Measured after");
                    String s = line[1].join();
                    context.assertTrue(s.contains("Wire 1 refused since start (" + refused[0] + ");"), "Counted by reason and channel: " + s);
                    for (String withheld : List.of("vault", "waterfall", "faction.need", "oak")) {
                        context.assertTrue(!s.contains(withheld), "Nothing of what it said (" + withheld + "): " + s);
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /**
     * The month run's pre-flight (M-06k7): the cost line gives the server's ticks since the last line as they were timed
     * (how many, the mean, the 99th, how many over 50 ms, the longest) and the heap's high water, then begins again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "costTicks", tickLimit = 600)
    public void theCostLineGivesTheTicksSinceTheLastLineAndTheHeapsHighWater(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        var server = context.getWorld().getServer();
        long[] from = new long[1];
        @SuppressWarnings("unchecked") CompletableFuture<String>[] line = new CompletableFuture[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    line[0] = f.runtime.logCost(1); // Begins the window.
                    from[0] = server.getTicks();
                })
                // A fixture's runtime is ticked through its bodies; this one has none, so each tick is handed to it.
                .createAndAdd(() -> {
                    f.runtime.onServerTick(server);
                    context.assertTrue(server.getTicks() - from[0] >= 45, "Forty-odd ticks");
                })
                .createAndAdd(() -> line[1] = f.runtime.logCost(1))
                .createAndAdd(() -> {
                    context.assertTrue(line[1].isDone(), "Measured");
                    String s = line[1].join();
                    var ticks = java.util.regex.Pattern.compile("server ticks (\\d+): mean ([\\d.]+) ms, p99 (\\d+) ms, (\\d+) over 50 ms, longest ([\\d.]+) ms").matcher(s);
                    context.assertTrue(ticks.find() && Long.parseLong(ticks.group(1)) >= 40 && Double.parseDouble(ticks.group(2)) > 0, "The ticks since the last line: " + s);
                    var heap = java.util.regex.Pattern.compile("high water (\\d+) MB since the last line").matcher(s);
                    context.assertTrue(heap.find() && Long.parseLong(heap.group(1)) > 0, "The heap's high water: " + s);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }
}
