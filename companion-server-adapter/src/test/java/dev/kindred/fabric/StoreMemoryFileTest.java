package dev.kindred.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: what a player knows of its chests (who it saw at them, what it counted in them) is written to its
 * file and read back as it was. A world with no such file, or a file without the counts, reads as knowing nothing more;
 * an entry that makes no sense is left out alone; a file that cannot be read is set aside, not guessed at.
 *
 * Technical: F-30d2 and F-30d2a, {@link PlayerMemoryStore#saveStores} and {@link PlayerMemoryStore#loadStores} on a
 * real folder.
 */
class StoreMemoryFileTest {
    private static final UUID ID = UUID.fromString("74d7d7c2-7971-4557-8d9b-64d105e0c911");
    private static final String HAL = "e813ecac-7156-4b18-b0f5-85c79f0089e3";

    private static PlayerMemoryStore.StoreMemory load(PlayerMemoryStore store) throws Exception {
        return store.loadStores(ID, "Rue").get(10, TimeUnit.SECONDS);
    }

    private static Path file(Path dir) { return dir.resolve("memory").resolve(ID + ".stores.json"); }

    @Test
    void whatItKnowsOfItsStoresIsReadBackAsItWasWritten(@TempDir Path dir) throws Exception {
        try (var store = new PlayerMemoryStore(dir)) {
            var out = new PlayerMemoryStore.StoreMemory();
            out.player = "Rue";
            out.seen.add(new PlayerMemoryStore.StoreSighting(5, 64, 30, "Hal", false, HAL, 120));
            out.stores.add(new PlayerMemoryStore.StoreCount("minecraft:overworld", 5, 64, 30, "OWN",
                    Map.of("minecraft:saddle", 1, "minecraft:enchanted_book", 2), 300));
            store.saveStores(ID, out);
            var back = load(store);
            assertEquals(out.seen, back.seen);
            assertEquals(out.stores, back.stores);
            assertEquals("Rue", back.player);
        }
    }

    @Test
    void noFileIsNothingKnownAndAFileWithoutTheCountsHasNone(@TempDir Path dir) throws Exception {
        try (var store = new PlayerMemoryStore(dir)) {
            assertNull(load(store), "No file: it knows nothing of its stores, as on a fresh start");
            Files.createDirectories(file(dir).getParent());
            Files.writeString(file(dir), "{\"player\":\"Rue\",\"seen\":[{\"x\":5,\"y\":64,\"z\":30,\"name\":\"Hal\",\"teammate\":false,\"who\":\""
                    + HAL + "\",\"age\":120}]}", StandardCharsets.UTF_8);
            var back = load(store);
            assertEquals(1, back.seen.size());
            assertEquals(List.of(), back.stores, "A part the file does not have is empty");
        }
    }

    @Test
    void anEntryThatMakesNoSenseIsLeftOutAlone(@TempDir Path dir) throws Exception {
        try (var store = new PlayerMemoryStore(dir)) {
            Files.createDirectories(file(dir).getParent());
            Files.writeString(file(dir), "{\"seen\":[{\"x\":1,\"y\":2,\"z\":3,\"name\":\"Hal\",\"teammate\":false,\"who\":\"not an id\",\"age\":5},"
                    + "{\"x\":1,\"y\":2,\"z\":3,\"name\":\"Hal\",\"teammate\":false,\"who\":\"" + HAL + "\",\"age\":-1},"
                    + "{\"x\":1,\"y\":2,\"z\":3,\"name\":\"Hal\",\"teammate\":true,\"who\":\"" + HAL + "\",\"age\":5}],"
                    + "\"stores\":[{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3,\"whose\":\"THEIRS\",\"counts\":{},\"age\":0},"
                    + "{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3,\"whose\":\"OWN\",\"counts\":{\"minecraft:saddle\":0},\"age\":0},"
                    + "{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3,\"whose\":\"TEAM\",\"counts\":{\"minecraft:bread\":4},\"age\":7}]}",
                    StandardCharsets.UTF_8);
            var back = load(store);
            assertEquals(1, back.seen.size(), "Only the sighting that makes sense");
            assertTrue(back.seen.getFirst().teammate());
            assertEquals(1, back.stores.size(), "Only the count that makes sense");
            assertEquals(Map.of("minecraft:bread", 4), back.stores.getFirst().counts());
        }
    }

    @Test
    void aFileThatCannotBeReadIsSetAsideAndNothingIsGuessed(@TempDir Path dir) throws Exception {
        try (var store = new PlayerMemoryStore(dir)) {
            Files.createDirectories(file(dir).getParent());
            Files.writeString(file(dir), "{\"seen\": [ {\"x\": ", StandardCharsets.UTF_8);
            assertNull(load(store), "Nothing held");
            assertFalse(Files.exists(file(dir)), "Not left to be written over");
            assertEquals("{\"seen\": [ {\"x\": ", Files.readString(file(dir).resolveSibling(ID + ".stores.json.unreadable")), "Set aside as it was");
        }
    }

    @Test
    void closedOnlyOnceEveryWriteAskedForIsMade(@TempDir Path dir) throws Exception {
        // F-31e: the stop waits for this; a restart reads the file after it.
        var store = new PlayerMemoryStore(dir);
        var out = new PlayerMemoryStore.StoreMemory();
        out.player = "Rue";
        for (int i = 0; i < 200; i++) out.seen.add(new PlayerMemoryStore.StoreSighting(i, 64, 30, "Hal", false, HAL, i));
        store.saveStores(ID, out);
        store.closeAsync().get(10, TimeUnit.SECONDS);
        assertTrue(Files.isRegularFile(file(dir)), "Written before it is closed");
        try (var again = new PlayerMemoryStore(dir)) {
            assertEquals(200, again.loadStores(ID, "Rue").get(10, TimeUnit.SECONDS).seen.size());
        }
        store.closeAsync().get(10, TimeUnit.SECONDS); // Closing again is done at once.
    }

    @Test
    void writtenBeforeItsFileIsReadBackWhatTheFileHeldIsKeptAndTheNewerKeptOverIt(@TempDir Path dir) throws Exception {
        // F-30d3: what it learned before the read-back, written merged with the file, so that neither is lost.
        String other = "f41041bd-47d3-4020-a8ae-fd9283de5b2a";
        try (var store = new PlayerMemoryStore(dir)) {
            var before = new PlayerMemoryStore.StoreMemory();
            before.player = "Rue";
            before.seen.add(new PlayerMemoryStore.StoreSighting(1, 64, 1, "Hal", false, HAL, 10));
            before.seen.add(new PlayerMemoryStore.StoreSighting(2, 64, 2, "Hal", false, HAL, 10));
            before.stores.add(new PlayerMemoryStore.StoreCount("minecraft:overworld", 1, 64, 1, "OWN", Map.of("minecraft:saddle", 1), 10));
            store.saveStores(ID, before);
            var now = new PlayerMemoryStore.StoreMemory();
            now.player = "Rue";
            now.seen.add(new PlayerMemoryStore.StoreSighting(2, 64, 2, "Ivy", true, other, 1));
            now.stores.add(new PlayerMemoryStore.StoreCount("minecraft:overworld", 3, 64, 3, "TEAM", Map.of("minecraft:bread", 4), 1));
            store.saveStoresMerging(ID, now);
            var back = load(store);
            assertEquals(2, back.seen.size(), "Both stores' sightings");
            assertTrue(back.seen.contains(new PlayerMemoryStore.StoreSighting(2, 64, 2, "Ivy", true, other, 1)), "The newer kept at its store");
            assertTrue(back.seen.contains(new PlayerMemoryStore.StoreSighting(1, 64, 1, "Hal", false, HAL, 10)), "The file's kept where the newer has none");
            assertEquals(2, back.stores.size(), "Both stores' counts");

            var villagers = new PlayerMemoryStore.VillagerMemory();
            villagers.player = "Rue";
            villagers.seen.add(new PlayerMemoryStore.VillagerSeen(HAL, "LIBRARIAN", 1, java.util.List.of(), 5));
            villagers.waits.add(new PlayerMemoryStore.VillagerWait(HAL, 100));
            villagers.told.add(HAL);
            store.saveVillagers(ID, villagers);
            var newer = new PlayerMemoryStore.VillagerMemory();
            newer.player = "Rue";
            newer.seen.add(new PlayerMemoryStore.VillagerSeen(other, "FARMER", 2, java.util.List.of(), 1));
            newer.waits.add(new PlayerMemoryStore.VillagerWait(HAL, 40));
            store.saveVillagersMerging(ID, newer);
            var kept = store.loadVillagers(ID, "Rue").get(10, TimeUnit.SECONDS);
            assertEquals(2, kept.seen.size(), "Both villagers");
            assertEquals(java.util.List.of(new PlayerMemoryStore.VillagerWait(HAL, 40)), kept.waits, "The newer wait kept over the file's");
            assertEquals(java.util.List.of(HAL), kept.told, "Those told of, of both");
        }
    }
}
