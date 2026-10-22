package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * The dragon goal past the End portal (E-39). Fixtures build what the player has reached by then (a portal room's frame
 * ring, an island's pillar and crystal) and hand over what it would carry; every step goes through the goal, the paid
 * activity and the ledger as in a run.
 */
public final class NativeEndGameTests implements FabricGameTest {
    /**
     * A portal room's ring: twelve frames round a 3x3 well at x 3..5, z 3..5, each facing in, all but the north three
     * holding an eye. Returns the north-west frame.
     */
    static BlockPos ring(TestContext context) {
        var world = context.getWorld();
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE_BRICKS.getDefaultState());
            for (int y = 2; y <= 6; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
        }
        for (int i = 3; i <= 5; i++) {
            frame(context, new BlockPos(i, 2, 2), Direction.SOUTH, false);
            frame(context, new BlockPos(i, 2, 6), Direction.NORTH, true);
            frame(context, new BlockPos(2, 2, i), Direction.EAST, true);
            frame(context, new BlockPos(6, 2, i), Direction.WEST, true);
        }
        return context.getAbsolutePos(new BlockPos(3, 2, 2));
    }

    private static void frame(TestContext context, BlockPos at, Direction facing, boolean eye) {
        context.getWorld().setBlockState(context.getAbsolutePos(at), Blocks.END_PORTAL_FRAME.getDefaultState()
                .with(EndPortalFrameBlock.FACING, facing).with(EndPortalFrameBlock.EYE, eye));
    }

    /** What the player remembers of the way here, as a restart would recall it: its portals and the End portal. */
    static void remember(NativePaidSkillsGameTests.Fixture f, BlockPos endPortal) {
        var memory = new PlayerMemoryStore.Memory();
        memory.overworldPortal = new PlayerMemoryStore.Place("minecraft:overworld", endPortal.getX() - 500, endPortal.getY() + 60, endPortal.getZ());
        memory.endPortal = new PlayerMemoryStore.Place("minecraft:overworld", endPortal.getX(), endPortal.getY(), endPortal.getZ());
        memory.stronghold = new double[] {endPortal.getX() + 0.5, endPortal.getZ() + 0.5};
        memory.pinned = true;
        try {
            var folder = f.directory.resolve("memory");
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve(f.id + ".json"), new com.google.gson.Gson().toJson(memory));
        } catch (java.io.IOException failed) {
            throw new AssertionError("Memory fixture could not be written", failed);
        }
    }

    /**
     * At the portal room with four eyes, the ring three short: it puts an eye into each empty frame, the portal opens,
     * and it steps in and comes out in the End. Exactly three eyes are spent.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endEntry", tickLimit = 1600)
    public void theRingIsFilledAndTheEndEntered(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] first = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Seeker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    first[0] = ring(context);
                    remember(f, first[0]);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(4, 2, 0));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.ENDER_EYE, 4));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.DRAGON) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getWorld().getRegistryKey() == World.END, "Into the End: "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    int filled = 0;
                    for (BlockPos at : BlockPos.iterate(context.getAbsolutePos(new BlockPos(2, 2, 2)), context.getAbsolutePos(new BlockPos(6, 2, 6)))) {
                        var state = world.getBlockState(at);
                        if (state.isOf(Blocks.END_PORTAL_FRAME) && state.get(EndPortalFrameBlock.EYE)) filled++;
                    }
                    KindredMod.LOGGER.info("Seeker: in the End at {}, frames filled {}, eyes left {}", player.getBlockPos(), filled,
                            player.getInventory().count(Items.ENDER_EYE));
                    context.assertTrue(filled == 12, "The ring is full: " + filled);
                    context.assertTrue(player.getInventory().count(Items.ENDER_EYE) == 1, "Three eyes went in, one is left");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Without the server's leave, the dragon goal is refused, and the End-portal goal stops at the portal. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endEntry", tickLimit = 200)
    public void withoutTheServersLeaveTheDragonGoalIsRefused(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waiter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.assertFalse(f.runtime.dragonFightAllowed(), "Off by default");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.DRAGON) == 0, "Refused while the fight is off");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The test server ticks no entity but players outside the test's own chunks; arrows and crystals need theirs. */
    static void forceChunks(net.minecraft.server.world.ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 16; x <= o.getX() + 48; x += 16) for (int z = o.getZ() - 32; z <= o.getZ() + 32; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    /**
     * An End crystal on its pillar: obsidian three wide and sixteen high, a bedrock block on top and the crystal on it,
     * twenty-one blocks off across open ground. With a bow and eight arrows the player shoots it down from the ground,
     * an arrow a shot, each on the arc vanilla's arrow flies; the crystal's blast is far from it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endCrystal", tickLimit = 1200)
    public void aCrystalOnItsPillarIsShotDownFromTheGround(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.decoration.EndCrystalEntity[] crystal = new net.minecraft.entity.decoration.EndCrystalEntity[1];
        int[] shots = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Archer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    forceChunks(world, o, true);
                    for (int x = -4; x <= 28; x++) for (int z = -6; z <= 6; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.END_STONE.getDefaultState());
                        for (int y = 2; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 20; x <= 22; x++) for (int z = -1; z <= 1; z++) for (int y = 2; y <= 16; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.OBSIDIAN.getDefaultState());
                    }
                    world.setBlockState(o.add(21, 17, 0), Blocks.BEDROCK.getDefaultState());
                    crystal[0] = net.minecraft.entity.EntityType.END_CRYSTAL.create(world);
                    crystal[0].refreshPositionAndAngles(o.getX() + 21.5, o.getY() + 18, o.getZ() + 0.5, 0, 0);
                    crystal[0].setShowBottom(true);
                    world.spawnEntity(crystal[0]);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 0.5, -90, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BOW));
                    inventory.setStack(1, new ItemStack(Items.ARROW, 8));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the ground"))
                .createAndAdd(() -> {
                    // A shot at a time; another after each that missed, five at most.
                    if (!crystal[0].isAlive()) return;
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (!activity.phase().equals("ACTIVE") && shots[0] < 5) {
                        shots[0]++;
                        context.assertTrue(f.runtime.nativeShoot(f.source, f.id, 0, crystal[0].getUuid()) == 1, "Shot requested");
                    }
                    context.assertTrue(false, "Shooting: " + shots[0] + " shots, " + activity + " " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int arrows = player.getInventory().count(Items.ARROW);
                    KindredMod.LOGGER.info("Archer: crystal down after {} shots, arrows left {}, health {}", shots[0], arrows, player.getHealth());
                    context.assertTrue(!crystal[0].isAlive(), "The crystal is down");
                    context.assertTrue(arrows == 8 - shots[0], "An arrow a shot, none conjured: " + arrows + " left after " + shots[0]);
                    context.assertTrue(player.getHealth() >= 20, "Far from the blast");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_shoot") && record.state() == ActionRecord.State.VERIFIED,
                            "A verified shot: " + record);
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 28; x++) for (int z = -6; z <= 6; z++) for (int y = 2; y <= 24; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState(), net.minecraft.block.Block.FORCE_STATE);
                    }
                    forceChunks(world, o, false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
