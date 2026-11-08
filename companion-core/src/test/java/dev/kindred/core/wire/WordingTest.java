package dev.kindred.core.wire;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** In plain terms: checks the plain words a message is said in: its kind and facts, a kind's own line, never half a line. */
class WordingTest {
    @Test
    void plainWordsCarryOnlyTheMessagesFacts() {
        var facts = new LinkedHashMap<String, Fact>();
        facts.put("target", Fact.of("birch's east wall", Visibility.FACTION));
        facts.put("when", Fact.of("dusk", Visibility.FACTION));
        var m = Message.of("faction.raid_planned", UUID.randomUUID(), Scope.faction("oak"), facts, List.of(3L));
        var w = new Wording();
        assertEquals("[faction] Wren: raid planned: target birch's east wall, when dusk.", w.words(m, "Wren"));
        w.with("faction.raid_planned", "Raid on {target} at {when}.");
        assertEquals("[faction] Wren: Raid on birch's east wall at dusk.", w.words(m, "Wren"));
        w.with("faction.raid_planned", "Raid on {target} with {count} of us.");
        assertNull(w.words(m, "Wren"), "a line naming a fact the message lacks is not said");
        var server = Message.of("market.shortage", null, Scope.server(), facts, List.of());
        assertTrue(w.words(server, "").startsWith("[public] shortage: "));
        var big = new LinkedHashMap<String, Fact>();
        for (int i = 0; i < 10; i++) big.put("k" + i, Fact.of("y".repeat(90), Visibility.PUBLIC));
        assertEquals(Wording.MAX_CHARS, w.words(Message.of("team.find", UUID.randomUUID(), Scope.team("t"), big, List.of()), "A").length());
    }
}
