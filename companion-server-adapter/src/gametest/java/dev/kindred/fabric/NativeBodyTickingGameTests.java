package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that an AI player keeps
 * moving even where no human is nearby.
 *
 * Technical: A native body moved outside its own tick is still ticked where it lands (M-05's rare idle
 * start). A world ticks an entity only where a chunk is simulated. A human's client re-centres its chunk
 * tickets with its next movement packet; a native body moved by a teleport command, or by a fixture, into a
 * chunk its tickets did not cover was never ticked again to move them, and froze there for good. Here the
 * body is moved far from every test's area, as a /tp would.
 */
public final class NativeBodyTickingGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "movedAway", tickLimit = 600)
    public void aBodyMovedOutsideItsOwnTickIsStillTickedWhereItLands(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        long[] before = {0};
        BlockPos[] far = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mover"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    far[0] = player.getBlockPos().add(4096, 0, 0).toImmutable();
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.setBlockState(far[0].add(x, -1, z), Blocks.STONE.getDefaultState());
                    before[0] = f.runtime.nativeBody(f.id).orElseThrow().ticksRun();
                    player.refreshPositionAndAngles(far[0].getX() + 0.5, far[0].getY(), far[0].getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    long ticks = f.runtime.nativeBody(f.id).orElseThrow().ticksRun() - before[0];
                    context.assertTrue(ticks >= 40, "Ticked where it was moved: " + ticks + " ticks; "
                            + f.runtime.nativeStatusLine(f.id));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getServerWorld().shouldTickEntity(player.getBlockPos()), "Its chunk simulated");
                    context.assertTrue(player.getBlockPos().isWithinDistance(far[0], 2), "Where it was moved: " + player.getBlockPos().toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
