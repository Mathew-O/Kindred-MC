package dev.kindred.fabric;

import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Placement;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.TreeMap;

/**
 * In plain terms: a GameTest: a player builds a pen while a chicken (or a pig) stands in the pen's gateway. The gate cannot
 * go in while the animal is there; once it steps out, the gate is set and the pen is whole. An animal that never leaves
 * does not hold the build up for good: the gate is given up in about 20 s, as before.
 *
 * Technical: F-22c93. A pen's fence gate was refused "Block placement produced no effect or was obstructed" from every spot
 * round it in about a second, BuildRunner tried what it gave up on once more at once, and the pen was given up at 23 of 24:
 * #359's CI (run 43729280146, `NativeOwnRanchGameTests`' chickens) and #357's on main (run 43722834395, the pigs). Here an
 * animal with no AI stands in the gate cell and is moved out of the pen 300 ticks (15 s) after the other 23 blocks are
 * placed: the pen must end whole. A pig the same. A chicken that never leaves must not hold the build up for good.
 */
public final class NativeBuildObstructedGameTests implements FabricGameTest {
    /** Ticks the animal stays in the gateway once the rest of the pen stands. */
    private static final int HELD = 300;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildObstructed", tickLimit = 6000)
    public void aPensGateIsSetOnceTheChickenInItsGatewayStepsOut(TestContext context) {
        scene(context, EntityType.CHICKEN, HELD);
    }

    /** The same with a pig, as #357's CI found on main: "The pen could not be built: ... 23/24". */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildObstructedPig", tickLimit = 6000)
    public void aPensGateIsSetOnceThePigInItsGatewayStepsOut(TestContext context) {
        scene(context, EntityType.PIG, HELD);
    }

    /**
     * A guard: a chicken that never leaves the gateway. The wait is bounded: the build ends without its gate, as before,
     * within {@link #GIVEN_UP_BY} ticks of the rest standing, rather than waiting on for good.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildObstructedForGood", tickLimit = 6000)
    public void aPensGateWithAChickenInItsGatewayForGoodIsGivenUpInTime(TestContext context) {
        scene(context, EntityType.CHICKEN, -1);
    }

    /** Ticks after the rest of the pen stands by which a gate whose gateway is never cleared is given up. */
    private static final int GIVEN_UP_BY = 800;

    private static void scene(TestContext context, EntityType<? extends MobEntity> kind, int held) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5500);
        var pen = Generators.pen(7, 7, "oak");
        var needed = new TreeMap<String, Integer>();
        for (var b : pen.blocks()) needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        BlockPos[] origin = new BlockPos[1];
        BlockPos[] gate = new BlockPos[1];
        MobEntity[] animal = new MobEntity[1];
        boolean[] told = {false};
        long[] restAt = {-1}, outAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Henwife"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.keep(context, o.add(-8, 1, -8), o.add(16, 8, 16));
                    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 8));
                    for (var item : needed.entrySet()) {
                        inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), item.getValue()));
                    }
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 2.5, 0, 0);
                    origin[0] = o.add(4, 2, 4);
                    for (var b : pen.placed(BuildRunner.pos(origin[0]), 0)) {
                        if (b.spec().block().endsWith("_fence_gate")) gate[0] = BuildRunner.blockPos(b.at());
                    }
                    context.assertTrue(gate[0] != null, "The pen has a gate");
                    // An animal standing in the gateway, as the ranch's chickens and pigs wander into a gap left open.
                    animal[0] = context.spawnEntity(kind, gate[0].subtract(o));
                    animal[0].setAiDisabled(true);
                    animal[0].setInvulnerable(true);
                    animal[0].refreshPositionAndAngles(gate[0].getX() + 0.5, gate[0].getY(), gate[0].getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, pen, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    long now = world.getTime();
                    int placed = 0;
                    for (var b : pen.placed(BuildRunner.pos(origin[0]), 0)) {
                        if (BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) placed++;
                    }
                    boolean gateIn = world.getBlockState(gate[0]).isOf(Blocks.OAK_FENCE_GATE);
                    if (restAt[0] < 0 && placed >= pen.blocks().size() - 1 && !gateIn) restAt[0] = now;
                    // Held in the gateway a while after the rest stands, then out of the pen, six blocks off.
                    if (held >= 0 && restAt[0] >= 0 && outAt[0] < 0 && now - restAt[0] >= held) {
                        outAt[0] = now;
                        animal[0].refreshPositionAndAngles(origin[0].getX() - 4.5, origin[0].getY(), origin[0].getZ() - 4.5, 0, 0);
                    }
                    String status = f.runtime.buildStatus(f.id).orElse("");
                    if (f.runtime.buildStatus(f.id).isEmpty()) {
                        if (!told[0]) KindredMod.LOGGER.info("Build obstructed ({}): the rest at {}, the animal out at {}, done at {}, {} of {}, the gate {}; {}",
                                EntityType.getId(kind), restAt[0], outAt[0], now, placed, pen.blocks().size(), gateIn ? "in" : "not in", f.runtime.nativeLastResult(f.id));
                        told[0] = true;
                        if (held < 0) {
                            context.assertTrue(!gateIn && placed == pen.blocks().size() - 1 && restAt[0] >= 0 && now - restAt[0] <= GIVEN_UP_BY,
                                    "Given up in time, the rest whole: " + placed + " of " + pen.blocks().size() + ", the rest standing at "
                                            + restAt[0] + ", the build over at " + now + " (within " + GIVEN_UP_BY + ")");
                            return;
                        }
                        context.assertTrue(gateIn && placed == pen.blocks().size(), "The pen whole once the animal stepped out: " + placed + " of "
                                + pen.blocks().size() + ", the gate " + (gateIn ? "in" : "not in") + ", the rest standing at " + restAt[0]
                                + ", the animal out at " + outAt[0] + ", the build over at " + now + "; " + f.runtime.nativeLastResult(f.id));
                        return;
                    }
                    context.assertTrue(false, "Building: " + status);
                })
                .createAndAdd(() -> {
                    animal[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
