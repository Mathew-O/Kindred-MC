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

/** On 707, under a frozen lake's bed with no pickaxe: out from under it to dry ground, not to and fro beneath it (F-25c4cq). */
public final class NativeLakeBedClimbGameTests implements FabricGameTest {
    /**
     * Climbing by hand toward the surface for logs, its pickaxe worn out, it comes up under a lake's bed: it gets out from
     * under the lake onto dry ground under the sky, rather than stepping aside and back to the wall beneath it. In E-40's
     * iteration 15 (`e40a15-dbf4e5c`) on 707 Echo's stone pickaxe wore out branch mining at Y=19. It climbed by hand toward
     * the surface for logs for sticks and came up under the bed of a frozen lake, 18 across, at 29, 55, -132. There, for 10
     * stuck spells, it went "step aside" to 29, 55, -131 and "to the wall" back, 10,564 such lines in its console. Staged:
     * the block of the run's kept world round it (read offline, diagnosis only), 28 by 17 by 23, the liquid on the block's
     * faces made stone; daylight held; the player where Echo stood, with what Echo had left: a stone sword, seven raw
     * iron, cobblestone, coal and bread, no pickaxe and no wood. Passes once it stands on dry ground under the open sky,
     * within 6,000 ticks, alive.
     */
    @GameTest(templateName = "kindred_gametest:lakebed-707", skyAccess = true, batchId = "lakeBed707", tickLimit = 6600)
    public void underALakesBedWithNoPickaxeItGetsOutFromUnderTheLake(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 6500);
        // The structure is placed one above the test's origin: the file's 11, 5, 10 (29, 55, -132 in the world) is here 11, 6, 10.
        BlockPos o = context.getAbsolutePos(new BlockPos(11, 6, 10));
        int[] watched = new int[1];
        int[] turns = {0};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "LakeBed"))
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
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.RAW_IRON, 7));
                    inventory.setStack(4, new ItemStack(Items.COAL, 6));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "Under the lake: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if ((progress.endsWith(": step aside") || progress.endsWith(": to the wall")) && !progress.equals(last[0])) turns[0]++;
                    last[0] = progress;
                    if (watched[0]++ % 500 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    BlockPos at = player.getBlockPos();
                    String tail = seen.length() > 600 ? "..." + seen.substring(seen.length() - 600) : seen.toString();
                    context.assertTrue(watched[0] <= 6000, "Still under the lake after 6,000 ticks, " + turns[0] + " steps aside and to the wall: " + tail
                            + " now " + progress + " @ " + at.subtract(o).toShortString());
                    boolean out = player.isOnGround() && !player.isTouchingWater() && context.getWorld().isSkyVisible(at.up());
                    context.assertTrue(out, "Not out from under the lake yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Lake bed 707: out under the sky after {} ticks at {}, {} steps aside and to the wall; {}", watched[0],
                            f.player().getBlockPos().subtract(o).toShortString(), turns[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
