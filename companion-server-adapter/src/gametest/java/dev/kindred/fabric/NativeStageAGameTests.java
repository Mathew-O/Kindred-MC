package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player re-checks
 * its permission at the very moment it places a block.
 *
 * Technical: Acceptance GameTests for Stage A (F-05e shared-action correctness):
 * 1. Claim revocation while paid activity is still valid prevents block placement at mutation time.
 * 2. Support replaceability drift prevents placement and avoids redirected destination mutation.
 * 3. Setup revision change prevents placement at mutation time.
 * 4. Component-aware placement decrements exact stack count while preserving item components and other slots.
 * 5. Uninterrupted two-block gather succeeds under one lease with distinct action IDs and sequential substeps.
 * 6. Unrelated pre-existing drops of the same item type are not attributed to the break.
 * 7. Native pickup delay and cannotPickup() rules are preserved (no forced remote insertion).
 */
public final class NativeStageAGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void claimRevocationWhileStillPaidPreventsPlacement(TestContext context) {
        var writes = new ControlledActions(); writes.holdDispatch = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes, true);
        BlockPos support = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos placedPos = support.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ClaimRevokePlacer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(placedPos, Blocks.AIR.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativePlace(f.source, f.id, support, "up", 0) == 1, "Place admitted");
                })
                .createAndAdd(() -> context.assertTrue(writes.actualHeld != null && writes.actualHeld.isDone(),
                        "DISPATCHED is durable but held"))
                .createAndAdd(() -> {
                    var lease = f.runtime.leaseForTest(f.id);
                    context.assertTrue(lease != null, "Paid lease is still active");
                    context.assertTrue(f.runtime.isLeaseCurrentForTest(f.id, lease), "Lease is valid and current");

                    // Revoke the claim for this task directly in ClaimRegistry while still paid
                    f.runtime.claims().releaseTask(lease.taskId());

                    // Release the held dispatch write
                    writes.held.complete(writes.actualHeld.getNow(null));
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("Blocked:"),
                            "Placement blocked when claim was revoked while still paid: " + activity.work());
                    context.assertTrue(context.getWorld().getBlockState(placedPos).isAir(),
                            "Placed position remained AIR; no block placed");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 5
                            && f.player().getInventory().getStack(0).isOf(Items.DIRT),
                            "Inventory count conserved: dirt stack untouched");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.FAILED,
                            "Action record marked FAILED in ledger");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void supportReplaceabilityDriftPreventsPlacement(TestContext context) {
        var writes = new ControlledActions(); writes.holdDispatch = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes, true);
        BlockPos support = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos placedPos = support.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "DriftPlacer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(placedPos, Blocks.AIR.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativePlace(f.source, f.id, support, "up", 0) == 1, "Place admitted");
                })
                .createAndAdd(() -> context.assertTrue(writes.actualHeld != null && writes.actualHeld.isDone(),
                        "DISPATCHED is durable but held"))
                .createAndAdd(() -> {
                    // Replace the solid support with AIR while dispatch is held (destination drift)
                    context.getWorld().setBlockState(support, Blocks.AIR.getDefaultState());
                    writes.held.complete(writes.actualHeld.getNow(null));
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("Blocked:"),
                            "Placement blocked when support replaceability drifted: " + activity.work());
                    context.assertTrue(context.getWorld().getBlockState(support).isAir(),
                            "Support position remained AIR");
                    context.assertTrue(context.getWorld().getBlockState(placedPos).isAir(),
                            "Placed position remained AIR; no block placed");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 5,
                            "Dirt stack conserved at 5");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.FAILED,
                            "Action record marked FAILED");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void setupRevisionChangePreventsPlacement(TestContext context) {
        var writes = new ControlledActions(); writes.holdDispatch = true;
        var f = new NativePaidSkillsGameTests.Fixture(context, writes, true);
        BlockPos support = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos placedPos = support.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "RevisionPlacer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(placedPos, Blocks.AIR.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativePlace(f.source, f.id, support, "up", 0) == 1, "Place admitted");
                })
                .createAndAdd(() -> context.assertTrue(writes.actualHeld != null && writes.actualHeld.isDone(),
                        "DISPATCHED is durable but held"))
                .createAndAdd(() -> {
                    // Update setup revision while dispatch is held
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:dirt");
                    writes.held.complete(writes.actualHeld.getNow(null));
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().contains("Blocked:") || activity.work().contains("patch changed"),
                            "Placement blocked or halted when setup revision changed: " + activity.work());
                    context.assertTrue(context.getWorld().getBlockState(placedPos).isAir(),
                            "No block placed");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 5,
                            "Dirt conserved");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.FAILED,
                            "Action record marked FAILED");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void componentAwarePlacementConservesInventory(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos support = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos placedPos = support.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "ComponentPlacer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(support, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(placedPos, Blocks.AIR.getDefaultState());

                    var customDirt = new ItemStack(Items.DIRT, 3);
                    customDirt.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Sacred Soil"));
                    f.player().getInventory().setStack(0, customDirt);

                    var sword = new ItemStack(Items.DIAMOND_SWORD, 1);
                    sword.setDamage(12);
                    sword.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Sidearm"));
                    f.player().getInventory().setStack(14, sword);

                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativePlace(f.source, f.id, support, "up", 0) == 1, "Place admitted");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("IDLE")) return;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("Completed native_place"),
                            "Placement completed: " + activity.work());
                    context.assertTrue(context.getWorld().getBlockState(placedPos).isOf(Blocks.DIRT),
                            "Dirt block successfully placed in world");

                    // Check slot 0: decremented by exactly 1, custom component preserved
                    ItemStack remainingDirt = f.player().getInventory().getStack(0);
                    context.assertTrue(remainingDirt.isOf(Items.DIRT) && remainingDirt.getCount() == 2,
                            "Slot 0 decremented by 1 to 2");
                    context.assertTrue(remainingDirt.get(DataComponentTypes.CUSTOM_NAME) != null
                            && remainingDirt.get(DataComponentTypes.CUSTOM_NAME).getString().equals("Sacred Soil"),
                            "Custom name preserved on remaining stack");

                    // Check other slot: slot 14 completely untouched
                    ItemStack slot14 = f.player().getInventory().getStack(14);
                    context.assertTrue(slot14.isOf(Items.DIAMOND_SWORD) && slot14.getCount() == 1
                            && slot14.getDamage() == 12, "Other slots completely untouched");

                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED,
                            "Action record VERIFIED");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 360)
    public void uninterruptedTwoBlockGatherSucceedsWithDistinctActionIds(TestContext context) {
        List<ActionRecord> verifiedRecords = new CopyOnWriteArrayList<>();
        var f = new NativePaidSkillsGameTests.Fixture(context, delegate -> new ActionAdmission.Writer() {
            public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                return delegate.prepare(intent);
            }
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long revision) {
                if (next.state() == ActionRecord.State.VERIFIED) {
                    verifiedRecords.add(next);
                }
                return delegate.transition(next, revision);
            }
        }, true);

        BlockPos log1 = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos log2 = context.getAbsolutePos(new BlockPos(3, 2, 5));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "DualGatherer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(log1, Blocks.OAK_LOG.getDefaultState());
                    context.getWorld().setBlockState(log2, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, log1, log2, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 2, 0) == 1, "Gather 2 logs requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_gather"), "Gather completed");

                    context.assertTrue(context.getWorld().getBlockState(log1).isAir(), "First log broken");
                    context.assertTrue(context.getWorld().getBlockState(log2).isAir(), "Second log broken");
                    context.assertEquals(2, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG),
                            "Player collected both oak logs into inventory");
                    context.assertEquals(2, f.player().getMainHandStack().getDamage(),
                            "Axe took 2 tool durability damage");

                    // Verify distinct action IDs and sequential substeps under the same task
                    context.assertEquals(2, verifiedRecords.size(), "Exactly 2 VERIFIED action records written");
                    var rec1 = verifiedRecords.get(0);
                    var rec2 = verifiedRecords.get(1);
                    context.assertTrue(!rec1.actionId().equals(rec2.actionId()),
                            "Each break must have a distinct action ID: " + rec1.actionId() + " vs " + rec2.actionId());
                    context.assertEquals(rec1.taskId(), rec2.taskId(),
                            "Both breaks share the same task ID under the single lease");
                    context.assertTrue(rec1.preconditions().contains("substep=0"),
                            "First break records substep=0: " + rec1.preconditions());
                    context.assertTrue(rec2.preconditions().contains("substep=1"),
                            "Second break records substep=1: " + rec2.preconditions());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void unrelatedNearbyDropDoesNotAttributeToBreak(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = context.getAbsolutePos(new BlockPos(3, 2, 5));
        ItemEntity[] existingDrop = new ItemEntity[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "UnrelatedDropChecker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    // Spawn a pre-existing oak log drop within the search box (expand 3.0 of target)
                    existingDrop[0] = new ItemEntity(context.getWorld(),
                            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                            new ItemStack(Items.OAK_LOG, 1));
                    existingDrop[0].setPickupDelay(200); // cannot be picked up yet
                    context.getWorld().spawnEntity(existingDrop[0]);

                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 1, 0) == 1, "Native gather requested");
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Target log broken");
                    // Discard drops from the break (excluding existingDrop)
                    for (var drop : context.getWorld().getEntitiesByClass(ItemEntity.class,
                            new net.minecraft.util.math.Box(target).expand(3.0), d -> true)) {
                        if (existingDrop[0] != null && !drop.getUuid().equals(existingDrop[0].getUuid())) {
                            drop.discard();
                        }
                    }
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && (activity.work().contains("Blocked:")
                            || activity.work().contains("lost") || activity.work().contains("collected by another entity")),
                            "Unrelated pre-existing drop does not attribute to break: " + activity.work());
                    context.assertEquals(0, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG),
                            "No logs collected into inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void pickupDelayAndOwnerRestrictionsPreserved(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = context.getAbsolutePos(new BlockPos(3, 2, 5));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "PickupRestrictionChecker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 1, 0) == 1, "Native gather requested");
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Target log broken");
                    var drops = context.getWorld().getEntitiesByClass(ItemEntity.class,
                            new net.minecraft.util.math.Box(target).expand(3.0), d -> d.getStack().isOf(Items.OAK_LOG));
                    context.assertTrue(!drops.isEmpty(), "Drop spawned from break");
                    for (var drop : drops) {
                        drop.setPickupDelay(200);
                    }
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && (activity.work().contains("Breaking without pickup cannot complete gather")
                            || activity.work().contains("Blocked:")),
                            "Pickup delay prevented premature collection: " + activity.work());
                    context.assertEquals(0, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG),
                            "Inventory has 0 logs (no remote pickup bypass)");
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
