package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The night shelter's hold on a walk out under the sky looks at the way, not only at where the walk ends (F-22c76). Dug in
 * for the night, F-25c4cd holds any walk of its work that would take it back out under the sky till daylight. It asked
 * only where the walk ended. In ci259's {@code NativeFactionShelterGameTests} (PR #259's CI, 12 November) a player dug in four
 * down by a stair was sent to explore toward iron. The walk ended twelve blocks off at a cell below the ground, so it was
 * not held, but its way went up the stair, over the ground and out of the shelter, and the player dug in again.
 */
public final class NativeShelterWayGameTests implements FabricGameTest {
    /** The stone's top over its feet: it stands this far down, dug in under the surface. */
    private static final int DOWN = 5;
    /** How far east the cave reached only over the ground lies, and how far west the one its tunnel reaches. */
    private static final int OFF = 10;

    /**
     * In plain terms: dug in for the night, a walk to a spot underground whose only way there goes up out of its shelter
     * and across open ground counts as a walk out under the sky, and waits for morning; a walk to a spot it can reach by a
     * tunnel underground does not.
     *
     * Technical: F-22c76. STAGED: stone {@value #DOWN} deep over its feet, 25 across; its cell (feet and head) hollowed at
     * the middle; a stair from it up to the surface, north; a cave two high {@value #OFF} east at its own depth, reached only
     * by a stair down from the surface, north of it, with stone all round it below ground; and a cave {@value #OFF} west at its
     * own depth reached by a tunnel two high from its cell. The night's rule is asked directly, as {@code NativeSeenColumnsGameTests}
     * asks it, so nothing moves the player. Fails if the walk to the east cave is not counted as out under the sky (its way
     * crosses the ground), or if the walk to the west cave is (its way stays under the rock), or if the surface itself is not.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "shelterWay", tickLimit = 400)
    public void dugInAWalkWhoseWayCrossesTheGroundCountsAsOutUnderTheSky(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 380);
        BlockPos[] o = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    // Inside its own wide structure, well clear of its neighbours, as NativeSeenColumnsGameTests' yard.
                    BlockPos in = context.getAbsolutePos(new BlockPos(42, 0, 60));
                    BlockPos up = player.getBlockPos().up(30);
                    o[0] = new BlockPos((in.getX() & ~15) + 8, up.getY(), (in.getZ() & ~15) + 8);
                    dugIn(context, o[0]);
                    player.refreshPositionAndAngles(o[0].getX() + 0.5, o[0].getY(), o[0].getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o[0]),
                        "Dug in at its cell: " + f.player().getBlockPos().toShortString()))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos east = o[0].add(OFF, 0, 0), west = o[0].add(-OFF, 0, 0), surface = o[0].add(0, DOWN, -(DOWN + 3));
                    boolean over = PortalRunCampaign.outUnderTheSkyForTest(player, east);
                    boolean under = PortalRunCampaign.outUnderTheSkyForTest(player, west);
                    boolean top = PortalRunCampaign.outUnderTheSkyForTest(player, surface);
                    KindredMod.LOGGER.info("Shelter way fixture: out under the sky - the cave over the ground {}, the cave by its tunnel {}, the surface {}", over, under, top);
                    context.assertTrue(top, "A walk to the surface is out under the sky");
                    context.assertTrue(over, "A walk to the cave east, its only way up the stair and over the ground, is not counted as out under the sky");
                    context.assertTrue(!under, "A walk to the cave west, its way a tunnel under the rock, is counted as out under the sky");
                })
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The staging: stone from ten below its feet to {@value #DOWN} over them, 25 across, air over it; its cell; a stair north
     * from its cell up to the surface; the east cave and its own stair north up to the surface; the west cave and its tunnel.
     */
    private static void dugIn(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-14, -12, -14), o.add(14, 14, 14));
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            for (int y = -10; y <= 13; y++) world.setBlockState(o.add(x, y, z), (y < DOWN ? Blocks.STONE : Blocks.AIR).getDefaultState());
        }
        // Its cell, and the stair from it: a step up and a step north each time, three clear over each tread.
        stair(context, o);
        // The east cave, two high at its own depth, with its own stair up to the surface; nothing joins it to the cell below.
        BlockPos east = o.add(OFF, 0, 0);
        stair(context, east);
        // The west cave and a tunnel two high to it from the cell.
        for (int x = -OFF; x <= 0; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
    }

    /** A cell two high at {@code foot}, and a stair north from it to the stone's top: each step one up and one north. */
    private static void stair(TestContext context, BlockPos foot) {
        var world = context.getWorld();
        for (int k = 0; k <= DOWN; k++) {
            for (int y = 0; y <= 2; y++) world.setBlockState(foot.add(0, k + y, -k), Blocks.AIR.getDefaultState());
        }
    }
}
