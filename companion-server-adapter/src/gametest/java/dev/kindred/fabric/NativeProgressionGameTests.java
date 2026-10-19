package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.progression.MilestoneId;
import dev.kindred.core.progression.MilestoneStatus;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Acceptance GameTests for Personal Milestone Graph and Standing Progression Policy (E-12):
 * 1. Milestone evaluation faithfully reflects physical inventory and workstation proximity.
 * 2. Autonomous progression executes candidate actions to advance the active frontier without human intervention.
 * 3. Lost gear creates recovery work in the recovery frontier without erasing achievement history, recovering upon crafting replacement.
 */
public final class NativeProgressionGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void milestoneEvaluationReflectsPhysicalInventory(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ProgressionObserver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Empty inventory: evaluate progression
                    context.assertTrue(f.runtime.nativeProgression(f.source, f.id) == 1, "Diagnostic command succeeds");
                    var state = f.runtime.nativeProgressionState(f.id);
                    context.assertTrue(state != null, "Progression state exists");
                    context.assertTrue(state.activeFrontier().contains(MilestoneId.GATHER_WOOD), "Active frontier starts with GATHER_WOOD");
                    context.assertTrue(state.recoveryFrontier().isEmpty(), "No recovery frontier initially");
                    context.assertTrue(!state.snapshots().get(MilestoneId.GATHER_WOOD).physicalPostconditionSatisfied(), "GATHER_WOOD not physically met");

                    // Add 1 oak log
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 1));
                    context.assertTrue(f.runtime.nativeProgression(f.source, f.id) == 1, "Progression updated");
                    state = f.runtime.nativeProgressionState(f.id);
                    context.assertTrue(state.snapshots().get(MilestoneId.GATHER_WOOD).physicalPostconditionSatisfied(), "GATHER_WOOD physical met");
                    context.assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_PLANKS), "CRAFT_PLANKS unlocked on frontier");

                    // Add 4 oak planks
                    f.player().getInventory().setStack(1, new ItemStack(Items.OAK_PLANKS, 4));
                    context.assertTrue(f.runtime.nativeProgression(f.source, f.id) == 1, "Progression updated");
                    state = f.runtime.nativeProgressionState(f.id);
                    context.assertTrue(state.snapshots().get(MilestoneId.CRAFT_PLANKS).physicalPostconditionSatisfied(), "CRAFT_PLANKS physical met");
                    context.assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_STICKS), "CRAFT_STICKS unlocked on frontier");
                    context.assertTrue(state.activeFrontier().contains(MilestoneId.CRAFT_CRAFTING_TABLE), "CRAFT_CRAFTING_TABLE unlocked on frontier");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "progressionWoodenPick", tickLimit = 540)
    public void autonomousProgressionAdvancesFrontierWithoutHumanIntervention(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true, 520);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> f.awaitReady(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ProgressionAdvancer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 4));
                    f.player().getInventory().setStack(1, new ItemStack(Items.STICK, 2));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeProgression(f.source, f.id);
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
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.WOODEN_PICKAXE)) {
                            f.runtime.stop();
                            return;
                        }
                    }
                })
                .createAndAdd(() -> {
                    boolean hasWoodenPickaxe = false;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.WOODEN_PICKAXE)) {
                            hasWoodenPickaxe = true;
                            break;
                        }
                    }
                    context.assertTrue(hasWoodenPickaxe, "Autonomous progression crafted wooden pickaxe at workstation");
                    var state = f.runtime.nativeProgressionState(f.id);
                    context.assertTrue(state != null, "Progression state recorded");
                    context.assertTrue(state.snapshots().get(MilestoneId.CRAFT_WOODEN_PICKAXE).historicallyAchieved(), "Wooden pickaxe milestone historically achieved");
                    context.assertTrue(f.runtime.nativeProgressionHistory(f.id).containsKey(MilestoneId.CRAFT_WOODEN_PICKAXE), "History map updated");
                    if (!f.runtime.shutdownComplete()) f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "progressionRecovery", tickLimit = 540)
    public void lostGearTriggersRecoveryFrontierWithoutErasingAchievementHistory(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true, 520);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "GearRecoverer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    // Start with stone pickaxe earned
                    f.player().getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE, 1));
                })
                .createAndAdd(() -> {
                    // Register stone pickaxe in history
                    f.runtime.nativeProgression(f.source, f.id);
                    var state = f.runtime.nativeProgressionState(f.id);
                    context.assertTrue(state.snapshots().get(MilestoneId.CRAFT_STONE_PICKAXE).status() == MilestoneStatus.ACHIEVED, "Stone pickaxe achieved initially");
                    context.assertTrue(f.runtime.nativeProgressionHistory(f.id).containsKey(MilestoneId.CRAFT_STONE_PICKAXE), "Recorded in history");

                    // Simulate gear loss: remove pickaxe, provide raw crafting materials (3 cobble, 2 sticks)
                    f.player().getInventory().setStack(0, ItemStack.EMPTY);
                    f.player().getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 3));
                    f.player().getInventory().setStack(2, new ItemStack(Items.STICK, 2));

                    // Evaluate progression after loss
                    f.runtime.nativeProgression(f.source, f.id);
                    state = f.runtime.nativeProgressionState(f.id);

                    // Verify loss recovery: physical unfulfilled, history preserved, placed in recoveryFrontier
                    var snap = state.snapshots().get(MilestoneId.CRAFT_STONE_PICKAXE);
                    context.assertTrue(snap.status() == MilestoneStatus.UNLOCKED, "Status is UNLOCKED for recovery");
                    context.assertTrue(snap.historicallyAchieved(), "Achievement history preserved");
                    context.assertTrue(!snap.physicalPostconditionSatisfied(), "Physical postcondition lost");
                    context.assertTrue(snap.needsRecovery(), "needsRecovery flag active");
                    context.assertTrue(state.recoveryFrontier().contains(MilestoneId.CRAFT_STONE_PICKAXE), "In recovery frontier");
                    context.assertTrue(!state.activeFrontier().contains(MilestoneId.CRAFT_STONE_PICKAXE), "Not in active frontier");

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
                    boolean recovered = false;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.STONE_PICKAXE)) {
                            recovered = true;
                            break;
                        }
                    }
                    context.assertTrue(recovered, "Autonomous progression recovered lost stone pickaxe");
                    f.runtime.nativeProgression(f.source, f.id);
                    var state = f.runtime.nativeProgressionState(f.id);
                    var snap = state.snapshots().get(MilestoneId.CRAFT_STONE_PICKAXE);
                    context.assertTrue(snap.status() == MilestoneStatus.ACHIEVED, "Recovered stone pickaxe milestone is ACHIEVED again");
                    context.assertTrue(!snap.needsRecovery(), "No longer needs recovery");
                    context.assertTrue(state.recoveryFrontier().isEmpty(), "Recovery frontier empty after recovery");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
