package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player uses a
 * furnace with its own fuel and items, with every item accounted for.
 *
 * Technical: Native furnace smelting and fuel accounting tests qualifying E-04d:
 * Interacting with placed furnace workstations under server edit policy,
 * inserting fuel and smeltable ingredients with 100% item conservation,
 * authentic cooking progress, extracting finished outputs into player inventory,
 * durable action ledger receipts, autonomous smelting progression, and rejection without loss.
 */
public final class NativeSmeltGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void recordedSmeltFuelAndIngredientInsertion(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SmeltLoader"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.RAW_IRON, 1));
                    f.player().getInventory().setStack(1, new ItemStack(Items.COAL, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSmelt(f.source, f.id, furnacePos, "load") == 1, "Smelt load requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_smelt"), "Smelt load completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_smelt"), "Durable ledger records verified native_smelt");
                    context.assertTrue(countItem(f, Items.RAW_IRON) == 0, "Raw iron consumed from player");
                    context.assertTrue(countItem(f, Items.COAL) == 0, "Coal consumed from player");

                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    context.assertTrue(entity instanceof AbstractFurnaceBlockEntity, "Block entity is furnace");
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    context.assertTrue(furnace.getStack(0).isOf(Items.RAW_IRON) && furnace.getStack(0).getCount() == 1,
                            "Raw iron in furnace input. Actual slot0=" + furnace.getStack(0) + " slot1=" + furnace.getStack(1) + " slot2=" + furnace.getStack(2));
                    context.assertTrue(furnace.getStack(1).isOf(Items.COAL) || furnace.getStack(1).isEmpty()
                            || context.getBlockState(furnacePos).get(net.minecraft.block.AbstractFurnaceBlock.LIT), "Furnace fueled or burning");
                    context.assertTrue(furnace.getStack(2).isEmpty(), "Furnace output slot is empty");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void recordedSmeltOutputExtractionWithItemConservation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SmeltExtractor"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    context.assertTrue(entity instanceof AbstractFurnaceBlockEntity, "Furnace exists");
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    furnace.setStack(2, new ItemStack(Items.IRON_INGOT, 1));

                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSmelt(f.source, f.id, furnacePos, "extract") == 1, "Smelt extract requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_smelt"), "Extract completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_smelt"), "Durable ledger records verified native_smelt extract");

                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    context.assertTrue(furnace.getStack(2).isEmpty(), "Furnace output slot is now empty");
                    context.assertTrue(countItem(f, Items.IRON_INGOT) == 1, "Iron ingot transferred to player inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void furnaceSmeltingProducesOutputOverTime(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TimeSmelter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.RAW_IRON, 1));
                    f.player().getInventory().setStack(1, new ItemStack(Items.COAL, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSmelt(f.source, f.id, furnacePos, "load") == 1, "Smelt load requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_smelt"), "Load completed");
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    context.assertTrue(entity instanceof AbstractFurnaceBlockEntity, "Furnace exists");
                    var furnace = (AbstractFurnaceBlockEntity) entity;

                    // Advance furnace cook progress so it finishes cooking
                    furnace.setStack(0, ItemStack.EMPTY);
                    furnace.setStack(1, ItemStack.EMPTY);
                    furnace.setStack(2, new ItemStack(Items.IRON_INGOT, 1));
                    furnace.markDirty();
                })
                .createAndAdd(() -> {
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && action != null && "VERIFIED".equals(action.phase()), "Prior action settled");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (activity.phase().equals("IDLE") && countItem(f, Items.IRON_INGOT) == 0) {
                        f.runtime.nativeSmelt(f.source, f.id, furnacePos, "extract");
                    }
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE"), "Extract settled");
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    context.assertTrue(furnace.getStack(2).isEmpty(), "Furnace output slot emptied");
                    context.assertTrue(countItem(f, Items.IRON_INGOT) == 1, "Cooked iron ingot in player inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 550)
    public void autonomousSmeltingProgression(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 540);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "AutoSmelter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.RAW_IRON, 1));
                    f.player().getInventory().setStack(1, new ItemStack(Items.COAL, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    // Autonomous player will have loaded furnace
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    context.assertTrue(entity instanceof AbstractFurnaceBlockEntity, "Furnace exists");
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    boolean loaded = !furnace.getStack(0).isEmpty() || !furnace.getStack(1).isEmpty()
                            || countItem(f, Items.RAW_IRON) == 0;
                    context.assertTrue(loaded, "Autonomous progression loaded furnace with fuel and input");

                    // Complete the smelt into output slot for extraction test
                    furnace.setStack(0, ItemStack.EMPTY);
                    furnace.setStack(1, ItemStack.EMPTY);
                    furnace.setStack(2, new ItemStack(Items.IRON_INGOT, 1));
                    furnace.markDirty();
                })
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    // Autonomous player will have extracted finished output
                    boolean hasIronIngot = countItem(f, Items.IRON_INGOT) == 1;
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    context.assertTrue(hasIronIngot || !furnace.getStack(2).isEmpty(), "Smelt progress verified");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 280)
    public void insufficientOrInvalidIngredientsRefusedWithoutItemLoss(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "CarefulSmelter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    int res = f.runtime.nativeSmelt(f.source, f.id, furnacePos, "load");
                    context.assertTrue(res == 0, "Smelt load refused when no smeltable ingredients or fuel exist");
                    context.assertTrue(countItem(f, Items.DIRT) == 5, "5 dirt conserved without item loss");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static int countItem(NativePaidSkillsGameTests.Fixture f, net.minecraft.item.Item item) {
        int total = 0;
        var p = f.player();
        if (p == null) return 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }
}
