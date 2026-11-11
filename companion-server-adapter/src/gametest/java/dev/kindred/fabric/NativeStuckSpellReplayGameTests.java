package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: with replays on, a player stuck long enough for its goal's watchdog to count a spell has the scene kept,
 * so that the failure corpus can play the spell again and see whether it gets out.
 *
 * Technical: F-27a. The watchdog's spell (PortalRunCampaign's stuckTicks, here 600 for a fixture) counted, the runtime
 * captures a {@code "spell"} replay on the server thread, with its budget (3600 ticks, the watchdog's three minutes) and
 * its pass condition in the file. Staged: the player boxed in bedrock where it stands, with nothing in its hands and the
 * portal goal set, so that its goal can make no progress. With replays off nothing is captured: the capture is only
 * reached through the runtime's replays, which are null unless KINDRED_REPLAYS is on.
 */
public final class NativeStuckSpellReplayGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "stuckSpellReplay", tickLimit = 2400)
    public void aStuckSpellCountedByTheWatchdogIsKeptAsAReplay(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var replays = new DeathReplays(f.directory.resolve("replays"), null);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pocketed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
                        if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                        world.setBlockState(feet.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    f.player().getInventory().clear();
                    f.runtime.captureReplays(replays);
                    f.runtime.stuckTicksForFixtures(f.id, 600);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(replays.latestForTest().isPresent(), "A spell kept"))
                .createAndAdd(() -> {
                    var capture = replays.latestForTest().orElseThrow();
                    String json;
                    try {
                        capture.written().get(10, java.util.concurrent.TimeUnit.SECONDS);
                        json = java.nio.file.Files.readString(capture.folder().resolve("replay.json"));
                    } catch (Exception failed) {
                        throw new net.minecraft.test.GameTestException("The replay not written: " + failed);
                    }
                    KindredMod.LOGGER.info("Stuck spell replay: {} ms on the server thread ({} ms of its CPU)",
                            String.format(java.util.Locale.ROOT, "%.1f", capture.nanosOnThread() / 1e6), String.format(java.util.Locale.ROOT, "%.1f", capture.cpuNanosOnThread() / 1e6));
                    context.assertTrue(json.contains("\"kind\": \"spell\""), "A spell scene: " + json.substring(0, Math.min(200, json.length())));
                    context.assertTrue(json.contains("\"ticks\": " + DeathReplays.SPELL_TICKS), "Its budget, three minutes");
                    context.assertTrue(json.contains("\"pass\": \"" + DeathReplays.SPELL_PASS + "\""), "Its pass condition named");
                    context.assertTrue(java.nio.file.Files.isRegularFile(capture.folder().resolve("replay.snbt")), "Its ground kept");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The spell replay keeps its goal's own memory of the box: a block it set aside comes back set aside when the memory
     * is put back into a fresh goal at another place, and what lies outside the box or is not known is dropped and named.
     * Staged as above, with a block three east of the pocket set aside by the fixture as a failed walk would set it aside.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "stuckSpellMemory", tickLimit = 2400)
    public void aStuckSpellsReplayKeepsWhatItsGoalSetAsideAndPutsItBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var replays = new DeathReplays(f.directory.resolve("replays"), null);
        BlockPos[] aside = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Minded"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos().toImmutable();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
                        if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue;
                        world.setBlockState(feet.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    f.player().getInventory().clear();
                    aside[0] = feet.add(3, 0, 0);
                    f.runtime.campaignForTest(f.id).setAside(aside[0], world.getServer().getTicks() + 1_000_000L);
                    f.runtime.captureReplays(replays);
                    f.runtime.stuckTicksForFixtures(f.id, 600);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(replays.latestForTest().isPresent(), "A spell kept"))
                .createAndAdd(() -> {
                    var capture = replays.latestForTest().orElseThrow();
                    com.google.gson.JsonObject json;
                    try {
                        capture.written().get(10, java.util.concurrent.TimeUnit.SECONDS);
                        json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(capture.folder().resolve("replay.json"))).getAsJsonObject();
                    } catch (Exception failed) {
                        throw new net.minecraft.test.GameTestException("The replay not written: " + failed);
                    }
                    context.assertTrue(json.has("memory"), "Its goal's memory kept");
                    var memory = json.getAsJsonObject("memory");
                    var origin = json.getAsJsonArray("origin");
                    BlockPos was = new BlockPos(origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt());
                    BlockPos rel = aside[0].subtract(was);
                    boolean kept = false;
                    for (var one : memory.getAsJsonArray("blocked")) {
                        var at = one.getAsJsonObject().getAsJsonArray("at");
                        if (at.get(0).getAsInt() == rel.getX() && at.get(1).getAsInt() == rel.getY() && at.get(2).getAsInt() == rel.getZ()) kept = true;
                    }
                    context.assertTrue(kept, "The set-aside block kept, at " + rel.toShortString() + " in the box: " + memory.get("blocked"));
                    int bytes = memory.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    KindredMod.LOGGER.info("Spell replay memory: {} bytes, {} left out; {}", bytes, memory.get("leftOut"), memory);
                    // Put back into a fresh goal whose box stands elsewhere, with one entry outside the box and one not known.
                    memory.getAsJsonArray("dug").add(com.google.gson.JsonParser.parseString("[" + (DeathReplays.WIDTH + 5) + ", 0, 0]"));
                    memory.addProperty("somethingElse", 1);
                    var fresh = new PortalRunCampaign();
                    BlockPos now = was.add(100, 0, -40);
                    var dropped = fresh.restoreMemory(memory, now, DeathReplays.WIDTH, DeathReplays.HEIGHT, 0);
                    context.assertTrue(fresh.blocked(now.add(rel)), "Set aside again where its box now stands; dropped " + dropped);
                    context.assertFalse(fresh.blocked(aside[0]), "Not where the box was");
                    context.assertTrue(dropped.size() == 2 && dropped.stream().anyMatch(d -> d.startsWith("dug: "))
                            && dropped.stream().anyMatch(d -> d.startsWith("somethingElse: ")), "The outside entry and the unknown one dropped and named: " + dropped);
                    context.assertTrue(new PortalRunCampaign().restoreMemory(null, now, DeathReplays.WIDTH, DeathReplays.HEIGHT, 0).isEmpty(), "No memory, nothing put back");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
