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

/** At a flooded cave's edge on 606, staged from the run's own kept ground: out of the water and on, not in and out. */
public final class NativeWaterEdgeGameTests implements FabricGameTest {
    /**
     * In a channel of flowing water two deep at a cave's edge, wanting lava for its cast: it gets out and goes on, rather
     * than being sent out of the water again and again. In E-40's iteration 11 (`e40a11-744c636`) on 606 Kindred, at
     * 365, 52, -79, was given "Get out of the water: to dry ground" 3,789 times and had its dig toward lava stopped "In
     * deep water; up for air first" 1,782 times: its walk ashore ended on the stand beside the channel with its body still
     * over the flowing water, which carried it back in, and its walks on into the cave toward lava led back into the
     * water. Staged: the block of the run's kept world round it (read offline, diagnosis only), 25 by 23 by 27, the
     * water on the block's faces made stone so that it does not spill out; daylight held; the player in the channel where
     * Kindred was, with the cast route's kit. Passes if in 2,400 ticks it is sent out of the water at most 6 times and
     * stays alive.
     */
    @GameTest(templateName = "kindred_gametest:water-edge-606", skyAccess = true, batchId = "waterEdge606", tickLimit = 3600)
    public void atAFloodedCavesEdgeItGetsOutAndGoesOn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3500);
        // The structure is placed one above the test's origin: the file's 12, 12, 13 (365, 52, -79 in the world) is here
        // 12, 13, 13.
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 13, 13));
        int[] watched = new int[1];
        int[] sentOut = new int[1];
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Shoreline"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 180, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.TORCH, 16));
                    inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().getBlockPos().isWithinDistance(o, 1.5), "In the channel: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its time out at the water's edge: sent out of the water " + sentOut[0] + " times; " + seen);
                    context.assertTrue(!player.isDead(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (progress.contains("Get out of the water: to dry ground") && !last[0].contains("Get out of the water: to dry ground")) sentOut[0]++;
                    last[0] = progress;
                    if (watched[0] % 200 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    context.assertTrue(sentOut[0] <= 6, "Sent out of the water " + sentOut[0] + " times in " + watched[0] + " ticks: " + seen + " now " + progress);
                    context.assertTrue(++watched[0] >= 2400, "Watched");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Water edge: sent out of the water {} times; at {}; {}", sentOut[0],
                            f.player().getBlockPos().subtract(o).toShortString(), seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
