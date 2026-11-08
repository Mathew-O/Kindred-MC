package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.social.ListenerPreferences;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks who gets each message: a faction's news reaches its members and never an outsider; a
 * manhunt side's news never reaches a runner, however near; nothing an AI player said comes back in as a message;
 * each channel's lines a minute hold, and each person's; the same message is not sent twice in a while, and a
 * public notice is said once to each person in earshot.
 */
class WireTest {
    private static final Listener.Place SPAWN = new Listener.Place("minecraft:overworld", 0, 64, 0);
    private static final GameModes.WorldMode FACTIONS = GameModes.WorldMode.FACTIONS;

    private static Listener ai(String name, String faction, String side, Listener.Place at) {
        return new Listener(UUID.randomUUID(), name, false, Set.of(), faction, "", side, false, at, null);
    }

    private static Listener person(String name, String faction, String side, Listener.Place at) {
        return new Listener(UUID.randomUUID(), name, true, Set.of(), faction, "", side, false, at, null);
    }

    private static Map<String, Fact> facts(Object... kv) {
        var out = new LinkedHashMap<String, Fact>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Fact) kv[i + 1]);
        return out;
    }

    @Test
    void aFactionMessageReachesItsMembersAndNeverAnOutsider() {
        var wire = new Wire(Wire.Config.defaults());
        var wren = ai("Wren", "oak", "", SPAWN);
        var ash = ai("Ash", "oak", "", SPAWN);
        var mat = person("Mat", "oak", "", SPAWN);
        var outsiderAi = ai("Rook", "birch", "", SPAWN);
        var outsider = person("Guest", "", "", SPAWN);
        var roster = List.of(wren, ash, mat, outsiderAi, outsider);
        var m = Message.of("faction.raid_planned", wren.id(), Scope.faction("oak"),
                facts("target", Fact.of("birch's east wall", Visibility.FACTION), "when", Fact.of("dusk", Visibility.FACTION)), List.of(41L, 42L));
        var d = wire.send(m, FACTIONS, roster, 0);
        assertTrue(d.sent());
        assertEquals(List.of(ash.id()), d.told().stream().map(Delivery.Told::receiver).toList());
        assertEquals(HowKnown.TOLD, d.told().getFirst().howKnown());
        assertEquals(List.of(mat.id()), d.phrase().stream().map(Delivery.Phrase::person).toList());
        assertFalse(d.receivers().contains(outsiderAi.id()));
        assertFalse(d.receivers().contains(outsider.id()));
        assertFalse(d.receivers().contains(wren.id()), "the sender is not its own receiver");
        assertEquals(List.of(41L, 42L), d.told().getFirst().message().eventIds());

        // An outsider cannot post on another faction's channel, nor put its facts in public.
        assertEquals(Delivery.Refusal.NOT_MEMBER, wire.send(Message.of("faction.need", outsiderAi.id(), Scope.faction("oak"), Map.of(), List.of()),
                FACTIONS, roster, 1).refused().orElseThrow());
        assertEquals(Delivery.Refusal.NARROWER_FACT, wire.send(Message.of("faction.stock", wren.id(), wire.earshotOf(SPAWN),
                facts("iron", Fact.of("40", Visibility.FACTION)), List.of()), FACTIONS, roster, 2).refused().orElseThrow());
        // A faction fact sent privately reaches a member and is withheld from an outsider.
        var toOutsider = wire.send(Message.of("private.stock", wren.id(), Scope.toPlayer(outsider.id()),
                facts("iron", Fact.of("40", Visibility.FACTION)), List.of()), FACTIONS, roster, 3);
        assertTrue(toOutsider.receivers().isEmpty());
        assertEquals(List.of(outsider.id()), toOutsider.withheld());
        var toMember = wire.send(Message.of("private.stock", wren.id(), Scope.toPlayer(mat.id()),
                facts("iron", Fact.of("40", Visibility.FACTION)), List.of()), FACTIONS, roster, 4);
        assertEquals(List.of(mat.id()), toMember.receivers());
    }

    @Test
    void aManhuntSideMessageNeverReachesARunner() {
        var wire = new Wire(Wire.Config.defaults());
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        var h1 = ai("Hawk", "", hunters, SPAWN);
        var h2 = person("Mat", "", hunters, SPAWN);
        var r1 = ai("Fox", "", runners, SPAWN);
        var r2 = person("Ren", "", runners, SPAWN);
        var bystander = person("Guest", "", "", SPAWN);
        var roster = List.of(h1, h2, r1, r2, bystander);
        var pos = facts("runner_at", Fact.of("120, 70, -40", Visibility.SIDE));
        var d = wire.send(Message.of("side.sighting", h1.id(), Scope.side("m1", "hunters"), pos, List.of(7L)), GameModes.WorldMode.MANHUNT, roster, 0);
        assertTrue(d.sent());
        assertEquals(List.of(h2.id()), d.receivers());
        // A runner, stood beside them, neither receives it nor may post on the hunters' side.
        assertEquals(Delivery.Refusal.NOT_MEMBER, wire.send(Message.of("side.need", r1.id(), Scope.side("m1", "hunters"), Map.of(), List.of()),
                GameModes.WorldMode.MANHUNT, roster, 1).refused().orElseThrow());
        // The side's facts never go public, and a hunter's private word to a runner holds them back.
        assertEquals(Delivery.Refusal.NARROWER_FACT, wire.send(Message.of("side.sighting", h1.id(), wire.earshotOf(SPAWN), pos, List.of()),
                GameModes.WorldMode.MANHUNT, roster, 2).refused().orElseThrow());
        var priv = wire.send(Message.of("private.sighting", h1.id(), Scope.toPlayer(r2.id()), pos, List.of()), GameModes.WorldMode.MANHUNT, roster, 3);
        assertTrue(priv.receivers().isEmpty());
        assertEquals(List.of(r2.id()), priv.withheld());
        // Over many messages, not one ever reaches a runner or the bystander.
        for (int i = 0; i < 200; i++) {
            var many = wire.send(Message.of("side.sighting", h1.id(), Scope.side("m1", "hunters"), facts("runner_at", Fact.of(i + ", 70, 0", Visibility.SIDE)),
                    List.of()), GameModes.WorldMode.MANHUNT, roster, 10_000L + i * 1000L);
            assertFalse(many.receivers().contains(r1.id()));
            assertFalse(many.receivers().contains(r2.id()));
            assertFalse(many.receivers().contains(bystander.id()));
        }
    }

    @Test
    void nothingAnAiPlayerSaidReentersAsAMessage() {
        var wire = new Wire(Wire.Config.defaults());
        var wren = ai("Wren", "oak", "", SPAWN);
        var ash = ai("Ash", "oak", "", SPAWN);
        var mat = person("Mat", "oak", "", SPAWN);
        var roster = List.of(wren, ash, mat);
        for (var origin : Message.Origin.values()) {
            var m = new Message("faction.need", wren.id(), Scope.faction("oak"), Map.of(), List.of(), origin);
            var d = wire.send(m, FACTIONS, roster, origin.ordinal() * 100_000L);
            if (origin == Message.Origin.RECORD) assertTrue(d.sent());
            else assertEquals(Delivery.Refusal.ECHO, d.refused().orElseThrow(), "an AI player sends " + origin);
        }
        // A person's words reach the AI players as a claim, never as told.
        var said = wire.send(new Message("faction.need", mat.id(), Scope.faction("oak"), facts("what", Fact.of("iron", Visibility.FACTION)), List.of(),
                Message.Origin.PERSON_WORDS), FACTIONS, roster, 1_000_000);
        assertTrue(said.sent());
        assertTrue(said.told().stream().allMatch(t -> t.howKnown() == HowKnown.CLAIMED));
        assertEquals(2, said.told().size());
        // The phrase handed to the voice is words for a person, not a message: it has no way back in.
        assertTrue(said.phrase().isEmpty());
    }

    @Test
    void eachChannelsBudgetAndDedupeHold() {
        var wire = new Wire(Wire.Config.defaults());
        var wren = ai("Wren", "oak", "", SPAWN);
        var ash = ai("Ash", "oak", "", SPAWN);
        var roster = List.of(wren, ash);
        var first = Message.of("faction.need", wren.id(), Scope.faction("oak"), facts("what", Fact.of("iron", Visibility.FACTION)), List.of());
        assertTrue(wire.send(first, FACTIONS, roster, 0).sent());
        assertEquals(Delivery.Refusal.DUPLICATE, wire.send(first, FACTIONS, roster, 59_999).refused().orElseThrow());
        assertTrue(wire.send(first, FACTIONS, roster, 60_000).sent());
        int sent = 0;
        for (int i = 0; i < 30; i++) {
            if (wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), facts("n", Fact.of("" + i, Visibility.PUBLIC)), List.of()),
                    FACTIONS, roster, 61_000 + i).sent()) sent++;
        }
        assertEquals(Channel.FACTION.perMinute - 1, sent, "the minute's lines, one already spent");
        assertTrue(wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), facts("n", Fact.of("late", Visibility.PUBLIC)), List.of()),
                FACTIONS, roster, 121_000).sent(), "a new minute");
        // Another faction's budget is its own.
        var rook = ai("Rook", "birch", "", SPAWN);
        assertTrue(wire.send(Message.of("faction.need", rook.id(), Scope.faction("birch"), Map.of(), List.of()), FACTIONS, List.of(rook), 61_500).sent());
    }

    @Test
    void thePublicNoticeIsSaidOncePerPersonInEarshotAndAnnouncementsAreTheServersAlone() {
        var wire = new Wire(Wire.Config.defaults().withAnnouncements(Set.of("market.shortage", "faction.vulnerable")));
        var far = new Listener.Place("minecraft:overworld", 500, 64, 0);
        var nether = new Listener.Place("minecraft:the_nether", 0, 64, 0);
        var wren = ai("Wren", "oak", "", SPAWN);
        var rook = ai("Rook", "birch", "", new Listener.Place("minecraft:overworld", 5, 64, 5));
        var near1 = person("Mat", "", "", new Listener.Place("minecraft:overworld", 10, 64, 0));
        var near2 = person("Ren", "birch", "", new Listener.Place("minecraft:overworld", -20, 64, 5));
        var away = person("Far", "", "", far);
        var under = person("Low", "", "", nether);
        var roster = List.of(wren, rook, near1, near2, away, under);
        var shortage = facts("item", Fact.of("iron ingot", Visibility.PUBLIC), "price", Fact.of("14", Visibility.PUBLIC));
        var a = wire.send(Message.of("market.shortage", wren.id(), wire.earshotOf(SPAWN), shortage, List.of()), FACTIONS, roster, 0);
        var b = wire.send(Message.of("market.shortage", rook.id(), wire.earshotOf(rook.at()), shortage, List.of()), FACTIONS, roster, 1_000);
        var phrased = new ArrayList<UUID>();
        a.phrase().forEach(p -> phrased.add(p.person()));
        b.phrase().forEach(p -> phrased.add(p.person()));
        assertEquals(List.of(near1.id(), near2.id()), phrased, "each person in earshot once, nobody far off or in another dimension");
        assertEquals(new HashSet<>(List.of(near1.id(), near2.id())), new HashSet<>(b.quiet()));
        // Only the server reaches the whole server, and only with a registered announcement.
        assertEquals(Delivery.Refusal.NOT_ANNOUNCEMENT, wire.send(Message.of("market.shortage", wren.id(), Scope.server(), shortage, List.of()),
                FACTIONS, roster, 2_000).refused().orElseThrow());
        assertEquals(Delivery.Refusal.NOT_ANNOUNCEMENT, wire.send(Message.of("faction.gossip", null, Scope.server(), Map.of(), List.of()),
                FACTIONS, roster, 2_000).refused().orElseThrow());
        int announced = 0;
        for (int i = 0; i < 12; i++) {
            var v = wire.send(Message.of("faction.vulnerable", null, Scope.server(), facts("faction", Fact.of("f" + i, Visibility.PUBLIC)), List.of()),
                    FACTIONS, roster, 3_000 + i);
            if (v.sent()) announced++;
            assertEquals(6, v.receivers().size());
        }
        assertEquals(12, announced, "announcements bypass the channel's budget");
    }

    @Test
    void eachModeUsesItsOwnChannelsAndOperatorsAloneHearAudits() {
        var wire = new Wire(Wire.Config.defaults());
        var wren = new Listener(UUID.randomUUID(), "Wren", false, Set.of("t1"), "", "", "", false, SPAWN, null);
        var ash = new Listener(UUID.randomUUID(), "Ash", false, Set.of("t1"), "", "", "", false, SPAWN, null);
        var op = new Listener(UUID.randomUUID(), "Op", true, Set.of(), "", "", "", true, SPAWN, null);
        var roster = List.of(wren, ash, op);
        var team = Message.of("team.find", wren.id(), Scope.team("t1"), Map.of(), List.of());
        assertTrue(wire.send(team, GameModes.WorldMode.COOP_SURVIVAL, roster, 0).sent());
        assertEquals(Delivery.Refusal.MODE, wire.send(team, GameModes.WorldMode.SPEEDRUN, roster, 1).refused().orElseThrow());
        assertEquals(Delivery.Refusal.MODE, wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), Map.of(), List.of()),
                GameModes.WorldMode.COOP_SURVIVAL, roster, 2).refused().orElseThrow());
        assertEquals(Set.of(Channel.MATCH, Channel.PUBLIC, Channel.OPERATOR), ModeChannels.of(GameModes.WorldMode.SPEEDRUN));
        assertEquals(Set.of(Channel.values()), ModeChannels.of(null));
        var audit = wire.send(Message.of("operator.anomaly", null, Scope.operators(), facts("note", Fact.of("claim refused", Visibility.OPERATOR)),
                List.of(9L)), GameModes.WorldMode.SPEEDRUN, roster, 3);
        assertEquals(List.of(op.id()), audit.receivers());
        assertEquals(Delivery.Refusal.NARROWER_FACT, wire.send(Message.of("team.note", wren.id(), Scope.team("t1"),
                facts("note", Fact.of("x", Visibility.OPERATOR)), List.of()), null, roster, 4).refused().orElseThrow());
        assertEquals(Delivery.Refusal.UNKNOWN_SENDER, wire.send(Message.of("team.find", UUID.randomUUID(), Scope.team("t1"), Map.of(), List.of()),
                null, roster, 5).refused().orElseThrow());
    }

    @Test
    void theBudgetHoldsUnderThreePlayersForFiveMinutes() {
        var wire = new Wire(Wire.Config.defaults());
        var a = ai("A", "oak", "", SPAWN);
        var b = ai("B", "oak", "", SPAWN);
        var c = ai("C", "oak", "", SPAWN);
        var mat = person("Mat", "oak", "", SPAWN);
        var shy = new Listener(UUID.randomUUID(), "Shy", true, Set.of(), "oak", "", "", false, SPAWN,
                ListenerPreferences.defaults(null).withUnasked(ListenerPreferences.Unasked.FEWER));
        var roster = List.of(a, b, c, mat, shy);
        int[] perMinute = new int[5], matLines = new int[5], shyLines = new int[5];
        int n = 0;
        for (long t = 0; t < 300_000; t += 1_000) {
            for (var from : List.of(a, b, c)) {
                var d = wire.send(Message.of("faction.need", from.id(), Scope.faction("oak"),
                        facts("n", Fact.of("" + (n++), Visibility.FACTION)), List.of()), FACTIONS, roster, t);
                int minute = (int) (t / 60_000);
                if (d.sent()) perMinute[minute]++;
                for (var p : d.phrase()) {
                    if (p.person().equals(mat.id())) matLines[minute]++;
                    if (p.person().equals(shy.id())) shyLines[minute]++;
                }
            }
        }
        for (int m = 0; m < 5; m++) {
            assertEquals(Channel.FACTION.perMinute, perMinute[m]);
            assertEquals(4, matLines[m]);
            assertEquals(2, shyLines[m], "fewer is half");
        }
        assertTrue(wire.heldForTest() < 200, "the Wire forgets what no window needs: " + wire.heldForTest());
    }

    @Test
    void aMatchMessageReachesEveryPlayerInItAndNobodyElseInEveryMode() {
        var wire = new Wire(Wire.Config.defaults());
        var hunter = ai("Hawk", "", Listener.side("m1", "hunters"), SPAWN);
        var runner = person("Ren", "", Listener.side("m1", "runners"), SPAWN);
        var other = person("Odd", "", Listener.side("m2", "runners"), SPAWN);
        var bystander = person("Guest", "", "", SPAWN);
        var roster = List.of(hunter, runner, other, bystander);
        var start = Message.of("match.started", null, Scope.match("m1"), facts("mode", Fact.of("manhunt", Visibility.MATCH)), List.of(11L));
        var d = wire.send(start, GameModes.WorldMode.MANHUNT, roster, 0);
        assertEquals(Set.of(hunter.id(), runner.id()), Set.copyOf(d.receivers()));
        // A side's fact never goes on the whole match's channel; an outsider cannot post on the match.
        assertEquals(Delivery.Refusal.NARROWER_FACT, wire.send(Message.of("match.note", hunter.id(), Scope.match("m1"),
                facts("runner_at", Fact.of("1, 2, 3", Visibility.SIDE)), List.of()), GameModes.WorldMode.MANHUNT, roster, 1).refused().orElseThrow());
        assertEquals(Delivery.Refusal.NOT_MEMBER, wire.send(Message.of("match.note", other.id(), Scope.match("m1"), Map.of(), List.of()),
                GameModes.WorldMode.MANHUNT, roster, 2).refused().orElseThrow());
        // A speedrun's split goes to its runners: each its own side of the match.
        var a = ai("A", "", Listener.side("s1", "A"), SPAWN);
        var b = person("B", "", Listener.side("s1", "B"), SPAWN);
        var split = wire.send(Message.of("match.split", a.id(), Scope.match("s1"), facts("split", Fact.of("nether 6:12", Visibility.MATCH)), List.of()),
                GameModes.WorldMode.SPEEDRUN, List.of(a, b, bystander), 3);
        assertEquals(List.of(b.id()), split.receivers());
        for (var mode : GameModes.WorldMode.values()) assertTrue(ModeChannels.allows(mode, Channel.MATCH), mode + " plays matches");
    }

    @Test
    void messagesAreBoundedAndKindsChecked() {
        var many = new LinkedHashMap<String, Fact>();
        for (int i = 0; i < 40; i++) many.put("k" + i, Fact.of("x".repeat(300), Visibility.PUBLIC));
        var ids = new ArrayList<Long>();
        for (long i = -2; i < 40; i++) ids.add(i);
        var m = Message.of("Faction.Need", UUID.randomUUID(), Scope.faction("oak"), many, ids);
        assertEquals("faction.need", m.kind());
        assertEquals(Message.MAX_FACTS, m.facts().size());
        assertEquals(Fact.MAX_CHARS, m.facts().get("k0").value().length());
        assertEquals(Message.MAX_EVENTS, m.eventIds().size());
        assertEquals(1L, m.eventIds().getFirst());
        assertThrows(IllegalArgumentException.class, () -> Message.of("bad kind!", null, Scope.operators(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Scope(Channel.FACTION, "", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Scope(Channel.PUBLIC, "", null, 0));
        assertEquals("a b", Fact.of("a\n§cb", Visibility.PUBLIC).value());
    }
}
