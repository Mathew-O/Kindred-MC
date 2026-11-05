package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneOreBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/** A break's outcome when the world changes the block being mined (F-25c4bp). */
public class NativeBreakOutcomeGameTests implements FabricGameTest {
    /**
     * Redstone ore lights up as mining begins: its block state changes, though it is not broken. When that happened while
     * the player picked something up (a drop from the break before), the break could not tell what it had done, and the
     * player was stopped for reconciliation for the rest of the run: Echo on 20261030 in E-40's iteration 8
     * (`e40a8-aed3e82`). Kindred on 404 met the same with dirt grown over with grass (F-25c4bp). Staged: deepslate
     * redstone ore set in stone two blocks in front of the player, which holds only an iron pickaxe; as the ore lights, a
     * cobblestone is put into its inventory as though picked up. Passes when the break is not left uncertain, the player is
     * not stopped, and the ore still stands.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "breakChanged", tickLimit = 600)
    public void aBlockTheWorldChangesUnbrokenWhileSomethingIsPickedUpLeavesNoBreakToReconcile(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        BlockPos[] ore = new BlockPos[1];
        long[] given = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    BlockPos at = ore[0] = feet.add(0, 0, 2);
                    for (BlockPos cell : BlockPos.iterate(at.add(-1, -1, 0), at.add(1, 1, 1))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    world.setBlockState(at, Blocks.DEEPSLATE_REDSTONE_ORE.getDefaultState());
                    // Facing it (south), nothing between.
                    player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 20);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                })
                .createAndAdd(() -> {
                    BlockPos at = ore[0];
                    f.runtime.nativeTaskForFixtures(f.source, f.id, "native_break", Map.of("x", String.valueOf(at.getX()),
                            "y", String.valueOf(at.getY()), "z", String.valueOf(at.getZ())));
                })
                .createAndAdd(() -> {
                    // As the ore lights, something is picked up, as a drop from the break before was in the run.
                    var state = context.getWorld().getBlockState(ore[0]);
                    context.assertTrue(state.isOf(Blocks.DEEPSLATE_REDSTONE_ORE) && state.get(RedstoneOreBlock.LIT), "The ore lit as mining began");
                    f.player().getInventory().insertStack(new ItemStack(Items.COBBLESTONE));
                    given[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getTick() - given[0] >= 60, "A moment on");
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Its body still in the world");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    context.assertTrue(context.getWorld().getBlockState(ore[0]).isOf(Blocks.DEEPSLATE_REDSTONE_ORE), "The ore still stands");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
