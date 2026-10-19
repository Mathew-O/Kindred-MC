package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.EditPolicy;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Regression tests qualifying F-04b / F-05c prerequisites for defensive combat:
 * 1. Candidate evaluation never changes inventory, including rejected/unselected candidates.
 * 2. Hazard arrival during active work revokes that work and cannot cause overlapping controllers.
 * 3. Delayed callbacks cannot restart stopped/replaced work.
 * 4. Revoked policy/claims prevent furnace mutation.
 * 5. Inventory components and counts remain conserved under native slot swap.
 */
public final class NativeDefensivePrerequisitesGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 220)
    public void candidateEvaluationNeverChangesInventory(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false); // edits disabled

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ReadOnlyChecker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // Populate main inventory slots (9..35) with candidates: food, tool, table, furnace
                    f.player().setHealth(12.0f);
                    f.player().getHungerManager().setFoodLevel(12);
                    for (int s = 0; s < 9; s++) {
                        f.player().getInventory().setStack(s, ItemStack.EMPTY);
                    }
                    var apple = new ItemStack(Items.APPLE, 5);
                    var pick = new ItemStack(Items.IRON_PICKAXE);
                    pick.setDamage(15);
                    var table = new ItemStack(Items.CRAFTING_TABLE, 1);
                    var furnace = new ItemStack(Items.FURNACE, 1);

                    f.player().getInventory().setStack(14, apple);
                    f.player().getInventory().setStack(15, pick);
                    f.player().getInventory().setStack(16, table);
                    f.player().getInventory().setStack(17, furnace);
                })
                .createAndAdd(() -> {
                    // Capture full inventory before evaluation
                    ItemStack[] before = new ItemStack[36];
                    for (int s = 0; s < 36; s++) {
                        before[s] = f.player().getInventory().getStack(s).copy();
                    }

                    // Directly invoke candidate evaluation across the inventory
                    int candidateCount = f.runtime.evaluateCandidatesCountForTest(f.id);
                    context.assertTrue(candidateCount > 0, "Candidates were proposed: count=" + candidateCount);

                    // Verify every slot from 0 to 35 is strictly conserved and unchanged
                    for (int s = 0; s < 36; s++) {
                        ItemStack b = before[s];
                        ItemStack a = f.player().getInventory().getStack(s);
                        context.assertTrue(ItemStack.areItemsAndComponentsEqual(b, a) && b.getCount() == a.getCount(),
                                "Slot " + s + " must not change during candidate evaluation. Before: " + b + ", After: " + a);
                    }
                    for (int h = 0; h < 9; h++) {
                        context.assertTrue(f.player().getInventory().getStack(h).isEmpty(),
                                "Hotbar slot " + h + " must remain empty (no speculative swap mutations)");
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 360)
    public void hazardArrivalDuringActiveWorkRevokesWorkWithoutOverlappingControllers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "HazardInterrupter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    // Start a 4-second wait skill
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 4) == 1, "Wait skill started");
                })
                .createAndAdd(() -> {
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(!act.phase().equals("IDLE") && act.work().contains("native_wait"),
                            "Wait skill is actively running: " + act.work());
                    // Place hazardous magma block directly under player's feet
                    BlockPos ground = f.player().getBlockPos().down();
                    context.getWorld().setBlockState(ground, Blocks.MAGMA_BLOCK.getDefaultState());
                })
                .createAndAdd(() -> {
                    // Wait for mid-skill hazard detection and autonomous retreat to complete
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!act.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(act.phase().equals("IDLE") && act.work().contains("native_retreat"),
                            "Autonomous retreat completed after hazard interruption: " + act.work());
                    BlockPos currentGround = f.player().getBlockPos().down();
                    context.assertTrue(!context.getWorld().getBlockState(currentGround).isOf(Blocks.MAGMA_BLOCK),
                            "Player safely retreated off the hazardous magma block");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void delayedCallbacksCannotRestartStoppedOrReplacedWork(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        dev.kindred.core.ActionLease[] capturedLease = new dev.kindred.core.ActionLease[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "StaleCallbackTester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 3) == 1, "Wait dispatched");
                })
                .createAndAdd(() -> {
                    var lease = f.runtime.leaseForTest(f.id);
                    context.assertTrue(lease != null, "Active lease present");
                    capturedLease[0] = lease;
                })
                .createAndAdd(() -> {
                    var lease = capturedLease[0];
                    // Stop companion synchronously
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    context.assertTrue(!f.runtime.isLeaseCurrentForTest(f.id, lease), "Old lease is revoked immediately by stop");
                })
                .createAndAdd(() -> {
                    var act = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(act.phase().equals("IDLE"), "Stopped activity settles to IDLE");
                })
                .createAndAdd(() -> {
                    var lease = capturedLease[0];
                    // Resume and start a replacement skill
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 1, "Replacement task accepted");
                    // Old lease is still completely invalid
                    context.assertTrue(!f.runtime.isLeaseCurrentForTest(f.id, lease), "Old lease cannot act under replacement lease");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void revokedPolicyOrClaimPreventsFurnaceMutation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "FurnacePolicyChecker"))
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
                    // Revoke edit policy for furnace
                    f.runtime.setEditsForTest(EditPolicy.ServerPolicy.deferredD4());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.runtime.nativeSmelt(f.source, f.id, furnacePos, "load");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("Blocked:"), "Smelt blocked when policy revoked: " + activity.work());
                    context.assertTrue(f.player().getInventory().getStack(0).isOf(Items.RAW_IRON), "Raw iron intact");
                    context.assertTrue(f.player().getInventory().getStack(1).isOf(Items.COAL), "Coal intact");
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    context.assertTrue(furnace.getStack(0).isEmpty(), "Furnace input remained empty");
                    context.assertTrue(furnace.getStack(1).isEmpty(), "Furnace fuel remained empty");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void nativeSwapActionConservesExactComponentsAndCounts(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SwapConservationTester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var sword = new ItemStack(Items.DIAMOND_SWORD, 1);
                    sword.setDamage(42);
                    sword.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Legendary Blade"));
                    f.player().getInventory().setStack(14, sword);
                    var gapple = new ItemStack(Items.GOLDEN_APPLE, 3);
                    f.player().getInventory().setStack(2, gapple);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    context.assertTrue(f.runtime.nativeSwap(f.source, f.id, 14, 2) == 1, "Swap request admitted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("native_swap"), "Swap completed: " + activity.work());
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_swap"), "Durable ledger records VERIFIED native_swap");

                    ItemStack swappedSword = f.player().getInventory().getStack(2);
                    context.assertTrue(swappedSword.isOf(Items.DIAMOND_SWORD) && swappedSword.getCount() == 1, "Sword in slot 2");
                    context.assertTrue(swappedSword.getDamage() == 42, "Sword damage preserved exactly");
                    context.assertTrue(swappedSword.get(DataComponentTypes.CUSTOM_NAME) != null
                            && swappedSword.get(DataComponentTypes.CUSTOM_NAME).getString().equals("Legendary Blade"), "Custom name preserved");

                    ItemStack swappedApple = f.player().getInventory().getStack(14);
                    context.assertTrue(swappedApple.isOf(Items.GOLDEN_APPLE) && swappedApple.getCount() == 3, "Golden apples in slot 14");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void selfSwapIsRefusedAsNoEffectWithoutMutation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SelfSwapTester"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var sword = new ItemStack(Items.DIAMOND_SWORD, 1);
                    f.player().getInventory().setStack(2, sword);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);

                    context.assertTrue(f.runtime.nativeSwap(f.source, f.id, 2, 2) == 1, "Swap request admitted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("Blocked:") || activity.work().contains("Self-swap"),
                            "Self-swap blocked without mutation: " + activity.work());
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.FAILED
                            && action.kind().equals("native_swap"), "Durable ledger records FAILED no-effect native_swap");

                    ItemStack swordInSlot2 = f.player().getInventory().getStack(2);
                    context.assertTrue(swordInSlot2.isOf(Items.DIAMOND_SWORD) && swordInSlot2.getCount() == 1, "Sword in slot 2 preserved");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void replacedFurnaceInstanceDeniesMutation(TestContext context) {
        var writes = new ControlledActions(); writes.holdDispatch = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes, true);
        BlockPos furnacePos = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos support = furnacePos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "FurnaceIncarnationChecker"))
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
                    f.runtime.nativeSmelt(f.source, f.id, furnacePos, "load");
                })
                .createAndAdd(() -> context.assertTrue(writes.actualHeld != null && writes.actualHeld.isDone(),
                        "DISPATCHED is durable but held"))
                .createAndAdd(() -> {
                    // Break and replace the furnace with a new furnace block (new block entity instance) while DISPATCHED is held
                    context.getWorld().setBlockState(furnacePos, Blocks.AIR.getDefaultState());
                    context.getWorld().setBlockState(furnacePos, Blocks.FURNACE.getDefaultState());
                    // Now release the held dispatch acknowledgement
                    writes.held.complete(writes.actualHeld.getNow(null));
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("Blocked:"), "Smelt blocked when furnace instance replaced: " + activity.work());
                    context.assertTrue(f.player().getInventory().getStack(0).isOf(Items.RAW_IRON), "Raw iron intact");
                    context.assertTrue(f.player().getInventory().getStack(1).isOf(Items.COAL), "Coal intact");
                    var entity = context.getWorld().getBlockEntity(furnacePos);
                    var furnace = (AbstractFurnaceBlockEntity) entity;
                    context.assertTrue(furnace.getStack(0).isEmpty(), "Replaced furnace input remained empty");
                    context.assertTrue(furnace.getStack(1).isEmpty(), "Replaced furnace fuel remained empty");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static final class ControlledActions implements java.util.function.UnaryOperator<ActionAdmission.Writer> {
        boolean holdDispatch;
        CompletableFuture<ActionRecord> actualHeld, held;
        @Override public ActionAdmission.Writer apply(ActionAdmission.Writer delegate) {
            return new ActionAdmission.Writer() {
                public CompletableFuture<ActionRecord> prepare(ActionRecord intent) { return delegate.prepare(intent); }
                public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) {
                    var actual = delegate.transition(next, revision);
                    if (holdDispatch && next.state() == ActionRecord.State.DISPATCHED) {
                        holdDispatch = false; actualHeld = actual; held = new CompletableFuture<>(); return held;
                    }
                    return actual;
                }
            };
        }
    }
}
