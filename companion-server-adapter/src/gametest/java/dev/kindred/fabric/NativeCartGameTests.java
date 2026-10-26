package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

/** An experiment (E-45e): a native player pushing a minecart along rails into a villager, and the loaded cart into a cell. */
public final class NativeCartGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "cartExperiment", tickLimit = 3000)
    public void experiment(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        MinecartEntity[] cart = new MinecartEntity[1];
        VillagerEntity[] villager = new VillagerEntity[1];
        int[] ticks = {0};
        BlockPos[] o = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    o[0] = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.simulated(context, o[0].add(-2, 0, -2), o[0].add(12, 0, 22));
                    for (int x = -2; x <= 12; x++) for (int z = -2; z <= 22; z++) {
                        world.setBlockState(o[0].add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o[0].add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A cell at the line's north end: walls either side, a back wall, a lintel over its front.
                    for (int z = 16; z <= 19; z++) for (int y = 2; y <= 4; y++) {
                        world.setBlockState(o[0].add(4, y, z), Blocks.COBBLESTONE.getDefaultState());
                        world.setBlockState(o[0].add(6, y, z), Blocks.COBBLESTONE.getDefaultState());
                    }
                    for (int y = 2; y <= 4; y++) world.setBlockState(o[0].add(5, y, 20), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(o[0].add(5, 4, 17), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(o[0].add(5, 2, 19), Blocks.WHITE_BED.getDefaultState()
                            .with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.SOUTH)
                            .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT), 18);
                    world.setBlockState(o[0].add(5, 2, 20), Blocks.AIR.getDefaultState(), 18);
                    // Rails from z 2 to 18 at x 5, south to north (the cell's floor at 18, its front at 17).
                    for (int z = 2; z <= 18; z++) world.setBlockState(o[0].add(5, 2, z), Blocks.RAIL.getDefaultState());
                    var minecart = EntityType.MINECART.create(world);
                    minecart.refreshPositionAndAngles(o[0].getX() + 5.5, o[0].getY() + 2, o[0].getZ() + 7.5, 0, 0);
                    world.spawnEntity(minecart);
                    cart[0] = minecart;
                    var v = EntityType.VILLAGER.create(world);
                    v.refreshPositionAndAngles(o[0].getX() + 5.5, o[0].getY() + 2, o[0].getZ() + 9.5, 0, 0);
                    v.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
                    v.setAiDisabled(true);
                    world.spawnEntity(v);
                    villager[0] = v;
                    var hal = f.player();
                    hal.getInventory().clear();
                    hal.getInventory().setStack(0, new ItemStack(Items.BREAD, 8));
                    hal.refreshPositionAndAngles(o[0].getX() + 5.5, o[0].getY() + 2, o[0].getZ() + 5.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativePush(f.source, f.id, cart[0].getUuid(), 10) == 1, "Push"))
                .createAndAdd(() -> {
                    if (++ticks[0] % 20 == 0) {
                        KindredMod.LOGGER.info("Cart: at {} moving {}, passengers {}; the villager riding {}; Hal at {}; {}", cart[0].getPos().subtract(o[0].getX(), o[0].getY(), o[0].getZ()),
                                cart[0].getVelocity(), cart[0].getPassengerList().size(), villager[0].hasVehicle(),
                                f.player().getPos().subtract(o[0].getX(), o[0].getY(), o[0].getZ()), f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(ticks[0] >= 600, "Pushed for thirty seconds");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // The front's rail taken up, a lectern there, and the cart broken: where does the villager land?
                    world.setBlockState(o[0].add(5, 2, 17), Blocks.LECTERN.getDefaultState());
                    cart[0].damage(world.getDamageSources().playerAttack(f.player()), 50);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cart: broken {}; the villager at {} riding {}", cart[0].isRemoved(),
                            villager[0].getPos().subtract(o[0].getX(), o[0].getY(), o[0].getZ()), villager[0].hasVehicle());
                    villager[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
