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

/** In an old mineshaft on 8675309, a cobweb over its head: cleared like any block, and it climbs (F-25c4cm). */
public final class NativeCobwebHeadRoomGameTests implements FabricGameTest {
    /**
     * Climbing for the surface from a mineshaft's corridor, with a cobweb in the cell over its head: it clears the cobweb
     * and steps up. In E-40's iteration 14 (`e40a14-80b7150`) on 8675309 Sol, wanting logs for a crafting table, climbed
     * from -4, -48, 52, in a mineshaft's corridor with a cobweb at -4, -46, 52. The climb counted the cobweb as head room,
     * having nothing to collide with, but a walk will not route through one: each step up was refused for "no walkable
     * route". With no way up it walked "to the wall", to -4, -48, 54 beside the mineshaft's lava, which no walk would go
     * to either: "No walkable route" 1,141 times in a minute and a half, until the watchdog moved it on. Staged: the block
     * of the run's kept world round it (read offline, diagnosis only), 21 by 13 by 19, the liquid on the block's faces made
     * stone; daylight held; the player where Sol stood, with the iron stage's kit and no wood or crafting table, and the
     * iron the goal wants already in hand, so that its first step is the climb for logs from there. Passes once
     * it is two or more above where it stood, within 1,200 ticks, alive.
     */
    @GameTest(templateName = "kindred_gametest:cobweb-8675309", skyAccess = true, batchId = "cobweb8675309", tickLimit = 1600)
    public void aCobwebOverItsHeadIsClearedAndItClimbs(TestContext context) {
        webbed(context, true);
    }

    /**
     * As above, with no sword: nothing it carries cuts a cobweb faster than a hand, a pickaxe no more than any, and it
     * clears the cobweb by hand, about twenty seconds, then climbs. Staged as above, the iron sword left out.
     */
    @GameTest(templateName = "kindred_gametest:cobweb-8675309", skyAccess = true, batchId = "cobwebByHand", tickLimit = 1600)
    public void withNoSwordItClearsTheCobwebByHandAndClimbs(TestContext context) {
        webbed(context, false);
    }

    private void webbed(TestContext context, boolean sword) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        // The structure is placed one above the test's origin: the file's 10, 4, 8 (-4, -48, 52 in the world) is here 10, 5, 8.
        BlockPos o = context.getAbsolutePos(new BlockPos(10, 5, 8));
        long[] start = {0};
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Webbed"))
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
                    // The iron stage, as Sol had it: pickaxes, the iron sword it carried, no wood, no table; and the iron the goal
                    // wants already in hand, so that its first step there is the climb for logs, as Sol's was.
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    if (sword) inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(4, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(5, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(6, new ItemStack(Items.RAW_IRON, 18));
                    inventory.setStack(7, new ItemStack(Items.COAL, 4));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In the corridor: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 100 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    context.assertTrue(watched[0] <= 1200, "Not up out of the corridor after 1,200 ticks: " + seen + " now " + progress);
                    context.assertTrue(player.getBlockPos().getY() >= o.getY() + 2, "Not two above where it stood yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cobweb 8675309 ({}): two up after {} ticks at {}; {}", sword ? "sword" : "by hand", watched[0],
                            f.player().getBlockPos().subtract(o).toShortString(), seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
