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
 * In plain terms: a founder on a plateau, its first claim out past the spawn exclusion beyond the plateau's edge and the
 * valley below, walks its first leg to ground it sees along the way, not to a point in the air over the valley at its own
 * height, where no route ends. On Factions seed 20261030, after M-06i2c1, 309 of 333 refused faction legs began at the
 * surface; of all the legs refused for no route on that seed, 211 ended in mid-air (M-06i2c2).
 *
 * Technical: staged 200 blocks from the world's spawn, inside a 250-block spawn exclusion, on a plateau of stone 30
 * high whose edge is 22 blocks east of it, toward its claim; past the edge, the flat ground. Its staged inventory: 16
 * bread. No goal of its own. Its first leg's 48 blocks end over the flat ground, which the plateau's own top hides from
 * its eyes. Asserted: its first claim outside the exclusion; its leg's end ground it could stand on, toward the claim
 * and short of the edge; within 600 ticks no faction walk refused, and its walk there done.
 */
public final class NativeFactionPlateauGameTests implements FabricGameTest {
    private static final FactionSettings PACK = FactionSettings.parse(Map.of("border", "60000000", "spawnExclusion", "250"));
    /** Its first leg walked within this. */
    private static final int WALKED = 600;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionPlateau", tickLimit = 2000)
    public void onAPlateauItsFirstLegEndsOnGroundItSeesNotInTheAirOverTheValley(TestContext context) {
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
                    // The plateau: stone 30 high from x 180 to its edge at x 222, toward its claim east; the flat ground past it.
                    for (int x = 180; x <= 222; x++) for (int z = 0; z <= 16; z++) for (int y = ground; y < top; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
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
                    KindredMod.LOGGER.info("Plateau fixture: its first leg ends at {}, standing at {}", end, player.getBlockPos().toShortString());
                    context.assertTrue(end != null && end.getX() > 200 && end.getX() <= 222, "Its leg ends toward its claim and short of the edge: " + end);
                    context.assertTrue(end.getY() == top && world.getBlockState(end.down()).isOf(Blocks.STONE) && world.getBlockState(end).isAir(),
                            "Its leg ends on the plateau's top, ground it could stand on: " + end + " on " + world.getBlockState(end.down()));
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
