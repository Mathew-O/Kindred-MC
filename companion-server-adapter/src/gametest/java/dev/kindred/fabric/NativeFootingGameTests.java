package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest of a player mining stone on a floor one block thick over a long drop (MOVEMENT, F-35l). Its
 * goal wants cobblestone for a stone pickaxe, and the only stone there is the floor it stands on, thirty blocks up. It
 * does not mine that floor out from round its feet; on a floor four thick at the ground's level it mines it as before.
 *
 * Technical: on #224's CI (job 130849133167) the back-off fixture's player, on its sky island, mined "stone at" the
 * island's one-thick floor round and under where it stood, stepped into a hole it had made and "fell from a high place"
 * (thirty blocks). Staged goalless of trees, so the floor is the only stone: a wooden pickaxe, sticks, a crafting table and
 * bread in hand, the goal a Nether portal (its next step a stone pickaxe). Each fixture alone in its batch, so that no
 * other plot's stone is near.
 */
public final class NativeFootingGameTests implements FabricGameTest {
    /** How far up the platform stands over the ground. */
    private static final int UP = 30;

    /**
     * A floor one block thick over thirty of air: no block of it is mined out from round its feet, and the player does
     * not fall. (Before, it mined a ring of the floor round the block it stood on, and in some runs stepped into it.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "footingThin", tickLimit = 2600)
    public void aFloorOneBlockThickOverALongDropIsNotMinedOutFromUnderIt(TestContext context) {
        run(context, 1);
    }

    /**
     * The control: the floor four thick at the ground's own level, no drop round it: it mines stone from it as before. (A
     * column solid to the ground thirty up was not a control: going on to iron, its branch mine came out through the
     * column's side and it fell off the cliff, 2 of 3.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "footingGround", tickLimit = 2600)
    public void aFloorAtTheGroundsLevelIsMinedForStoneAsBefore(TestContext context) {
        run(context, 4);
    }

    private static void run(TestContext context, int thick) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2500);
        var server = context.getWorld().getServer();
        BlockPos[] platform = new BlockPos[1];
        long[] start = {-1};
        int[] lowest = {Integer.MAX_VALUE};
        boolean[] died = {false};
        Object[] body = new Object[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Quarry"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    platform[0] = staged(context, f, thick);
                    start[0] = server.getTicks();
                    body[0] = f.player();
                    context.runAtEveryTick(() -> {
                        var player = f.player();
                        // A death is seen as the body dead, or replaced by a respawned one.
                        if (player == null || !player.isAlive() || player != body[0]) died[0] = true;
                        else lowest[0] = Math.min(lowest[0], player.getBlockY());
                    });
                })
                .createAndAdd(() -> context.assertTrue(server.getTicks() - start[0] >= 2000, "Two thousand ticks on"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    int holes = 0;
                    for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) if (world.getBlockState(platform[0].add(x, -1, z)).isAir()) holes++;
                    int cobble = ItemMoves.count(player.getInventory(), Items.COBBLESTONE) + ItemMoves.count(player.getInventory(), Items.STONE_PICKAXE);
                    String state = "at " + player.getBlockPos().toShortString() + ", lowest " + lowest[0] + " (the platform's top at "
                            + platform[0].getY() + "), " + holes + " of the floor's 81 blocks mined out, " + cobble + " cobblestone or stone pickaxe; " + f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    KindredMod.LOGGER.info("Footing fixture ({} thick): {}", thick, state);
                    context.assertTrue(!died[0] && player.isAlive(), "Alive, never died: " + state);
                    if (thick == 1) {
                        context.assertTrue(holes == 0, "No block of the floor over the drop mined out: " + state);
                        context.assertTrue(lowest[0] >= platform[0].getY(), "Never below its floor: " + state);
                    } else {
                        context.assertTrue(cobble >= 1, "Stone mined from the floor solid to the ground: " + state);
                    }
                })
                .completeIfSuccessful();
    }

    /**
     * The platform, nine across, {@code thick} blocks of stone: one thick with its top {@value #UP} over the ground, or set
     * into the ground at its own level; the player on its middle.
     */
    private static BlockPos staged(TestContext context, NativePaidSkillsGameTests.Fixture f, int thick) {
        var world = context.getWorld();
        var player = f.player();
        BlockPos o = player.getBlockPos().up(thick == 1 ? UP : 0).toImmutable();
        FixtureGround.keep(context, o.add(-5, -thick - 1, -5), o.add(5, 6, 5));
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            for (int y = 1; y <= thick; y++) world.setBlockState(o.add(x, -y, z), Blocks.STONE.getDefaultState());
            for (int y = 0; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        world.setTimeOfDay(1000);
        player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
        player.setVelocity(Vec3d.ZERO);
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.STICK, 8));
        inventory.setStack(2, new ItemStack(Items.CRAFTING_TABLE));
        inventory.setStack(3, new ItemStack(Items.BREAD, 5));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
        context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
        return o;
    }
}
