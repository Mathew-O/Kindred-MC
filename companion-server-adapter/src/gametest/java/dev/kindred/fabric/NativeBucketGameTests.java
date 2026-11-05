package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/** The bucket skill and the inventory it fills into (F-25c4bl). */
public class NativeBucketGameTests implements FabricGameTest {
    /**
     * A bucket filled from a stack of two, with no slot free for the full one, is refused before it is used: nothing is
     * taken from the source and no outcome is left uncertain (F-25c4bl). Vanilla puts a filled bucket from a stack in a
     * free slot, and with none drops it at the player's feet. The skill read that as the inventory changed without the
     * bucket it expected, and stopped the player to reconcile. Found in Echo's death replay from 404 (`echo-73670`, E-40's
     * iteration 6): once it fought back (F-25c4bk), it went on to fill a bucket with its inventory full, and its body was
     * stopped. Staged: a floor of stone with a water source in front of the player; the player with two empty buckets and
     * every other slot full of cobblestone; the fill asked for directly. Passes when the player is still at work 100 ticks
     * on, no action uncertain, its two buckets held and no bucket on the ground.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "bucketNoRoom", tickLimit = 600)
    public void aBucketFilledFromAStackWithNoSlotFreeIsRefusedNotLeftUncertain(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        BlockPos[] water = new BlockPos[1];
        long[] asked = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pail"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    // A source set into the floor two blocks ahead, walled so that it stays a source.
                    BlockPos source = water[0] = feet.add(0, -1, 2);
                    for (BlockPos at : BlockPos.iterate(source.add(-1, -1, -1), source.add(1, 0, 1))) {
                        if (!at.equals(source)) world.setBlockState(at, Blocks.STONE.getDefaultState());
                    }
                    world.setBlockState(source, Blocks.WATER.getDefaultState());
                    player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 30);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BUCKET, 2));
                    for (int slot = 1; slot < 36; slot++) inventory.setStack(slot, new ItemStack(Items.COBBLESTONE, 64));
                })
                .createAndAdd(() -> {
                    BlockPos source = water[0];
                    f.runtime.nativeTaskForFixtures(f.source, f.id, "native_bucket", Map.of("x", String.valueOf(source.getX()),
                            "y", String.valueOf(source.getY()), "z", String.valueOf(source.getZ()), "mode", "fill"));
                    asked[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Its body still in the world");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    context.assertTrue(context.getTick() - asked[0] >= 100, "A moment on");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.BUCKET) == 2 && ItemMoves.count(inventory, Items.WATER_BUCKET) == 0,
                            "Its two buckets held, none filled: " + ItemMoves.count(inventory, Items.BUCKET) + " empty, "
                                    + ItemMoves.count(inventory, Items.WATER_BUCKET) + " full");
                    var dropped = context.getWorld().getEntitiesByClass(ItemEntity.class, new Box(player.getBlockPos()).expand(4), e -> true);
                    context.assertTrue(dropped.isEmpty(), "Nothing dropped: " + dropped.size());
                    context.assertTrue(context.getWorld().getFluidState(water[0]).isStill(), "The source still there");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
