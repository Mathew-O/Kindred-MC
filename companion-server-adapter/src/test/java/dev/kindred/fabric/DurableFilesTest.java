package dev.kindred.fabric;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: the small files beside a world (F-31h) are written whole and on disk, leaving no temporary file
 * behind; one that a power loss left empty, or that reads as nothing or as nonsense, is put aside as it was under a
 * dated {@code .corrupt} name and named in the message, never deleted and never written over unseen; and a player whose
 * bases file came back empty or cut short starts with no bases, the damaged file kept beside it, which its next write
 * does not touch.
 *
 * Technical: the power loss is staged, not made: each fixture writes the empty or truncated file a power loss can leave
 * where the store would read it.
 */
class DurableFilesTest {
    private record Sample(String name, int count) { }

    private final Gson gson = new Gson();

    @Test
    void aFileIsWrittenWholeAndReadBackWithNothingLeftBeside(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deep").resolve("sample.json");
        DurableFiles.write(file, gson.toJson(new Sample("Rue", 3)));
        DurableFiles.write(file, gson.toJson(new Sample("Rue", 4)));
        assertEquals(new Sample("Rue", 4), DurableFiles.parse(file, gson, Sample.class));
        try (var listed = Files.list(file.getParent())) {
            assertEquals(1, listed.count(), "no temporary file left beside it");
        }
    }

    @Test
    void anEmptyOrNullOrMalformedFileIsSetAsideAsItWasAndNamed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sample.json");
        for (String text : new String[] {"", "   \n", "null", "{\"name\": \"Rue\", \"count\": "}) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
            var refused = assertThrows(IOException.class, () -> DurableFiles.parse(file, gson, Sample.class), "'" + text + "'");
            assertFalse(Files.exists(file), "not left to be written over: '" + text + "'");
            assertTrue(refused.getMessage().contains("set aside as sample.json.corrupt-"), refused.getMessage());
        }
        try (var listed = Files.list(dir)) {
            var asides = listed.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(4, asides.size(), "each kept, none over another: " + asides);
            assertTrue(asides.stream().allMatch(name -> name.matches("sample\\.json\\.corrupt-\\d{8}-\\d{6}-\\d{3}(-\\d+)?")), "dated: " + asides);
            var contents = new java.util.TreeSet<String>();
            for (String name : asides) contents.add(Files.readString(dir.resolve(name)));
            assertEquals(new java.util.TreeSet<>(java.util.List.of("", "   \n", "null", "{\"name\": \"Rue\", \"count\": ")), contents, "each as it was");
        }
    }

    @Test
    void aBasesFileAPowerLossLeftEmptyIsKeptAsideAndTheNextWriteLeavesIt(@TempDir Path dir) throws Exception {
        staged(dir, "");
    }

    @Test
    void aBasesFileAPowerLossCutShortIsKeptAsideAndTheNextWriteLeavesIt(@TempDir Path dir) throws Exception {
        staged(dir, "{\"player\":\"Rue\",\"built\":[{\"stage\":\"cab");
    }

    /** Staged as a power loss leaves it: {@code damaged} where the bases file was; then a start, and the next save. */
    private static void staged(Path dir, String damaged) throws Exception {
        UUID id = UUID.fromString("74d7d7c2-7971-4557-8d9b-64d105e0c911");
        Path folder = dir.resolve("memory");
        Path bases = folder.resolve(id + ".bases.json");
        Files.createDirectories(folder);
        Files.writeString(bases, damaged, StandardCharsets.UTF_8);
        try (var store = new PlayerMemoryStore(dir)) {
            var read = store.loadBases(id, "Rue").get(10, TimeUnit.SECONDS);
            assertTrue(read.built.isEmpty(), "it starts with no bases");
            Path aside;
            try (var listed = Files.list(folder)) {
                aside = listed.filter(p -> p.getFileName().toString().startsWith(id + ".bases.json.corrupt-")).findFirst().orElse(null);
            }
            assertNotNull(aside, "the damaged file kept aside under a dated name");
            assertFalse(Files.exists(bases), "not left where the next write would replace it");
            store.saveBases(id, read);
            store.closeAsync().get(10, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(bases), "the new file written");
            assertEquals(damaged, Files.readString(aside), "and the damaged one left as it was, never deleted");
        }
    }
}
