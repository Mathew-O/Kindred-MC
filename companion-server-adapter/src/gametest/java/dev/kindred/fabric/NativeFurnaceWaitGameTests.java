package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: its first iron smelted and made into a shield, the player waits by its furnace for the rest of the
 * iron in it before it goes on for more. It used to walk off to the next ore with the iron still smelting, and sixteen
 * blocks on it no longer counted that iron, and never went back for it.
 *
 * Technical: F-28's decision bug, found by the simulator ({@code companion-sim}): in its caving pack 41 runs of 100
 * ended with iron left in a furnace they had walked away from. Once the shield takes the first ingot, the iron stage
 * asks for more iron ore while the rest is still in the furnace; the campaign's snapshot counts a furnace's contents
 * only within sixteen blocks, so on the way to the ore that iron drops out of the count and out of the plan. Here, in a
 * passage sealed in stone and lit, the player has four raw iron, one coal, a shield's planks and an iron pickaxe (so
 * the iron goes to the shield first and not to a pickaxe), and iron ore lies 24 blocks down the passage. The furnace's
 * contents are read at the moment the player first goes more than sixteen blocks from it: nothing of its iron may be
 * left there.
 */
public final class NativeFurnaceWaitGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, batchId = "furnaceWait", tickLimit = 3600)
    public void itWaitsForTheIronInItsFurnaceBeforeGoingOnForMore(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(10, 0, 10));
        BlockPos start = o.add(4, 2, 8), ore = o.add(28, 2, 8);
        BlockPos[] furnace = new BlockPos[1];
        String[] left = new String[1];
        int[] ironLeft = new int[1];
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Smelter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(0, 1, 2), o.add(32, 6, 14));
                    // Sealed in stone: a room where it starts, a passage east from it, and the ore at the passage's end.
                    for (int x = 0; x <= 32; x++) for (int z = 2; z <= 14; z++) for (int y = 1; y <= 6; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    for (int x = 2; x <= 6; x++) for (int z = 6; z <= 10; z++) for (int y = 2; y <= 3; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 7; x <= 27; x++) for (int y = 2; y <= 3; y++) world.setBlockState(o.add(x, y, 8), Blocks.AIR.getDefaultState());
                    world.setBlockState(ore, Blocks.IRON_ORE.getDefaultState());
                    // Lit from the ceiling, so nothing spawns and no torch is wanted.
                    world.setBlockState(o.add(4, 4, 8), Blocks.GLOWSTONE.getDefaultState());
                    for (int x = 8; x <= 27; x += 4) world.setBlockState(o.add(x, 4, 8), Blocks.GLOWSTONE.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // As though gathered: an iron pickaxe already (so the first iron goes to a shield), spare stone ones
                    // packed, the sticks for them, a shield's planks, four raw iron and one coal to smelt them with.
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 16));
                    inventory.setStack(5, new ItemStack(Items.OAK_PLANKS, 6));
                    inventory.setStack(6, new ItemStack(Items.RAW_IRON, 4));
                    inventory.setStack(7, new ItemStack(Items.COAL));
                    inventory.setStack(8, new ItemStack(Items.FURNACE));
                    inventory.setStack(9, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(10, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(11, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    furnace[0] = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 3, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.FURNACE)).findFirst().map(BlockPos::toImmutable).orElse(null);
                    context.assertTrue(furnace[0] != null, "It placed its own furnace");
                })
                // Watched every tick from here: what is left in the furnace when it first goes more than sixteen blocks from it.
                .createAndAdd(() -> {
                    if (left[0] == null && f.player().getBlockPos().getSquaredDistance(furnace[0]) > 16 * 16) {
                        var world = context.getWorld();
                        if (world.getBlockEntity(furnace[0]) instanceof AbstractFurnaceBlockEntity block) {
                            ironLeft[0] = (block.getStack(0).isOf(Items.RAW_IRON) ? block.getStack(0).getCount() : 0)
                                    + (block.getStack(2).isOf(Items.IRON_INGOT) ? block.getStack(2).getCount() : 0);
                            left[0] = "input " + block.getStack(0) + ", output " + block.getStack(2);
                        } else {
                            left[0] = "the furnace taken along";
                        }
                    }
                    context.assertTrue(left[0] != null, "On its way on, past sixteen blocks from its furnace");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ironLeft[0] == 0, "None of its iron left in the furnace when it went on: " + left[0]);
                    context.assertTrue(ItemMoves.count(inventory, Items.SHIELD) == 1, "Its first iron made into a shield");
                    context.assertTrue(ItemMoves.count(inventory, Items.IRON_INGOT) == 3,
                            "The other three smelted and taken with it: " + ItemMoves.count(inventory, Items.IRON_INGOT));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
