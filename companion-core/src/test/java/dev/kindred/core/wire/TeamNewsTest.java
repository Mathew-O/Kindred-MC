package dev.kindred.core.wire;

import dev.kindred.core.knowledge.KnowledgeWeb;
import dev.kindred.core.knowledge.WebFeed;
import dev.kindred.core.match.GameModes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: an AI player's news of its team's chest (what it put in, took out, found gone) reaches its teammates
 * as something they were told, with where; another faction's players never get it; a manhunt's hunters' news stays
 * with the hunters; and it is never anything but a team's or a side's.
 */
class TeamNewsTest {
    private static final Listener.Place SPAWN = new Listener.Place("minecraft:overworld", 0, 64, 0);

    private static Listener ai(String name, String team, String faction, String side) {
        return new Listener(UUID.randomUUID(), name, false, team.isEmpty() ? Set.of() : Set.of(team), faction, "", side, false, SPAWN, null);
    }

    @Test
    void aTeammateKnowsWhatWasPutInTookOutAndFoundGoneAsTold() {
        var wire = new Wire(Wire.Config.defaults());
        var hal = ai("Hal", "world", "", "");
        var wren = ai("Wren", "world", "", "");
        var roster = List.of(hal, wren);
        var scope = TeamState.scopeOf(hal).orElseThrow();
        var web = new KnowledgeWeb(wren.id());
        var sends = List.of(TeamNews.stored(hal.id(), scope, true, "a saddle and a name tag", "the chest at 12, 64, -30"),
                TeamNews.stored(hal.id(), scope, false, "3 iron ingots", "the chest at 12, 64, -30"),
                TeamNews.missing(hal.id(), scope, "a name tag", "the chest at 12, 64, -30"));
        for (int i = 0; i < sends.size(); i++) {
            var d = wire.send(sends.get(i), GameModes.WorldMode.COOP_SURVIVAL, roster, i * 1_000L);
            assertEquals(List.of(wren.id()), d.receivers(), sends.get(i).kind() + ": " + d.refused());
            WebFeed.told(web, d.told().getFirst(), wren, false, 100 + i);
        }
        var me = KnowledgeWeb.Viewer.of(wren);
        String halId = WebFeed.entityOf(hal.id(), false);
        assertEquals(List.of("a saddle and a name tag at the chest at 12, 64, -30"),
                web.about(me, halId, KnowledgeWeb.Verb.STORED, 200).stream().map(KnowledgeWeb.Relation::object).toList());
        assertEquals(List.of("3 iron ingots at the chest at 12, 64, -30"),
                web.about(me, halId, KnowledgeWeb.Verb.TOOK, 200).stream().map(KnowledgeWeb.Relation::object).toList());
        var gone = web.about(me, halId, KnowledgeWeb.Verb.MISSING, 200);
        assertEquals(List.of("a name tag at the chest at 12, 64, -30"), gone.stream().map(KnowledgeWeb.Relation::object).toList());
        assertEquals(HowKnown.TOLD, gone.getFirst().provenance().how());
        assertEquals(Visibility.TEAM, gone.getFirst().visibility());
        // Someone not on the team is shown none of it.
        var stranger = KnowledgeWeb.Viewer.of(ai("Rook", "world:birch", "birch", ""));
        assertTrue(web.query(stranger, 200, r -> true).isEmpty());
    }

    @Test
    void itNeverCrossesAFactionOrASideAndIsOnlyATeamsOrASides() {
        var wire = new Wire(Wire.Config.defaults());
        var hal = ai("Hal", "world:oak", "oak", "");
        var ash = ai("Ash", "world:oak", "oak", "");
        var rook = ai("Rook", "world:birch", "birch", "");
        var d = wire.send(TeamNews.stored(hal.id(), TeamState.scopeOf(hal).orElseThrow(), true, "40 iron ingots", "our chest at 1, 2, 3"),
                GameModes.WorldMode.FACTIONS, List.of(hal, ash, rook), 0);
        assertEquals(List.of(ash.id()), d.receivers(), "another faction never gets it");
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        var hawk = ai("Hawk", "", "", hunters);
        var kite = ai("Kite", "", "", hunters);
        var fox = ai("Fox", "", "", runners);
        var side = TeamState.scopeOf(hawk).orElseThrow();
        var m = TeamNews.missing(hawk.id(), side, "an ender pearl", "the chest at 5, 60, 5");
        assertTrue(m.facts().values().stream().allMatch(f -> f.visibility() == Visibility.SIDE));
        assertEquals(List.of(kite.id()), wire.send(m, GameModes.WorldMode.MANHUNT, List.of(hawk, kite, fox), 0).receivers());
        assertThrows(IllegalArgumentException.class, () -> TeamNews.stored(hal.id(), Scope.faction("oak"), true, "iron", "here"));
        assertThrows(IllegalArgumentException.class, () -> TeamNews.missing(hal.id(), TeamState.scopeOf(hal).orElseThrow(), "", "here"));
    }
}
