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
 * Chronicle hold.
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
}
