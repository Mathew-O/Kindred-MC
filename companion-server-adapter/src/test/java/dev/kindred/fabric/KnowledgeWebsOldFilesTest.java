package dev.kindred.fabric;

import com.google.gson.Gson;
import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.knowledge.KnowledgeWeb;
import dev.kindred.core.social.PeerLedger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: at start, once what the World Record kept is back in the AI players' webs, each player's old memory
 * files are read into its own web once: what it knew of its teammates and what it found, from a real playthrough's
 * files. Nothing is read before the web is rebuilt (the rebuild would lose it), a player's own finds never go into
 * another's web, another player's ledger is not read as its own, and a second start reads it all again.
 *
 * Technical: F-32b's load path in {@link KnowledgeWebs}: {@code readOldOnce} gates on the rebuild and marks each player;
 * {@code readOld} applies {@code LegacyMemory}. The runtime reads the files off the thread and calls these on it.
 */
class KnowledgeWebsOldFilesTest {
    private static final String RUN = "/legacy/f29e-on-8675309/";
    private static final UUID A = UUID.fromString("74d7d7c2-7971-4557-8d9b-64d105e0c911");
    private static final UUID B = UUID.fromString("e813ecac-7156-4b18-b0f5-85c79f0089e3");

    private static <T> T read(String file, Class<T> type) throws IOException {
        try (InputStream in = KnowledgeWebsOldFilesTest.class.getResourceAsStream(RUN + file)) {
            assertNotNull(in, "fixture " + file);
            return new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
        }
    }

    @Test
    void eachPlayersOldFilesAreReadIntoItsOwnWebOnceAStartAfterTheRebuild() throws IOException {
        var webs = new KnowledgeWebs(() -> 0L);
        var peersA = read(A + ".peers.json", PeerLedger.Snapshot.class);
        var atlasA = read(A + ".atlas.json", Atlas.Snapshot.class);
        assertFalse(webs.readOldOnce(A), "not before the web is rebuilt from the Record");
        webs.rebuild(List.of());
        assertTrue(webs.readOldOnce(A));
        assertFalse(webs.readOldOnce(A), "once a start");
        var read = webs.readOld(A, peersA, atlasA, KindredWire.TEAM);
        assertTrue(read.added() > 0, "the real files hold something");
        int held = webs.of(A).size();
        assertEquals(read.added(), held);
        assertEquals(read.added(), webs.oldAdded());
        assertTrue(webs.of(A).all().stream().anyMatch(r -> r.verb() == KnowledgeWeb.Verb.FOUND), "its finds");
        assertTrue(webs.of(A).all().stream().anyMatch(r -> r.verb() == KnowledgeWeb.Verb.DOING), "its teammates' doings");
        assertEquals(0, webs.readOld(A, peersA, atlasA, KindredWire.TEAM).added(), "read again, nothing added");
        // Another player's web is untouched; A's ledger is not read as B's.
        assertTrue(webs.existing(B).isEmpty());
        assertEquals(0, webs.readOld(B, peersA, null, KindredWire.TEAM).added());
        assertEquals(0, webs.of(B).size());
        // A second start: rebuilt, read again, the same held.
        webs.rebuild(List.of());
        assertTrue(webs.existing(A).isEmpty(), "the rebuild empties the players' webs");
        assertTrue(webs.readOldOnce(A));
        assertEquals(held, webs.readOld(A, peersA, atlasA, KindredWire.TEAM).added());
        // No files at all: nothing.
        assertTrue(webs.readOldOnce(B));
        assertEquals(0, webs.readOld(B, null, null, KindredWire.TEAM).added());
    }
}
