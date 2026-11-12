package dev.kindred.fabric;

import dev.kindred.core.Actor;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: GameTests for the evaluation run's check that every item a player came to hold came from somewhere.
 * An item put straight into a player's inventory, or dropped by a command and picked up, is caught; a block's drop
 * picked up, a craft, a furnace's output and a chest taken from are not.
 *
 * Technical: M-06k's item-source audit ({@link EvalItemSources}) on a native player, its staged items its baseline
 * ({@code start(false)}). Snapshots are taken by the fixture, three after the last change so that every credit and
 * debt is past its carry. The audit is one per server, so each test is a batch of its own: two at once would share it.
 */
public final class NativeItemSourceGameTests implements FabricGameTest {
    private static final BlockPos AT = new BlockPos(2, 2, 2);
    private static final BlockPos CHEST = new BlockPos(5, 2, 2);
    private static final BlockPos FURNACE = new BlockPos(2, 2, 4);
    private static final BlockPos DIRT = new BlockPos(3, 2, 1);

    private static void ground(TestContext context) {
        var world = context.getWorld();
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        for (int x = -1; x <= 7; x++) for (int z = -1; z <= 7; z++) {
            world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
            for (int y = 2; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        world.setTimeOfDay(1000);
    }

    private static void stand(ServerPlayerEntity player, TestContext context) {
        BlockPos at = context.getAbsolutePos(AT);
        player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        player.getInventory().clear();
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    private static int count(ServerPlayerEntity player, Item item) {
        int total = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) total += inventory.getStack(i).getCount();
        return total;
    }

    private static void settle(TestContext context) {
        for (int i = 0; i <= dev.kindred.core.eval.ItemSourceLedger.CARRY + 1; i++) EvalItemSources.snapshot(context.getWorld().getServer());
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "itemSourcesCaught", tickLimit = 600)
    public void anItemFromNowhereOrACommandsDropIsCaughtAndABlocksDropIsNot(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        FixtureGround.whenDone(context, EvalItemSources::stop);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stray"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    ground(context);
                    stand(f.player(), context);
                    EvalItemSources.start(false);
                    EvalItemSources.snapshot(context.getWorld().getServer());
                    var player = f.player();
                    // A block's own drop (what breaking it drops), where the player stands.
                    Block.dropStack(context.getWorld(), player.getBlockPos(), new ItemStack(Items.OAK_LOG, 2));
                    // An item put straight into its inventory, from no source.
                    player.getInventory().insertStack(new ItemStack(Items.DIAMOND));
                    // A drop a command made, where it stands.
                    var server = context.getWorld().getServer();
                    server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(),
                            String.format(java.util.Locale.ROOT, "summon item %.1f %.1f %.1f {Item:{id:\"minecraft:emerald\",count:1}}",
                                    player.getX(), player.getY(), player.getZ()));
                })
                .createAndAdd(() -> context.assertTrue(count(f.player(), Items.OAK_LOG) == 2 && count(f.player(), Items.EMERALD) == 1,
                        "Both drops picked up: logs " + count(f.player(), Items.OAK_LOG) + ", emeralds " + count(f.player(), Items.EMERALD)))
                .createAndAdd(() -> {
                    settle(context);
                    var result = EvalItemSources.result();
                    var report = result.report();
                    context.assertTrue(report.violationCount() == 2, "Two items from no source: " + report.violations() + " / sources " + report.bySource() + " / origins " + result.origins() + " / untagged " + result.untaggedPickups() + " / gained " + report.gained() + " paid " + report.paid() + " lapsed " + report.lapsed());
                    context.assertTrue(report.violations().stream().anyMatch(v -> v.contains("gained 1 minecraft:diamond with no source")),
                            "The diamond put straight in: " + report.violations());
                    context.assertTrue(report.violations().stream().anyMatch(v -> v.contains("gained 1 minecraft:emerald with no source")),
                            "The command's emerald: " + report.violations());
                    context.assertTrue(report.violations().stream().noneMatch(v -> v.contains("oak_log")), "Not the block's logs: " + report.violations());
                    context.assertTrue(report.bySource().getOrDefault("pickup:Block.dropStack", 0L) == 2, "The logs paid by the block's drop: " + report.bySource());
                    context.assertTrue(result.origins().getOrDefault("command", 0L) == 1, "The emerald known for a command's: " + result.origins());
                    EvalItemSources.stop();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "itemSourcesPaid", tickLimit = 1600)
    public void aCraftAFurnacesOutputAChestTakenFromAndABlockBrokenAreAllPaidFor(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        FixtureGround.whenDone(context, EvalItemSources::stop);
        var min = context.getAbsolutePos(new BlockPos(-1, 0, -1));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Tally"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    ground(context);
                    var world = context.getWorld();
                    world.setBlockState(context.getAbsolutePos(CHEST), Blocks.CHEST.getDefaultState());
                    ((ChestBlockEntity) world.getBlockEntity(context.getAbsolutePos(CHEST))).setStack(0, new ItemStack(Items.COBBLESTONE, 3));
                    world.setBlockState(context.getAbsolutePos(FURNACE), Blocks.FURNACE.getDefaultState());
                    ((AbstractFurnaceBlockEntity) world.getBlockEntity(context.getAbsolutePos(FURNACE))).setStack(2, new ItemStack(Items.IRON_INGOT, 1));
                    world.setBlockState(context.getAbsolutePos(DIRT), Blocks.DIRT.getDefaultState());
                    var player = f.player();
                    stand(player, context);
                    player.getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStorage(f.source, f.id, context.getAbsolutePos(CHEST)) == 1, "Storage given"))
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Storage acknowledged"))
                .createAndAdd(() -> {
                    EvalItemSources.start(false);
                    EvalItemSources.snapshot(context.getWorld().getServer());
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeCraft(f.source, f.id, "oak_planks") == 1, "Craft requested");
                })
                .createAndAdd(() -> context.assertTrue(count(f.player(), Items.OAK_PLANKS) == 4, "Crafted / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE"), "Idle after the craft"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeSmelt(f.source, f.id, context.getAbsolutePos(FURNACE), "extract") == 1, "Extract requested"))
                .createAndAdd(() -> context.assertTrue(count(f.player(), Items.IRON_INGOT) == 1, "The furnace's output taken / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE"), "Idle after the furnace"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_take",
                        Map.of("item", "minecraft:cobblestone", "count", "3")) == 1, "Take requested / " + f.runtime.nativeActivityStatus(Actor.console(), f.id)
                        + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> context.assertTrue(count(f.player(), Items.COBBLESTONE) == 3, "Taken from the chest / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE"), "Idle after the take"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeBreak(f.source, f.id, context.getAbsolutePos(DIRT), true) == 1, "Break requested"))
                .createAndAdd(() -> context.assertTrue(count(f.player(), Items.DIRT) == 1, "The dirt broken and picked up / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    settle(context);
                    var report = EvalItemSources.result().report();
                    context.assertTrue(report.clean(), "Nothing from no source: " + report.violations());
                    context.assertTrue(report.gained() >= 9, "Planks, iron, cobblestone and dirt all counted: " + report.gained() + " / " + report.bySource());
                    context.assertTrue(report.bySource().getOrDefault("screen:PlayerScreenHandler", 0L) >= 4, "The craft: " + report.bySource());
                    context.assertTrue(report.bySource().getOrDefault("container.take:furnace", 0L) == 1, "The furnace: " + report.bySource());
                    context.assertTrue(report.bySource().getOrDefault("container.take", 0L) == 3, "The chest: " + report.bySource());
                    context.assertTrue(report.bySource().keySet().stream().anyMatch(k -> k.startsWith("pickup:")), "The dirt's drop: " + report.bySource());
                    EvalItemSources.stop();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
