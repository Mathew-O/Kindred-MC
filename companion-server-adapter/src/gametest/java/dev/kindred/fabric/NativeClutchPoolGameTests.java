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
 * In plain terms: a GameTest: a player falling twenty blocks into a shallow pool, one or two blocks deep and too
 * shallow to break the fall, saves itself with its water bucket as over dry ground. The water it pours lands on the
 * pool's water and the bucket comes back full, so nothing is lost and the player must not be held for a reconcile.
 *
 * Technical: The clutch's inventory check over water (E-07d1). On 31102026 in E-40's iteration 27, second batch,
 * Sol fell from Y=73 into shallow water at Y=61 with its water bucket. The clutch ended in "Clutch conservation check
 * failed; reconcile", and Sol was held for reconciliation for the run's last 10.1 minutes, while the run's item
 * sources balanced (8032 against 8032). {@code NativeClutchAction.landing} reads the pool's bed as the ground, since
 * water has no collision shape. The pour lands on water, and the scoop's own raycast takes a bucket of the pool's
 * water. The fixture stages that: an eight-by-eight pool over stone, the player put twenty blocks above it with a
 * water bucket, bread and a pack of cobblestone. It must end the clutch with its bucket full and nothing lost, and
 * not blocked for reconciliation.
 */
public final class NativeClutchPoolGameTests implements FabricGameTest {
    /** Ticks after joining past which vanilla's join invulnerability (sixty ticks) is over. */
    private static final int JOINED = 80;

    private static void pool(TestContext context, int depth) {
        var world = context.getWorld();
        FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(0, 1, 0)), context.getAbsolutePos(new BlockPos(7, 28, 7)));
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
            for (int y = 2; y <= 28; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)),
                    y < 2 + depth ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState());
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "clutchPool1", tickLimit = 600)
    public void fallingIntoAPoolOneDeepItsClutchIsNotHeldForAReconcile(TestContext context) {
        fall(context, "Pooled", 1);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "clutchPool2", tickLimit = 600)
    public void fallingIntoAPoolTwoDeepItsClutchIsNotHeldForAReconcile(TestContext context) {
        fall(context, "Pooled", 2);
    }

    /**
     * The check is not loosened (the lead's negative case): over dry stone, its cobblestone taken from the pack while it
     * falls, the clutch still ends held for a reconcile, as it does before the fix.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "clutchPool0", tickLimit = 600)
    public void aStackLostInTheFallStillHoldsTheClutchForAReconcile(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] taken = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Lost"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> context.assertTrue(f.player().age > JOINED, "Past the join's sixty ticks of grace: " + f.player().age))
                .createAndAdd(() -> {
                    pool(context, 0);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 4));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(3, new ItemStack(Items.WATER_BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    player.refreshPositionAndAngles(o.getX() + 3.5, o.getY() + 22, o.getZ() + 3.5, 0, 0);
                    player.fallDistance = 0;
                })
                .createAndAdd(() -> {
                    // Staged: once the clutch is under way, its cobblestone goes from the pack, as an item lost would.
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_clutch"), "The clutch under way: " + record);
                    f.player().getInventory().setStack(1, ItemStack.EMPTY);
                    taken[0] = true;
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(taken[0] && record.state() != ActionRecord.State.PREPARED && record.state() != ActionRecord.State.DISPATCHED,
                            "The clutch settled: " + record + " " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    KindredMod.LOGGER.info("Lost: {}; {}; {}", record, activity, f.runtime.nativeLastResult(f.id));
                    context.assertTrue(record.state() != ActionRecord.State.VERIFIED && String.valueOf(record.detail()).contains("conservation"),
                            "Held for a reconcile, the stack lost: " + record + " " + activity + " " + f.runtime.nativeLastResult(f.id));
                    // What was lost is named for the reconcile: the item, its count and its slot.
                    context.assertTrue(String.valueOf(record.detail()).contains("64 minecraft:cobblestone from slot 1"),
                            "The loss named: " + record.detail());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void fall(TestContext context, String name, int depth) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> context.assertTrue(f.player().age > JOINED, "Past the join's sixty ticks of grace: " + f.player().age))
                .createAndAdd(() -> {
                    pool(context, depth);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 4));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(3, new ItemStack(Items.WATER_BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    player.refreshPositionAndAngles(o.getX() + 3.5, o.getY() + 22, o.getZ() + 3.5, 0, 0);
                    player.fallDistance = 0;
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_clutch") && record.state() != ActionRecord.State.PREPARED
                                    && record.state() != ActionRecord.State.DISPATCHED,
                            "The clutch settled: " + record + " " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    KindredMod.LOGGER.info("{} (pool {} deep): {}; {}; {}; {}", name, depth, player == null ? "its body gone" : "in at "
                            + player.getBlockPos().toShortString() + " with health " + player.getHealth(), record, activity, f.runtime.nativeLastResult(f.id));
                    context.assertTrue(player != null, "Not held for a reconcile, its body gone: " + record + " " + activity + " " + f.runtime.nativeLastResult(f.id));
                    var inventory = player.getInventory();
                    context.assertTrue(inventory.count(Items.WATER_BUCKET) + inventory.count(Items.BUCKET) == 1, "Its one bucket kept");
                    context.assertTrue(inventory.count(Items.BREAD) == 4 && inventory.count(Items.COBBLESTONE) == 64, "Nothing else lost");
                    context.assertTrue(record.state() == ActionRecord.State.VERIFIED,
                            "A verified clutch, not held for a reconcile: " + record + " " + activity + " " + f.runtime.nativeLastResult(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
