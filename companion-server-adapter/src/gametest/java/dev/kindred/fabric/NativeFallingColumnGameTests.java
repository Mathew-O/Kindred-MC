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

/** Buried under a column of gravel, it steps out from under the column instead of digging it down onto itself (F-25c4ck). */
public final class NativeFallingColumnGameTests implements FabricGameTest {
    /**
     * Hurt, in a tunnel, with gravel in its own cell and a column of it overhead, it gets out from under the column alive.
     * In E-40's iteration 14 (`e40a14-80b7150`) three of the first fifteen deaths were "suffocated in a wall": on 8675309
     * Kindred twice and on 31102026 Echo, each having walked or stood where a column of gravel or sand came down. Each
     * time the goal's step was "Dig out of the wall", breaking the block in its head room; the next block of the column
     * came down into it, and it died within a second or two, at the first or second block. Staged: a block of stone with a
     * one-wide tunnel running east and west through it; the player in the tunnel with gravel in both its cells and seven
     * blocks more over its head; a stone pickaxe and food, six health and hungry (food twelve: it does not heal), as they
     * were hurt; daylight held. Passes once the same body has been out of the wall and alive for forty ticks, within 600;
     * a death fails it, whatever the body it respawns in does after.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "fallingColumn", tickLimit = 1200)
    public void buriedUnderAColumnOfGravelItStepsOutFromUnderIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int[] watched = new int[1], out = new int[1];
        float[] lowest = {20};
        net.minecraft.server.network.ServerPlayerEntity[] buried = new net.minecraft.server.network.ServerPlayerEntity[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Buried"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-8, -3, -8), o.add(8, 14, 8));
                    FixtureGround.simulated(context, o.add(-8, -3, -8), o.add(8, -3, 8));
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -3; y <= 14; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 12 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The tunnel, east and west, two high.
                    for (int x = -6; x <= 6; x++) for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, 0), Blocks.AIR.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.setHealth(6);
                    player.getHungerManager().setFoodLevel(12);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In place"))
                .createAndAdd(() -> {
                    // The column comes down: gravel in both its cells and seven blocks more over its head.
                    var world = context.getWorld();
                    buried[0] = f.player();
                    for (int y = 0; y <= 8; y++) world.setBlockState(o.up(y), Blocks.GRAVEL.getDefaultState());
                    context.assertTrue(f.player().isInsideWall(), "Buried");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = buried[0];
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 20 == 0) seen.append(watched[0]).append(": ").append(progress).append("; ");
                    context.assertTrue(player.isAlive() && !player.isRemoved() && f.player() == player, "It died under the column: " + seen);
                    lowest[0] = Math.min(lowest[0], player.getHealth());
                    context.assertTrue(watched[0] <= 600, "Still under the column after 600 ticks: " + seen + " now " + progress);
                    out[0] = player.isInsideWall() ? 0 : out[0] + 1;
                    context.assertTrue(out[0] >= 40, "Not out of the wall for long enough yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Falling column: out after {} ticks, health down to {}; {}", watched[0], lowest[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
