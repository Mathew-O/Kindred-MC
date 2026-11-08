package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.DrownedEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Attacked in deep water with no way out of it, breath to spare: it fights back there (F-25c4cb). */
public final class NativeWaterFightGameTests implements FabricGameTest {
    /**
     * Swimming in a pool it cannot get out of, its head above water, a mob on it: it fights back from the water while its
     * breath lasts, rather than have each strike stopped "up for air first" and given out again. On 404 in E-40's iteration
     * 11 (`e40a11-744c636`) Sol, in an aquifer's pool at Y=-50 by the lava it wanted, with a creeper after it, was given
     * "Fight creeper" 2,101 times and had 2,097 of them stopped so; between them every other step was stopped for the
     * mob attacking it, and it stayed there through eight stuck spells. Staged: a pool five square and three deep, walled
     * with obsidian two blocks over the water, lit, daylight held; the player swimming in it with a stone sword and the
     * portal stage's kit, and a drowned in the pool after it.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "waterFight", tickLimit = 1600)
    public void attackedInAPoolItCannotLeaveItFightsBackFromTheWater(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        DrownedEntity[] drowned = new DrownedEntity[1];
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Paddler"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-5, -5, -5), o.add(5, 6, 5));
                    FixtureGround.simulated(context, o.add(-5, 0, -5), o.add(5, 0, 5));
                    for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -5; y <= 6; y++) {
                        boolean pool = Math.abs(x) <= 2 && Math.abs(z) <= 2 && y >= -3 && y <= 2;
                        boolean wall = Math.abs(x) <= 3 && Math.abs(z) <= 3 && y >= -4 && y <= 2 && !pool;
                        var state = pool ? (y <= -1 ? Blocks.WATER : Blocks.AIR) : wall ? Blocks.OBSIDIAN : y <= -5 ? Blocks.STONE : Blocks.AIR;
                        world.setBlockState(o.add(x, y, z), state.getDefaultState());
                    }
                    for (int x = -3; x <= 3; x += 6) for (int z = -3; z <= 3; z += 6) world.setBlockState(o.add(x, 2, z), Blocks.GLOWSTONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() - 1.5, o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 16));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isTouchingWater(), "In the pool"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var world = context.getWorld();
                    var mob = EntityType.DROWNED.create(world);
                    mob.refreshPositionAndAngles(o.getX() + 2.5, o.getY() - 2.0, o.getZ() + 2.5, 0, 0);
                    mob.setPersistent();
                    world.spawnEntity(mob);
                    mob.setTarget(f.player());
                    drowned[0] = mob;
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && !player.isDead(), "The player died in the pool: " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 100 == 0) {
                        seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(o).toShortString())
                                .append(", health ").append(Math.round(player.getHealth())).append("; ");
                    }
                    context.assertTrue(!drowned[0].isAlive(), "The drowned still after it: " + seen + " now " + progress + "; "
                            + f.runtime.nativeActivityStatus(Actor.console(), f.id));
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Water fight: the drowned killed after {} ticks, the player's health {}", watched[0], Math.round(f.player().getHealth()));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
