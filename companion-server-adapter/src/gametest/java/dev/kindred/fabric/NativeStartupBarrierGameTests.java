package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.persistence.ActionRef;
import dev.kindred.persistence.LedgerScan;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StartupLedgerSnapshot;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that AI players do not
 * start until unfinished business from last time is sorted out.
 *
 * Technical: Acceptance GameTests for Guide 02:
 * 1. Pending startup ledger read blocks ready and connect.
 * 2. Failed startup ledger read closes store and leaves runtime not ready.
 * 3. Unresolved action rows in startup ledger block native entry.
 * 4. Stale connection callback is fenced by ConnectionToken on stop/generation change.
 */
public final class NativeStartupBarrierGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void pendingStartupLedgerReadBlocksReadyAndConnect(TestContext context) {
        var server = context.getWorld().getServer();
        Path directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-barrier-fixture-" + UUID.randomUUID());
        var source = server.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(3.5, 2, 3.5)));

        CompletableFuture<StartupLedgerSnapshot> heldFuture = new CompletableFuture<>();
        PlayerRuntime.StartupLedgerReader delayedReader = (store, ids) -> heldFuture;

        PlayerRuntime runtime = new PlayerRuntime(server, directory, NativePlayerSaves::read,
                writer -> writer, delayedReader);
        runtime.start();

        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    // While startup ledger read is held, ready must be false
                    context.assertTrue(!runtime.isReady(), "Runtime ready must be false while startup ledger read is pending");
                    // Connect must be rejected while not ready
                    int res = runtime.nativeLifecycle(source, "connect", UUID.randomUUID());
                    context.assertTrue(res == 0, "Connect must be rejected while runtime is loading saved records");
                })
                .createAndAdd(() -> {
                    // Release the held future with an empty snapshot
                    heldFuture.complete(new StartupLedgerSnapshot(Map.of()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(runtime.isReady(), "Runtime must become ready after snapshot acknowledgement");
                    runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void failedStartupLedgerReadClosesStoreAndLeavesRuntimeNotReady(TestContext context) {
        var server = context.getWorld().getServer();
        Path directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-barrier-fail-" + UUID.randomUUID());
        var source = server.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(3.5, 2, 3.5)));

        PlayerRuntime.StartupLedgerReader failingReader = (store, ids) ->
                CompletableFuture.failedFuture(new IllegalStateException("Forced startup ledger read failure"));

        PlayerRuntime runtime = new PlayerRuntime(server, directory, NativePlayerSaves::read,
                writer -> writer, failingReader);
        runtime.start();

        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    // Failing reader must fail closed: runtime never becomes ready
                    context.assertTrue(!runtime.isReady(), "Runtime ready must remain false after read failure");
                    int res = runtime.createNative(source, "FailClosed");
                    context.assertTrue(res == 0, "Creation refused while runtime failed to load");
                    runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void unresolvedActionLedgerRowsBlockNativeEntry(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        CompletableFuture<?>[] injection = new CompletableFuture<?>[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "UnresolvedBlocker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "First runtime stopped"))
                .createAndAdd(() -> {
                    // Open database and inject an UNCERTAIN action row for this companion
                    injection[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store -> {
                        var lease = f.runtime.leaseForTest(f.id) != null ? f.runtime.leaseForTest(f.id) :
                                new dev.kindred.core.ActionLease(f.id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0);
                        ActionRecord prep = ActionRecord.prepare(UUID.randomUUID(), lease, "native_swap", "slot 0 to 1");
                        return store.prepareAction(prep)
                                .thenCompose(p -> store.transitionAction(prep.dispatched("dispatched"), p.revision()))
                                .thenCompose(d -> store.transitionAction(d.uncertain("Unresolved test row"), d.revision()))
                                .handle((res, err) -> store.closeAsync().thenApply(ignored -> {
                                    if (err != null) throw new java.util.concurrent.CompletionException(err);
                                    return res;
                                })).thenCompose(future -> future);
                    });
                })
                .createAndAdd(() -> context.assertTrue(injection[0].isDone() && !injection[0].isCompletedExceptionally(), "Unresolved row injected"))
                .createAndAdd(() -> {
                    // Reopen runtime; startup ledger scan must detect the unresolved row and block entry
                    f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory);
                    f.runtime.start();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.isReady(), "Reopened metadata ready");
                    // Assert ledger state is BLOCKED
                    context.assertTrue(f.runtime.ledgerStateForTest(f.id) == PlayerRuntime.LedgerCheckState.BLOCKED,
                            "Ledger state must be BLOCKED for companion with unresolved action");
                    // Connect must be refused
                    int res = f.runtime.nativeLifecycle(f.source, "connect", f.id);
                    context.assertTrue(res == 0, "Connect must be refused when ledger check state is BLOCKED");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Final cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void staleConnectionCallbackTokenFencedOnStop(TestContext context) {
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
        var server = context.getWorld().getServer();
        Path directory = server.getSavePath(WorldSavePath.ROOT).resolve("kindred-fencing-fixture-" + UUID.randomUUID());
        var source = server.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(3.5, 2, 3.5)));

        CompletableFuture<java.util.Optional<NativePlayerSaves.Bytes>> heldSave = new CompletableFuture<>();
        NativePlayerSaves.Reader delayedSaveReader = (saveDir, uuid) -> heldSave;

        PlayerRuntime runtime = new PlayerRuntime(server, directory, delayedSaveReader, writer -> writer);
        runtime.start();

        UUID[] companionId = new UUID[1];

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> {
                    int res = runtime.createNative(source, "FencedTester");
                    context.assertTrue(res == 1, "Native player creation accepted");
                })
                .createAndAdd(() -> {
                    // Creation dispatched connectNative with delayedSaveReader.
                    // Wait for asynchronous store creation to allocate the entry.
                    var statuses = runtime.nativeStatuses(Actor.console());
                    context.assertTrue(!statuses.isEmpty(), "Native player record created");
                    companionId[0] = statuses.getFirst().id();
                    context.assertTrue(runtime.pendingConnectionForTest(companionId[0]) != null, "Pending connection token exists");

                    // Stop companion or runtime immediately while save read is in-flight
                    runtime.nativeLifecycle(source, "stop", companionId[0]);
                    context.assertTrue(runtime.pendingConnectionForTest(companionId[0]) == null, "Pending connection retired by stop");
                })
                .createAndAdd(() -> {
                    // Now deliver the delayed save response (simulate stale callback)
                    heldSave.complete(java.util.Optional.empty());
                })
                .createAndAdd(() -> {
                    var statuses = runtime.nativeStatuses(Actor.console());
                    context.assertTrue(!statuses.isEmpty(), "Native player record still exists");
                    context.assertTrue(!statuses.getFirst().connected(), "Stale callback must NOT connect the body");
                    runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
