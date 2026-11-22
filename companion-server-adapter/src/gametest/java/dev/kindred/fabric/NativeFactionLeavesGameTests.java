package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a founder on a plateau, its first claim out past the spawn exclusion, stands behind a hedge of spruce
 * leaves that hides from its eyes the ground it would walk to. It breaks the leaves in its line, by hand, and its leg then
 * ends on the ground it now sees, not in the air at its own height. On Factions seed 20261030, of the legs M-06i2c2 left as
 * they were, leaves in its sight line hid the ground along the way on 885 and 337 (bases on and off), most within 4
 * blocks of it (M-06i2c3); far looks keep counting leaves as blocking (M-06i2c5).
 *
 * Technical: staged 200 blocks from the world's spawn, inside a 250-block spawn exclusion, on a plateau of stone 30 high
 * whose edge is 22 blocks east of it, toward its claim; a hedge of persistent spruce leaves 2 and 3 blocks east of it,
 * 3 high and 5 wide, across its line. Its staged inventory: 16 bread. No goal of its own. Asserted: its first claim
 * outside the exclusion; within 600 ticks, between 1 and {@link FactionsPlayer#LEAF_BREAKS} leaves broken in its sight line,
 * no faction walk refused, and its leg's end on the plateau's top; then its walk done within 600 more.
 */
public final class NativeFactionLeavesGameTests implements FabricGameTest {
    private static final FactionSettings PACK = FactionSettings.parse(Map.of("border", "60000000", "spawnExclusion", "250"));
    /** The leaves broken and its leg on seen ground within this; then its walk done within this again. */
    private static final int WITHIN = 600;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionLeaves", tickLimit = 2400)
    public void behindAHedgeItBreaksTheLeavesInItsLineAndItsLegEndsOnGroundItSees(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
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
                    FixtureGround.keep(context, new BlockPos(176, ground - 1, 0), new BlockPos(262, top + 6, 16));
                    // The plateau: stone 30 high from x 180 to its edge at x 222, toward its claim east; the flat ground past it.
                    for (int x = 180; x <= 222; x++) for (int z = 0; z <= 16; z++) for (int y = ground; y < top; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    // The hedge: spruce leaves that do not fall, 2 and 3 blocks east of where it will stand, 3 high, 5 wide.
                    var leaves = Blocks.SPRUCE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
                    for (BlockPos at : hedge(top)) world.setBlockState(at, leaves);
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
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    int broken = f.runtime.factionLeavesForTest(f.id)[0];
                    var end = f.runtime.factionDestinationForTest(f.id);
                    int[] blocked = f.runtime.factionBlockedForTest(f.id);
                    boolean onPlateau = end != null && end.getY() == top && end.getX() > 202 && end.getX() <= 222;
                    context.assertTrue(server.getTicks() - start[0] <= WITHIN || onPlateau, "Its leg on the ground it sees within " + WITHIN
                            + " ticks: " + end + ", " + broken + " leaves broken in its sight line, " + blocked[0] + " walks refused, at " + f.player().getBlockPos().toShortString());
                    context.assertTrue(onPlateau, "Its leg on the plateau's top");
                    int gone = (int) hedge(top).stream().filter(at -> world.getBlockState(at).isAir()).count();
                    KindredMod.LOGGER.info("Leaves fixture: {} leaves broken in its sight line ({} of the hedge's gone), its leg to {}", broken, gone, end);
                    context.assertTrue(broken >= 1 && broken <= FactionsPlayer.LEAF_BREAKS, "Between 1 and " + FactionsPlayer.LEAF_BREAKS + " leaves broken in its sight line: " + broken);
                    context.assertTrue(gone >= broken, "The leaves it broke gone from the hedge: " + gone + " of " + broken);
                    context.assertTrue(blocked[0] == 0, "No faction walk refused: " + blocked[0]);
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    int ended = f.runtime.factionOverWaterForTest(f.id)[1];
                    int[] blocked = f.runtime.factionBlockedForTest(f.id);
                    context.assertTrue(blocked[0] == 0, "No faction walk refused: " + blocked[0] + ", at " + f.player().getBlockPos().toShortString());
                    context.assertTrue(server.getTicks() - start[0] <= WITHIN || ended >= 1, "Its walk done within " + WITHIN + " ticks: none, at "
                            + f.player().getBlockPos().toShortString());
                    context.assertTrue(ended >= 1, "Its walk done");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The hedge's cells: x 202 and 203, 3 high from the plateau's top, z 6 to 10. */
    private static List<BlockPos> hedge(int top) {
        var out = new java.util.ArrayList<BlockPos>();
        for (int x = 202; x <= 203; x++) for (int y = top; y <= top + 2; y++) for (int z = 6; z <= 10; z++) out.add(new BlockPos(x, y, z));
        return out;
    }
}
