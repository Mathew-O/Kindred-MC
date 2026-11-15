package dev.kindred.fabric;

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
                .createAndAdd(() -> line[0] = f.runtime.logCost(1))
                .createAndAdd(() -> {
                    context.assertTrue(line[0].isDone(), "Measured");
                    String s = line[0].join();
                    context.assertTrue(s.contains("claim=2/") && s.contains("journal=1/"), "Counted by kind: " + s);
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
}
