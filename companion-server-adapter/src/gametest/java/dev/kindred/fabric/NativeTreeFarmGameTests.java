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
 * A tree farm (E-02, E-03): a player given a patch of two oak trees, a stone axe and two oak saplings cuts the patch's
 * logs down with its axe, picking up what drops, and once each trunk is down plants a sapling where it stood. Both
 * saplings in the ground on the grass the trunks stood on, no log left standing, and it says so.
 */
public final class NativeTreeFarmGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "treeFarm", tickLimit = 4000)
    public void itCutsThePatchsTreesThenReplantsWhereEachTrunkStood(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3800);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        BlockPos[] trunks = {o.add(6, 7, 6), o.add(10, 7, 6)};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-6, 0, -6), o.add(22, 20, 18));
                    for (int x = -6; x <= 22; x++) for (int z = -6; z <= 18; z++) {
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (BlockPos trunk : trunks) for (int y = 0; y < 4; y++) world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_AXE));
                    inventory.setStack(1, new ItemStack(Items.OAK_SAPLING, 2));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 8.5, o.getY() + 7, o.getZ() + 3.5, 0, 0);
                    // Its patch: the two trees.
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, trunks[0], trunks[1].up(3), "minecraft:oak_log") == 1, "Patch given");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch saved"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    if (world.getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Tree farm fixture: {} / {}; {}", world.getBlockState(trunks[0]), world.getBlockState(trunks[1]), f.runtime.nativeLastResult(f.id));
                    }
                    for (BlockPos trunk : trunks) {
                        context.assertTrue(world.getBlockState(trunk).isOf(Blocks.OAK_SAPLING) && world.getBlockState(trunk.down()).isOf(Blocks.GRASS_BLOCK),
                                "A sapling where the trunk stood at " + trunk.toShortString() + ": " + world.getBlockState(trunk));
                    }
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (BlockPos trunk : trunks) for (int y = 1; y < 4; y++) {
                        context.assertTrue(!world.getBlockState(trunk.up(y)).isOf(Blocks.OAK_LOG), "No log left standing: " + trunk.up(y).toShortString());
                    }
                    var inventory = f.player().getInventory();
                    // The logs its own: held, or made into what its own progression made of them.
                    context.assertTrue(inventory.count(Items.OAK_SAPLING) == 0 && inventory.count(Items.BREAD) == 8,
                            "Both saplings planted, its bread kept: " + inventory.main);
                    context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.equals("I cut the patch down: 8 oak logs.")), "Said: " + f.runtime.said(f.id));
                    context.assertTrue(f.runtime.said(f.id).stream().filter(line -> line.equals("I planted an oak sapling where the tree stood.")).count() == 2,
                            "Said: " + f.runtime.said(f.id));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
