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

/** Attacked in deep water with a shore in reach, it gets out onto the shore rather than neither leaving nor fighting. */
public final class NativeWaterExitGameTests implements FabricGameTest {
    private static final int FIELD = 8;

    /**
     * Swimming in a pool two deep with dry ground a few blocks off and a drowned after it: it climbs out onto the shore.
     * In E-40's iteration 12 (`e40a12-66a7c0a`) on 8675309 Echo, in a flooded passage with a drowned about, had its walk
     * out of the water stopped "A hostile mob is attacking; fight back first" 502 times. With a way out of the water, the
     * goal gives that before any fight, so it neither left nor fought, and a drowned killed it there later. Staged: a
     * pool five square and two deep in a block of stone, its shore level with the water's top; daylight held; the player
     * swimming in the middle with a stone sword and the portal stage's kit, full health and breath; a drowned in the pool
     * after it. Passes once it stands on dry ground out of the water, alive, within 200 ticks: a swim of two or three
     * blocks and a climb out.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "waterExit", tickLimit = 800)
    public void attackedInAPoolWithAShoreInReachItGetsOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Swimmer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-FIELD, -3, -FIELD), o.add(FIELD, 8, FIELD));
                    FixtureGround.simulated(context, o.add(-FIELD, -3, -FIELD), o.add(FIELD, -3, FIELD));
                    for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -3; y <= 8; y++) {
                        boolean pool = Math.abs(x) <= 2 && Math.abs(z) <= 2 && (y == -1 || y == 0);
                        world.setBlockState(o.add(x, y, z), (pool ? Blocks.WATER : y <= 0 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    world.setTimeOfDay(6000);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(4, new ItemStack(Items.TORCH, 16));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(6, new ItemStack(Items.OAK_LOG, 8));
                    player.setHealth(20);
                    player.setAir(player.getMaxAir());
                    player.getHungerManager().setFoodLevel(20);
                    var drowned = EntityType.DROWNED.create(world);
                    drowned.refreshPositionAndAngles(o.getX() + 2.5, o.getY() - 1, o.getZ() + 0.5, 0, 0);
                    drowned.setPersistent();
                    drowned.setTarget(player);
                    world.spawnEntity(drowned);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isTouchingWater() && !f.player().isOnGround()
                        && PlayerRuntime.inDeepWater(f.player()), "Swimming in the pool"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && !player.isDead(), "The player died or is gone; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (watched[0]++ % 50 == 0) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append(" ").append((int) player.getHealth()).append("hp; ");
                    context.assertTrue(watched[0] <= 200, "Still in the water after 200 ticks: " + seen);
                    context.assertTrue(player.isOnGround() && !player.isTouchingWater(), "In the water");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Water exit: out after {} ticks at {}, {} health", watched[0],
                            f.player().getBlockPos().subtract(o).toShortString(), f.player().getHealth());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
