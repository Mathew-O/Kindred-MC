package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a founder at the world spawn, with bases on, does not make the ground for its first base, inside the
 * spawn exclusion, its first claim or its base's anchor. A claim there is refused by the rules, so it was pushed some 500
 * blocks out on its bearing from spawn and moved again with every look; on Factions seed 20261030 Kindred chose that
 * claim 25 times in nine minutes, 64 of its 65 walks toward it refused, and held no land (M-06i2a). Its first land is
 * taken as without bases, from where it stands, and its base follows on that land.
 *
 * Technical: staged 150 blocks from the world's spawn, past the hub's safe zone and inside the 500-block spawn exclusion
 * as the pack sets it, on a grass field laid round where it stands. Its staged inventory: 16 bread, nothing else. Asserted, after it has played and looked round: its bases'
 * anchor is unset or outside the exclusion, and its faction's choice, a claim, is of a chunk outside it; then it lands that
 * first claim, outside the exclusion, within 3,600 ticks, the place it walks to for it holding still on the way.
 */
public final class NativeFactionSpawnClaimGameTests implements FabricGameTest {
    private static final FactionSettings PACK = FactionSettings.parse(Map.of("border", "60000000", "spawnExclusion", "500"));
    private static final String W = Territory.OVERWORLD;
    /** Played this long by the spawn before it is asked: its look round fills, and a base site is reckoned. */
    private static final int PLAYED = 400;
    /** Stood playing and looking round before the faction world begins. */
    private static final int LOOK_ROUND = 200;
    /** Its first claim landed within this many ticks of the faction world beginning: some 370 blocks' walk, out of the exclusion. */
    private static final int CLAIMED = 3600;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionSpawnClaim", tickLimit = 5200)
    public void atTheSpawnItsFirstClaimAndBaseAnchorAreNotInsideTheExclusion(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5100);
        var world = context.getWorld();
        // Past the hub's safe zone (64 blocks round spawn, where it may build nothing, so where no site is reckoned) and
        // well inside the 500-block exclusion: where Kindred's sites were, on the seed.
        world.getChunk(150 >> 4, 8 >> 4);
        int ground = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 150, 8);
        BlockPos stand = new BlockPos(150, ground, 8);
        long[] start = {-1};
        var targets = new java.util.LinkedHashSet<String>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, new BlockPos(100, ground - 3, -40), new BlockPos(200, ground + 6, 56));
                    // A grass field round where it stands, open to the sky: ground a cabin's site can be on, as the base fixtures lay it.
                    for (int x = 102; x <= 198; x++) for (int z = -38; z <= 54; z++) {
                        world.setBlockState(new BlockPos(x, ground - 2, z), net.minecraft.block.Blocks.DIRT.getDefaultState());
                        world.setBlockState(new BlockPos(x, ground - 1, z), net.minecraft.block.Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = ground; y <= ground + 6; y++) world.setBlockState(new BlockPos(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    // No goal of its own, so that only faction play and its base move it, by the spawn.
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    start[0] = world.getServer().getTicks();
                })
                // It plays and looks round before the faction world begins, as a player who has stood there a while has.
                .createAndAdd(() -> context.assertTrue(world.getServer().getTicks() - start[0] >= LOOK_ROUND, "Looked round"))
                .createAndAdd(() -> context.assertTrue(f.runtime.basesLoadedForTest(f.id), "Its bases' record read"))
                .createAndAdd(() -> {
                    var player = f.player();
                    f.runtime.factionsWorldForTest(PACK);
                    f.runtime.factionsTickForTest(); // Its starting coins, as for anyone first seen in a faction world.
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, player.getUuid(), "create", List.of("Red", "RED")).accepted(), "Mason founds Red");
                    f.runtime.factions().clockForTest(0);
                    start[0] = world.getServer().getTicks();
                })
                .createAndAdd(() -> context.assertTrue(world.getServer().getTicks() - start[0] >= PLAYED, "Played " + PLAYED + " ticks by the spawn"))
                .createAndAdd(() -> {
                    var anchor = f.runtime.baseAnchorForTest(f.id);
                    context.assertTrue(anchor == null || Territory.Chunk.at(W, anchor.x(), anchor.z()).nearestToSpawn() >= PACK.spawnExclusion(),
                            "Its bases' anchor not inside the spawn exclusion: " + anchor);
                    var choice = f.runtime.factionChooseForTest(f.id);
                    context.assertTrue(choice != null && choice.kind() == FactionPlay.Kind.CLAIM, "A first claim chosen: " + choice);
                    context.assertTrue(Territory.Chunk.of(choice.target()).nearestToSpawn() >= PACK.spawnExclusion(),
                            "Its first claim outside the spawn exclusion: " + choice);
                })
                .createAndAdd(() -> {
                    // On its way to its first claim: the place it walks to holds still, and it lands the claim in time.
                    var move = f.runtime.factionMoveForTest(f.id);
                    if (move != null && move.kind() == FactionPlay.Kind.CLAIM) targets.add(move.target());
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    var held = f.runtime.projected().claims().entrySet().stream().filter(e -> e.getValue().equals(red)).map(java.util.Map.Entry::getKey).toList();
                    context.assertTrue(world.getServer().getTicks() - start[0] <= CLAIMED || !held.isEmpty(),
                            "Its first claim landed within " + CLAIMED + " ticks: none; at " + f.player().getBlockPos().toShortString() + ", move " + move);
                    context.assertTrue(!held.isEmpty(), "Its first claim landed");
                    context.assertTrue(Territory.Chunk.of(held.getFirst()).nearestToSpawn() >= PACK.spawnExclusion(), "Landed outside the exclusion: " + held);
                    context.assertTrue(targets.size() <= 1, "The place it walked to for its first claim held still: " + targets);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
