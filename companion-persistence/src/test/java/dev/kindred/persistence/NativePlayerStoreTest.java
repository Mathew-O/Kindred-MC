package dev.kindred.persistence;

import dev.kindred.core.Actor;
import dev.kindred.core.LifecycleState;
import dev.kindred.core.Personality;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that AI players and their settings are saved to and loaded from the database
 * correctly, including after an older database is upgraded.
 */
class NativePlayerStoreTest {
    @TempDir Path directory;
    private final List<SqliteCompanionStore> stores = new ArrayList<>();
    @AfterEach void close() { stores.forEach(store -> store.closeAsync().join()); }

    @Test void oneControllerCreatesThreePlayersAndConcurrentRetriesRemainOneIdentity() {
        var store = open(3);
        UUID controller = UUID.randomUUID();
        var request = request(controller);
        var retries = IntStream.range(0, 24).parallel().mapToObj(ignored -> store.createNativePlayer(request).join()).toList();
        assertEquals(1, retries.stream().map(player -> player.metadata().companion().id()).distinct().count());
        var second = store.createNativePlayer(request(controller)).join();
        var third = store.createNativePlayer(request(controller)).join();
        assertNotEquals(second.metadata().companion().id(), third.metadata().companion().id());
        assertEquals(3, store.nativePlayers().join().size());
        assertTrue(store.findByOwner(controller).join().isEmpty());
        assertEquals(retries.getFirst(), store.createNativePlayer(request).join());
        failure(IllegalStateException.class, store.createNativePlayer(request(controller)));
        for (var player : store.nativePlayers().join()) {
            assertEquals(player.metadata().companion().id(), player.metadata().companion().ownerId());
            assertTrue(player.access().mayControl(Actor.player(controller)));
            assertFalse(player.access().mayControl(Actor.player(UUID.randomUUID())));
            assertEquals(StoredNativePlayer.Phase.ALLOCATED, player.phase());
            assertNull(player.saveReceipt());
        }
    }

    @Test void operationTypeControllerWorldAndPayloadConflictsAreRejected() {
        var store = open(10);
        var request = request(UUID.randomUUID());
        store.createNativePlayer(request).join();
        failure(SecurityException.class, store.createNativePlayer(new PlayerCreationRequest(request.operationId(),
                UUID.randomUUID(), request.displayName(), request.worldId(), request.dimension())));
        failure(SecurityException.class, store.createNativePlayer(new PlayerCreationRequest(request.operationId(),
                request.controllerId(), request.displayName(), UUID.randomUUID(), request.dimension())));
        failure(SecurityException.class, store.createNativePlayer(new PlayerCreationRequest(request.operationId(),
                request.controllerId(), "Other", request.worldId(), request.dimension())));
        failure(SecurityException.class, store.create(new CreationRequest(request.operationId(), request.controllerId(),
                request.displayName(), request.worldId(), request.dimension())));
        var legacy = new CreationRequest(UUID.randomUUID(), request.controllerId(), "Legacy", request.worldId(), request.dimension());
        store.create(legacy).join();
        failure(SecurityException.class, store.createNativePlayer(new PlayerCreationRequest(legacy.operationId(),
                legacy.ownerId(), legacy.displayName(), legacy.worldId(), legacy.dimension())));
        assertEquals(1, store.nativePlayers().join().size());
    }

    @Test void saveReceiptsUseOptimisticRevisionsAndBlockedBodiesNeverAutoRecover() {
        var store = open(4);
        var allocated = store.createNativePlayer(request(UUID.randomUUID())).join();
        UUID id = allocated.metadata().companion().id();
        UUID receipt = UUID.randomUUID();
        var established = store.establishNativePlayer(id, receipt, 0).join();
        assertEquals(receipt, established.saveReceipt());
        assertEquals(1, established.revision());
        failure(ConcurrentModificationException.class, store.establishNativePlayer(id, UUID.randomUUID(), 0));
        var blocked = store.blockNativePlayer(id, "Missing native save", 1).join();
        assertEquals(StoredNativePlayer.Phase.BLOCKED, blocked.phase());
        assertEquals(receipt, blocked.saveReceipt());
        failure(IllegalStateException.class, store.establishNativePlayer(id, UUID.randomUUID(), 2));
        store.closeAsync().join();
        var restored = open(4).nativePlayers().join().getFirst();
        assertEquals(blocked, restored);
    }

    @Test void nativeCreationPreservesLegacyOwnerRetryAndDefaults() {
        var store = open(4);
        UUID controller = UUID.randomUUID();
        var legacyRequest = new CreationRequest(UUID.randomUUID(), controller, "Legacy", UUID.randomUUID(), "minecraft:overworld");
        var legacy = store.create(legacyRequest).join();
        var nativePlayer = store.createNativePlayer(request(controller)).join();
        assertEquals(legacy, store.create(new CreationRequest(UUID.randomUUID(), controller, "Ignored retry name",
                legacyRequest.worldId(), legacyRequest.dimension())).join());
        UUID id = nativePlayer.metadata().companion().id();
        assertTrue(store.profiles().join().stream().anyMatch(profile -> profile.companionId().equals(id)));
        assertTrue(store.mannerisms().join().stream().anyMatch(profile -> profile.companionId().equals(id)));
        assertTrue(store.workSetups().join().stream().anyMatch(setup -> setup.companionId().equals(id)));
        assertNotNull(store.limit(id).join());
    }

    @Test void additiveSchemaSixMigrationPreservesLegacyStopAndPreferences() throws Exception {
        var store = open(4);
        var legacy = store.create(new CreationRequest(UUID.randomUUID(), UUID.randomUUID(), "Existing",
                UUID.randomUUID(), "minecraft:overworld")).join();
        UUID id = legacy.companion().id();
        var stopped = store.save(legacy.companion().withStopLatched(true).withLifecycle(LifecycleState.PAUSED), 0).join();
        var profile = store.profiles().join().getFirst();
        var changed = store.saveProfile(profile.withTrait(Personality.Trait.MINING, 83), profile.revision()).join();
        store.closeAsync().join();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path()); var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE activity_limits DROP COLUMN window_start");
            sql.execute("ALTER TABLE activity_limits DROP COLUMN window_end");
            sql.execute("DROP TABLE IF EXISTS stock_reservations");
            sql.execute("DROP TABLE IF EXISTS shared_tasks");
            sql.execute("DROP TABLE IF EXISTS shared_projects");
            sql.execute("DROP TABLE native_players");
            sql.execute("ALTER TABLE creation_operations DROP COLUMN request_fingerprint");
            sql.execute("PRAGMA user_version = 6");
        }
        var upgraded = open(4);
        assertEquals(stopped, upgraded.find(id).join().orElseThrow());
        assertEquals(changed, upgraded.profiles().join().getFirst());
        assertTrue(upgraded.nativePlayers().join().isEmpty());
        upgraded.createNativePlayer(request(legacy.companion().ownerId())).join();
        assertEquals(stopped, upgraded.findByOwner(legacy.companion().ownerId()).join().orElseThrow());
    }

    private Path path() { return directory.resolve("companions.sqlite"); }
    private SqliteCompanionStore open(int maximum) {
        var store = SqliteCompanionStore.open(path(), maximum).join();
        stores.add(store);
        return store;
    }
    private static PlayerCreationRequest request(UUID controller) {
        return new PlayerCreationRequest(UUID.randomUUID(), controller, "AI player", UUID.randomUUID(), "minecraft:overworld");
    }
    private static void failure(Class<? extends Throwable> expected, CompletableFuture<?> future) {
        assertInstanceOf(expected, assertThrows(CompletionException.class, future::join).getCause());
    }
}
