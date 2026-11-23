package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * With no pickaxe, its climb by hand comes up under a block it may not clear by hand: it steps aside round it, as a player
 * does, and climbs on from there (F-25c4dp). In E-40 iteration 29 (main `9a0921b5`), batch 2's 707 Kindred, its pickaxe
 * gone, climbed by hand from y −23 to y −13 in deepslate, clearing the cell over its head and pillaring up each time. Over
 * its head at y −11 was deepslate iron ore (hardness 4.5, over what it clears by hand, 3.5; read from its spell's replay
 * world, Kindred-78320, `claude/corpus-data-e40a29r-707-replay-1123` at `a5df02c0`). With iron ore also west of that and
 * north of its head, and plain deepslate east and south, no step up was offered; the plan fell to "Head back up toward
 * home", refused about a hundred times, and it waited "for a feasible step" until its spell.
 */
public final class NativeHandPillarAsideGameTests implements FabricGameTest {
    /**
     * In plain terms: an AI player with no pickaxe, climbing out of a shaft by hand, finds iron ore over its head that it
     * cannot break by hand; it digs into the plain rock beside it and climbs on from there, instead of standing still.
     *
     * Technical: F-25c4dp, as 707 Kindred (above). STAGED: deepslate round it, no sky; a shaft one wide, two high, its feet
     * on cobbled deepslate; deepslate iron ore over its head, west of that and north of its head, as in the replay world; the
     * player in the shaft with no pickaxe, two stacks of cobbled deepslate and eight bread; the portal goal, no logs; daylight
     * held; 20 ticks for the light to settle before the goal. Fails unless, within 6,000 ticks, its feet are three or more
     * above where it began: past the ore's level (by hand each deepslate block takes about 300 ticks, and the way aside and
     * up takes eight or more).
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "handPillarAside", tickLimit = 7000)
    public void underABlockItCannotClearByHandItStepsAsideAndClimbsOn(TestContext context) {
        shaft(context, false);
    }

    /**
     * In plain terms: an AI player with no pickaxe, shut in a shaft with iron ore over its head and on every side, has
     * nowhere to step aside to, and does nothing new: it waits, as before.
     *
     * Technical: F-25c4dp's negative control. STAGED as the case above, but the four cells beside its feet and its head, and
     * the four over those, deepslate iron ore too. Fails if, after 600 ticks, it has moved, any of the ore is gone, or a step
     * aside or a pocket beside it was offered.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "handPillarBoxed", tickLimit = 1200)
    public void boxedInByBlocksItCannotClearByHandItDoesNothingNew(TestContext context) {
        shaft(context, true);
    }

    private static void shaft(TestContext context, boolean boxed) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 6, 40));
        BlockState ore = Blocks.DEEPSLATE_IRON_ORE.getDefaultState();
        int[] watched = new int[1];
        int[] waited = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, boxed ? "Boxed" : "Climber"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // STAGED: deepslate round it, no sky; the shaft at o and over it; its floor cobbled deepslate.
                    FixtureGround.keep(context, o.add(-4, -2, -4), o.add(4, 10, 4));
                    for (BlockPos cell : BlockPos.iterate(o.add(-4, -2, -4), o.add(4, 10, 4))) world.setBlockState(cell, Blocks.DEEPSLATE.getDefaultState());
                    world.setBlockState(o, Blocks.AIR.getDefaultState());
                    world.setBlockState(o.up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(o.down(), Blocks.COBBLED_DEEPSLATE.getDefaultState());
                    // STAGED: iron ore over its head, west of that, and north of its head, as in the replay world.
                    world.setBlockState(o.up(2), ore);
                    world.setBlockState(o.add(-1, 2, 0), ore);
                    world.setBlockState(o.add(0, 1, -1), ore);
                    // The control: ore beside its feet and its head on every side, and over those.
                    if (boxed) for (Direction side : Direction.Type.HORIZONTAL) for (int dy = 0; dy <= 2; dy++) world.setBlockState(o.offset(side).up(dy), ore);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // STAGED: no pickaxe; blocks to pillar with, and food.
                    inventory.setStack(0, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
                    inventory.setStack(1, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.selectedSlot = 0;
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                // 20 ticks for the light to settle under the new stone, or the shaft reads as open to the sky.
                .createAndAdd(() -> context.assertTrue(waited[0]++ >= 20 && !context.getWorld().isSkyVisible(o.up()), "The light settled"))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In place"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died or is gone; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 50 == 0) seen.append(watched[0]).append(" y").append(player.getBlockY() - o.getY()).append(": ").append(progress).append("; ");
                    if (boxed) {
                        context.assertTrue(!progress.contains("step aside") && !progress.contains("pocket beside it"),
                                "Boxed in, it was offered a way aside: " + progress + "; " + seen);
                        context.assertTrue(watched[0] >= 600, "Watching");
                        context.assertTrue(player.getBlockPos().equals(o), "Boxed in, it moved: " + player.getBlockPos().subtract(o) + "; " + seen);
                        context.assertTrue(world.getBlockState(o.up(2)).equals(ore), "Boxed in, the ore over its head is gone");
                        for (Direction side : Direction.Type.HORIZONTAL) for (int dy = 0; dy <= 2; dy++) {
                            context.assertTrue(world.getBlockState(o.offset(side).up(dy)).equals(ore), "Boxed in, the ore beside it is gone");
                        }
                        return;
                    }
                    context.assertTrue(watched[0] <= 6000, "Not past the ore over its head after 6,000 ticks: " + seen + " now y"
                            + (player.getBlockY() - o.getY()) + " " + progress);
                    context.assertTrue(player.getBlockY() >= o.getY() + 3, "Not past the ore yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Hand pillar aside ({}): after {} ticks at {}; {}", boxed ? "boxed" : "open", watched[0],
                            f.player().getBlockPos().subtract(o), seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
