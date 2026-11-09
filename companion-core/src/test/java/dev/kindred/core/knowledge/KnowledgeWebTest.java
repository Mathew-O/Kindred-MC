package dev.kindred.core.knowledge;

import dev.kindred.core.knowledge.KnowledgeWeb.Verb;
import dev.kindred.core.knowledge.KnowledgeWeb.Viewer;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Visibility;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the web of what is known: each fact keeps its source; a sighting and a mere claim lapse in a
 * day; a confirmation makes a claim a fact; "forget that" closes a fact and keeps it; and every answer holds only what
 * the one asking may know, by who they are, never how near they stand.
 */
class KnowledgeWebTest {
    private static Viewer viewer(String faction, String side, boolean operator, String... teams) {
        return Viewer.of(new Listener(UUID.randomUUID(), "v", false, Set.of(teams), faction, "", side, operator, null, null));
    }

    @Test
    void provenanceAndValidity() {
        var web = new KnowledgeWeb();
        web.entity("player:rook", KnowledgeWeb.Kind.PLAYER, "Rook");
        var seen = web.add("player:rook", Verb.SAW_AT, "place:120,70,-40", "", HowKnown.RECORDED, List.of(7L), 100, Visibility.PUBLIC, "");
        var said = web.add("person:mat", Verb.NEEDS, "item:iron_ingot", "4", HowKnown.CLAIMED, List.of(8L), 100, Visibility.PUBLIC, "");
        var told = web.add("faction:oak", Verb.HAS_SPARE, "item:iron_ingot", "40", HowKnown.TOLD, List.of(9L), 100, Visibility.PUBLIC, "");
        var anyone = viewer("", "", false);
        assertEquals(List.of(7L), seen.provenance().events());
        assertTrue(said.claimed() && said.weight() < told.weight() && told.weight() < seen.weight());
        assertEquals(3, web.query(anyone, 100 + KnowledgeWeb.DAY - 1, r -> true).size());
        assertEquals(List.of(told.id()), web.query(anyone, 100 + KnowledgeWeb.DAY, r -> true).stream().map(KnowledgeWeb.Relation::id).toList(),
                "a sighting and a claim lapse in a game day; a told fact holds");
        var confirmed = web.confirm(said.id(), 12L).orElseThrow();
        assertEquals(HowKnown.RECORDED, confirmed.provenance().how());
        assertEquals(List.of(8L, 12L), confirmed.provenance().events());
        assertTrue(confirmed.validAt(100 + 10 * KnowledgeWeb.DAY), "confirmed, it no longer lapses");
        // A newer fact of the same kind ends the older; its history stays.
        var newer = web.add("faction:oak", Verb.HAS_SPARE, "item:iron_ingot", "12", HowKnown.TOLD, List.of(20L), 500, Visibility.PUBLIC, "");
        assertFalse(web.get(told.id()).orElseThrow().validAt(500));
        assertTrue(web.get(told.id()).orElseThrow().validAt(499));
        assertEquals("12", web.about(anyone, "faction:oak", Verb.HAS_SPARE, 600).getFirst().value());
        assertEquals(newer.id(), web.about(anyone, "faction:oak", Verb.HAS_SPARE, 600).getFirst().id());
    }

    @Test
    void visibilityByRelationNeverProximity() {
        var web = new KnowledgeWeb();
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        web.add("player:fox", Verb.SAW_AT, "place:12,64,8", "", HowKnown.RECORDED, List.of(1L), 0, Visibility.SIDE, runners);
        web.add("faction:oak", Verb.HAS_SPARE, "item:iron_ingot", "40", HowKnown.TOLD, List.of(2L), 0, Visibility.FACTION, "oak");
        web.add("faction:oak", Verb.CLAIMED, "claim:12,-4", "", HowKnown.RECORDED, List.of(3L), 0, Visibility.PUBLIC, "");
        web.add("person:mat", Verb.LIVES_AT, "place:0,64,0", "", HowKnown.CLAIMED, List.of(4L), 0, Visibility.SELF, "person:mat");
        web.add("audit", Verb.OWES, "x", "", HowKnown.RECORDED, List.of(5L), 0, Visibility.OPERATOR, "");
        var hunter = viewer("", hunters, false);
        var teammate = viewer("", runners, false);
        var member = viewer("oak", "", false);
        var outsider = viewer("birch", "", false);
        var op = viewer("", "", true);
        assertTrue(web.about(hunter, "player:fox", Verb.SAW_AT, 10).isEmpty(), "a hunter never gets a runner's position");
        assertEquals(1, web.about(teammate, "player:fox", Verb.SAW_AT, 10).size(), "the runner's teammate does");
        assertEquals(1, web.about(member, "faction:oak", Verb.HAS_SPARE, 10).size());
        assertTrue(web.about(outsider, "faction:oak", Verb.HAS_SPARE, 10).isEmpty(), "stock is the faction's");
        assertEquals(1, web.about(outsider, "faction:oak", Verb.CLAIMED, 10).size(), "claims are public");
        assertTrue(web.about(member, "person:mat", null, 10).isEmpty(), "a person's place is their own");
        var mat = new Listener(UUID.randomUUID(), "Mat", true, Set.of(), "", "", "", false, null, null);
        var matWeb = new KnowledgeWeb();
        matWeb.add("person:" + mat.id(), Verb.LIVES_AT, "place:0,64,0", "", HowKnown.CLAIMED, List.of(4L), 0, Visibility.SELF, "person:" + mat.id());
        assertEquals(1, matWeb.about(Viewer.of(mat), "person:" + mat.id(), null, 10).size(), "the person themself");
        assertTrue(web.query(hunter, 10, r -> r.verb() == Verb.OWES).isEmpty());
        assertEquals(1, web.query(op, 10, r -> r.verb() == Verb.OWES).size());
        assertThrows(NullPointerException.class, () -> web.query(null, 10, r -> true), "every query names its viewer");
    }

    @Test
    void forgetThatClosesWithTheCorrectionAndNeverDeletes() {
        var web = new KnowledgeWeb();
        var r = web.add("person:mat", Verb.NEEDS, "item:bread", "", HowKnown.CLAIMED, List.of(4L), 10, Visibility.PUBLIC, "");
        var anyone = viewer("", "", false);
        var closed = web.correct(r.id(), 99L, 20).orElseThrow();
        assertEquals(99L, closed.closedBy());
        assertEquals(20, closed.until());
        assertTrue(web.about(anyone, "person:mat", Verb.NEEDS, 21).isEmpty());
        assertEquals(1, web.size(), "kept, not deleted");
        assertEquals(1, web.history(viewer("", "", true), "person:mat").size());
        assertTrue(web.history(anyone, "person:mat").isEmpty(), "history is the operators'");
        assertTrue(web.correct(r.id(), 100L, 30).isEmpty(), "closed once");
        assertTrue(web.confirm(r.id(), 101L).isEmpty(), "a corrected fact is not confirmed back");
        // Under the bound, lapsed facts go; corrections stay.
        for (int i = 0; i < KnowledgeWeb.MAX_RELATIONS + 10; i++) {
            web.add("player:p" + i, Verb.SAW_AT, "place:" + i, "", HowKnown.RECORDED, List.of(), 100L + i, Visibility.PUBLIC, "");
        }
        assertEquals(KnowledgeWeb.MAX_RELATIONS + 11, web.size(), "open facts are never dropped");
        web.add("player:last", Verb.SAW_AT, "place:x", "", HowKnown.RECORDED, List.of(), 100L + 3 * KnowledgeWeb.DAY, Visibility.PUBLIC, "");
        assertTrue(web.size() <= KnowledgeWeb.MAX_RELATIONS);
        assertTrue(web.get(r.id()).isPresent(), "the correction survives the bound");
    }
}
