package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a founder on a narrow summit, its first claim out past the spawn exclusion down the mountain's side,
 * looks from the summit's edge for the ground below it cannot see from the middle, and its first leg ends there, not in
 * the air at its own height. On Factions seed 20261030, after M-06i2c2, the brink of the ground it stood on hid the slope
 * below on 443 and 321 of the legs it left as they were (M-06i2c3's trace, bases on and off) (M-06i2c4).
 *
 * Technical: staged 200 blocks from the world's spawn, inside a 250-block spawn exclusion, on a ridge of stone: a summit
 * 5 wide and 30 high, its sides stepping down 2 blocks a block to the flat ground. No spruce. From the summit's middle its
 * own edge hides the steps and the flat ground past them; from its edge, 2 blocks off, they are in sight. Its staged
 * inventory: 16 bread. No goal of its own. Asserted: its first claim outside the exclusion; its leg's end on ground below
 * the summit, toward the claim, that it could stand on; within 600 ticks no faction walk refused, and its walk done.
 */
public final class NativeFactionSummitGameTests implements FabricGameTest {
    private static final FactionSettings PACK = FactionSettings.parse(Map.of("border", "60000000", "spawnExclusion", "250"));
    /** Its first leg walked within this. */
    private static final int WALKED = 600;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionSummit", tickLimit = 2000)
    public void onASummitItsFirstLegEndsOnTheGroundItSeesFromTheEdge(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1900);
        var world = context.getWorld();
        var server = world.getServer();
        world.getChunk(200 >> 4, 8 >> 4);
        int ground = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 200, 8);
        int top = ground + 30;
        long[] start = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, new BlockPos(176, ground - 1, 0), new BlockPos(262, top + 4, 16));
                    // The ridge: its summit x 198 to 202, its sides 2 blocks lower each block out, down to the flat ground.
                    for (int x = 180; x <= 220; x++) {
                        int height = top - 2 * Math.max(0, Math.abs(x - 200) - 2);
                        for (int z = 0; z <= 16; z++) for (int y = ground; y < height; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
                    }
                })
                .createAndAdd(() -> {
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(200.5, top, 8.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    f.runtime.factionsWorldForTest(PACK);
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, player.getUuid(), "create", List.of("Red", "RED")).accepted(), "Mason founds Red");
                    f.runtime.factions().clockForTest(0);
                    var choice = f.runtime.factionChooseForTest(f.id);
                    context.assertTrue(choice != null && choice.kind() == FactionPlay.Kind.CLAIM, "A first claim chosen: " + choice);
                    context.assertTrue(Territory.Chunk.of(choice.target()).nearestToSpawn() >= PACK.spawnExclusion(),
                            "Its first claim outside the spawn exclusion: " + choice);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var end = f.runtime.factionDestinationForTest(f.id);
                    KindredMod.LOGGER.info("Summit fixture: its first leg ends at {}, standing at {}", end, player.getBlockPos().toShortString());
                    context.assertTrue(end != null && end.getX() > 202 && end.getX() <= 248 && end.getY() < top - 2,
                            "Its leg ends on ground below the summit, toward its claim: " + end + " (the summit's top " + top + ")");
                    context.assertTrue(!world.getBlockState(end.down()).isAir() && world.getBlockState(end).isAir() && world.getBlockState(end.up()).isAir(),
                            "Its leg ends where it could stand: " + end + " on " + world.getBlockState(end.down()));
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    int[] blocked = f.runtime.factionBlockedForTest(f.id);
                    int ended = f.runtime.factionOverWaterForTest(f.id)[1];
                    context.assertTrue(blocked[0] == 0, "No faction walk refused: " + blocked[0] + " refused, at " + f.player().getBlockPos().toShortString());
                    context.assertTrue(server.getTicks() - start[0] <= WALKED || ended >= 1, "Its first leg walked within " + WALKED + " ticks: none, at "
                            + f.player().getBlockPos().toShortString() + ", move " + f.runtime.factionMoveForTest(f.id));
                    context.assertTrue(ended >= 1, "Its first leg walked");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
