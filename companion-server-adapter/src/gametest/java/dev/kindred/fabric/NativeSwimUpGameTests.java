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
 * In plain terms: a player a little under the surface of a wide pool, short of breath, swims the last bit up and
 * breathes, rather than drowning a hand's breadth from the air.
 *
 * Technical: F-25c4de. The failure corpus's drowning cluster (22 scenes, 20 of them failing 6 of 6 on 084dde5):
 * "Get out of the water: up for air" was a partial walk to the top water cell straight above, which ended at once as
 * COMPLETED with no route (no stand in water), so the player hovered with its eyes under, the goal's own step was given
 * back and stopped "In deep water; up for air first", 10–15 times a second, until it drowned (f29e-on-707-sol-12912:
 * feet at y −50.8 to −50.9 in the cell below the top water cell, air −7 to −17, health 14 to 0.8). STAGED: a pool
 * eleven wide and four deep, open to the air, its edges six blocks off (beyond the three-block reach for climbing
 * ashore from the surface); the player put one block under the top water cell with little air left, a crafting table
 * and the stone kit given as though made, the portal goal set.
 */
public final class NativeSwimUpGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "swimUpStagedPool", tickLimit = 1400)
    public void stagedJustUnderTheSurfaceOfAWidePoolShortOfBreathItSwimsUpAndBreathes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] deaths = {-1};
        long[] since = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Treader"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // p is the top water cell; the pool's floor is four below it.
                    BlockPos p = f.player().getBlockPos().up(6);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-7, -5, -7), p.add(7, 3, 7));
                    for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) for (int y = -5; y <= 3; y++) {
                        boolean pool = Math.abs(x) <= 5 && Math.abs(z) <= 5 && y >= -3 && y <= 0;
                        world.setBlockState(p.add(x, y, z), (y >= 1 ? Blocks.AIR : pool ? Blocks.WATER : Blocks.STONE).getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_AXE));
                    inventory.setStack(2, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(4, new ItemStack(Items.BREAD, 8));
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY() - 1, p.getZ() + 0.5, 0, 0);
                    player.setVelocity(0, 0, 0);
                    player.setAir(0);
                    player.setHealth(14);
                    player.getHungerManager().setFoodLevel(18);
                    deaths[0] = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    since[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int died = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    if (died > deaths[0]) throw new net.minecraft.test.GameTestException("Drowned a block under the surface");
                    if (context.getTick() % 20 == 0) KindredMod.LOGGER.info("Swim up: feet {}, air {}, under {}, health {}; {}", String.format(java.util.Locale.ROOT, "%.2f", player.getY() - at[0].getY()),
                            player.getAir(), player.isSubmergedInWater(), player.getHealth(), f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress());
                    context.assertTrue(player.getAir() >= player.getMaxAir() && player.getHealth() > 0, "Breath back: air " + player.getAir());
                    context.assertTrue(context.getTick() - since[0] >= 600, "Alive a while on");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int died = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    context.assertTrue(died == deaths[0], "Not drowned");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * As above, the player put where the corpus scene's player hovered: STAGED with its feet 0.85 below the top water
     * cell, still, its head under (f29e-on-707-sol-12912 on 084dde5: feet −0.8 to −0.9 below it for 250 ticks while its
     * walks up for air completed 10–15 times a second).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "swimUpStagedHover", tickLimit = 1400)
    public void stagedHoveringJustUnderTheSurfaceAsTheCorpusSceneDidItSwimsUpAndBreathes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] deaths = {-1};
        long[] since = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hoverer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // p is the top water cell; the pool's floor is four below it.
                    BlockPos p = f.player().getBlockPos().up(6);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-7, -5, -7), p.add(7, 3, 7));
                    for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) for (int y = -5; y <= 3; y++) {
                        boolean pool = Math.abs(x) <= 5 && Math.abs(z) <= 5 && y >= -3 && y <= 0;
                        world.setBlockState(p.add(x, y, z), (y >= 1 ? Blocks.AIR : pool ? Blocks.WATER : Blocks.STONE).getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_AXE));
                    inventory.setStack(2, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(4, new ItemStack(Items.BREAD, 8));
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY() - 0.85, p.getZ() + 0.5, 0, 0);
                    player.setVelocity(0, 0, 0);
                    player.setAir(0);
                    player.setHealth(14);
                    player.getHungerManager().setFoodLevel(18);
                    deaths[0] = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    since[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int died = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    if (died > deaths[0]) throw new net.minecraft.test.GameTestException("Drowned a block under the surface");
                    if (context.getTick() % 20 == 0) KindredMod.LOGGER.info("Swim up: feet {}, air {}, under {}, health {}; {}", String.format(java.util.Locale.ROOT, "%.2f", player.getY() - at[0].getY()),
                            player.getAir(), player.isSubmergedInWater(), player.getHealth(), f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress());
                    context.assertTrue(player.getAir() >= player.getMaxAir() && player.getHealth() > 0, "Breath back: air " + player.getAir());
                    context.assertTrue(context.getTick() - since[0] >= 600, "Alive a while on");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int died = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    context.assertTrue(died == deaths[0], "Not drowned");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * With no way out of the water at all, it does not spin (F-25c4de): no air through the water, no dry ground, nothing
     * it can dig. STAGED: a pool five wide and five deep sealed in bedrock on every side and above, the player in its
     * middle with full breath and the portal goal set. It drowns in the end, as anyone would; what is checked is that it is not
     * given step after step only to have each stopped at once (before: 67 stopped "In deep water; up for air first" in
     * 300 ticks).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "swimUpStagedSealed", tickLimit = 1400)
    public void stagedSealedInWaterWithNoWayOutItsWaysOutAreTriedBoundedTimes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var tries = new java.util.concurrent.atomic.AtomicInteger();
        long[] since = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sealed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().up(6);
                    FixtureGround.keep(context, p.add(-4, -4, -4), p.add(4, 4, 4));
                    for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -4; y <= 4; y++) {
                        boolean pool = Math.abs(x) <= 2 && Math.abs(z) <= 2 && Math.abs(y) <= 2;
                        world.setBlockState(p.add(x, y, z), (pool ? Blocks.WATER : Blocks.BEDROCK).getDefaultState());
                    }
                    f.runtime.observeRun(new RunObserver() {
                        @Override public void ended(String player, String skill, String state, String label) {
                            if ("CANCELLED".equals(state)) tries.incrementAndGet();
                        }
                    });
                    var player = f.player();
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    player.setVelocity(0, 0, 0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    since[0] = context.getTick();
                })
                .createAndAdd(() -> context.assertTrue(context.getTick() - since[0] >= 300, "Fifteen seconds in the sealed water"))
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Sealed in water: {} steps stopped in 300 ticks", tries.get());
                    context.assertTrue(tries.get() <= 20, "Not step after step stopped at once: " + tries.get());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
