package dev.kindred.persistence;
import dev.kindred.core.*;
import dev.kindred.scheduler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch02NativeIdentityTest {
    @TempDir Path dir;
    @Test void operationRetryAndSaveReceiptSurviveReopenWithoutNewBody() {
        Path db=dir.resolve("native.sqlite");var request=new PlayerCreationRequest(UUID.randomUUID(),UUID.randomUUID(),"fixture",UUID.randomUUID(),"minecraft:overworld");StoredNativePlayer established;
        var store=SqliteCompanionStore.open(db,3).join();try {
            var created=store.createNativePlayer(request).join();UUID id=created.metadata().companion().id();
            assertEquals(created,store.createNativePlayer(request).join());established=store.establishNativePlayer(id,UUID.randomUUID(),0).join();
        }finally{store.closeAsync().join();}
        store=SqliteCompanionStore.open(db,3).join();try {
            var restored=store.createNativePlayer(request).join();assertEquals(established,restored);assertEquals(1,store.nativePlayers().join().size());
            assertTrue(restored.access().mayControl(Actor.player(request.controllerId())));assertFalse(restored.access().mayControl(Actor.player(UUID.randomUUID())));
        }finally{store.closeAsync().join();}
    }
    @Test void conflictingOperationAndStaleReceiptCannotRewriteIdentity() {
        var store=SqliteCompanionStore.open(dir.resolve("native.sqlite"),3).join();try {
            var r=new PlayerCreationRequest(UUID.randomUUID(),UUID.randomUUID(),"fixture",UUID.randomUUID(),"minecraft:overworld");
            var created=store.createNativePlayer(r).join();UUID id=created.metadata().companion().id();var established=store.establishNativePlayer(id,UUID.randomUUID(),0).join();
            assertThrows(CompletionException.class,()->store.createNativePlayer(new PlayerCreationRequest(r.operationId(),UUID.randomUUID(),r.displayName(),r.worldId(),r.dimension())).join());
            assertThrows(CompletionException.class,()->store.establishNativePlayer(id,UUID.randomUUID(),0).join());assertEquals(established,store.nativePlayers().join().getFirst());
        }finally{store.closeAsync().join();}
    }
}
