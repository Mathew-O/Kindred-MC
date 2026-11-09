package dev.kindred.core.knowledge;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.wire.Fact;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Message;
import dev.kindred.core.wire.Scope;
import dev.kindred.core.wire.Visibility;
import dev.kindred.core.wire.Wire;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that what an AI player is told becomes what it knows, and that it keeps each fact for whom
 * it was meant: a runner's position told on the runners' side is never in what a hunter can be told, from any channel.
 */
class WebFeedTest {
    private static Listener ai(String side, String faction) {
        return new Listener(UUID.randomUUID(), "p", false, Set.of(), faction, "", side, false, null, null);
    }

    @Test
    void aRunnersPositionNeverReachesAHuntersQueryFromAnyChannelWhileATeammatesDoes() {
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        var fox = ai(runners, "");
        var wolf = ai(runners, "");
        var hawk = ai(hunters, "");
        var roster = List.of(fox, wolf, hawk);
        var webs = new java.util.HashMap<UUID, KnowledgeWeb>();
        for (var l : roster) webs.put(l.id(), new KnowledgeWeb(l.id()));
        var wire = new Wire(Wire.Config.defaults());
        var pos = new LinkedHashMap<String, Fact>();
        pos.put("runner_at", Fact.of("120, 70, -40", Visibility.SIDE));
        pos.put("needs", Fact.of("food", Visibility.SIDE));
        long t = 0;
        var tries = List.of(Scope.side("m1", "runners"), Scope.match("m1"), Scope.toPlayer(hawk.id()), Scope.toPlayer(wolf.id()),
                wire.earshotOf(new Listener.Place("minecraft:overworld", 0, 64, 0)), Scope.server(), Scope.operators());
        for (var scope : tries) {
            var d = wire.send(Message.of("side.where", fox.id(), scope, pos, List.of(5L)), GameModes.WorldMode.MANHUNT, roster, t += 31_000);
            for (var told : d.told()) {
                var receiver = roster.stream().filter(l -> l.id().equals(told.receiver())).findFirst().orElseThrow();
                WebFeed.told(webs.get(receiver.id()), told, receiver, false, 100);
            }
        }
        String foxPos = WebFeed.entityOf(fox.id(), false) + "/runner";
        var asHawk = KnowledgeWeb.Viewer.of(hawk);
        var asWolf = KnowledgeWeb.Viewer.of(wolf);
        for (var web : webs.values()) {
            assertTrue(web.about(asHawk, foxPos, KnowledgeWeb.Verb.SAW_AT, 200).isEmpty(), "never to the hunter, from any web");
        }
        var known = webs.get(wolf.id()).about(asWolf, foxPos, KnowledgeWeb.Verb.SAW_AT, 200);
        assertEquals(1, known.size(), "the teammate knows it");
        assertEquals(HowKnown.TOLD, known.getFirst().provenance().how());
        assertEquals(List.of(5L), known.getFirst().provenance().events());
        assertEquals(runners, known.getFirst().group());
        assertTrue(webs.get(hawk.id()).size() == 0, "the hunter was told nothing");
        // The teammate's needs, too, kept for the side.
        assertEquals("food", webs.get(wolf.id()).about(asWolf, WebFeed.entityOf(fox.id(), false), KnowledgeWeb.Verb.NEEDS, 200).getFirst().object());
    }

    @Test
    void aSelfFactSharedPrivatelyStaysTheSendersAndAPersonsWordsStayAClaim() {
        var mat = new Listener(UUID.randomUUID(), "Mat", true, Set.of(), "oak", "", "", false, null, null);
        var wren = ai("", "oak");
        var ash = ai("", "oak");
        var webWren = new KnowledgeWeb(wren.id());
        var wire = new Wire(Wire.Config.defaults());
        var facts = new LinkedHashMap<String, Fact>();
        facts.put("lives_at", Fact.of("0, 64, 0", Visibility.SELF));
        facts.put("note", Fact.of("ignored", Visibility.PUBLIC));
        var d = wire.send(new Message("private.home", mat.id(), Scope.toPlayer(wren.id()), facts, List.of(9L), Message.Origin.PERSON_WORDS),
                GameModes.WorldMode.FACTIONS, List.of(mat, wren, ash), 0);
        var added = WebFeed.told(webWren, d.told().getFirst(), wren, true, 50);
        assertEquals(1, added.size(), "only keys that name a relation are kept");
        assertEquals(HowKnown.CLAIMED, added.getFirst().provenance().how());
        assertTrue(webWren.about(KnowledgeWeb.Viewer.of(wren), "person:" + mat.id(), null, 60).size() == 1, "Wren knows it");
        assertTrue(webWren.about(KnowledgeWeb.Viewer.of(ash), "person:" + mat.id(), null, 60).isEmpty(), "but would not tell her faction-mate");
        assertEquals(1, webWren.about(KnowledgeWeb.Viewer.of(mat), "person:" + mat.id(), null, 60).size(), "Mat may hear his own");
        assertTrue(webWren.about(KnowledgeWeb.Viewer.of(wren), "person:" + mat.id(), null, 50 + KnowledgeWeb.DAY).isEmpty(), "a claim lapses");
    }

    @Test
    void aDeliveryKeptInTheRecordRebuildsTheSameKnowledge() {
        var wren = ai("", "oak");
        var ash = ai("", "oak");
        var wire = new Wire(Wire.Config.defaults());
        var facts = new LinkedHashMap<String, Fact>();
        facts.put("needs", Fact.of("iron", Visibility.FACTION));
        facts.put("runner_at", Fact.of("1, 2, 3", Visibility.SELF));
        facts.put("note", Fact.of("not kept", Visibility.PUBLIC));
        var d = wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), facts, List.of(41L)), GameModes.WorldMode.FACTIONS, List.of(wren, ash), 0);
        var live = new KnowledgeWeb(ash.id());
        WebFeed.told(live, d.told().getFirst(), ash, false, 100);
        var data = WebFeed.record(d.message(), ash, false, List.of(ash.id()));
        var event = new dev.kindred.core.record.WorldEvent(77, 100, 0, "", WebFeed.MESSAGE, wren.id(), "faction:oak", "", data,
                dev.kindred.core.record.WorldEvent.HowKnown.TOLD);
        var webs = new java.util.HashMap<UUID, KnowledgeWeb>();
        assertEquals(2, WebFeed.replay(id -> webs.computeIfAbsent(id, KnowledgeWeb::new), event));
        var rebuilt = webs.get(ash.id());
        var asAsh = KnowledgeWeb.Viewer.of(ash);
        for (var r : live.query(asAsh, 150, x -> true)) {
            var again = rebuilt.about(asAsh, r.subject(), r.verb(), 150);
            assertEquals(1, again.size(), r.toString());
            var x = again.getFirst();
            assertEquals(r.object(), x.object());
            assertEquals(r.visibility(), x.visibility());
            assertEquals(r.group(), x.group());
            assertEquals(r.provenance(), x.provenance());
            assertEquals(r.until(), x.until());
        }
        assertEquals(live.size(), rebuilt.size());
        assertNull(webs.get(wren.id()), "the sender is not a receiver");
        assertEquals(0, WebFeed.replay(id -> new KnowledgeWeb(id), new dev.kindred.core.record.WorldEvent(78, 1, 0, "", "death", null, "x", "", java.util.Map.of(),
                dev.kindred.core.record.WorldEvent.HowKnown.RECORDED)));
    }
}
