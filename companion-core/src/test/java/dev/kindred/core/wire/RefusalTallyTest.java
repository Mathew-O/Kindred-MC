package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: the Wire's refusals are counted by why and on which channel, and the count says nothing of what was
 * refused: not its kind, its sender or its facts (F-34e).
 */
class RefusalTallyTest {
    private static final Listener.Place SPAWN = new Listener.Place("minecraft:overworld", 0, 64, 0);

    private static Listener ai(String name, String faction) {
        return new Listener(UUID.randomUUID(), name, false, Set.of(), faction, "", "", false, SPAWN, null);
    }

    private static Map<String, Fact> what(String value) {
        var out = new LinkedHashMap<String, Fact>();
        out.put("what", Fact.of(value, Visibility.FACTION));
        return out;
    }

    @Test
    void eachRefusalIsCountedByReasonAndChannelAndNothingItSaid() {
        var tally = new RefusalTally();
        assertEquals("none refused since start", tally.describe());
        assertEquals(0, tally.total());

        var wire = new Wire(Wire.Config.defaults());
        var wren = ai("Wren", "oak");
        var ash = ai("Ash", "oak");
        var rook = ai("Rook", "birch");
        var roster = List.of(wren, ash, rook);
        var mode = GameModes.WorldMode.FACTIONS;
        var secret = what("the vault behind the waterfall");
        var sends = List.of(
                wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), secret, List.of()), mode, roster, 0),
                wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), secret, List.of()), mode, roster, 1_000),
                wire.send(Message.of("faction.need", wren.id(), Scope.faction("oak"), secret, List.of()), mode, roster, 2_000),
                wire.send(Message.of("faction.need", rook.id(), Scope.faction("oak"), secret, List.of()), mode, roster, 3_000),
                wire.send(Message.of("faction.need", UUID.randomUUID(), Scope.faction("oak"), secret, List.of()), mode, roster, 4_000));
        assertTrue(sends.getFirst().sent());
        for (var d : sends) d.refused().ifPresent(r -> tally.count(r, d.message().scope().channel()));

        assertEquals(4, tally.total());
        assertEquals(Map.of("DUPLICATE on faction", 2L, "NOT_MEMBER on faction", 1L, "UNKNOWN_SENDER on faction", 1L), tally.snapshot());
        String line = tally.describe();
        assertEquals("4 refused since start (DUPLICATE on faction 2, NOT_MEMBER on faction 1, UNKNOWN_SENDER on faction 1)", line);
        // Nothing of what was refused: not its facts, its kind, its scope's key or who sent it.
        for (String withheld : List.of("vault", "waterfall", "faction.need", "oak", "Wren", "Rook", wren.id().toString(), rook.id().toString())) {
            assertFalse(line.contains(withheld), withheld + " in " + line);
        }
        // A copy: the tally goes on counting after a snapshot.
        var before = tally.snapshot();
        tally.count(Delivery.Refusal.BUDGET, Channel.TEAM);
        assertEquals(3, before.size());
        assertEquals(5, tally.total());
    }
}
