package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** Stranded by walks elsewhere, its pickaxe gone, it walks back up the way it came (F-25c4ce). */
public final class NativeStrandedReturnGameTests implements FabricGameTest {

    /**
     * The staircase of {@code NativePortalCampaignGameTests.goalWalksBackUpTheWayItCameWhenItsPickaxeBreaksBelow}, with
     * three of its walks refused just before its pickaxe breaks below: it still walks back up the way it came, breaking
     * nothing (F-25c4ce). In PR #35's build on CI (`f2d93b7`) the iron went out of its view on the first step down,
     * and the goal went exploring toward it. The top of the fixture's wall, deep below sea level, read as a cave floor,
     * and three walks there were refused. Stranded by them, with its pickaxe gone on the next block it mined, it dug a
     * stair up by hand beside the staircase it had walked down: "Nothing dug on the way back up: 440 of 448 stone left".
     * Staged: that fixture's scene; those three refused walks are given to the goal as the runtime gives them, as it
     * starts on the ore at the foot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600)
    public void goalWalksBackUpTheWayItCameWhenStrandedByWalksElsewhere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] base = new BlockPos[1];
        long[] stone = new long[1];
        java.util.function.Supplier<Long> stoneLeft = () -> {
            BlockPos b = base[0];
            return BlockPos.stream(b.add(-2, 0, -2), b.add(10, 7, 2)).filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.STONE)).count();
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stairwalker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos b = base[0] = f.player().getBlockPos().toImmutable();
                    // As in that fixture: a rock mass eight high on ground of its own, a staircase cut down into it, iron ore at the
                    // foot of its last step in sight from the top, open sky over it and a wall two high round the top.
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 7; y++) {
                        world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    for (int k = 1; k <= 8; k++) for (int y = 8 - k; y <= 10 - k; y++) world.setBlockState(b.add(k, y, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, 1, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, 0, 0), Blocks.IRON_ORE.getDefaultState());
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = 8; y <= 24; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = 8; y <= 9; y++) {
                        if (x == -2 || x == 10 || z == -2 || z == 2) world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.refreshPositionAndAngles(b.getX() + 0.5, b.getY() + 8, b.getZ() + 0.5, -90, 30);
                    var inventory = player.getInventory();
                    ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
                    pickaxe.setDamage(pickaxe.getMaxDamage() - 1); // One block left in it.
                    inventory.setStack(0, pickaxe);
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 12));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(goal.contains("Mine iron ore at"), "Down the staircase, on to the ore: " + goal);
                    // Staged: three walks from here refused, as its walks to the wall's top were on CI.
                    BlockPos b = base[0];
                    var campaign = f.runtime.campaignForTest(f.id);
                    long tick = context.getWorld().getServer().getTicks();
                    for (BlockPos top : java.util.List.of(b.add(0, 10, 2), b.add(-2, 10, 0), b.add(-2, 10, -1))) {
                        var walk = new PortalRunCampaign.Task("native_goto", java.util.Map.of("x", Integer.toString(top.getX()),
                                "y", Integer.toString(top.getY()), "z", Integer.toString(top.getZ()), "range", "0.8"), "Explore the cave toward iron");
                        campaign.taskFinished(walk, false, tick, "No walkable route toward " + top.toShortString());
                    }
                    context.assertTrue(campaign.stranded(), "Stranded by the refused walks");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.IRON_PICKAXE) == 0,
                            "The pickaxe broke on the work below: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    context.assertTrue(f.player().getBlockY() <= base[0].getY() + 4, "Well down the staircase when it broke: " + f.player().getBlockPos().toShortString());
                    stone[0] = stoneLeft.get();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player.getBlockY() >= base[0].getY() + 6 && context.getWorld().isSkyVisible(player.getBlockPos().up()),
                            "Back up under open sky: " + player.getBlockPos().toShortString() + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(stoneLeft.get() == stone[0], "Nothing dug on the way back up: " + stoneLeft.get() + " of " + stone[0] + " stone left");
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
