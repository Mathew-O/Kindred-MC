package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Under 8675309's lake with no pickaxe, staged from the run's own kept ground (F-25c4bu). */
public class NativeUnderLakeKeptGameTests implements FabricGameTest {
    /**
     * Its pickaxe broken, in a corridor under a lake, wanting logs from the surface: it digs out from under the lake by
     * hand and climbs. In E-40's iteration 9 (`e40a9-dd9b037`) on 8675309 Kindred, its pickaxe gone, climbed for the
     * surface at -26, 24, 55, one block of stone between its corridor and the lake; every way up refused by the water, its
     * way along under the lake dug only with a tool, it stepped up into the corridor's upper part and back down for 10
     * stuck spells (F-25c4bu). Staged: the block of the run's kept world round it (read offline, diagnosis only), 29 by
     * 17 by 21, the corridor Kindred had dug in it, the lake's water on the region's sides and floor made stone so that
     * it does not spill out; daylight held; the player where Kindred stood, with cobblestone and bread, no pickaxe and no
     * wood. Passes once it is three or more above where it stood with no water over its head.
     */
    @GameTest(templateName = "kindred_gametest:under-lake-8675309", skyAccess = true, batchId = "underLake8675309", tickLimit = 12000)
    public void itsPickaxeGoneUnderALakeItDigsOutByHandAndClimbs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 11900);
        // The structure is placed one above the test's origin: the file's 12, 4, 10 (-26, 24, 55 in the world) is here 12, 5, 10.
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 5, 10));
        long[] start = {0};
        int[] ups = {0};
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Lakebound"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
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
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null && f.player().isAlive(), "Its panel and body; " + ups[0] + " steps up; last " + last[0]);
                    String progress = bots.getFirst().goalProgress();
                    if (progress.endsWith(": step up") && !progress.equals(last[0])) ups[0]++;
                    last[0] = progress;
                    var player = f.player();
                    var world = context.getWorld();
                    boolean dryOverhead = true;
                    for (int dy = 2; dy <= 14; dy++) if (world.getFluidState(player.getBlockPos().up(dy)).isIn(FluidTags.WATER)) dryOverhead = false;
                    boolean out = player.isOnGround() && !player.isTouchingWater() && player.getBlockPos().getY() >= o.getY() + 3 && dryOverhead;
                    context.assertTrue(out, "Still under the lake at " + player.getBlockPos().subtract(o).toShortString() + ", "
                            + (context.getTick() - start[0]) + " ticks on, " + ups[0] + " steps up; " + progress);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive(), "Alive");
                    KindredMod.LOGGER.info("Under the lake 8675309: out {} ticks on at {}, {} steps up; {}", context.getTick() - start[0],
                            player.getBlockPos().subtract(o).toShortString(), ups[0], last[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
