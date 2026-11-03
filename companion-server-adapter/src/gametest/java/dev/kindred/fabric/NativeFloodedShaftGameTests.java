package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** A player in water it let in on itself, out of the water by the ways a player gets out. */
public final class NativeFloodedShaftGameTests implements FabricGameTest {
    /**
     * Its own staircase up dug into a lake's floor: the shaft and the pocket it stands in flooded, a lake above, and it
     * gets out of the water. In the ten seeds of main with E-25a (`e25a-7c8febf`) Kindred on 8675309, climbing for food,
     * opened its stair under a lake; standing on its flooded pocket's floor, every step out was refused for the water beside
     * it, and its walk over "to the wall" (the water under the hole) found no route 830 times in 19 minutes. Staged: the
     * stone, the lake three deep with its shore, the pocket and its shaft as dug, full of water, and its kit as though
     * made: a stone pickaxe, dirt and bread.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "floodedShaft", tickLimit = 3600)
    public void inItsOwnShaftFloodedFromALakeAboveItGetsOutOfTheWater(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3000);
        BlockPos[] at = new BlockPos[1];
        // Its body as it went into the water: one respawned after drowning is another, and out of the water is no pass.
        net.minecraft.server.network.ServerPlayerEntity[] body = new net.minecraft.server.network.ServerPlayerEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Swimmer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().up(3);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-8, -3, -8), p.add(18, 10, 8));
                    for (int x = -8; x <= 18; x++) for (int z = -8; z <= 8; z++) for (int y = -3; y <= 10; y++) {
                        // The lake three deep, its shore along its south side, level with its water.
                        boolean lake = y >= 4 && y <= 6 && Math.abs(x) <= 7 && z >= -7 && z <= 1;
                        var state = y <= 3 || (y <= 6 && !lake) ? Blocks.STONE.getDefaultState()
                                : lake ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    // What it dug: the pocket it stands in, its stair's first steps, the shaft up to the hole in the
                    // lake's floor, flooded, and its tunnel in from the east, sixteen long.
                    int[][] dug = {{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {0, 1, -1}, {0, 2, -1}, {0, 3, -1}, {0, 1, 1},
                            {1, 1, 0}, {1, 2, 0}};
                    for (int x = 1; x <= 16; x++) for (int y = -1; y <= 0; y++) world.setBlockState(p.add(x, y, 0), Blocks.AIR.getDefaultState());
                    for (int[] c : dug) world.setBlockState(p.add(c[0], c[1], c[2]), Blocks.WATER.getDefaultState());
                    // The lake's floor of sand came down the shaft as it opened, and the last of it lies in the shaft's
                    // bottom cell, beside the pocket: the way north at the pocket's middle is shut, and over the
                    // pocket's top cell is rock.
                    world.setBlockState(p.add(0, 1, -1), Blocks.SAND.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.DIRT, 16));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    world.setTimeOfDay(1000);
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(PlayerRuntime.inDeepWater(f.player()), "In the pocket's water");
                    body[0] = f.player();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player == body[0] && player.isAlive(), "Drowned in the pocket: "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    if (context.getWorld().getTime() % 200 == 0) {
                        KindredMod.LOGGER.info("Flooded shaft fixture: at {}, {}", player.getBlockPos().toShortString(),
                                f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    }
                    context.assertTrue(player.isOnGround() && !player.isTouchingWater(),
                            "Out of the water: " + player.getBlockPos().toShortString() + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Flooded shaft fixture: out of the water at {}", f.player().getBlockPos().toShortString());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
