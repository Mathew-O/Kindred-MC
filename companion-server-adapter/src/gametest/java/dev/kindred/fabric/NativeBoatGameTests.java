package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Boats (F-10f): across water the way players cross it, in their own boat. Fixtures build the water and hand over the
 * boat a player would have made; the launch, the rowing and the landing go through the paid activity and the ledger.
 */
public final class NativeBoatGameTests implements FabricGameTest {
    private static void forceChunks(net.minecraft.server.world.ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 8; x <= o.getX() + 40; x += 16) for (int z = o.getZ() - 8; z <= o.getZ() + 8; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    /**
     * A channel of water two deep and 25 long between two shores, high above the plot. From the near shore the player
     * puts its own boat on the water and gets in, rows across, gets out on the far shore and breaks the boat, which
     * drops as an item to be picked up again. Exactly one boat is spent on the water and none is conjured.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "boatCrossing", tickLimit = 1200)
    public void aBoatIsLaunchedRowedAcrossAndTakenBackOnTheFarShore(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        int[] rows = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rower"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    // The test server sets no simulation distance, so only players tick outside the test's own chunks;
                    // a live server ticks the ground round its players. The channel's chunks are kept ticking here.
                    forceChunks(world, o, true);
                    for (int x = -3; x <= 32; x++) for (int z = -5; z <= 5; z++) for (int y = -4; y <= 4; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -2; x <= 30; x++) for (int z = -3; z <= 3; z++) {
                        boolean channel = x >= 2 && x <= 26 && Math.abs(z) <= 2;
                        world.setBlockState(o.add(x, -3, z), Blocks.STONE.getDefaultState());
                        for (int y = -2; y <= -1; y++) {
                            world.setBlockState(o.add(x, y, z), channel ? Blocks.WATER.getDefaultState() : Blocks.STONE.getDefaultState());
                        }
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    player.getInventory().setStack(0, new ItemStack(Items.OAK_BOAT));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the near shore"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeBoat(f.source, f.id, "launch", 0, origin[0].add(3, -1, 0)) == 1, "Launch requested"))
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_boat"), "Launched: " + activity);
                    context.assertTrue(f.player().getVehicle() instanceof BoatEntity, "Aboard");
                    context.assertTrue(f.player().getInventory().count(Items.OAK_BOAT) == 0, "The one boat is on the water");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.state() == ActionRecord.State.VERIFIED, "Verified launch: " + record);
                })
                .createAndAdd(() -> {
                    // Rowing ends at the far shore, or at its time limit with the shore still ahead; then it rows on.
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    var result = f.runtime.nativeLastResult(f.id);
                    if (activity.work().startsWith("Completed native_row") && "shore".equals(result.get("ended"))) return;
                    // Counted only when taken, with nothing running: a request while the last row is still reserving
                    // its slice is refused.
                    if ((activity.phase().equals("IDLE") || activity.phase().equals("UNLOADED")) && rows[0] < 3
                            && f.runtime.nativeRow(f.source, f.id, origin[0].getX() + 29.5, origin[0].getZ() + 0.5, 20) == 1) {
                        rows[0]++;
                    }
                    context.assertTrue(false, "Rowing: " + f.player().getPos() + " " + activity + " " + result);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().getX() > origin[0].getX() + 22, "Across the channel: " + f.player().getPos());
                    context.assertTrue(f.runtime.nativeBoat(f.source, f.id, "land", -1, null) == 1, "Landing requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_boat"), "Landed: " + activity);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    context.assertTrue(!player.hasVehicle() && player.getX() > origin[0].getX() + 26.5 && player.getY() >= origin[0].getY() - 0.1,
                            "Out on the far shore: " + player.getPos());
                    var box = new Box(origin[0].add(20, -3, -3)).stretch(12, 6, 6);
                    context.assertTrue(world.getEntitiesByClass(BoatEntity.class, box, BoatEntity::isAlive).isEmpty(), "The boat is broken up");
                    int dropped = world.getEntitiesByClass(ItemEntity.class, box, item -> item.isAlive() && item.getStack().isOf(Items.OAK_BOAT))
                            .stream().mapToInt(item -> item.getStack().getCount()).sum();
                    int held = player.getInventory().count(Items.OAK_BOAT);
                    context.assertTrue(dropped + held == 1, "One boat to take back, none conjured: dropped " + dropped + ", held " + held);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.state() == ActionRecord.State.VERIFIED, "Verified landing: " + record);
                    forceChunks(world, origin[0], false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void forceSea(net.minecraft.server.world.ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 32; x <= o.getX() + 64; x += 16) for (int z = o.getZ() - 32; z <= o.getZ() + 32; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    /**
     * Under the surface of open sea with a boat, the End goal travelling on toward the stronghold across it (staged run
     * 13: a restart left the player in deep water, where it tried to put its boat in from below the surface and sank
     * while it tried). It swims up first, then puts the boat in and rows on, and never drowns.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "boatFromTheDeep", tickLimit = 1200)
    public void underTheSeaWithABoatItSurfacesBeforePuttingTheBoatIn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        String[] labels = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Diver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // Far off the test grid: batches reuse the same ground, and a sea left there floods the next tests.
                    BlockPos o = origin[0] = f.player().getBlockPos().add(4000, 24, 0).toImmutable();
                    forceSea(world, o, true);
                    // Open sea, five deep, with no shore within twenty blocks of the player.
                    for (int x = -24; x <= 56; x++) for (int z = -24; z <= 24; z++) {
                        world.setBlockState(o.add(x, -6, z), Blocks.STONE.getDefaultState());
                        for (int y = -5; y <= -1; y++) world.setBlockState(o.add(x, y, z), Blocks.WATER.getDefaultState());
                        for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() - 4, o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.OAK_BOAT));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(2, new ItemStack(Items.ENDER_EYE, 10));
                    inventory.setStack(3, new ItemStack(Items.STONE_SWORD));
                    // Where its eyes' flights crossed, far off across the sea, and the portal it came by, as a restart recalls them.
                    var memory = new PlayerMemoryStore.Memory();
                    memory.stronghold = new double[] {o.getX() + 400.5, o.getZ() + 0.5};
                    memory.overworldPortal = new PlayerMemoryStore.Place("minecraft:overworld", o.getX() - 300, o.getY(), o.getZ());
                    try {
                        var folder = f.directory.resolve("memory");
                        java.nio.file.Files.createDirectories(folder);
                        java.nio.file.Files.writeString(folder.resolve(f.id + ".json"), new com.google.gson.Gson().toJson(memory));
                    } catch (java.io.IOException failed) {
                        throw new AssertionError("Memory fixture could not be written", failed);
                    }
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!labels[0].contains(progress)) labels[0] += progress + " | ";
                    context.assertTrue(player.isAlive(), "Drowned: " + labels[0]);
                    context.assertTrue(player.getVehicle() instanceof BoatEntity && player.getX() > origin[0].getX() + 8,
                            "Afloat and rowing on: " + player.getPos() + " - " + progress);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Diver: afloat at {}, health {}, air {}; steps: {}", player.getPos(), player.getHealth(), player.getAir(), labels[0]);
                    context.assertTrue(player.getInventory().count(Items.OAK_BOAT) == 0, "Its one boat is on the water, none conjured");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    var world = context.getWorld();
                    for (int x = -24; x <= 56; x++) for (int z = -24; z <= 24; z++) for (int y = -6; y <= 1; y++) {
                        world.setBlockState(origin[0].add(x, y, z), Blocks.AIR.getDefaultState(), net.minecraft.block.Block.FORCE_STATE);
                    }
                    forceSea(world, origin[0], false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
