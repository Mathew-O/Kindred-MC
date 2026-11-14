package dev.kindred.fabric;

import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest of where an AI player sites its first base (MOVEMENT, F-35m1). Standing on a lookout over
 * rough ground, it has flat ground in sight across a pool to the east, and nearer flat ground to the west that a high wall
 * hides from it. It sites its cabin on the ground it can see, not on the ground behind the wall that it has never looked
 * at. Before, siting read the server's heightmap and chose the hidden ground, the nearer.
 *
 * Technical: staged. Its home on the lookout is the base's anchor; the rough ground is two-high posts every third block,
 * which no site fits among. Passes on two counts within {@value #BUDGET} ticks of bases being allowed:
 * <ol>
 *   <li>its first base sited, its cabin's middle on the flat ground in sight (east);</li>
 *   <li>never on the hidden flat ground (west).</li>
 * </ol>
 */
public final class NativeSeenSiteGameTests implements FabricGameTest {
    static final int BUDGET = 3000;
    /** The hidden flat ground, west of the wall, x from {@value #HIDDEN_X0} to {@value #HIDDEN_X1}. */
    static final int HIDDEN_X0 = -26, HIDDEN_X1 = -12, WALL_X = -10;
    /** The flat ground in sight, east of the pool, x from {@value #SEEN_X0} to {@value #SEEN_X1}. */
    static final int SEEN_X0 = 15, SEEN_X1 = 29;
    /** Both flat grounds run z from -{@value #FLAT_Z} to {@value #FLAT_Z}. */
    static final int FLAT_Z = 7;
    /** The lookout's top, its feet there: high enough to see over the posts. */
    static final int LOOKOUT = 6;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "seenSite", tickLimit = BUDGET + 600)
    public void itSitesItsBaseOnGroundItHasSeenNotOnGroundBehindAWall(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 500);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 8, 60));
        int[] watched = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Looker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-34, -3, -34), o.add(34, 14, 34));
                    lay(context, o);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    ItemStack[] held = {
                            new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_PLANKS, 64),
                            new ItemStack(Items.OAK_PLANKS, 34), new ItemStack(Items.OAK_LOG, 12), new ItemStack(Items.OAK_STAIRS, 42),
                            new ItemStack(Items.OAK_SLAB, 7), new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 1),
                            new ItemStack(Items.CRAFTING_TABLE, 2), new ItemStack(Items.CHEST, 2), new ItemStack(Items.TORCH, 4),
                            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + LOOKOUT, o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == o.getY() + LOOKOUT, "On its lookout: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    watched[0]++;
                    var base = f.runtime.currentBase(f.id);
                    if (base.isPresent()) {
                        int x = base.get().x() + 3 - o.getX();
                        context.assertTrue(x < HIDDEN_X0 - 2 || x > HIDDEN_X1 + 2, "2: sited on the ground behind the wall it never saw: its middle at x "
                                + x + " / " + base.get());
                    }
                    context.assertTrue(base.isPresent(), "1: no base sited after " + watched[0] + " ticks: " + f.runtime.baseNote(f.id));
                    int x = base.get().x() + 3 - o.getX(), z = base.get().z() + 3 - o.getZ();
                    context.assertTrue(x >= SEEN_X0 - 2 && x <= SEEN_X1 + 2 && Math.abs(z) <= FLAT_Z + 2, "1: sited off the flat ground in sight: its middle at "
                            + x + ", " + z + " / " + base.get());
                })
                .createAndAdd(() -> {
                    var base = f.runtime.currentBase(f.id).orElseThrow();
                    KindredMod.LOGGER.info("Seen site: its base at {}, {} after {} ticks; {}", base.x() - o.getX(), base.z() - o.getZ(), watched[0],
                            f.runtime.baseNote(f.id));
                    // The evidence's costs: the sweep a tick, the columns kept, and the siting (these fields exist only with F-35m1).
                    KindredMod.LOGGER.info("Seen site cost: sweep {} us a tick ({} sweeps, {} sight lines), siting {} ms a call ({} calls)",
                            String.format(java.util.Locale.ROOT, "%.1f", SeenGround.nanos / 1000.0 / Math.max(1, SeenGround.sweeps)), SeenGround.sweeps,
                            SeenGround.rays, String.format(java.util.Locale.ROOT, "%.2f", PlayerRuntime.siteNanos / 1e6 / Math.max(1, PlayerRuntime.siteCalls)),
                            PlayerRuntime.siteCalls);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Grass round {@code o} with two-high stone posts every third block (no site fits among them); its lookout, a stone
     * tower three across with its top {@value #LOOKOUT} up; a pool from x 3 to 12; flat grass east of it, in sight; a wall
     * twelve high at x {@value #WALL_X}; and flat grass behind it, out of sight.
     */
    private static void lay(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (int x = -34; x <= 34; x++) for (int z = -34; z <= 34; z++) for (int y = -3; y <= 14; y++) {
            boolean hidden = x >= HIDDEN_X0 && x <= HIDDEN_X1 && Math.abs(z) <= FLAT_Z;
            boolean seen = x >= SEEN_X0 && x <= SEEN_X1 && Math.abs(z) <= FLAT_Z;
            boolean pool = x >= 3 && x <= 12 && Math.abs(z) <= FLAT_Z + 2;
            boolean lookout = Math.abs(x) <= 1 && Math.abs(z) <= 1;
            boolean wall = x == WALL_X && Math.abs(z) <= FLAT_Z + 4;
            boolean post = !hidden && !seen && !pool && !lookout && Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0;
            var state = y <= -2 ? Blocks.STONE
                    : y == -1 ? (pool ? Blocks.WATER : Blocks.GRASS_BLOCK)
                    : lookout && y < LOOKOUT ? Blocks.STONE
                    : wall && y <= 11 ? Blocks.STONE
                    : post && y <= 1 ? Blocks.STONE
                    : Blocks.AIR;
            world.setBlockState(o.add(x, y, z), state.getDefaultState());
        }
    }
}
