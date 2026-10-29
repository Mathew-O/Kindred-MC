package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Placement;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.TreeMap;

/**
 * In plain terms: a GameTest: in a real test world, a player's work is checked to survive a server restart. A
 * hut build, and in the second test a farm field, is interrupted partway, the player is stopped and the
 * server's runtime started again on the same world; when the player reconnects it takes the work up again by
 * itself but stays stopped until resumed, then finishes it with nothing placed, sown or spent twice.
 *
 * Technical: Work kept across a restart (F-13): what an operator set a player to is saved with it, and taken
 * up again from the world when the server's runtime starts again and the player reconnects. Nothing is
 * placed, sown or spent twice.
 */
public final class NativeWorkRestartGameTests implements FabricGameTest {
    /** A check retried each tick until it passes or the test runs out, its first failure kept as the message. */
    private static void check(TestContext context, String[] first, boolean ok, String message) {
        if (ok) return;
        if (first[0] == null) first[0] = message;
        context.assertTrue(false, first[0]);
    }

    /**
     * A build kept across a restart (F-13): the hut, started by its plan's name, is interrupted once the player has a
     * scaffold up. The player is stopped and the server's runtime shut down and started again on the same world. Its
     * work read back, the player takes the hut up again by itself as it reconnects, and stays stopped until its
     * administrator resumes it; then it finishes the hut. Every block is in its state and none was placed twice: every
     * block it was given went into the hut, none left over and none short. The scaffold it put up before the restart is
     * taken down as its own.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildRestart", tickLimit = 16000)
    public void aHutInterruptedByARestartIsTakenUpAgainAndFinished(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 15500);
        String[] first = {null};
        var hut = Generators.hut(5, 5, 3);
        var needed = new TreeMap<String, Integer>();
        for (var b : hut.blocks()) needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        BlockPos[] origin = new BlockPos[1];
        int[] placedBefore = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 16; x++) for (int z = -8; z <= 16; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 8));
                    for (var item : needed.entrySet()) {
                        int left = item.getValue();
                        while (left > 0) {
                            int count = Math.min(64, left);
                            inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), count));
                            left -= count;
                        }
                    }
                    inventory.setStack(slot, new ItemStack(Items.DIRT, 64));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 2.5, 0, 0);
                    origin[0] = o.add(4, 2, 4);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hut, origin[0], 0, "hut-5x5x3") == 1, "Build started by its plan's name");
                })
                .createAndAdd(() -> {
                    // On its way up: a scaffold of its dirt two high or more, the hut not yet done.
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    int dirt = 0;
                    for (BlockPos pos : BlockPos.iterate(o.add(-8, 3, -8), o.add(16, 16, 16))) if (world.getBlockState(pos).isOf(Blocks.DIRT)) dirt++;
                    context.assertTrue(dirt >= 2 && f.runtime.buildStatus(f.id).isPresent(), "A scaffold up, the hut not done: " + dirt + " dirt");
                    int placed = 0;
                    for (var b : hut.placed(BuildRunner.pos(origin[0]), 0)) {
                        if (BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at()))).sameAs(b.spec())) placed++;
                    }
                    placedBefore[0] = placed;
                })
                .createAndAdd(() -> {
                    // Stopped between its steps, as an administrator stops it: a stop in the middle of a step that
                    // may have placed a block leaves an outcome to reconcile, and the player is held for that instead.
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue((activity.phase().equals("IDLE") || activity.phase().equals("UNLOADED")) && !activity.work().startsWith("Running"),
                            "Between its steps: " + activity.phase() + " " + activity.work());
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue((activity.phase().equals("UNLOADED") || activity.phase().equals("IDLE")) && !activity.work().startsWith("Running"),
                            "Stopped and settled before the restart: " + activity.phase() + " " + activity.work());
                    KindredMod.LOGGER.info("Mason: {} of the hut's {} blocks placed before the restart", placedBefore[0], hut.blocks().size());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "The runtime shut down"))
                .createAndAdd(() -> {
                    f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory);
                    f.runtime.start();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Started again"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 1, "Reconnect requested"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().connected() && f.player() != null, "Reconnected");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.buildStatus(f.id).isPresent(), "The hut taken up again by itself");
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Still stopped: taking its work up does not resume it");
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    check(context, first, player != null, "The player still on the server");
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hut.placed(BuildRunner.pos(origin[0]), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " blocks not in their state: " + report);
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    var dirt = new java.util.ArrayList<String>();
                    for (BlockPos pos : BlockPos.iterate(o.add(-8, 2, -8), o.add(16, 16, 16))) {
                        if (world.getBlockState(pos).isOf(Blocks.DIRT)) dirt.add(pos.subtract(origin[0]).toShortString());
                    }
                    check(context, first, dirt.isEmpty(), "The scaffold taken down, that of before the restart too: dirt left at " + dirt);
                    var inventory = player.getInventory();
                    var left = new TreeMap<String, Integer>();
                    for (var item : needed.keySet()) {
                        int count = inventory.count(Registries.ITEM.get(Identifier.of(item)));
                        if (count != 0) left.put(item, count);
                    }
                    check(context, first, left.isEmpty(), "Every block it was given went into the hut once: left " + left);
                    KindredMod.LOGGER.info("Mason: built {} at {} across a restart, {} blocks placed before it", hut.name(), origin[0].toShortString(), placedBefore[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The farm ward's field kept across a restart (F-13), as {@link NativeCompoundGameTests} builds it: once the channel
     * is wet and a dozen cells are sown, the player is stopped and the server's runtime shut down and started again on
     * the same world. The player takes the field up again by itself as it reconnects, and is resumed by its
     * administrator; it finishes the field, the channel not poured again and no cell sown twice: one seed a cell, all
     * told.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "fieldRestart", tickLimit = 14000)
    public void aFieldInterruptedByARestartIsTakenUpAgainAndFinished(TestContext context) {
        NativeCompoundGameTests.field(context, true);
    }
}
