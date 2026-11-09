package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.social.PeerUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that a teammate's update on the Wire reaches the team's other AI players every five seconds,
 * unchanged, past the team channel's lines a minute; that a person on the team never hears it; that another
 * faction's players never get it; and that team talk keeps its own limit.
 */
class TeamStateTest {
    private static final Listener.Place SPAWN = new Listener.Place("minecraft:overworld", 0, 64, 0);
    private static final GameModes.WorldMode COOP = GameModes.WorldMode.COOP_SURVIVAL;

    private static Listener ai(String name, String team, String faction) {
        return new Listener(UUID.randomUUID(), name, false, Set.of(team), faction, "", "", false, SPAWN, null);
    }

    private static Listener person(String name, String team) {
        return new Listener(UUID.randomUUID(), name, true, Set.of(team), "", "", "", false, SPAWN, null);
    }

    private static Wire wire() { return new Wire(Wire.Config.defaults().withState(TeamState.KINDS)); }

    private static PeerUpdate update(Listener from, int x) {
        return new PeerUpdate(from.id(), from.name(), "working on an iron pickaxe: 2 of 3 iron",
                List.of("3 iron ingots", "1 stick"), List.of("12 cobblestone"), new PeerLedger.Place("minecraft:overworld", x, 64, -7));
    }

    @Test
    void anUpdateComesBackAsItWasSent() {
        var wren = ai("Wren", "world", "");
        var u = update(wren, 40);
        var m = TeamState.of(u, "world");
        assertEquals(Channel.TEAM, m.scope().channel());
        assertTrue(m.facts().values().stream().allMatch(f -> f.visibility() == Visibility.TEAM));
        assertEquals(u, TeamState.update(m).orElseThrow());
        var full = new PeerUpdate(wren.id(), "Wren", "idle", List.of("1", "2", "3", "4", "5", "6", "7", "8"),
                List.of("a", "b", "c", "d", "e", "f", "g", "h"), null);
        assertEquals(full, TeamState.update(TeamState.of(full, "world")).orElseThrow(), "eight needs, eight spares, no place");
        assertTrue(TeamState.update(Message.of("team.talk", wren.id(), Scope.team("world"), Map.of(), List.of())).isEmpty());
    }

    @Test
    void everyFiveSecondsReachesTheTeamsAiPlayersAndNoPerson() {
        var wire = wire();
        var wren = ai("Wren", "world", "");
        var ash = ai("Ash", "world", "");
        var mat = person("Mat", "world");
        var roster = List.of(wren, ash, mat);
        for (int i = 0; i < 12; i++) { // A minute of updates, the same update over and over while Wren stands still.
            var d = wire.send(TeamState.of(update(wren, 40), "world"), COOP, roster, i * 5_000L);
            assertTrue(d.sent(), "update " + i + ": " + d.refused());
            assertEquals(List.of(ash.id()), d.told().stream().map(Delivery.Told::receiver).toList());
            assertTrue(d.phrase().isEmpty(), "never words");
            assertTrue(d.quiet().isEmpty(), "a person is not even counted as holding back");
        }
        // Team talk on the same team keeps its six lines a minute.
        int sent = 0;
        for (int i = 0; i < 8; i++) {
            if (wire.send(Message.of("team.need", wren.id(), Scope.team("world"), Map.of("item", Fact.of("iron " + i, Visibility.TEAM)), List.of()),
                    COOP, roster, 60_000L + i * 1_000L).sent()) sent++;
        }
        assertEquals(Channel.TEAM.perMinute, sent);
    }

    @Test
    void inAManhuntHuntersTellHuntersAndRunnersRunners() {
        var wire = wire();
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        var hawk = new Listener(UUID.randomUUID(), "Hawk", false, Set.of(), "", "", hunters, false, SPAWN, null);
        var kite = new Listener(UUID.randomUUID(), "Kite", false, Set.of(), "", "", hunters, false, SPAWN, null);
        var fox = new Listener(UUID.randomUUID(), "Fox", false, Set.of(), "", "", runners, false, SPAWN, null);
        var vix = new Listener(UUID.randomUUID(), "Vix", false, Set.of(), "", "", runners, false, SPAWN, null);
        var roster = List.of(hawk, kite, fox, vix);
        var scope = TeamState.scopeOf(hawk).orElseThrow();
        assertEquals(Channel.SIDE, scope.channel());
        var m = TeamState.of(update(hawk, 40), scope);
        assertTrue(m.facts().values().stream().allMatch(f -> f.visibility() == Visibility.SIDE));
        for (int i = 0; i < 12; i++) {
            assertEquals(List.of(kite.id()), wire.send(TeamState.of(update(hawk, 40), scope), GameModes.WorldMode.MANHUNT, roster, i * 5_000L).receivers());
            assertEquals(List.of(vix.id()), wire.send(TeamState.of(update(fox, 9), TeamState.scopeOf(fox).orElseThrow()), GameModes.WorldMode.MANHUNT, roster, i * 5_000L).receivers());
        }
        assertEquals(hawk.id(), TeamState.update(m).orElseThrow().from());
        // A team comes first; neither team nor side, nowhere.
        assertEquals(Channel.TEAM, TeamState.scopeOf(ai("Wren", "world", "")).orElseThrow().channel());
        assertTrue(TeamState.scopeOf(new Listener(UUID.randomUUID(), "Lone", false, Set.of(), "", "", "", false, SPAWN, null)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TeamState.of(update(hawk, 1), Scope.faction("oak")));
    }

    @Test
    void itKeepsEveryOtherCheck() {
        var wire = wire();
        var wren = ai("Wren", "world:oak", "oak");
        var ash = ai("Ash", "world:oak", "oak");
        var rook = ai("Rook", "world:birch", "birch");
        var mat = person("Mat", "world:oak");
        var roster = List.of(wren, ash, rook, mat);
        var d = wire.send(TeamState.of(update(wren, 40), "world:oak"), GameModes.WorldMode.FACTIONS, roster, 0);
        assertEquals(List.of(ash.id()), d.receivers(), "another faction's team never gets it");
        // Not a member of that team.
        assertEquals(Delivery.Refusal.NOT_MEMBER, wire.send(TeamState.of(update(rook, 1), "world:oak"), GameModes.WorldMode.FACTIONS, roster, 1).refused().orElseThrow());
        // A faction's fact cannot ride the team's channel.
        var narrower = new Message(TeamState.KIND, wren.id(), Scope.team("world:oak"), Map.of("stock", Fact.of("40 iron", Visibility.FACTION)), List.of(), Message.Origin.RECORD);
        assertEquals(Delivery.Refusal.NARROWER_FACT, wire.send(narrower, GameModes.WorldMode.FACTIONS, roster, 2).refused().orElseThrow());
        // Only an AI player's own record: never a person's, never words, never the server's.
        assertEquals(Delivery.Refusal.NOT_STATE, wire.send(TeamState.of(update(mat, 1), "world:oak"), GameModes.WorldMode.FACTIONS, roster, 3).refused().orElseThrow());
        var words = new Message(TeamState.KIND, wren.id(), Scope.team("world:oak"), Map.of(), List.of(), Message.Origin.AI_WORDS);
        assertEquals(Delivery.Refusal.ECHO, wire.send(words, GameModes.WorldMode.FACTIONS, roster, 4).refused().orElseThrow());
        var server = new Message(TeamState.KIND, null, Scope.team("world:oak"), Map.of(), List.of(), Message.Origin.RECORD);
        assertEquals(Delivery.Refusal.NOT_STATE, wire.send(server, GameModes.WorldMode.FACTIONS, roster, 5).refused().orElseThrow());
        // A mode without a team channel carries none.
        var solo = List.of(ai("Wren", "world", ""), ai("Ash", "world", ""));
        assertEquals(Delivery.Refusal.MODE, wire.send(TeamState.of(update(solo.getFirst(), 1), "world"), GameModes.WorldMode.SPEEDRUN, solo, 6).refused().orElseThrow());
        // Without the state kind configured, the team's budget holds as for anything else.
        var plain = new Wire(Wire.Config.defaults());
        int sent = 0;
        for (int i = 0; i < 12; i++) if (plain.send(TeamState.of(update(wren, i), "world:oak"), GameModes.WorldMode.FACTIONS, roster, i * 5_000L).sent()) sent++;
        assertEquals(Channel.TEAM.perMinute, sent);
    }
}
