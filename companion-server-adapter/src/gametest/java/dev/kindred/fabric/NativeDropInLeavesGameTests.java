package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest: a log a player cut that fell into a nook walled by the tree's leaves is fetched, the leaves
 * in the way broken by hand, rather than left lying; a drop walled by what it cannot break by hand is still left be.
 *
 * Technical (F-22c114): in {@code NativePortalCampaignGameTests}' sapling test (#442's CI, run 44972370869 attempt 2),
 * Planter mined its grown oak's trunk and the log fell into the cell beside it diagonally, its collection ending with the
 * drop uncollected; the walk to pick it up was refused ("No walkable route"), the drop set aside, and left lying ("in
 * sight false, blocked true") while it went on to wait for another sapling ("It cut the grown tree: … Wait for the sapling
 * … to grow"). Staged: on open stone, a log of its own (its own break's drop, as the body knows them) lying in a cell
 * walled round, feet and head, and roofed: by persistent oak leaves, or by obsidian for the control. Its goal on, wood
 * its stage.
 */
public final class NativeDropInLeavesGameTests implements FabricGameTest {
    /** Walled by leaves: the leaves in the way broken by hand, and the log picked up. */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "dropInLeaves", tickLimit = 1000)
    public void itsOwnLogWalledInByLeavesIsFetchedTheLeavesBrokenByHand(TestContext context) {
        scene(context, Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true), true, true);
    }

    /**
     * The same nook of leaves in a faction world's safe zone, where nobody breaks anything (M-06d): the leaves are broken
     * only under the ordinary break's rules ({@code RoamingEdits.breakDenial}, its territory check among them), so here
     * none is broken, and the log is left be as before.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "dropInLeavesSafeZone", tickLimit = 1000)
    public void itsOwnLogWalledInByLeavesIsLeftBeInASafeZone(TestContext context) {
        scene(context, Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true), false, false);
    }

    /** The control: walled by obsidian, which it cannot break by hand: left be, nothing of the wall broken. */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "dropInObsidian", tickLimit = 1000)
    public void itsOwnLogWalledInByWhatItCannotBreakByHandIsLeftBe(TestContext context) {
        scene(context, Blocks.OBSIDIAN.getDefaultState(), false, true);
    }

    private static void scene(TestContext context, BlockState wall, boolean fetched, boolean edits) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 950);
        BlockPos[] cell = new BlockPos[1];
        long[] since = {-1};
        java.util.List<BlockPos> walls = new java.util.ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Leafy"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = context.getAbsolutePos(new BlockPos(60, 2, 60)).toImmutable();
                    FixtureGround.keep(context, p.add(-12, -2, -12), p.add(12, 8, 12));
                    for (BlockPos c : BlockPos.iterate(p.add(-12, -2, -12), p.add(12, 8, 12))) {
                        world.setBlockState(c, (c.getY() < p.getY() ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The nook three east: walled round at its feet and head, and roofed.
                    BlockPos d = cell[0] = p.add(3, 0, 0);
                    for (int y = 0; y <= 1; y++) {
                        for (BlockPos side : new BlockPos[] {d.east(), d.west(), d.north(), d.south()}) walls.add(side.up(y));
                    }
                    walls.add(d.up(2));
                    for (BlockPos w : walls) world.setBlockState(w, wall);
                    if (!edits) {
                        // The nook and its walls in a safe zone of a faction world: no edit there by anybody.
                        f.runtime.factionsWorldForTest(dev.kindred.core.factions.FactionSettings.DEFAULTS);
                        var chunks = new java.util.TreeSet<String>();
                        for (BlockPos w : walls) chunks.add(dev.kindred.core.factions.SafeZones.chunkAt("minecraft:overworld", w.getX(), w.getZ()));
                        f.runtime.record(dev.kindred.core.factions.SafeZones.zone("fixture", chunks, null, world.getServer().getTicks(), System.currentTimeMillis()));
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 8));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                    // Its own log, lying in the nook, as its break's drop: the body knows where what it broke fell.
                    var log = new ItemEntity(world, d.getX() + 0.5, d.getY(), d.getZ() + 0.5, new ItemStack(Items.OAK_LOG));
                    log.setVelocity(Vec3d.ZERO);
                    log.setToDefaultPickupDelay();
                    world.spawnEntity(log);
                    f.runtime.nativeBodyForTest(f.id).knowOwnDrop(log.getUuid());
                    since[0] = world.getServer().getTicks();
                })
                .createAndAdd(() -> context.assertTrue(edits || f.runtime.recordWritten(), "The safe zone written"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int held = f.player().getInventory().count(Items.OAK_LOG);
                    long standing = walls.stream().filter(w -> world.getBlockState(w).isOf(wall.getBlock())).count();
                    String state = "held " + held + ", the wall " + standing + " of " + walls.size() + " standing, "
                            + (world.getServer().getTicks() - since[0]) + " ticks; " + f.runtime.nativeLastResult(f.id);
                    if (fetched) {
                        context.assertTrue(held >= 1, "Its log fetched from the nook: " + state);
                        KindredMod.LOGGER.info("Drop in leaves: fetched; {}", state);
                    } else {
                        context.assertTrue(world.getServer().getTicks() - since[0] >= 600, "Six hundred ticks on");
                        context.assertTrue(held == 0 && standing == walls.size(), "Left be, its wall standing: " + state);
                        KindredMod.LOGGER.info("Drop in {}{}: left be; {}", wall.getBlock().getName().getString(), edits ? "" : ", a safe zone", state);
                    }
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
