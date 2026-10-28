package dev.kindred.fabric;

import dev.kindred.core.tactics.BlastExposure;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Blast exposure (E-39i): the blast a crystal does to a player, reckoned beforehand from the rays between them and the
 * distance ({@link BlastExposure}, with the world's own collision shapes), is what the game then does to it: ten blocks
 * off in the open, and behind a wall of stone as high as the player, which stops all but the blast's last point.
 */
public final class NativeBlastGameTests implements FabricGameTest {
    private static void blast(TestContext context, boolean wall) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 900);
        double[] predicted = {-1};
        net.minecraft.entity.decoration.EndCrystalEntity[] crystal = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 14; x++) for (int z = -2; z <= 6; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.BEDROCK.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A wall of obsidian (the blast does not break it) three high and five wide, two blocks before the player.
                    if (wall) for (int z = 0; z <= 4; z++) for (int y = 2; y <= 4; y++) world.setBlockState(o.add(10, y, z), Blocks.OBSIDIAN.getDefaultState());
                    var player = f.player();
                    BlockPos at = o.add(12, 2, 2);
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.getInventory().clear();
                    var made = EntityType.END_CRYSTAL.create(world);
                    made.refreshPositionAndAngles(o.getX() + 2.5, o.getY() + 2, o.getZ() + 2.5, 0, 0);
                    made.setShowBottom(false);
                    world.spawnEntity(made);
                    crystal[0] = made;
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    var c = crystal[0];
                    predicted[0] = BlastExposure.toPlayer(BlastExposure.CRYSTAL, c.getX(), c.getY(), c.getZ(), player.getX(), player.getY(), player.getZ(),
                            PlayerRuntime.blastRays(world, player));
                    KindredMod.LOGGER.info("Blast fixture ({}): the crystal at {}, the player at {}, the blast reckoned at {}", wall ? "behind a wall" : "in the open",
                            c.getPos(), player.getPos(), predicted[0]);
                    c.damage(world.getDamageSources().generic(), 1.0f);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    double taken = 20.0 - player.getHealth();
                    KindredMod.LOGGER.info("Blast fixture ({}): reckoned {}, taken {}", wall ? "behind a wall" : "in the open", predicted[0], taken);
                    context.assertTrue(Math.abs(taken - predicted[0]) < 0.01, "The blast as reckoned: " + predicted[0] + " reckoned, " + taken + " taken");
                    context.assertTrue(wall ? predicted[0] < 2 : predicted[0] > 5, "Where it stands matters: " + predicted[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "blastOpen", tickLimit = 1000)
    public void aCrystalsBlastInTheOpenIsWhatItReckoned(TestContext context) {
        blast(context, false);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "blastWall", tickLimit = 1000)
    public void behindAWallTheBlastIsWhatItReckonedAndAlmostNothing(TestContext context) {
        blast(context, true);
    }
}
