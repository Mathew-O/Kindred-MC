package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a swimmer far out in a wide pool, with no dry ground it can reach, does not search the whole pool for
 * dry ground on every plan; it asks again from where it is now and then (M-06k9). Dry ground that comes within its reach
 * is still found: at once once it moves, within {@value PortalRunCampaign#DRY_MISS_TICKS} ticks where it stands.
 *
 * Technical: M-06k's shipped-configuration read (2026-11-22): over 200 ms the way out of the water's searches for dry
 * ground (`PortalRunCampaign.surface` → `nearestReachableDryStand`, 400 and twice 4,000 nodes) were 67.7% of the band,
 * players going "up for air" hundreds of times from much the same cells, every search finding nothing. STAGED: a pool
 * {@value #R} out each way and four deep, its rim three above the water (out of reach to climb onto), an island beyond
 * reach of its middle at {@value #ISLAND} east; the player held at the pool's middle with its head out, so that every
 * plan weighs its way out of the water. Counted: its plans out of the water and the searches they asked. The two other
 * cases: moved within reach of the island, it makes for it on its next plans; held where it is, dry ground set beside
 * it is found within the bound.
 */
public final class NativeDryMissGameTests implements FabricGameTest {
    /** The pool's reach from its middle each way, past the searches' 16. */
    static final int R = 20;
    /** The island's near edge east of the pool's middle: out of a search's 16 from the middle. */
    static final int ISLAND = 18;
    /** Ticks it is held and counted. */
    static final int HELD = 200;

    /** Held where nothing dry is in reach: searches per plan, logged with plans a second; at most one search in two plans. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "dryMissHeld", tickLimit = 1400)
    public void heldFarOutInAWidePoolItDoesNotSearchForDryGroundOnEveryPlan(TestContext context) {
        run(context, Case.HELD);
    }

    /** Moved within reach of the island, it makes for it: "to dry ground" on its next plans. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "dryMissMoved", tickLimit = 1400)
    public void movedWithinReachOfDryGroundItMakesForItAtOnce(TestContext context) {
        run(context, Case.MOVED);
    }

    /** Held where it is, a stand of stone set beside it: "to dry ground" within the bound. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "dryMissPlaced", tickLimit = 1400)
    public void heldWhereItIsDryGroundSetBesideItIsFoundWithinTheBound(TestContext context) {
        run(context, Case.PLACED);
    }

    enum Case { HELD, MOVED, PLACED }

    private static void run(TestContext context, Case which) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        long[] since = {-1}, changed = {-1};
        int[][] base = new int[1][];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Floater"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // p is the top water cell at the pool's middle; the pool's floor is four below it.
                    BlockPos p = f.player().getBlockPos().up(6);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-R - 1, -5, -R - 1), p.add(R + 1, 4, R + 1));
                    for (int x = -R - 1; x <= R + 1; x++) for (int z = -R - 1; z <= R + 1; z++) for (int y = -5; y <= 4; y++) {
                        boolean pool = Math.abs(x) <= R && Math.abs(z) <= R && y >= -3 && y <= 0;
                        boolean rim = (Math.abs(x) > R || Math.abs(z) > R) && y <= 3;
                        boolean island = x >= ISLAND && x <= ISLAND + 1 && Math.abs(z) <= 1 && y >= -3 && y <= 0;
                        world.setBlockState(p.add(x, y, z), (island || rim || y < -3 ? Blocks.STONE : pool ? Blocks.WATER : Blocks.AIR).getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    hold(f, p);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    hold(f, at[0]);
                    var counts = f.runtime.drySearchForTest(f.id);
                    context.assertTrue(counts[0] >= 1, "Its way out of the water weighed: " + counts[0] + " plans; " + progress(f));
                    base[0] = counts;
                    since[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    // Held where it is, its head out, nothing dry in reach: counted for HELD ticks.
                    hold(f, at[0]);
                    var counts = f.runtime.drySearchForTest(f.id);
                    long held = context.getTick() - since[0];
                    context.assertTrue(held >= HELD, "Held " + HELD + " ticks: " + held);
                    int plans = counts[0] - base[0][0], searches = counts[1] - base[0][1];
                    KindredMod.LOGGER.info("Dry-miss fixture ({}): held {} ticks, {} plans out of the water ({} a second), {} dry-ground searches ({} a plan); {}",
                            which, held, plans, String.format(java.util.Locale.ROOT, "%.1f", plans * 20.0 / held), searches,
                            String.format(java.util.Locale.ROOT, "%.2f", plans == 0 ? 0 : (double) searches / plans), progress(f));
                    context.assertTrue(!progress(f).contains("to dry ground"), "Nothing dry in reach of the pool's middle: " + progress(f));
                    if (which == Case.HELD) {
                        context.assertTrue(plans >= 10, "Its way out weighed on its plans: " + plans);
                        context.assertTrue(2 * searches <= plans, "At most one dry-ground search in two plans: " + searches + " in " + plans);
                    }
                })
                .createAndAdd(() -> {
                    if (which == Case.HELD) return;
                    if (changed[0] < 0) {
                        if (which == Case.MOVED) {
                            hold(f, at[0].add(ISLAND - 3, 0, 0));
                        } else {
                            var world = context.getWorld();
                            for (int dz = -1; dz <= 1; dz++) for (int y = -3; y <= 0; y++) world.setBlockState(at[0].add(2, y, dz), Blocks.STONE.getDefaultState());
                        }
                        changed[0] = context.getTick();
                    }
                    if (which == Case.PLACED) hold(f, at[0]);
                    long after = context.getTick() - changed[0];
                    boolean found = progress(f).contains("to dry ground");
                    if (!found) {
                        long bound = which == Case.MOVED ? 10 : PortalRunCampaign.DRY_MISS_TICKS + 10;
                        context.assertTrue(after <= bound, "Dry ground in reach not made for within " + bound + " ticks: " + after + " ticks on; " + progress(f));
                        throw new net.minecraft.test.GameTestException("Not yet: " + progress(f));
                    }
                    KindredMod.LOGGER.info("Dry-miss fixture ({}): dry ground made for {} ticks after it came in reach; {}", which, after, progress(f));
                })
                .completeIfSuccessful();
    }

    /** At {@code p}'s column, its head out of the water, still. */
    private static void hold(NativePaidSkillsGameTests.Fixture f, BlockPos p) {
        var player = f.player();
        player.refreshPositionAndAngles(p.getX() + 0.5, p.getY() - 0.4, p.getZ() + 0.5, player.getYaw(), player.getPitch());
        player.setVelocity(0, 0, 0);
        player.setAir(player.getMaxAir());
    }

    private static String progress(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
    }
}
