package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A walk to a drop in a pocket of its own pit that no body can get into: the route search spends all of its positions
 * before it says so (F-22c74, staged for MOVEMENT's navigator).
 */
public final class NativePitPocketGameTests implements FabricGameTest {
    /**
     * As in NativeBaseGameTests' stone-tools cabin, when it fails (CI, 12 November: #259, #265 twice, #268 twice): a
     * player two down in the pit it dug for stone, a stair cut up out of it to the open ground, the cell beside its feet
     * mined with stone still over it (a pocket one high), and the cell under that mined too, its cobblestone lying there.
     * The lower cell is a stand, two high with stone under it, but no body gets into it: the one way in is through the
     * pocket above it, which is one high. Each walk to pick up a drop there was "No walkable route", most of them after
     * the search had expanded all 6,000 of its positions over the open ground above the pit ("expanded 6000, standing
     * true; the goal standable true, on stone, at air"). Staged: the pit, the stair, the pocket and the ground, and the
     * walk's own search, as `native_goto` makes it for "Pick up" (range 0.6, the default 6,000 positions, radius 96).
     * Fails while the search spends all of its positions on a goal it cannot get into; passes once it is refused sooner.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocket", tickLimit = 200)
    public void aWalkIntoAPocketOfItsPitNoBodyCanEnterIsRefusedWithoutSpendingTheWholeSearch(TestContext context) {
        BlockPos p = stage(context, false);
        BlockPos target = p.add(1, -1, 0);
        var found = search(context, p, target);
        KindredMod.LOGGER.info("Pit pocket, its head room stone: complete {}, expanded {}, ends at {}", found.complete(),
                found.expanded(), found.end() == null ? null : found.end().subtract(p).toShortString());
        context.assertTrue(!found.complete(), "No body gets into the cell under a pocket one high, yet the search found a way: " + found.nodes());
        context.assertTrue(found.expanded() < NativeNavigator.DEFAULT_MAX_NODES, "The search spent all " + found.expanded()
                + " of its positions over the open ground before refusing a walk into a pocket one high beside it");
        FixtureGround.restore(context);
        context.complete();
    }

    /**
     * The control: the same pit with the pocket's head room mined too, so that it is two high and the cell under it a
     * stand it steps down into. The search finds it at once: the refusal above comes from the head room alone.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "pitPocketOpen", tickLimit = 200)
    public void theSamePocketWithHeadRoomIsFoundAtOnce(TestContext context) {
        BlockPos p = stage(context, true);
        BlockPos target = p.add(1, -1, 0);
        var found = search(context, p, target);
        KindredMod.LOGGER.info("Pit pocket, its head room open: complete {}, expanded {}", found.complete(), found.expanded());
        context.assertTrue(found.complete() && target.equals(found.end()), "No way found into the pocket with head room: " + found.nodes());
        context.assertTrue(found.expanded() < 50, "The way into the pocket with head room took " + found.expanded() + " positions");
        FixtureGround.restore(context);
        context.complete();
    }

    private static NativeNavigator.Path search(TestContext context, BlockPos from, BlockPos target) {
        var navigator = new NativeNavigator(context.getWorld()).throughDoors();
        return navigator.find(from, target, feet -> Math.sqrt(feet.getSquaredDistance(target)) <= 0.61,
                NativeNavigator.DEFAULT_MAX_NODES, 96);
    }

    /**
     * Stone from two under the pit's floor to the open ground's top, 81 by 81 (more stands than the search's 6,000), air
     * above. The pit: its floor's cell and the one over it ({@code p}, two down from the ground), a stair cut west up to
     * the ground, the pocket east of its feet ({@code p} + 1 east) and the cell under the pocket. With
     * {@code headRoom}, the cell over the pocket is mined too.
     */
    private static BlockPos stage(TestContext context, boolean headRoom) {
        var world = context.getWorld();
        BlockPos p = context.getAbsolutePos(new BlockPos(60, 5, 60)).toImmutable();
        FixtureGround.keep(context, p.add(-40, -2, -40), p.add(40, 6, 40));
        for (BlockPos c : BlockPos.iterate(p.add(-40, -2, -40), p.add(40, 6, 40))) {
            world.setBlockState(c, (c.getY() <= p.getY() + 1 ? Blocks.STONE : Blocks.AIR).getDefaultState(), 2);
        }
        for (BlockPos c : new BlockPos[] {p, p.up(), p.add(-1, 1, 0), p.add(1, 0, 0), p.add(1, -1, 0)}) {
            world.setBlockState(c, Blocks.AIR.getDefaultState(), 2);
        }
        if (headRoom) world.setBlockState(p.add(1, 1, 0), Blocks.AIR.getDefaultState(), 2);
        return p;
    }
}
