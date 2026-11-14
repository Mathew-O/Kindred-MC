package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.Territory;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest of what a player knows of ground it cannot see (MOVEMENT, F-35m). An AI player stands in a
 * yard walled ten high, thirty up in the open sky. Out past the wall stand stone towers: one at the end of its next faction
 * leg, forty-eight blocks east, and four lower ones twenty-eight blocks out, where its explore walks would go. With the wall
 * up it cannot see their tops, and they set no walk's height: the walk goes at its own height, for the route to find the
 * way. With no wall it sees them, and their tops are where the walks go, as before.
 *
 * Technical: before F-35m the leg's end and the explore target took the column's top from the server's heightmap
 * ({@code MOTION_BLOCKING_NO_LEAVES}) whether or not the player could see it, which is knowledge of the hidden world. Both
 * now take a far top only through {@code WorldKnowledge.Looks}: a sight line from the eyes must reach it, over loaded
 * ground. The walks are asked directly ({@code FactionsPlayer.destination}, the campaign's explore), so nothing else
 * moves the player; it has no goal. The cost per far column asked is logged.
 */
public final class NativeSeenColumnsGameTests implements FabricGameTest {
    /** The yard's wall, out from the player's feet each way, and its height. */
    private static final int WALL = 6, WALL_HIGH = 10;
    /** The leg's tower: its top this far over the player's feet, nothing to stand on within eight of its own height. */
    private static final int LEG_TOP = 12;
    /** The explore towers: their tops within eight of its height, so that before F-35m their tops were the walk's end. */
    private static final int EXPLORE_TOP = 6;
    /** How far over its feet the fixture's ground is cleared: the towers' columns well over their tops. */
    private static final int CLEAR_TOP = 32;

    /** The towers' tops behind the wall, out of its sight: the walks go at its own height. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "seenColumnsWalled", tickLimit = 400)
    public void aTopBehindAWallItCannotSeeSetsNoWalksHeight(TestContext context) {
        run(context, true);
    }

    /** No wall: it sees the towers' tops, and its walks go to them as before. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "seenColumnsOpen", tickLimit = 400)
    public void aTopInItsSightIsWhereItsWalksGo(TestContext context) {
        run(context, false);
    }

    /**
     * The night's rule for a walk out under the sky (F-25c4cd), sealed in a cell in the stone where it sees nothing: a walk
     * to the ground's top is out under the sky, and one to a cave below the surface is not, as before. A walk to a pocket
     * in a hill beside it, at the height of the surface over its own feet, now counts as out under the sky: it cannot see
     * the hill's top, and goes by its own column's (F-35m's A7, the lead's ruling); before, the hidden top said it was not.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "seenColumnsNight", tickLimit = 400)
    public void sealedInTheNightsRuleUsesNoTopItCannotSee(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 380);
        BlockPos[] o = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos up = player.getBlockPos().up(30);
                    o[0] = new BlockPos((up.getX() & ~15) + 8, up.getY(), (up.getZ() & ~15) + 8);
                    sealed(context, o[0]);
                    player.refreshPositionAndAngles(o[0].getX() + 0.5, o[0].getY(), o[0].getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o[0]), "Sealed in its cell: " + f.player().getBlockPos().toShortString()))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos surface = o[0].add(10, SEALED_TOP + 1, 0), cave = o[0].add(10, -4, 0), hill = o[0].add(-10, SEALED_TOP, 0);
                    boolean out = PortalRunCampaign.outUnderTheSkyForTest(player, surface);
                    boolean down = PortalRunCampaign.outUnderTheSkyForTest(player, cave);
                    boolean pocket = PortalRunCampaign.outUnderTheSkyForTest(player, hill);
                    KindredMod.LOGGER.info("Seen-columns fixture (sealed): out under the sky - the surface {}, the cave below {}, the hill's pocket {}", out, down, pocket);
                    context.assertTrue(out, "A walk to the ground's top is out under the sky");
                    context.assertTrue(!down, "A walk to a cave below its surface is not");
                    context.assertTrue(pocket, "A walk to the hill's pocket at its own surface's height is, its top unseen");
                })
                .completeIfSuccessful();
    }

    /** The stone round its cell: its top this far over its feet. */
    private static final int SEALED_TOP = 4;

    /**
     * A block of stone twenty-five across from ten below its feet to {@value #SEALED_TOP} over them, its cell (feet and head)
     * hollowed at the middle and nothing else open to it; a cave two high ten east and four down; and west a hill to twelve
     * over its feet, with a pocket two high in it ten west at the height of the stone's top.
     */
    private static void sealed(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-14, -12, -14), o.add(14, 14, 14));
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            int top = x <= -6 ? 12 : SEALED_TOP;
            for (int y = -10; y <= 13; y++) world.setBlockState(o.add(x, y, z), (y <= top ? Blocks.STONE : Blocks.AIR).getDefaultState());
        }
        for (int y = 0; y <= 1; y++) {
            world.setBlockState(o.up(y), Blocks.AIR.getDefaultState());
            world.setBlockState(o.add(10, -4 + y, 0), Blocks.AIR.getDefaultState());
            world.setBlockState(o.add(-10, SEALED_TOP + y, 0), Blocks.AIR.getDefaultState());
        }
    }

    private static void run(TestContext context, boolean walled) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 380);
        BlockPos[] o = new BlockPos[1];
        BlockPos[] leg = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    BlockPos up = player.getBlockPos().up(30);
                    o[0] = new BlockPos((up.getX() & ~15) + 8, up.getY(), (up.getZ() & ~15) + 8);
                    leg[0] = yard(context, o[0], walled);
                    player.refreshPositionAndAngles(o[0].getX() + 0.5, o[0].getY(), o[0].getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o[0]), "On the yard's floor: " + f.player().getBlockPos().toShortString()))
                .createAndAdd(() -> {
                    var player = f.player();
                    int feet = player.getBlockY();
                    long calls = WorldKnowledge.Looks.calls, nanos = WorldKnowledge.Looks.nanos, rays = WorldKnowledge.Looks.raysCast;
                    BlockPos to = FactionsPlayer.destination(scoutEast(o[0]), player);
                    var explore = f.runtime.campaignForTest(f.id).exploreTargetForTest(player);
                    calls = WorldKnowledge.Looks.calls - calls;
                    rays = WorldKnowledge.Looks.raysCast - rays;
                    nanos = WorldKnowledge.Looks.nanos - nanos;
                    KindredMod.LOGGER.info("Seen-columns fixture ({}): leg's end {} (its feet at {}, the tower's top {}); explore {}; {} far columns asked, {} sight lines, {} us each",
                            walled ? "walled" : "open", to.toShortString(), feet, feet + LEG_TOP, explore.map(BlockPos::toShortString).orElse("none"),
                            calls, rays, calls == 0 ? 0 : nanos / calls / 1000.0);
                    // The cost warmed: two hundred looks at the leg tower's top, each a decision of its own.
                    long warm = System.nanoTime();
                    for (int i = 0; i < 200; i++) WorldKnowledge.looks(player, 1).ground(leg[0].getX(), leg[0].getZ());
                    KindredMod.LOGGER.info("Seen-columns fixture ({}): a far column's top asked 200 times, {} us each warmed", walled ? "walled" : "open",
                            (System.nanoTime() - warm) / 200 / 1000.0);
                    context.assertTrue(to.getX() == leg[0].getX() && to.getZ() == leg[0].getZ(), "The leg's end on the tower's column: " + to.toShortString());
                    context.assertTrue(explore.isPresent(), "An explore walk");
                    if (walled) {
                        context.assertTrue(to.getY() == feet, "The leg's end at its own height, the tower's top out of sight: " + to.toShortString());
                        context.assertTrue(explore.get().getY() == feet, "The explore walk at its own height, the tower's top out of sight: " + explore.get().toShortString());
                    } else {
                        context.assertTrue(to.getY() == feet + LEG_TOP, "The leg's end on the tower's top it sees: " + to.toShortString());
                        context.assertTrue(explore.get().getY() == feet + EXPLORE_TOP, "The explore walk to the tower's top it sees: " + explore.get().toShortString()
                                + " / " + inTheWay(player, explore.get(), feet + EXPLORE_TOP - 1));
                    }
                })
                .completeIfSuccessful();
    }

    /**
     * For a failure's message: what a sight line from its eyes to the top of the tower under {@code walk} meets first, and
     * the column's top by the heightmap, both from the yard's floor.
     */
    private static String inTheWay(net.minecraft.server.network.ServerPlayerEntity player, BlockPos walk, int top) {
        var world = player.getServerWorld();
        var hit = world.raycast(new net.minecraft.world.RaycastContext(player.getEyePos(), new Vec3d(walk.getX() + 0.5, top + 0.999, walk.getZ() + 0.5),
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
        int column = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, walk.getX(), walk.getZ()) - 1;
        BlockPos feet = player.getBlockPos();
        return "its sight line to the tower's top meets " + (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS ? "nothing"
                : hit.getBlockPos().subtract(feet).toShortString() + " " + world.getBlockState(hit.getBlockPos())) + "; the column's top "
                + (column - feet.getY()) + " over its feet, " + world.getBlockState(new BlockPos(walk.getX(), column, walk.getZ()));
    }

    /** A scout of the chunk two hundred blocks east: its first leg ends forty-eight east, on the leg tower's column. */
    private static FactionPlay.Choice scoutEast(BlockPos o) {
        return new FactionPlay.Choice(FactionPlay.Kind.SCOUT, Territory.Chunk.at(Territory.OVERWORLD, o.getX() + 200, o.getZ()).key(), 0, "A fixture's scout");
    }

    /**
     * The yard: a floor thirteen across at {@code o}'s feet less one, walled {@value #WALL} out and {@value #WALL_HIGH} high
     * when {@code walled}; four explore towers twenty-eight out each way, their tops {@value #EXPLORE_TOP} over its feet; and
     * the leg's tower forty-eight east, from nine below its feet to {@value #LEG_TOP} over, so that nothing within eight of
     * its height is ground to stand on. All the ground it keeps, from its feet to {@value #CLEAR_TOP} over them, is open
     * air before these are laid. The leg tower's column, returned. Each tower is one column: its top block, over its eyes,
     * is seen from below by the side it shows them (the top of a broad tower's middle is hidden by its own edge).
     */
    private static BlockPos yard(TestContext context, BlockPos o, boolean walled) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-32, -12, -32), o.add(52, CLEAR_TOP, 32));
        // Open air first from its feet up: the sight lines from the yard to the towers, and the towers' columns over
        // them, are the fixture's own, whatever another test on the same ground left there (#275's CI: an explore look's
        // sight line north that reached no tower top, at a place where the test passed nowhere else). Not below its
        // feet, where a neighbour's own fixture may stand (a corridor twenty over the ground).
        for (int x = -32; x <= 52; x++) for (int z = -32; z <= 32; z++) for (int y = 0; y <= CLEAR_TOP; y++) {
            world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        for (int x = -WALL; x <= WALL; x++) for (int z = -WALL; z <= WALL; z++) {
            world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
            boolean edge = Math.abs(x) == WALL || Math.abs(z) == WALL;
            for (int y = 0; y < WALL_HIGH; y++) world.setBlockState(o.add(x, y, z), (walled && edge ? Blocks.STONE : Blocks.AIR).getDefaultState());
        }
        for (var out : new int[][] {{28, 0}, {-28, 0}, {0, 28}, {0, -28}}) {
            for (int y = -3; y < EXPLORE_TOP; y++) world.setBlockState(o.add(out[0], y, out[1]), Blocks.STONE.getDefaultState());
        }
        // Where the scout's leg ends: the walk's destination, forty-eight blocks toward the chunk, clamped inside it as
        // FactionsPlayer clamps it; its z the player's, the chunk's row being the player's.
        BlockPos leg = o.add(FactionsPlayer.LEG, 0, 0);
        for (int y = -9; y < LEG_TOP; y++) world.setBlockState(leg.up(y), Blocks.STONE.getDefaultState());
        return leg;
    }
}
