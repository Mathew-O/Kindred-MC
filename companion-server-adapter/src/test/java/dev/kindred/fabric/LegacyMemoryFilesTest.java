package dev.kindred.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.knowledge.KnowledgeWeb;
import dev.kindred.core.knowledge.LegacyMemory;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Visibility;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: reads three AI players' real old memory files, saved by a real playthrough (the f29e-on run, seed
 * 8675309), into their knowledge webs: every teammate's doing, needs and spare things and where the team last had it;
 * every resource found, place sheltered, stretch explored and spot it was hurt; each kept with when it was learnt and
 * how. A player's own finds are its own: another player is never shown them. Read again, nothing is added.
 *
 * Technical: F-32b's legacy reading against real files (`src/test/resources/legacy/f29e-on-8675309`, copied from
 * {@code world/kindred/memory/}), parsed by Gson as {@link PlayerMemoryStore} parses them.
 */
class LegacyMemoryFilesTest {
    private static final String RUN = "/legacy/f29e-on-8675309/";
    private static final List<UUID> PLAYERS = List.of(UUID.fromString("74d7d7c2-7971-4557-8d9b-64d105e0c911"),
            UUID.fromString("e813ecac-7156-4b18-b0f5-85c79f0089e3"), UUID.fromString("f41041bd-47d3-4020-a8ae-fd9283de5b2a"));
    private static final String TEAM = KindredWire.TEAM;
    private static final long LATE = 10_000_000;
    private final Gson gson = new GsonBuilder().create(); // As PlayerMemoryStore reads (its pretty-printing only shapes writing).

    private <T> T read(String file, Class<T> type) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RUN + file)) {
            assertNotNull(in, "fixture " + file);
            return gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
        }
    }

    private static Listener listener(UUID id, Set<String> teams) {
        return new Listener(id, "", false, teams, "", "", "", false, null, null);
    }

    @Test
    void everyPeerNoteOfEachRealFileIsInItsWebAsKnownAndWhen() throws IOException {
        int total = 0;
        for (UUID id : PLAYERS) {
            var ledger = read(id + ".peers.json", PeerLedger.Snapshot.class);
            assertEquals(id, ledger.self());
            var web = new KnowledgeWeb(id);
            var got = LegacyMemory.peers(web, ledger, TEAM);
            int expected = 0;
            for (var p : ledger.peers()) {
                for (var n : p.notes()) {
                    if (n.text().isBlank() || n.source() == PeerLedger.Source.SAID || n.correction() != null) continue;
                    expected += n.topic() == PeerLedger.Topic.NEEDS || n.topic() == PeerLedger.Topic.SPARE
                            ? (int) java.util.Arrays.stream(n.text().split(",")).filter(t -> !t.isBlank()).count() : 1;
                    var subject = "player:" + p.id();
                    var doing = web.all().stream().filter(r -> r.subject().equals(subject) && r.value().equals(n.text())).findFirst();
                    if (n.topic() == PeerLedger.Topic.DOING) {
                        assertTrue(doing.isPresent(), p.name() + "'s doing in " + id + "'s web");
                        assertEquals(n.at(), doing.get().from(), "learnt when the ledger had it");
                        assertEquals(n.source() == PeerLedger.Source.TEAM ? HowKnown.TOLD : HowKnown.RECORDED, doing.get().provenance().how());
                    }
                }
                if (p.teamPlace() != null) expected++;
                expected += p.episodes().size() + p.given().size() + p.received().size();
                assertEquals(p.name(), web.entity("player:" + p.id()).orElseThrow().name());
            }
            assertEquals(expected, got.added(), id + ": every note read once");
            assertEquals(0, LegacyMemory.peers(web, ledger, TEAM).added(), "read again, nothing added");
            total += got.added();
        }
        assertTrue(total > 0, "the real files hold something");
        // One fact as the file has it: what Sol was doing when it last told the team, in 74d7d7c2's file.
        var web = new KnowledgeWeb(PLAYERS.getFirst());
        LegacyMemory.peers(web, read(PLAYERS.getFirst() + ".peers.json", PeerLedger.Snapshot.class), TEAM);
        var sol = web.about(KnowledgeWeb.Viewer.of(listener(PLAYERS.getFirst(), Set.of())), "player:" + PLAYERS.get(2), KnowledgeWeb.Verb.DOING, LATE);
        assertEquals(List.of("working on nether portal: Done: The Nether portal is built and lit"), sol.stream().map(KnowledgeWeb.Relation::value).toList());
        assertEquals(Visibility.TEAM, sol.getFirst().visibility());
    }

    @Test
    void everyFindOfEachRealAtlasIsItsOwnAndReadOnce() throws IOException {
        for (UUID id : PLAYERS) {
            var atlas = read(id + ".atlas.json", Atlas.Snapshot.class);
            var web = new KnowledgeWeb(id);
            var got = LegacyMemory.atlas(web, id, atlas);
            var finds = new HashSet<String>();
            int explored = 0;
            for (var c : atlas.chunks()) {
                if (c.visited()) explored++;
                for (var s : c.sightings()) finds.add(s.resource() + "@" + LegacyMemory.place(s.at().dimension(), s.at().x(), s.at().y(), s.at().z()) + "=" + s.count());
            }
            long found = web.all().stream().filter(r -> r.verb() == KnowledgeWeb.Verb.FOUND).count();
            assertEquals(finds.size(), found, id + ": every resource found, once each");
            assertEquals(explored, web.all().stream().filter(r -> r.verb() == KnowledgeWeb.Verb.EXPLORED).count());
            assertEquals(atlas.danger().stream().filter(d -> !d.harms().isEmpty()).count(), web.all().stream().filter(r -> r.verb() == KnowledgeWeb.Verb.FEARS).count());
            assertEquals(atlas.shelters().size(), web.all().stream().filter(r -> r.verb() == KnowledgeWeb.Verb.SHELTERS_AT).count());
            assertEquals(got.added(), web.size());
            assertTrue(web.size() < 20_000, "well inside the web's bound: " + web.size());
            assertTrue(web.all().stream().allMatch(r -> r.visibility() == Visibility.SELF && r.provenance().how() == HowKnown.RECORDED && r.until() == Long.MAX_VALUE),
                    "its own, as the game recorded it, held until replaced");
            // The owner knows its finds long after; a teammate is never shown them.
            var owner = KnowledgeWeb.Viewer.of(listener(id, Set.of(TEAM)));
            var mate = KnowledgeWeb.Viewer.of(listener(UUID.randomUUID(), Set.of(TEAM)));
            assertEquals(found, web.query(owner, LATE, r -> r.verb() == KnowledgeWeb.Verb.FOUND).size());
            assertTrue(web.query(mate, LATE, r -> true).isEmpty(), "another player is told nothing of it");
            assertEquals(0, LegacyMemory.atlas(web, id, atlas).added(), "read again, nothing added");
        }
        // One find as the file has it: f41041bd's one diamond.
        var web = new KnowledgeWeb(PLAYERS.get(2));
        LegacyMemory.atlas(web, PLAYERS.get(2), read(PLAYERS.get(2) + ".atlas.json", Atlas.Snapshot.class));
        assertEquals(1, web.all().stream().filter(r -> r.subject().equals("resource:diamond")).count());
    }
}
