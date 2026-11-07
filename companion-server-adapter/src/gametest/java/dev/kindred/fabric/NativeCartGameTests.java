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

/**
 * In plain terms: a GameTest: in a real test world, a player is told to push an empty minecart along a rail
 * line. The cart is checked to pick up a villager standing on the line and stop inside a one-block-wide cell
 * at the end; once the test itself blocks the cell's front with a lectern and breaks the cart, the villager
 * is checked to be left inside the cell.
 *
 * Technical: A minecart pushed by a player along its rails (E-45e): Hal walks into an empty cart two blocks
 * behind a villager standing on the line; rolling, the cart takes the villager up and runs on eleven blocks
 * into a cell one block wide, stopping at the rail's end against the bed. With a lectern put across the
 * cell's front and the cart broken, the villager is set down inside the cell. The villager's AI is off so
 * that it stands where it was put; the lectern and the cart's breaking are the fixture's here (bringing a
 * villager home whole is {@link NativeBringGameTests}).
 */
public final class NativeCartGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "cartPush", tickLimit = 3000)
    public void aPushedCartTakesUpAVillagerAndRollsIntoItsCell(TestContext context) {
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
                    context.assertTrue(villager[0].getVehicle() == cart[0] && cart[0].getBlockPos().equals(o[0].add(5, 2, 18))
                            && cart[0].getVelocity().horizontalLength() < 0.01, "The cart with the villager in it stopped on the cell's floor");
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
                    var at = villager[0].getBlockPos().subtract(o[0]);
                    context.assertTrue(cart[0].isRemoved() && !villager[0].hasVehicle() && at.getX() == 5 && at.getZ() >= 18 && at.getZ() <= 19,
                            "Set down in the cell: at " + at.toShortString());
                    villager[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A cart rolling back down its line toward the player, as one does that ran out of way short of its cell: the push
     * goes on until the cart rolls away from the player, not ended at once for a cart that is moving. Taken for one
     * rolling on, each push of a bring's loaded cart that had rolled back ended before it began, at the speed it was
     * rolling back at, and after eight the bring gave up with the librarian short of its cell (the wandered bring
     * fixture in CI on 5b130a6: pushes ended at 0.14 to 0.20, the cart past the line's start each time). Staged: level
     * rails, a cart with a villager in it (its AI off), as a bring's is loaded, rolling back toward Hal at a quarter of a
     * block a tick from seven blocks off.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "cartPushBack", tickLimit = 1200)
    public void aCartRollingBackTowardThePlayerIsPushedOnNotTakenForOneRollingAway(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        MinecartEntity[] cart = new MinecartEntity[1];
        BlockPos[] o = new BlockPos[1];
        String[] ended = {null};
        VillagerEntity[] rider = new VillagerEntity[1];
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
                    for (int z = 2; z <= 20; z++) world.setBlockState(o[0].add(5, 2, z), Blocks.RAIL.getDefaultState());
                    var hal = f.player();
                    hal.getInventory().clear();
                    hal.refreshPositionAndAngles(o[0].getX() + 5.5, o[0].getY() + 2, o[0].getZ() + 4.5, 0, 0);
                    var minecart = EntityType.MINECART.create(world);
                    minecart.refreshPositionAndAngles(o[0].getX() + 5.5, o[0].getY() + 2, o[0].getZ() + 11.5, 0, 0);
                    world.spawnEntity(minecart);
                    cart[0] = minecart;
                    var v = EntityType.VILLAGER.create(world);
                    v.refreshPositionAndAngles(minecart.getX(), minecart.getY(), minecart.getZ(), 0, 0);
                    v.setAiDisabled(true);
                    world.spawnEntity(v);
                    v.startRiding(minecart, true);
                    rider[0] = v;
                })
                .createAndAdd(() -> context.assertTrue(rider[0].getVehicle() == cart[0], "The villager in the cart"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> {
                    // Rolling back toward Hal, south, as the push begins.
                    cart[0].setVelocity(0, 0, -0.25);
                    context.assertTrue(f.runtime.nativePush(f.source, f.id, cart[0].getUuid(), 10) == 1, "Push");
                })
                .createAndAdd(() -> {
                    var result = f.runtime.nativeLastResult(f.id);
                    if (ended[0] == null && "rolling".equals(result.get("ended"))) {
                        var v = cart[0].getVelocity();
                        var away = cart[0].getPos().subtract(f.player().getPos());
                        ended[0] = String.format(java.util.Locale.ROOT, "%s, moving %.2f %.2f, at %s", v.x * away.x + v.z * away.z > 0 ? "away" : "toward Hal",
                                v.x, v.z, cart[0].getPos().subtract(o[0].getX(), o[0].getY(), o[0].getZ()));
                        KindredMod.LOGGER.info("Cart pushed back: the push ended with the cart {}", ended[0]);
                    }
                    context.assertTrue(ended[0] != null, "The push ended: " + result);
                })
                .createAndAdd(() -> {
                    context.assertTrue(ended[0].startsWith("away"), "The cart rolling away from Hal as the push ended: " + ended[0]);
                    rider[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
