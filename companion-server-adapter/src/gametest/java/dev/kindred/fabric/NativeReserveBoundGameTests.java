package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** The reserve turn's wood search, bounded where there are no trees (F-25c4cr1). */
public final class NativeReserveBoundGameTests implements FabricGameTest {
    /**
     * On the cast route with its last pickaxe, a stone one, no sticks or wood, and no lava seen, the reserve turn goes up
     * for wood; where no tree is anywhere, as on 31102026's open sand (LOOPS's census: players idle on the sand, no tree
     * known), that search would go on while the pickaxe could still be finding lava. Staged: a stone plot, flat and open,
     * nothing on it, daylight held; a stone pickaxe and sword, an iron chestplate and a shield, a water bucket and an empty one, two iron
     * ingots, cobblestone for the mould, bread, a table beside it. It first goes up for wood, and then, within the bound
     * and a margin, goes on to the cast's lava (down toward it, or a branch mine for it at the plot's depth).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "reserveBound", tickLimit = PortalRunCampaign.RESERVE_SEARCH_TICKS + 1600)
    public void withNoTreeAnywhereItsReserveSearchIsSetAsideAndItGoesOnToTheLava(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        long[] started = {-1};
        boolean[] reserveFirst = {false};
        StringBuilder steps = new StringBuilder();
        String[] lastStep = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sandreserve"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-24, -3, -24), p.add(24, 7, 24));
                    for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(p.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 7; y++) world.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(p.add(1, 0, 0), Blocks.CRAFTING_TABLE.getDefaultState());
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 30));
                    inventory.setStack(4, new ItemStack(Items.IRON_INGOT, 2));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(6, new ItemStack(Items.BUCKET)); // The lava's: two buckets, as the cast route wants.
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    world.setTimeOfDay(1000);
                    f.runtime.campaignForTest(f.id).knowledge().scan(player);
                    started[0] = context.getTick();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (!progress.equals(lastStep[0])) {
                        lastStep[0] = progress;
                        steps.append(" @").append(context.getTick() - started[0]).append(" ").append(progress).append(";");
                    }
                    reserveFirst[0] |= progress.contains(dev.kindred.core.survival.PortalRunPlanner.RESERVE_WHY);
                    String tail = steps.length() > 900 ? "..." + steps.substring(steps.length() - 900) : steps.toString();
                    // The cast's own step: down toward its lava, or along a branch mine for it at the plot's depth.
                    context.assertTrue(progress.contains("Cast the portal frame from lava and water"), "On to the cast's lava: " + tail);
                })
                .createAndAdd(() -> {
                    String tail = steps.length() > 900 ? "..." + steps.substring(steps.length() - 900) : steps.toString();
                    // The staging itself: the reserve turn was taken first, so the bound is what ended it.
                    context.assertTrue(reserveFirst[0], "Up for wood first, as staged: " + tail);
                    KindredMod.LOGGER.info("Reserve bound: on to the lava after {} ticks; {}", context.getTick() - started[0], tail);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
