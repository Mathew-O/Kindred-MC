package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtElement;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player chops only
 * in its patch and picks up only the drops it caused.
 *
 * Technical: Attributable drop collection, patch containment, and checkpointed native gathering (F-08).
 */
public final class NativeGatherGameTests implements FabricGameTest {

    private static BlockPos target(TestContext context) {
        return context.getAbsolutePos(new BlockPos(3, 2, 5));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void gatherBreaksScopedLogsAndCollectsAttributableDrops(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Gatherer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    context.assertEquals(0, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "Starts with 0 logs");
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 1, 0) == 1, "Native gather requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_gather"), "Gather completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.afterEvidence().contains("drops=verified") && action.afterEvidence().contains("collected=1"), "Drops verified and attributed");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Target log broken");
                    context.assertEquals(1, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "Drop physically picked up into inventory");
                    context.assertEquals(1, f.player().getMainHandStack().getDamage(), "Axe took 1 tool durability damage");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 360)
    public void partialGatheringThenPauseAndResumeDoesNotGatherOriginalCountAgain(TestContext context) {
        pauseAndResume(context, false);
    }

    /**
     * As {@link #partialGatheringThenPauseAndResumeDoesNotGatherOriginalCountAgain}, its last log's drop picked up as it walks
     * to it: on #144's CI (run 42805028977) the resumed gather broke its last log and walked to the drop, and the drop came into
     * its inventory mid-step. The resume's completion check (meant for the resume only, made every tick) then returned
     * complete before the drop's own branch stopped its walking and verified the break. Still walking, its body was never
     * settled, so its completion never settled either: ACTIVE on "Resumed native_gather …", its record DISPATCHED, to the end
     * of the test (F-22c53). Staged: before "continue", the player stood two blocks from the last log, in reach, so it
     * breaks it from there and walks to its drop, its drop held 1.1 blocks ahead of it, in pickup
     * reach, until it is picked up.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gatherResumedWalksToItsDrop", tickLimit = 360)
    public void resumedItsLastLogsDropPickedUpAsItWalksToItTheGatherCompletes(TestContext context) {
        pauseAndResume(context, true);
    }

    private static void pauseAndResume(TestContext context, boolean walkToDrop) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos log1 = context.getAbsolutePos(new BlockPos(3, 2, 4));
        BlockPos log2 = context.getAbsolutePos(new BlockPos(3, 2, 5));
        // Where its ticks go after "continue", named in the failure (F-22c48's diagnostics): the tick it was asked, the first
        // tick its work read "Resuming" (the paid activity's acknowledgement pending), "Resumed" (acknowledged, the runner
        // resumed) and "Completed".
        long[] at = {-1, -1, -1, -1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Checkpointer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(log1, Blocks.OAK_LOG.getDefaultState());
                    context.getWorld().setBlockState(log2, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, log1, log2, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.player().getInventory().setStack(1, new ItemStack(Items.OAK_LOG, 2)); // Starts with 2 logs
                    context.assertEquals(2, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "Carrying 2 initial logs");
                    // Ask for 2 logs: targetTotal = 2 + 2 = 4
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 2, 0) == 1, "Gather 2 logs requested");
                })
                .createAndAdd(() -> {
                    // Wait until first log breaks and is collected
                    var player = f.player();
                    context.assertTrue(player != null, "Player connected");
                    int count = ItemMoves.count(player.getInventory(), Items.OAK_LOG);
                    context.assertTrue(count >= 3, "Collected first log, now carrying at least 3");
                    // Pause via native lifecycle
                    f.runtime.nativeLifecycle(f.source, "pause", f.id);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE"), "Paused activity settled to IDLE");
                })
                .createAndAdd(() -> {
                    if (walkToDrop) {
                        // Two blocks off the log still standing, level with it and in sight.
                        BlockPos left = context.getWorld().getBlockState(log2).isOf(Blocks.OAK_LOG) ? log2 : log1;
                        context.assertTrue(context.getWorld().getBlockState(left).isOf(Blocks.OAK_LOG), "A log left to gather");
                        f.player().refreshPositionAndAngles(left.getX() + 0.5, left.getY(), left.getZ() + 2.5, 180, 0);
                        f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    }
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "continue", f.id) == 1, "Resume gather from checkpoint");
                    if (walkToDrop) {
                        // Its drop held 1.1 blocks ahead of it, within a player's pickup reach but past the 0.9 at which the gather
                        // stops walking to it: picked up the tick its pickup delay ends, as it walks.
                        context.runAtEveryTick(() -> {
                            var player = f.player();
                            if (player == null) return;
                            if (context.getWorld().getBlockState(log1).isOf(Blocks.OAK_LOG) || context.getWorld().getBlockState(log2).isOf(Blocks.OAK_LOG)) return;
                            for (var drop : context.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, player.getBoundingBox().expand(4),
                                    d -> d.getStack().isOf(Items.OAK_LOG) && d.isAlive())) {
                                drop.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ() - 1.1, 0, 0);
                                drop.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                            }
                        });
                    }
                    at[0] = context.getWorld().getTime();
                    context.runAtEveryTick(() -> {
                        if (at[0] < 0) return;
                        String work = String.valueOf(f.runtime.nativeActivityStatus(Actor.console(), f.id).work());
                        long now = context.getWorld().getTime();
                        if (at[1] < 0 && work.startsWith("Resuming")) at[1] = now;
                        if (at[2] < 0 && work.startsWith("Resumed")) at[2] = now;
                        if (at[3] < 0 && work.startsWith("Completed")) at[3] = now;
                    });
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    var player = f.player();
                    // What ran instead, and how far it got, named in the failure (F-22c37): on #87's CI (run 42565807178) the
                    // gather resumed and never completed within the test, and "Gather completed" said nothing more.
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_gather"), "Gather completed: "
                            + activity + "; logs " + (player == null ? "no body" : ItemMoves.count(player.getInventory(), Items.OAK_LOG))
                            + "; log blocks left " + context.getWorld().getBlockState(log1).isOf(Blocks.OAK_LOG) + "/" + context.getWorld().getBlockState(log2).isOf(Blocks.OAK_LOG)
                            + "; " + f.runtime.nativeLastResult(f.id) + "; said " + f.runtime.said(f.id) + "; " + ticks(context, at)
                            + "; action " + f.runtime.nativeActionStatus(Actor.console(), f.id).record() + "; " + f.runtime.nativeStatusLine(f.id));
                    context.assertTrue(player != null, "Player connected");
                    context.assertEquals(4, ItemMoves.count(player.getInventory(), Items.OAK_LOG), "Exactly 4 logs reached; did not gather 2 more to 5");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The ticks after "continue" until its work read "Resuming", "Resumed" and "Completed", and so far: where a slow resume spent them. */
    private static String ticks(TestContext context, long[] at) {
        long now = context.getWorld().getTime();
        java.util.function.LongFunction<String> since = t -> t < 0 ? "not yet" : "+" + (t - at[0]);
        return "after continue (" + (at[0] < 0 ? "not yet asked" : (now - at[0]) + " ticks ago") + "): pending " + since.apply(at[1])
                + ", acknowledged " + since.apply(at[2]) + ", completed " + since.apply(at[3]);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 240)
    public void gatherRefusesToHarvestOutsideDesignatedPatch(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos outsideLog = context.getAbsolutePos(new BlockPos(5, 2, 5));
        BlockPos patchPos = context.getAbsolutePos(new BlockPos(1, 2, 1));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Contained"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(outsideLog, Blocks.OAK_LOG.getDefaultState());
                    // Patch is at (1, 2, 1) where there are no logs
                    f.runtime.nativePatch(f.source, f.id, patchPos, patchPos, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeGather(f.source, f.id, 1, 0);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().contains("Blocked: no minecraft:oak_log left in the patch"),
                            "Refused to harvest outside patch");
                    context.assertTrue(context.getWorld().getBlockState(outsideLog).isOf(Blocks.OAK_LOG), "Outside log remained untouched");
                    context.assertEquals(0, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "No logs collected");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void brokenBlockWithoutPickupDoesNotCountAsCollectionSuccess(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Discarder"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    // Fill remaining slots with dirt so logs cannot be picked up into inventory
                    for (int slot = 1; slot < 36; slot++) {
                        f.player().getInventory().setStack(slot, new ItemStack(Items.DIRT, 64));
                    }
                    context.assertEquals(0, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "0 logs initially");
                    f.runtime.nativeGather(f.source, f.id, 1, 0);
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().contains("Breaking without pickup cannot complete gather"),
                            "Must block without pickup after drop timeout");
                    context.assertEquals(0, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "Inventory still has 0 logs");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Target log was broken");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 320)
    public void gatheredInventorySurvivesNativeSaveAndReload(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        CompletableFuture<?>[] loaded = new CompletableFuture<?>[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Saver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 1, 0) == 1, "Native gather requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_gather"), "Gather completed");
                    context.assertEquals(1, ItemMoves.count(f.player().getInventory(), Items.OAK_LOG), "Log gathered into inventory");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Runtime shutdown complete"))
                .createAndAdd(() -> {
                    loaded[0] = NativePlayerSaves.read(context.getWorld().getServer().getSavePath(WorldSavePath.PLAYERDATA), f.nativeId);
                })
                .createAndAdd(() -> context.assertTrue(loaded[0].isDone() && !loaded[0].isCompletedExceptionally(), "Save read complete"))
                .createAndAdd(() -> {
                    @SuppressWarnings("unchecked") var saved = (Optional<NativePlayerSaves.Bytes>) loaded[0].getNow(null);
                    var nbt = saved.orElseThrow().decode(context.getWorld().getServer());
                    var inventory = nbt.getList("Inventory", NbtElement.COMPOUND_TYPE);
                    int logs = 0;
                    for (int i = 0; i < inventory.size(); i++) {
                        var tag = inventory.getCompound(i);
                        var stack = ItemStack.fromNbt(context.getWorld().getRegistryManager(), tag).orElseThrow();
                        if (stack.isOf(Items.OAK_LOG)) {
                            logs += stack.getCount();
                        }
                    }
                    context.assertTrue(logs >= 1, "Native player save NBT contains the gathered oak log");
                })
                .completeIfSuccessful();
    }
}
