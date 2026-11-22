package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The climb's walk back that ends lower than it began is not taken again at once from beside where it began (F-25c4at1).
 * In E-40 iteration 26 (`claude/corpus-data-e40a26-consoles-1122` at `1b99f522`), batch 1's 31102026 Sol stood at 57 to
 * 59, 63 to 64, 171 for three stuck spells, about 21 game minutes, going round "Climb toward the surface for logs: step
 * up", "back the way it came", "step aside": from the higher cell the walk back went toward a cell of its way in it could
 * not reach and ended short, a block lower; the stall on the walk back was set where the walk ended and lifted as soon as
 * the climb stepped it up again, higher than there, and the walk back was offered again. 8 hole-or-pit spells cycled so,
 * 5 of them with most walks back ending lower than they began.
 *
 * Staged at the walk back itself: three whole-run scenes did not reproduce the cycle (the climb's choice between its
 * steps turns on more than the console shows), so the fixture stages the world and the body, sets where it stands and
 * its way in through the campaign's fixture setters, sets the moment the walk back ended, and asks the campaign for the
 * walk back as the climb does. It does not cover the climb's own choice of step: the gate's marker and E-40 iteration
 * 28's five spells are what test that in play.
 */
public final class NativeRetraceLowerGameTests implements FabricGameTest {
    /**
     * In plain terms: an AI player whose walk back along its way in ended a block lower than it began is not sent the same
     * way again the moment its climb steps it back up; once the while is out, the way back is worth a try again.
     *
     * Technical: F-25c4at1, as iteration 26's 31102026 Sol (above). STAGED: stone round it, no sky; a floor at o with a
     * step one high east of it and, west, a ledge one high three off, its way in come down from the ledge along the floor
     * to o and up the step; its body put at each cell as the staging needs; the fixture sets where it stands and its way
     * in (the campaign's fixture setters). Then, as the climb asks: from the step its walk back, toward the ledge; the walk ended at o, a block lower, and
     * done; from o its walk back is held, as it was before the change; up the step again 40 ticks later, its walk back.
     * Fails if that last walk back is offered (the cycle), or if, 700 ticks after the walk ended, the walk back from the
     * step is still held.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "retraceLower", tickLimit = 600)
    public void aWalkBackThatEndsLowerIsNotTakenAgainFromBesideWhereItBegan(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 400);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        BlockPos ledge = o.add(-3, 1, 0), step = o.add(1, 1, 0);
        List<BlockPos> way = new ArrayList<>(List.of(ledge, o.add(-2, 0, 0), o.add(-1, 0, 0), o));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Climber"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // STAGED: stone round it, no sky; the floor x −3 to 1 at y 0, three high; the step's block at x 1 and
                    // the ledge's at x −3, each one high.
                    FixtureGround.keep(context, o.add(-6, -2, -3), o.add(4, 5, 3));
                    for (BlockPos cell : BlockPos.iterate(o.add(-6, -2, -3), o.add(4, 5, 3))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    for (BlockPos cell : BlockPos.iterate(o.add(-3, 0, 0), o.add(1, 2, 0))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
                    world.setBlockState(o.add(-3, 0, 0), Blocks.STONE.getDefaultState());
                    world.setBlockState(o.add(1, 0, 0), Blocks.STONE.getDefaultState());
                    world.setBlockState(ledge.up(2), Blocks.AIR.getDefaultState());
                    world.setBlockState(step.up(2), Blocks.AIR.getDefaultState());
                    world.setTimeOfDay(1_000);
                    var campaign = f.runtime.campaignForTest(f.id);
                    var body = f.runtime.nativeBodyForTest(f.id);
                    var player = f.player();
                    long now = world.getServer().getTicks();
                    // Up the step: its way in now ends there. From the step, its walk back, toward the ledge.
                    at(player, step);
                    way.add(step);
                    campaign.wayInForTest(step, way);
                    var first = campaign.walkBackForTest(body, now);
                    context.assertTrue(first.isPresent() && first.get().label().endsWith(": back the way it came"),
                            "No walk back from the step to begin with: " + first);
                    // The walk ended at o, a block lower than it began, and was done.
                    at(player, o);
                    way.add(o);
                    campaign.wayInForTest(o, way);
                    campaign.taskFinished(first.get(), true, now + 20);
                    context.assertTrue(campaign.walkBackForTest(body, now + 20).isEmpty(), "The walk back offered again from where it ended");
                    // The climb steps it up again, 40 ticks on: from where the walk began, its walk back is held.
                    at(player, step);
                    way.add(step);
                    campaign.wayInForTest(step, way);
                    var again = campaign.walkBackForTest(body, now + 60);
                    context.assertTrue(again.isEmpty(), "The walk back offered again from the step 40 ticks after it ended a block lower: the cycle ("
                            + again.map(PortalRunCampaign.Task::label).orElse("") + ")");
                    // Once the while is out, the way back is worth a try again.
                    var later = campaign.walkBackForTest(body, now + 720);
                    context.assertTrue(later.isPresent(), "The walk back from the step still held 700 ticks after the walk ended");
                    KindredMod.LOGGER.info("Retrace lower: held from the step 40 ticks after, offered again after the while: {}", later.get().label());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    private static void at(net.minecraft.server.network.ServerPlayerEntity player, BlockPos feet) {
        player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, -90, 10);
        player.setVelocity(Vec3d.ZERO);
    }
}
