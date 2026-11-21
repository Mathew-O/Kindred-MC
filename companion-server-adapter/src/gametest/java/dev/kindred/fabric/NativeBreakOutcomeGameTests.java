package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneOreBlock;
import net.minecraft.entity.ItemEntity;
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

    /**
     * In plain terms: an AI player digging at gravel, when the gravel drops out of its spot by itself before the player has
     * broken it, and the player picks up something lying at its feet at the same moment, carries on rather than freezing
     * for good as though it could not tell what had happened.
     *
     * Technical: F-25c4bp1. In E-40's iteration 24 replica (`e40a24r-83321ac`) on 707, Sol was digging down toward lava
     * through a column of gravel over a cave (x 56 to 57, z 110 to 111, gravel from y −5 to −2 in fresh seed-707 terrain;
     * all of it gone in the kept world). The target, 56, −2, 110, fell out of its cell before its break landed, and a tick
     * before, Sol picked up a gravel: "Native break outcome requires reconciliation (The block changed before an
     * attributable break; inventory changed without an attributable break)", and it did nothing for the last 85,000 ticks
     * of its run (RUNNER 2's {@code claude/corpus-data-e40a24r-707-1116@a748dd7}). STAGED: in stone, a column of four gravel
     * two blocks south of the player, its top at its feet, standing on one stone with a cell of air under that; the player
     * holds only an iron pickaxe. As mining begins, the fixture takes that stone away (the world's change, not the
     * player's), so the column falls a block, and leaves a gravel on the ground at the player's feet, as a drop of the break
     * before lay in the run, to be picked up by walking over it. Passes when the break is not left uncertain, the player is
     * not stopped, and nothing it holds is gone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "breakFell", tickLimit = 600)
    public void aBlockThatFallsFromItsCellBeforeItsBreakWhileSomethingIsPickedUpLeavesNoBreakToReconcile(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 500);
        BlockPos[] top = new BlockPos[1];
        long[] fell = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    BlockPos at = top[0] = feet.add(0, 0, 2);
                    // Stone round the column, from under its cell of air to over its top, so nothing else moves.
                    for (BlockPos cell : BlockPos.iterate(at.add(-1, -6, -1), at.add(1, 1, 1))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    world.setBlockState(at.down(5), Blocks.AIR.getDefaultState());
                    for (int dy = 0; dy >= -3; dy--) world.setBlockState(at.up(dy), Blocks.GRAVEL.getDefaultState());
                    // Facing it (south), nothing between.
                    world.setBlockState(feet.add(0, 0, 1), Blocks.AIR.getDefaultState());
                    world.setBlockState(feet.add(0, 1, 1), Blocks.AIR.getDefaultState());
                    player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 20);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(top[0].down(3)).isOf(Blocks.GRAVEL), "The column stands");
                    BlockPos at = top[0];
                    f.runtime.nativeTaskForFixtures(f.source, f.id, "native_break", Map.of("x", String.valueOf(at.getX()),
                            "y", String.valueOf(at.getY()), "z", String.valueOf(at.getZ())));
                })
                .createAndAdd(() -> {
                    // Mining under way: the stone under the column taken away, and a drop of the break before at its feet.
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(f.runtime.nativeBodyForTest(f.id).miningActive(top[0]), "Mining begun");
                    world.setBlockState(top[0].down(4), Blocks.AIR.getDefaultState());
                    var drop = new ItemEntity(world, player.getX(), player.getY() + 0.2, player.getZ(), new ItemStack(Items.GRAVEL));
                    drop.setVelocity(Vec3d.ZERO);
                    world.spawnEntity(drop);
                    fell[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getTick() - fell[0] >= 60, "A moment on");
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Its body still in the world");
                    context.assertTrue(!context.getWorld().getBlockState(top[0]).isOf(Blocks.GRAVEL), "The gravel still in its cell: it did not fall");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "An uncertain action: " + record);
                    context.assertTrue(player.getInventory().count(Items.IRON_PICKAXE) == 1, "Its pickaxe gone");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
