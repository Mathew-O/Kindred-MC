package dev.kindred.fabric;

import dev.kindred.core.tactics.BlastExposure;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: in a real test world, an End crystal is set off ten blocks from a player, once
 * in the open and once with an obsidian wall between them, and the damage the player's own reckoning
 * predicted is checked to match the damage it really takes: a lot in the open, almost none behind the wall.
 *
 * Technical: Blast exposure (E-39i): the blast a crystal does to a player, reckoned beforehand from the rays
 * between them and the distance ({@link BlastExposure}, with the world's own collision shapes), is what the
 * game then does to it: ten blocks off in the open, and behind a wall of stone as high as the player, which
 * stops all but the blast's last point.
 */
public final class NativeBlastGameTests implements FabricGameTest {
    /**
     * Builds the floor, places the player and the crystal (and the wall if asked), sets the crystal off and
     * compares predicted with real damage.
     */
    private static void blast(TestContext context, boolean wall) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 900);
        double[] predicted = {-1}, taken = {-1};
        long[] setAt = {-1};
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
                    // Too little food to heal by between the blast and the reading of what it took.
                    player.getHungerManager().setFoodLevel(17);
                    player.getHungerManager().setSaturationLevel(0);
                    player.getInventory().clear();
                    setAt[0] = world.getTime();
                    var made = EntityType.END_CRYSTAL.create(world);
                    made.refreshPositionAndAngles(o.getX() + 2.5, o.getY() + 2, o.getZ() + 2.5, 0, 0);
                    made.setShowBottom(false);
                    world.spawnEntity(made);
                    crystal[0] = made;
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // A player who has just joined is spared all harm for three seconds; past them, and still.
                    context.assertTrue(world.getTime() - setAt[0] >= 80 && f.player().getVelocity().horizontalLengthSquared() < 1e-6, "Settled");
                    var player = f.player();
                    var c = crystal[0];
                    predicted[0] = BlastExposure.toPlayer(BlastExposure.CRYSTAL, c.getX(), c.getY(), c.getZ(), player.getX(), player.getY(), player.getZ(),
                            PlayerRuntime.blastRays(world, player));
                    KindredMod.LOGGER.info("Blast fixture ({}): the crystal at {}, the player at {}, the blast reckoned at {}", wall ? "behind a wall" : "in the open",
                            c.getPos(), player.getPos(), predicted[0]);
                    float health = player.getHealth();
                    c.damage(world.getDamageSources().generic(), 1.0f);
                    // The blast's harm is done in the crystal's breaking: read at once.
                    taken[0] = health - player.getHealth();
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Blast fixture ({}): reckoned {}, taken {}", wall ? "behind a wall" : "in the open", predicted[0], taken[0]);
                    context.assertTrue(Math.abs(taken[0] - predicted[0]) < 0.01, "The blast as reckoned: " + predicted[0] + " reckoned, " + taken[0] + " taken");
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
