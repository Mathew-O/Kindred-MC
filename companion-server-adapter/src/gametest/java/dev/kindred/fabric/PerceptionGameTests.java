package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.BlockObservation;
import dev.kindred.core.BlockObservations;
import dev.kindred.core.BlockPerception;
import dev.kindred.core.SectionKey;
import dev.kindred.core.SectionPlanner;
import dev.kindred.core.WorkSetup;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player sees only
 * the blocks it could really see.
 *
 * Technical: Native contracts for bounded block perception over real chunk sections: palette prefiltering,
 * exposure, unloaded-chunk refusal and the per-tick budget. They prove what the companion can and cannot see;
 * they do not claim any gathering, mining or live client behavior.
 */
public final class PerceptionGameTests implements FabricGameTest {
    private static final String LOG = "minecraft:oak_log";
    private static final String DIAMOND = "minecraft:diamond_ore";

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void lookCommandParsesWithOptionalBlockAndUuid(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();
        for (String command : List.of("companion look", "companion look minecraft:oak_log", "companion look " + UUID.randomUUID(),
                "companion look minecraft:oak_log " + UUID.randomUUID())) {
            var parsed = dispatcher.parse(command, source);
            context.assertFalse(parsed.getReader().canRead(), "Must parse: " + command);
            context.assertTrue(parsed.getExceptions().isEmpty(), "Must accept: " + command);
        }
        var invalid = dispatcher.parse("companion look not a block", source);
        context.assertTrue(invalid.getReader().canRead() || !invalid.getExceptions().isEmpty(), "Garbage is rejected by the grammar");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void paletteFilterSkipsSectionsWithoutBlocksOfInterestAndWalksOnlyMatches(TestContext context) {
        BlockPos log = context.getAbsolutePos(new BlockPos(1, 2, 1));
        context.setBlockState(new BlockPos(1, 2, 1), Blocks.OAK_LOG);
        ServerWorld world = context.getWorld();
        var source = new ChunkSectionSource(world);
        SectionKey key = SectionKey.of(point(log));
        BlockPerception.SectionView view = source.section(key);
        context.assertTrue(view != null && !view.isEmpty(), "The test section is loaded and not empty");
        context.assertTrue(view.mayContain(Set.of(LOG)), "The palette admits oak logs");
        context.assertFalse(view.mayContain(Set.of(DIAMOND)), "The palette holds no diamond ore, so the section is never walked for it");
        context.assertFalse(view.mayContain(Set.of("kindred:not_a_block")), "Unknown identities match nothing");

        BlockPerception perception = perception();
        perception.retarget(List.of(key), Set.of(LOG));
        var stats = perception.tick(source);
        context.assertTrue(stats.scanned() == 1 && stats.passes() == 1, "One section walked in one pass: " + stats);
        BlockObservations seen = perception.snapshot();
        context.assertTrue(seen.exposedCount(LOG) >= 1, "An exposed log is positioned");
        context.assertTrue(seen.nearest(LOG, point(log), 1).get(0).position().equals(point(log)), "The nearest exposed log is the placed one");
        context.assertTrue(seen.nearest(LOG, point(log), 1).get(0).observedAtMillis() > 0, "Facts carry an observation time");

        perception.retarget(List.of(key), Set.of(DIAMOND));
        stats = perception.tick(source);
        context.assertTrue(stats.skippedByPalette() == 1 && stats.scanned() == 1, "Diamond interest skips the section by palette: " + stats);
        context.assertTrue(perception.snapshot().matchCount(DIAMOND) == 0 && perception.snapshot().matchCount(LOG) == 0,
                "A new question starts from nothing and sees nothing it should not");
        KindredMod.LOGGER.info("[F-24 evidence] palette prefilter: {}", stats);
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void buriedBlocksAreCountedButNeverLocated(TestContext context) {
        BlockPos relative = new BlockPos(2, 2, 2);
        context.setBlockState(relative, Blocks.OAK_LOG);
        for (Direction direction : Direction.values()) context.setBlockState(relative.offset(direction), Blocks.STONE);
        BlockPos log = context.getAbsolutePos(relative);
        var source = new ChunkSectionSource(context.getWorld());
        BlockPerception perception = perception();
        perception.retarget(List.of(SectionKey.of(point(log))), Set.of(LOG));
        perception.tick(source);
        BlockObservations buried = perception.snapshot();
        context.assertTrue(buried.matchCount(LOG) >= 1, "The buried log is counted");
        context.assertTrue(buried.nearest(LOG, point(log), 16).stream().noneMatch(o -> o.position().equals(point(log))),
                "A log with six opaque neighbours is never positioned: no X-ray perception");
        context.setBlockState(relative.up(), Blocks.AIR);
        perception.tick(source);
        BlockObservations uncovered = perception.snapshot();
        List<BlockObservation> nearest = uncovered.nearest(LOG, point(log), 1);
        context.assertTrue(!nearest.isEmpty() && nearest.get(0).position().equals(point(log)), "Uncovering one face exposes it on the next pass");
        context.assertTrue(uncovered.version() == 2 && nearest.get(0).observedAtMillis() >= buried.newestObservationMillis().orElse(0),
                "The second pass is a newer version with a fresh observation time");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void unloadedChunksAreSkippedAndNeverLoaded(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos here = context.getAbsolutePos(BlockPos.ORIGIN);
        int farChunkX = (here.getX() >> 4) + 4_000;
        int farChunkZ = (here.getZ() >> 4) + 4_000;
        context.assertFalse(world.isChunkLoaded(farChunkX, farChunkZ), "The far chunk starts unloaded");
        var source = new ChunkSectionSource(world);
        SectionKey far = new SectionKey(farChunkX, here.getY() >> 4, farChunkZ);
        context.assertTrue(source.section(far) == null, "An unloaded section is reported as unknown");
        context.assertTrue(source.section(new SectionKey(here.getX() >> 4, world.getTopSectionCoord() + 3, here.getZ() >> 4)) == null,
                "Above the world is unknown, not an error");
        BlockPerception perception = perception();
        perception.retarget(List.of(far, SectionKey.of(point(here))), Set.of(LOG));
        var stats = perception.tick(source);
        context.assertTrue(stats.unloaded() == 1 && stats.passes() == 1, "One unloaded section skipped in a completed pass: " + stats);
        context.assertFalse(perception.snapshot().sections().containsKey(far), "No facts exist for an unloaded section");
        context.assertFalse(world.isChunkLoaded(farChunkX, farChunkZ), "Perception did not load the chunk");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void budgetBoundsSectionsPerTickAcrossTheNineChunkArea(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos here = context.getAbsolutePos(new BlockPos(1, 1, 1));
        context.setBlockState(new BlockPos(1, 1, 1), Blocks.OAK_LOG);
        WorkSetup.WorkRegion area = WorkSetup.WorkRegion.around(point(here));
        List<SectionKey> plan = SectionPlanner.plan(area, point(here), SectionPlanner.DEFAULT, world.getBottomSectionCoord(), world.getTopSectionCoord());
        // The test world floor sits near the bottom of the world, so the five-section band is clipped by world height.
        int bodySection = here.getY() >> 4;
        int expectedSections = 9 * (Math.min(world.getTopSectionCoord() - 1, bodySection + 2) - Math.max(world.getBottomSectionCoord(), bodySection - 2) + 1);
        context.assertTrue(plan.size() == expectedSections && plan.size() <= 45, "Nine chunks by the clipped vertical band: " + plan.size() + " vs " + expectedSections);
        context.assertTrue(plan.get(0).equals(SectionKey.of(point(here))), "The body's own section is examined first");
        int sections = plan.size();
        var budget = new BlockPerception.Budget(4, 1, 256, 32);
        BlockPerception perception = new BlockPerception("minecraft:overworld", budget, System::currentTimeMillis, System::nanoTime);
        perception.retarget(plan, Set.of(LOG));
        var source = new ChunkSectionSource(world);
        var first = perception.tick(source);
        context.assertTrue(first.sectionsVisited() <= 4, "At most four sections per tick: " + first);
        context.assertTrue(first.scanned() <= 1, "At most one detailed walk per tick: " + first);
        int ticks = 1;
        while (perception.stats().passes() == 0 && ticks < 200) { perception.tick(source); ticks++; }
        var done = perception.stats();
        context.assertTrue(done.passes() == 1, "The pass completes under the budget");
        int minTicks = (sections + 3) / 4;
        context.assertTrue(done.lastPassTicks() >= minTicks && done.lastPassTicks() <= sections,
                "A " + sections + "-section pass at four per tick takes " + minTicks + ".." + sections + " ticks: " + done.lastPassTicks());
        context.assertTrue(done.sectionsVisited() == sections, "Every planned section was examined exactly once: " + done.sectionsVisited());
        context.assertTrue(perception.snapshot().nearest(LOG, point(here), 1).get(0).position().equals(point(here)), "The log beside the body is seen");
        context.assertTrue(done.maxTickNanos() < 50_000_000L, "No single perception tick took 50 ms or more: " + done.maxTickNanos() + " ns");
        KindredMod.LOGGER.info("[F-24 evidence] nine-chunk pass: {}", done);
        // A default-budget pass over the same plan for the timing record.
        BlockPerception defaults = perception();
        defaults.retarget(plan, Set.of(LOG));
        int defaultTicks = 0;
        while (defaults.stats().passes() == 0 && defaultTicks < 200) { defaults.tick(source); defaultTicks++; }
        KindredMod.LOGGER.info("[F-24 evidence] default budget pass: {}", defaults.stats());
        int defaultMaxTicks = (sections + 1) / 2; // Worst case: every section needs a walk at two walks per tick.
        context.assertTrue(defaults.stats().lastPassTicks() <= defaultMaxTicks,
                "Default budget completes a " + sections + "-section pass within " + defaultMaxTicks + " ticks: " + defaults.stats().lastPassTicks());
        context.complete();
    }

    private static BlockPerception perception() {
        return new BlockPerception("minecraft:overworld", BlockPerception.DEFAULT, System::currentTimeMillis, System::nanoTime);
    }

    private static WorkSetup.BlockPoint point(BlockPos pos) { return new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()); }
}
