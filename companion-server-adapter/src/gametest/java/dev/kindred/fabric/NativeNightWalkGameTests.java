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

/** Dug in for the night, a step that would walk it out under the sky waits for daylight (F-25c4cd). */
public final class NativeNightWalkGameTests implements FabricGameTest {
    private static final int FIELD = 14;

    /**
     * Night on open stone, iron ore it saw lying on the surface ten blocks off, a stone pickaxe and the Nether portal goal:
     * it digs in for the night and stays below until morning, rather than walk back out under the sky to the ore and dig
     * in again when it gets there. In E-40's iteration 11 (`e40a11-744c636`) the night's dig-in was the largest stuck
     * cause left once the fixes from that iteration were made, 56 of the watchdog's lines: Kindred on 606, dug in, was
     * sent out of its shelter by the cast's next steps (into a cave open to the sky toward lava, to coal in view) and dug
     * in again, and Echo on 707 was stepped out of its shelter by the watchdog's move elsewhere and stepped back in.
     * Staged: a block of stone open to the sky, the night held, three iron ore set in its top ten blocks off; the
     * player with stone tools, cobblestone, torches and bread. Passes if it is below at the night's end having come back
     * out under the sky at most once, and goes for the ore in the morning.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "nightWalk", tickLimit = 4000)
    public void dugInForTheNightAWalkOutUnderTheSkyWaitsForDaylight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int surface = o.getY() + 7;
        int[] watched = new int[1];
        int[] surfaced = new int[1];
        boolean[] down = new boolean[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightowl"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-FIELD, -2, -FIELD), o.add(FIELD, 14, FIELD));
                    FixtureGround.simulated(context, o.add(-FIELD, 0, -FIELD), o.add(FIELD, 0, FIELD));
                    for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -2; y <= 14; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 6 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    for (int x = 9; x <= 11; x++) world.setBlockState(o.add(x, 6, 0), Blocks.IRON_ORE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, surface, o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(4, new ItemStack(Items.TORCH, 16));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(7, new ItemStack(Items.FURNACE));
                    inventory.setStack(8, new ItemStack(Items.OAK_LOG, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setTimeOfDay(14000);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockY() == surface, "On the stone"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(14000);
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    boolean below = feet.getY() <= surface - 2 && !world.isSkyVisible(feet.up());
                    if (below) down[0] = true;
                    else if (down[0] && world.isSkyVisible(feet.up())) {
                        surfaced[0]++;
                        down[0] = false;
                    }
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (watched[0] % 100 == 0) seen.append(watched[0]).append(": ").append(goal).append(" @ ").append(feet.subtract(o).toShortString()).append("; ");
                    context.assertTrue(surfaced[0] < 2, "Out from its night shelter under the sky and dug in again, " + surfaced[0] + " times: " + seen);
                    context.assertTrue(++watched[0] >= 1500, "Through the night");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(player.getBlockY() <= surface - 2, "Below for the night: at " + player.getBlockPos().subtract(o).toShortString() + " - " + goal + "; " + seen);
                    context.getWorld().setTimeOfDay(1000);
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(goal.contains("iron"), "Morning: on to the iron: " + goal);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
