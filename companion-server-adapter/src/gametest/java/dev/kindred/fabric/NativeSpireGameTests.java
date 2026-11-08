package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Stranded on the top of a spire of rock in a great cave, every way off it a long drop and a mob below: it gets off (F-25c4ca). */
public final class NativeSpireGameTests implements FabricGameTest {
    /**
     * Staged: a cave 27 high and 21 square in a block of stone, lit from its ceiling so nothing more spawns in it, a lava
     * pool in its floor and a zombie standing on the floor below, its AI off; the player on the top of a spire of rock one
     * block wide in the middle of the cave, twenty blocks over the floor and seven under the ceiling, with the cast route's
     * kit, its sword and water bucket at hand, no shield; its watchdog giving a spot up after 400 ticks. Passes once it is
     * off the spire, alive. Lit and with no mob below, it took the jump with its fall broken with water at once, on the
     * code before the fix too: with a mob near any landing and no shield, the jump is refused. On 505 in E-40's iteration 11 (`e40a11-744c636`) Sol's stair down for lava at Y=-10 came out in a cave
     * from Y=-2 down to Y=-48; dug out of the cave's wall, its last steps left it on a single block over a spire, every
     * way off it a drop of 16 to 40 blocks, and it waited there "for a feasible step" from tick 70,800 to the end of the
     * run, 21 stuck spells.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "spire", tickLimit = 4000)
    public void strandedOnASpireOverAMobItGetsOffOnceStranded(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 2, 60)).toImmutable();
        BlockPos top = o.add(0, 21, 0);
        boolean[] died = new boolean[1];
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Spirewalker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(12, 36, 12));
                    FixtureGround.simulated(context, o.add(-12, 0, -12), o.add(12, 0, 12));
                    for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = 0; y <= 36; y++) {
                        boolean cave = Math.abs(x) <= 10 && Math.abs(z) <= 10 && y >= 1 && y <= 27;
                        world.setBlockState(o.add(x, y, z), (cave ? Blocks.AIR : Blocks.STONE).getDefaultState());
                    }
                    for (int x = -8; x <= 8; x += 4) for (int z = -8; z <= 8; z += 4) world.setBlockState(o.add(x, 28, z), Blocks.GLOWSTONE.getDefaultState());
                    for (int y = 1; y <= 20; y++) world.setBlockState(o.add(0, y, 0), Blocks.STONE.getDefaultState());
                    for (int x = -7; x <= -5; x++) for (int z = -7; z <= -5; z++) world.setBlockState(o.add(x, 0, z), Blocks.LAVA.getDefaultState());
                    BlockPos below = o.add(4, 1, 4);
                    var zombie = EntityType.ZOMBIE.create(world);
                    zombie.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, 0, 0);
                    zombie.setAiDisabled(true);
                    zombie.setPersistent();
                    world.spawnEntity(zombie);
                    KindredMod.LOGGER.info("Spire zombie: alive {} at {}", zombie.isAlive(), zombie.getBlockPos().subtract(o).toShortString());
                    var player = f.player();
                    player.refreshPositionAndAngles(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(top), "On the spire"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.stuckTicksForFixtures(f.id, 400);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its time out on the spire: " + seen);
                    if (player.isDead()) died[0] = true;
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 200 == 0) {
                        seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    }
                    context.assertTrue(!died[0], "The player died; " + seen + " now " + progress);
                    context.assertTrue(player.isOnGround() && player.getBlockY() <= top.getY() - 4, "Still on the spire: " + seen + " now " + progress);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Spire: off it after {} ticks, at {}", watched[0], f.player().getBlockPos().subtract(o).toShortString());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
