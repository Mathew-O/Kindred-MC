package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** On 505, a ledge high in a cavern's wall with an aquifer behind it: off it, not to and fro on it (F-25c4cp). */
public final class NativeCavernLedgeGameTests implements FabricGameTest {
    /** Echo's way in, as offsets from where it stood: each place its goal lines put it at, from the stair's top on. */
    private static final int[][] WAY_IN = {
            {-6, 10, 4}, {-6, 10, 5}, {-5, 10, 5}, {-5, 9, 5}, {-5, 6, 6}, {-5, 1, 10}, {-6, 0, 10}, {-5, -1, 10}, {-5, 0, 9},
            {-4, -1, 9}, {-4, -2, 8}, {-4, -1, 7}, {-4, 0, 6}, {-3, 0, 6}, {-3, -1, 6}, {-3, -2, 5}, {-3, -1, 5}, {-3, -1, 6},
            {-3, -2, 6}, {-3, -1, 6}, {-3, -2, 6}, {-3, -1, 5}, {-3, 0, 5}, {-3, 0, 4}, {-2, -1, 4}, {-2, -1, 3}, {-2, -2, 3},
            {-2, -3, 4}, {-2, -2, 4}, {-2, -2, 3}, {-2, -1, 3}, {-2, -2, 2}, {-2, -1, 2}, {-2, -1, 3}, {-2, 0, 3}, {-2, -1, 4},
            {-3, 0, 4}, {-3, 1, 4}, {-3, 1, 3}, {-2, 0, 3}, {-2, 0, 2}, {-2, 0, 1}, {-1, 0, 1}, {-1, -2, 1}, {-1, -1, 1},
            {-1, -1, 0}, {0, 0, 0}};

    /**
     * Digging down for iron into a cavern's wall, with the cavern open below and an aquifer behind the wall: it gets off
     * the ledge it came down onto, by some way it can take, rather than stepping down and back up on it. In E-40's
     * iteration 15 (`e40a15-dbf4e5c`) on 505 Echo dug its staircase down for iron from 13, 57, -43 into the wall between
     * an aquifer and a cavern some fifteen deep, and came onto ledges at 16 to 18, 45 to 47, -48 to -44. There, for 20 stuck
     * spells, it stepped down "toward iron" or "tunnel past liquid: advance", found "no way on from here" one below, and
     * climbed back the one step; each walk to the cave's floor was "No walkable route". Staged: the block of the run's kept
     * world round it (read offline, diagnosis only), 29 by 34 by 27, the liquid on the block's faces made stone; daylight
     * held; the player where Echo stood, 18, 47, -48, with what the goal's iron stage has, a stone pickaxe, sticks, planks
     * and a crafting table, with cobblestone, torches and bread; the way it came, as its trail, the 47 places its goal
     * lines put it at on its way in from the stair's top at 12, 57, -44 (from its console). Passes once it stands on ground
     * eight or more from where it stood, within 3,000 ticks, alive.
     */
    @GameTest(templateName = "kindred_gametest:ledge-505", skyAccess = true, batchId = "cavernLedge505", tickLimit = 3600)
    public void onACavernsLedgeWithAnAquiferBehindItItGetsOffTheLedge(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3500);
        // The structure is placed one above the test's origin: the file's 14, 20, 8 (18, 47, -48 in the world) is here 14, 21, 8.
        BlockPos o = context.getAbsolutePos(new BlockPos(14, 21, 8));
        long[] start = {0};
        int[] watched = new int[1];
        int[] noWay = {0};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ledge"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.STICK, 8));
                    inventory.setStack(5, new ItemStack(Items.OAK_PLANKS, 8));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "On the ledge: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var way = new java.util.ArrayList<BlockPos>();
                    for (int[] step : WAY_IN) way.add(o.add(step[0], step[1], step[2]));
                    f.runtime.campaignForTest(f.id).trailForTest(way);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (progress.contains("no way on from here") && !progress.equals(last[0])) noWay[0]++;
                    last[0] = progress;
                    if (watched[0]++ % 300 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    BlockPos at = player.getBlockPos();
                    String tail = seen.length() > 600 ? "..." + seen.substring(seen.length() - 600) : seen.toString();
                    context.assertTrue(watched[0] <= 3000, "Still on the ledge after 3,000 ticks, " + noWay[0] + " times no way on: " + tail
                            + " now " + progress + " @ " + at.subtract(o).toShortString());
                    context.assertTrue(player.isOnGround() && at.getSquaredDistance(o) >= 8 * 8, "Not off the ledge yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cavern ledge 505: off the ledge after {} ticks at {}, {} times no way on; {}", watched[0],
                            f.player().getBlockPos().subtract(o).toShortString(), noWay[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
