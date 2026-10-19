package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.WorkSetup;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Scoped work metadata does not install mining or grant server edit permission. */
public final class NativeWorkScopeGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 340)
    public void nativePatchRejectsUnsafeScopeStopsWorkAndPersistsWithoutAHome(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 2));
        BlockPos b = context.getAbsolutePos(new BlockPos(4, 3, 4));
        WorkSetup[] saved = new WorkSetup[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Scoped"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativePatch(f.source.withLevel(0), f.id, a, b, "minecraft:oak_log") == 0, "Foreign scope control denied");
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, a, b, "kindred:unknown") == 0, "Unknown resource denied");
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, a, b.add(1000, 0, 1000), "minecraft:oak_log") == 0, "Oversized scope denied");
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, a.withY(-1000), b, "minecraft:oak_log") == 0, "Out-of-build scope denied");
                    var nether = context.getWorld().getServer().getWorld(World.NETHER);
                    context.assertTrue(f.runtime.nativePatch(f.source.withWorld(nether), f.id, a, b, "minecraft:oak_log") == 0, "Wrong dimension denied");
                    BlockPos remote = a.add(4096, 0, 4096);
                    context.assertTrue(!context.getWorld().isChunkLoaded(remote), "Remote fixture area initially unloaded");
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, remote, remote, "minecraft:oak_log") == 0, "Unloaded work patch denied");
                    context.assertTrue(!context.getWorld().isChunkLoaded(remote), "Setup never loads its requested area");
                    context.assertTrue(f.runtime.nativeSetupStatus(Actor.console(), f.id).setup().revision() == 0, "Rejected changes preserve saved scope/revision");
                    f.player().getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
                    f.player().getHungerManager().setFoodLevel(8);
                    f.runtime.nativeSkill(f.source, f.id, "native_eat", 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isUsingItem(), "Actual paid food use started"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, b, a, "minecraft:oak_log") == 1, "Explicit patch accepts normalized bounds");
                    context.assertTrue(!f.player().isUsingItem() && f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Scope change synchronously cancels physical work and latches stop");
                    context.assertTrue(f.runtime.nativeSkill(f.source, f.id, "native_wait", 1) == 0, "Stopped/pending scope cannot run another task");
                    if (f.runtime.nativeSetupStatus(Actor.console(), f.id).pending()) {
                        context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 0, "Scope acknowledgement must reach the owner before resume");
                    }
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending()
                        && f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE"), "Both scope and cancelled activity settled"))
                .createAndAdd(() -> {
                    saved[0] = f.runtime.nativeSetupStatus(Actor.console(), f.id).setup();
                    context.assertTrue(saved[0].revision() == 1 && saved[0].patch().resourceId().equals("minecraft:oak_log")
                            && saved[0].patch().min().equals(new WorkSetup.BlockPoint(a.getX(), a.getY(), a.getZ()))
                            && saved[0].patch().max().equals(new WorkSetup.BlockPoint(b.getX(), b.getY(), b.getZ())), "Exact normalized patch persisted with one revision");
                    context.assertTrue(saved[0].home() == null && saved[0].storage() == null, "Native work metadata has no home/companion prerequisite");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 2, "Cancelled food not consumed by a late callback");
                    context.assertTrue(f.runtime.nativeSetupStatus(Actor.player(java.util.UUID.randomUUID()), f.id) == null, "Private scope status respects access");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Explicit resume after acknowledgement");
                    context.assertTrue(f.runtime.nativeMine(f.source, f.id, a, 0) == 0, "Patch does not grant server edit permission or a supported target");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Metadata closed"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Metadata reopened"))
                .createAndAdd(() -> {
                    context.assertTrue(saved[0].equals(f.runtime.nativeSetupStatus(Actor.console(), f.id).setup()), "Scope/revision survive actual metadata reopen");
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.stopped() && !status.connected(), "Reopen never clears stop or creates a body");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Final cleanup"))
                .completeIfSuccessful();
    }
}
