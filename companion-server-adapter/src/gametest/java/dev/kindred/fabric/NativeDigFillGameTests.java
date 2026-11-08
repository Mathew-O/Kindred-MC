package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** A staircase step it will not take is not dug, so its tunnel's fill is not dug out again and again (F-25c4ch). */
public final class NativeDigFillGameTests implements FabricGameTest {
    private static final int FIELD = 6;

    /**
     * Digging down toward lava for its cast, the one way down onto ground it has set aside: it does not dig that step, and
     * so does not dig out the fill its tunnel puts in it, again and again. In E-40's iteration 12 (`e40a12-66a7c0a`) on 505
     * Kindred, at 16, 46, -45, had "Dig down toward iron: clear 16, 45, -44" and "Tunnel past liquid toward iron: fill 16,
     * 45, -44" by turns hundreds of times. The staircase cleared its step, then would not step down onto the landing
     * below it. The tunnel filled the step as a pit over a drop, and the staircase cleared it again. Then it waited "for a
     * feasible step", 29 of the evaluation's 92 stuck spells. Staged: a block of stone above lava's depth, the player in a
     * cell two high; under each other way down a pocket of water; the way down south onto a pocket of air set aside, as a
     * walk there that did not go sets it aside, given to the goal as the runtime gives it; daylight held; the cast route's
     * kit. Passes if in 1,500 ticks the step south is dug out at most twice.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "digFill", tickLimit = 2400)
    public void aStepItWillNotTakeIsNotDugSoItsTunnelsFillStays(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        BlockPos p = context.getAbsolutePos(new BlockPos(40, 13, 40));
        BlockPos step = p.add(0, -1, 1);
        BlockPos landing = p.add(0, -2, 1);
        int[] watched = new int[1];
        int[] dugOut = new int[1];
        boolean[] open = new boolean[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stairdigger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, p.add(-FIELD, -8, -FIELD), p.add(FIELD, 6, FIELD));
                    FixtureGround.simulated(context, p.add(-FIELD, -8, -FIELD), p.add(FIELD, -8, FIELD));
                    for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -8; y <= 6; y++) {
                        world.setBlockState(p.add(x, y, z), (y <= 2 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    world.setBlockState(p, Blocks.AIR.getDefaultState());
                    world.setBlockState(p.up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(p.up(2), Blocks.AIR.getDefaultState());
                    // Under the other three ways down, water in a pocket of its own: no step that way.
                    world.setBlockState(p.add(-1, -2, 0), Blocks.WATER.getDefaultState());
                    world.setBlockState(p.add(0, -2, -1), Blocks.WATER.getDefaultState());
                    world.setBlockState(p.add(1, -2, 0), Blocks.WATER.getDefaultState());
                    world.setBlockState(landing, Blocks.AIR.getDefaultState());
                    world.setTimeOfDay(6000);
                    var player = f.player();
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
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
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(p), "In its cell: at "
                        + f.player().getBlockPos().subtract(p).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    // Staged: a step down onto the landing that did not go, as the runtime gives one; it sets the landing aside.
                    var walk = new PortalRunCampaign.Task("native_goto", java.util.Map.of("x", Integer.toString(landing.getX()),
                            "y", Integer.toString(landing.getY()), "z", Integer.toString(landing.getZ()), "range", "0.4"), "Dig down toward lava: step down");
                    var campaign = f.runtime.campaignForTest(f.id);
                    campaign.taskFinished(walk, false, context.getWorld().getServer().getTicks(), "Cancelled");
                    context.assertTrue(campaign.blocked(landing), "The landing set aside");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && !player.isDead(), "The player died or is gone; " + seen);
                    boolean nowOpen = world.getBlockState(step).isAir();
                    if (nowOpen && !open[0]) dugOut[0]++;
                    open[0] = nowOpen;
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0] % 150 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(p).toShortString()).append("; ");
                    context.assertTrue(dugOut[0] <= 2, "The step south dug out " + dugOut[0] + " times in " + watched[0] + " ticks: " + seen + " now " + progress);
                    context.assertTrue(++watched[0] >= 1500, "Watched");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Dig fill: the step dug out {} times; at {}; {}", dugOut[0], f.player().getBlockPos().subtract(p).toShortString(), seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
