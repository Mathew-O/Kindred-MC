package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Native death and vanilla respawn: work settles before the drop, nothing is restored, the identity carries on. */
public final class NativeRespawnGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void deathSettlesWorkDropsTheRealInventoryAndRespawnsTheSameIdentity(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos obsidian = context.getAbsolutePos(new BlockPos(5, 2, 3));
        BlockPos spawn = context.getAbsolutePos(new BlockPos(1, 2, 6));
        ServerPlayerEntity[] dead = new ServerPlayerEntity[1];
        Box[] dropArea = new Box[1];
        int[] mined = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Phoenix"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(obsidian, Blocks.OBSIDIAN.getDefaultState());
                    // Respawn inside this test's own area rather than at the shared world spawn other tests use.
                    f.player().setSpawnPoint(context.getWorld().getRegistryKey(), spawn, 0, true, false);
                    f.player().getInventory().setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    f.player().getInventory().setStack(4, new ItemStack(Items.COBBLESTONE, 5));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, obsidian) == 1, "A long obsidian break is requested");
                })
                .createAndAdd(() -> context.assertTrue(++mined[0] >= 30, "Mining for a while"))
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dropArea[0] = dead[0].getBoundingBox().expand(8, 4, 8);
                    dead[0].kill();
                    context.assertFalse(dead[0].isAlive(), "Killed mid-break");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "The same identity respawned as a fresh body");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh.getUuid().equals(dead[0].getUuid()), "Same native UUID");
                    context.assertTrue(fresh.getBlockPos().equals(spawn), "Vanilla respawn at its spawn point: " + fresh.getBlockPos());
                    context.assertTrue(fresh.getInventory().isEmpty(), "Nothing was restored into the respawned inventory");
                    context.assertTrue(fresh.getHealth() == fresh.getMaxHealth(), "Respawned at full health");
                    context.assertTrue(dropped(context, dropArea[0], Items.COBBLESTONE) == 5 && dropped(context, dropArea[0], Items.DIAMOND_PICKAXE) == 1,
                            "The real inventory lies where it died");
                    context.assertTrue(context.getWorld().getBlockState(obsidian).isOf(Blocks.OBSIDIAN), "The unfinished break changed nothing");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_break") && record.state() != ActionRecord.State.UNCERTAIN,
                            "The interrupted break settled without ambiguity: " + record);
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.phase() == StoredNativePlayer.Phase.ESTABLISHED && status.connected(),
                            "Still established and connected, not blocked for reconciliation: " + status);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static int dropped(TestContext context, Box area, Item item) {
        return context.getWorld().getEntitiesByClass(ItemEntity.class, area, drop -> drop.getStack().isOf(item))
                .stream().mapToInt(drop -> drop.getStack().getCount()).sum();
    }
}
