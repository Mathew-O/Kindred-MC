package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest of a wheat plot laid where grass grows (M-01a1). Its plot is on a patch of grass with a tuft
 * of short grass on every block of it. A hoe tills nothing under a plant, so it clears each tuft first, as a player does,
 * then tills. Before, its hoe went at the grass block under the tuft, "Soil tilling produced no effect", every try.
 *
 * Technical: staged, as {@link NativeOwnRanchGameTests}' wheat fixture is (its cabin, a pair of cows, logs and no wheat or
 * seeds), but with its plot recorded on the tufts by the fixture ({@code PlayerRuntime.stagePlotForTest}). Passes when all
 * four of the plot's blocks are tilled within {@value #BUDGET} ticks of bases being allowed.
 */
public final class NativePlotGrassGameTests implements FabricGameTest {
    static final int BUDGET = 6000;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "plotGrass", tickLimit = BUDGET + 1500)
    public void aPlotUnderTuftsOfGrassIsClearedThenTilled(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 1000);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        // The plot's soil, the grass blocks a step east of the cabin: its corner, as a plot is recorded, at the ground itself.
        BlockPos plotAt = home.add(8, -1, 0);
        int[] watched = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Tiller"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    NativeOwnRanchGameTests.walled(context);
                    world.setTimeOfDay(1000);
                    var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        try {
                            world.setBlockState(BuildRunner.blockPos(b.at()),
                                    BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), b.spec().toString(), false).blockState());
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                            throw new RuntimeException(failure);
                        }
                    }
                    // Staged: the plot's four blocks grass, a tuft of short grass on each, and tufts about it for seeds.
                    for (int x = -2; x <= 3; x++) for (int z = -2; z <= 3; z++) {
                        BlockPos soil = plotAt.add(x, 0, z);
                        world.setBlockState(soil, Blocks.GRASS_BLOCK.getDefaultState());
                        world.setBlockState(soil.up(), Blocks.SHORT_GRASS.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    ItemStack[] held = {new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_LOG, 18),
                            new ItemStack(Items.BREAD, 16)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(5, 0, 4);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    BlockPos rel = home.add(12, 0, 6).subtract(context.getAbsolutePos(BlockPos.ORIGIN));
                    context.spawnEntity(EntityType.COW, rel);
                    context.spawnEntity(EntityType.COW, rel.add(1, 0, 2));
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.stagePlotForTest(f.id,
                        new PlayerMemoryStore.Plot("minecraft:overworld", plotAt.getX(), plotAt.getY(), plotAt.getZ())), "Its plot recorded on the tufts"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    watched[0]++;
                    var world = context.getWorld();
                    int tilled = 0;
                    for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) if (world.getBlockState(plotAt.add(dx, 0, dz)).isOf(Blocks.FARMLAND)) tilled++;
                    context.assertTrue(tilled == 4, "Its plot tilled: " + tilled + " of 4 after " + watched[0] + " ticks / " + f.runtime.ranchNote(f.id)
                            + " / " + f.runtime.plot(f.id));
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Plot under grass: tilled after {} ticks", watched[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
