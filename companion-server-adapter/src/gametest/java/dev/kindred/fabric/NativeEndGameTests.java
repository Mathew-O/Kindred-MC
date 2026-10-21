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
}
