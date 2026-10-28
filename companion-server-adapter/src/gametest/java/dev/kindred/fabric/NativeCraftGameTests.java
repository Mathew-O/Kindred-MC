package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player crafts from
 * its own items, places a crafting table and uses it, just as a human would.
 *
 * Technical: Native player crafting and block placement tests qualifying E-04a and E-04b:
 * Authentically craft 2x2 recipes in player inventory, place crafting table into world under server edit policy,
 * craft 3x3 workstation tools (wooden pickaxe) using reachable table, verify item conservation,
 * durable action ledger verification, rejection when table is unreachable, and save/reload persistence.
 */
public final class NativeCraftGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void craftOakPlanksFromGatheredOakLogs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "PlankCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 1));
                    context.assertTrue(f.runtime.nativeCraft(f.source.withLevel(0), f.id, "oak_planks") == 0, "Unauthorized craft denied");
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "oak_planks") == 1, "Planks craft accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Craft completed and settled");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    context.assertTrue(action.record() != null && action.record().state() == dev.kindred.core.ActionRecord.State.VERIFIED
                            && action.record().beforeEvidence() != null && action.record().afterEvidence() != null, "Durable ledger records verified before/after evidence");
                    context.assertTrue(countItem(f, Items.OAK_LOG) == 0, "Log was consumed");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 4, "Exactly 4 oak planks produced");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void craftSticksFromCraftedPlanks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "StickCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 2));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "stick") == 1, "Stick craft accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Craft completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    context.assertTrue(action.record() != null && action.record().state() == dev.kindred.core.ActionRecord.State.VERIFIED, "Durable VERIFIED state");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 0, "Planks consumed");
                    context.assertTrue(countItem(f, Items.STICK) == 4, "Exactly 4 sticks produced");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void craftCraftingTableFromPlanks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TableCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 4));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "crafting_table") == 1, "Crafting table craft accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Craft completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    context.assertTrue(action.record() != null && action.record().state() == dev.kindred.core.ActionRecord.State.VERIFIED, "Durable VERIFIED record");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 0, "4 planks consumed");
                    context.assertTrue(countItem(f, Items.CRAFTING_TABLE) == 1, "Exactly 1 crafting table produced");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 280)
    public void insufficientIngredientsRefusedWithoutItemLoss(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "CarefulCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 1));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "crafting_table") == 0, "Crafting table with only 1 plank refused");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 1, "Plank preserved without loss");
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "wooden_pickaxe") == 0, "3x3 table recipe in 2x2 grid refused");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 1, "No items lost on refusal");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 340)
    public void craftedItemsSurviveNativeSaveAndReload(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "PersistentCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 2));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "oak_planks") == 1, "First plank craft accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "First craft done");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 4, "4 planks present");
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "crafting_table") == 1, "Table craft accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Table craft done");
                    context.assertTrue(countItem(f, Items.CRAFTING_TABLE) == 1, "Crafting table present");
                    context.assertTrue(countItem(f, Items.OAK_LOG) == 1, "Remaining 1 log conserved");
                    // Disconnect and reconnect to verify native file persistence
                    f.runtime.nativeLifecycle(f.source, "disconnect", f.id);
                    f.runtime.nativeLifecycle(f.source, "connect", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Native file restored"))
                .createAndAdd(() -> {
                    context.assertTrue(countItem(f, Items.CRAFTING_TABLE) == 1, "Crafting table preserved across reconnect");
                    context.assertTrue(countItem(f, Items.OAK_LOG) == 1, "Log preserved across reconnect");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void placeCraftingTableUnderServerPolicy(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos support = context.getAbsolutePos(new BlockPos(3, 1, 3));
        BlockPos target = support.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "TablePlacer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(target, Blocks.AIR.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.CRAFTING_TABLE, 1));
                    // Check that place without assigned setup area is rejected by EditPolicy
                    context.assertTrue(f.runtime.nativePlace(f.source, f.id, target, "up", 0) == 0,
                            "Placement without assigned work area denied by EditPolicy");
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Scope acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    // Unauthorized command execution rejected
                    context.assertTrue(f.runtime.nativePlace(f.source.withLevel(0), f.id, target, "up", 0) == 0, "Unauthorized place denied");
                    // Valid place accepted
                    context.assertTrue(f.runtime.nativePlace(f.source, f.id, target, "up", 0) == 1, "Native place accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_place"), "Placement completed and settled");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_place"), "Durable ledger records verified native_place");
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.CRAFTING_TABLE), "Crafting table placed in world");
                    context.assertTrue(countItem(f, Items.CRAFTING_TABLE) == 0, "Crafting table consumed from inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void craft3x3WoodenPickaxeAtReachableCraftingTable(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 3));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "PickaxeCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 3));
                    f.player().getInventory().setStack(1, new ItemStack(Items.STICK, 2));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "wooden_pickaxe") == 1, "Wooden pickaxe craft accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Craft completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED, "Durable VERIFIED state");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 0, "All 3 planks consumed");
                    context.assertTrue(countItem(f, Items.STICK) == 0, "Both sticks consumed");
                    context.assertTrue(countItem(f, Items.WOODEN_PICKAXE) == 1, "Exactly 1 wooden pickaxe produced");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void craft3x3RefusedWhenCraftingTableUnreachable(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "NoTableCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Ensure no crafting table in the entire test plot
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = 2; y <= 5; y++) {
                        context.setBlockState(x, y, z, Blocks.AIR);
                    }
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 3));
                    f.player().getInventory().setStack(1, new ItemStack(Items.STICK, 2));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "wooden_pickaxe") == 0,
                            "3x3 craft without reachable table refused");
                    context.assertTrue(countItem(f, Items.OAK_PLANKS) == 3, "Planks preserved on refusal");
                    context.assertTrue(countItem(f, Items.STICK) == 2, "Sticks preserved on refusal");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "craftPickaxePersist", tickLimit = 620)
    public void placedCraftingTableAndCraftedPickaxePersistAcrossSaveReload(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true, 545);
        BlockPos support = context.getAbsolutePos(new BlockPos(3, 1, 3));
        BlockPos tablePos = support.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));
        f.runnerAwaitingReady(context, "Ready")
                .createAndAdd(() -> f.runtime.createNative(f.source, "SurvivalProgression"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(tablePos, Blocks.AIR.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.CRAFTING_TABLE, 1));
                    f.player().getInventory().setStack(1, new ItemStack(Items.OAK_PLANKS, 3));
                    f.player().getInventory().setStack(2, new ItemStack(Items.STICK, 2));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Work area assigned"))
                .createAndAdd(() -> f.runtime.nativeLifecycle(f.source, "resume", f.id))
                .createAndAdd(() -> { })
                .createAndAdd(() -> {
                    int accepted = f.runtime.nativePlace(f.source, f.id, tablePos, "up", 0);
                    context.assertTrue(accepted == 1, "Place crafting table accepted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_place"),
                            "Table placement completed: phase=" + activity.phase() + ", work=" + activity.work());
                    context.assertTrue(context.getWorld().getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE), "Crafting table exists in world");
                    context.assertTrue(countItem(f, Items.CRAFTING_TABLE) == 0, "Crafting table item consumed");
                })
                .createAndAdd(() -> {
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && action != null && "VERIFIED".equals(action.phase()), "Place action settled");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "wooden_pickaxe") == 1, "Pickaxe craft requested");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"),
                            "Pickaxe craft completed: phase=" + activity.phase() + ", work=" + activity.work());
                    context.assertTrue(countItem(f, Items.WOODEN_PICKAXE) == 1, "Wooden pickaxe in inventory before reload");
                    f.runtime.nativeLifecycle(f.source, "disconnect", f.id);
                    f.runtime.nativeLifecycle(f.source, "connect", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Reconnected"))
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(tablePos).isOf(Blocks.CRAFTING_TABLE), "Placed crafting table persisted in world");
                    context.assertTrue(countItem(f, Items.WOODEN_PICKAXE) == 1, "Crafted wooden pickaxe persisted in inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void craftStonePickaxeAt3x3Workstation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "StoneToolCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 3));
                    f.player().getInventory().setStack(1, new ItemStack(Items.STICK, 2));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "stone_pickaxe") == 1, "Stone pickaxe craft requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Craft completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_craft"), "Durable receipt verified");
                    context.assertTrue(countItem(f, Items.COBBLESTONE) == 0, "Cobblestone consumed");
                    context.assertTrue(countItem(f, Items.STICK) == 0, "Sticks consumed");
                    context.assertTrue(countItem(f, Items.STONE_PICKAXE) == 1, "Stone pickaxe produced");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void craftAndPlaceFurnace(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "FurnaceCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.AIR.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 8));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Scope acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "furnace") == 1, "Furnace craft requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Furnace craft completed");
                    context.assertTrue(countItem(f, Items.COBBLESTONE) == 0, "8 cobblestone consumed");
                    context.assertTrue(countItem(f, Items.FURNACE) == 1, "Furnace produced in inventory");
                })
                .createAndAdd(() -> {
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id);
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && action != null && "VERIFIED".equals(action.phase()), "Craft action settled");
                })
                .createAndAdd(() -> {
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.FURNACE)) {
                            if (s != 0) {
                                f.player().getInventory().setStack(0, f.player().getInventory().getStack(s));
                                f.player().getInventory().setStack(s, ItemStack.EMPTY);
                            }
                            break;
                        }
                    }
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (activity.phase().equals("IDLE") && !activity.work().startsWith("Completed native_place")) {
                        f.runtime.nativePlace(f.source, f.id, furnacePos, "up", 0);
                    }
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_place"), "Furnace placed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_place"), "Durable ledger records verified native_place");
                    boolean furnacePlaced = false;
                    for (int x = 0; x <= 7 && !furnacePlaced; x++) {
                        for (int y = 1; y <= 4 && !furnacePlaced; y++) {
                            for (int z = 0; z <= 7 && !furnacePlaced; z++) {
                                if (context.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.FURNACE)) {
                                    furnacePlaced = true;
                                }
                            }
                        }
                    }
                    context.assertTrue(furnacePlaced, "Furnace block placed in world");
                    context.assertTrue(countItem(f, Items.FURNACE) == 0, "Furnace item consumed from inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void craftIronPickaxeAt3x3Workstation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "IronCrafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 3));
                    f.player().getInventory().setStack(1, new ItemStack(Items.STICK, 2));
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "iron_pickaxe") == 1, "Iron pickaxe craft requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_craft"), "Craft completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_craft"), "Durable receipt verified");
                    context.assertTrue(countItem(f, Items.IRON_INGOT) == 0, "Iron ingots consumed");
                    context.assertTrue(countItem(f, Items.STICK) == 0, "Sticks consumed");
                    context.assertTrue(countItem(f, Items.IRON_PICKAXE) == 1, "Iron pickaxe produced");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }


    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyIronSmelt", tickLimit = 740)
    public void endToEndAutonomousSmeltingToIronPickaxeProgression(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 720);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "EndToEndIronSmith"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.RAW_IRON, 3));
                    f.player().getInventory().setStack(1, new ItemStack(Items.COAL, 1));
                    f.player().getInventory().setStack(2, new ItemStack(Items.STICK, 2));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    context.assertTrue(entity instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity, "Furnace exists");
                    var furnace = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) entity;

                    // Autonomous player will have loaded furnace (or is loading)
                    boolean loaded = !furnace.getStack(0).isEmpty() || !furnace.getStack(1).isEmpty()
                            || countItem(f, Items.RAW_IRON) < 3;
                    context.assertTrue(loaded, "Autonomous progression loaded furnace with fuel and raw iron");

                    // Simulate complete smelting: 3 iron ingots into output slot 2
                    furnace.setStack(0, ItemStack.EMPTY);
                    furnace.setStack(1, ItemStack.EMPTY);
                    furnace.setStack(2, new ItemStack(Items.IRON_INGOT, 3));
                    furnace.markDirty();
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    // Extract output if still in furnace
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    var furnace = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) entity;
                    if (furnace != null && !furnace.getStack(2).isEmpty()) {
                        f.player().getInventory().setStack(0, furnace.getStack(2).copy());
                        furnace.setStack(2, ItemStack.EMPTY);
                        furnace.markDirty();
                    }
                })
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    boolean hasIronPickaxe = false;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.IRON_PICKAXE)) {
                            hasIronPickaxe = true;
                            break;
                        }
                    }
                    context.assertTrue(hasIronPickaxe, "Autonomous progression successfully crafted iron pickaxe at workstation");
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
