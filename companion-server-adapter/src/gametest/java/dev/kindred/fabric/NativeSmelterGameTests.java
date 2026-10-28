package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.construction.Machines;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * A functional build with redstone parts (M-01d): a player holding fifteen iron, forty-eight planks, eight cobblestone
 * and a crafting table makes the smelter's hoppers, chests and furnace and builds it, every part in its state, each
 * hopper facing what it feeds. Then it works as built: raw iron put in its input chest and coal in its fuel chest run
 * down through the furnace, and the iron comes out in its output chest.
 */
public final class NativeSmelterGameTests implements FabricGameTest {
    private static int count(ChestBlockEntity chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.size(); i++) if (chest.getStack(i).isOf(item)) total += chest.getStack(i).getCount();
        return total;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "smelter", tickLimit = 9000)
    public void itMakesItsSmeltersPartsBuildsItAndTheSmelterWorks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8800);
        var smelter = Machines.smelter();
        BlockPos[] origin = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -6; x <= 12; x++) for (int z = -6; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 12; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // As though mined, smelted and cut: what its hoppers, chests and furnace are made of.
                    inventory.setStack(0, new ItemStack(Items.IRON_INGOT, 15));
                    inventory.setStack(1, new ItemStack(Items.OAK_PLANKS, 48));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 8));
                    inventory.setStack(3, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(4, new ItemStack(Items.DIRT, 32));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 4.5, o.getY() + 2, o.getZ() + 8.5, 180, 0);
                    origin[0] = o.add(4, 2, 4);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, smelter, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    NativeBaseGameTests.exact(context, smelter, origin[0]);
                    var inventory = f.player().getInventory();
                    context.assertTrue(inventory.count(Items.IRON_INGOT) == 0 && inventory.count(Items.HOPPER) == 0 && inventory.count(Items.CHEST) == 0
                            && inventory.count(Items.FURNACE) == 0, "Its parts made of what it held, all built in: " + inventory.main);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                    // As its player uses it: raw iron in at the top, coal at the side.
                    var world = context.getWorld();
                    var input = (ChestBlockEntity) world.getBlockEntity(BuildRunner.blockPos(Machines.INPUT.add(BuildRunner.pos(origin[0]))));
                    var fuel = (ChestBlockEntity) world.getBlockEntity(BuildRunner.blockPos(Machines.FUEL.add(BuildRunner.pos(origin[0]))));
                    input.setStack(0, new ItemStack(Items.RAW_IRON, 8));
                    fuel.setStack(0, new ItemStack(Items.COAL, 1));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var output = (ChestBlockEntity) world.getBlockEntity(BuildRunner.blockPos(Machines.OUTPUT.add(BuildRunner.pos(origin[0]))));
                    context.assertTrue(count(output, Items.IRON_INGOT) == 8, "Eight iron out at the bottom: " + count(output, Items.IRON_INGOT));
                    KindredMod.LOGGER.info("Smelter fixture: built exact, and eight raw iron came out as eight iron ingots");
                })
                .completeIfSuccessful();
    }
}
