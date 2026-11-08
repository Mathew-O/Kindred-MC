package dev.kindred.core.chronicle;

import dev.kindred.core.chronicle.Chronicle.Event;
import dev.kindred.core.chronicle.Chronicle.Facts;
import dev.kindred.core.chronicle.Chronicle.Summary;
import dev.kindred.core.chronicle.Chronicle.Tier;
import dev.kindred.core.wire.Visibility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the Chronicle: each tier written only from the one below, the plain list when there is no
 * model, every cited event real, no summary contradicting a ledger's death count, owner or price, a bad summary
 * replaced by the plain list, and the strategist's history shorter with the chronicle than with its event list.
 */
class ChronicleTest {
    private static final UUID WREN = UUID.randomUUID();

    private static List<Event> day(long day) {
        long t = day * Chronicle.DAY;
        long base = day * 100;
        return List.of(new Event(base + 1, t + 100, "death", WREN, "Wren", "Wren fell into lava"),
                new Event(base + 2, t + 900, "death", WREN, "Wren", "Wren was killed by a Zombie"),
                new Event(base + 3, t + 2_000, "trade", WREN, "Wren", "4 iron ingots for 6 bread"),
                new Event(base + 4, t + 5_000, "capture", WREN, "oak", "oak took the chunk 12, -4 from birch"),
                new Event(base + 5, t + Chronicle.DAY + 1, "death", WREN, "Wren", "next day's"));
    }

    @Test
    void tiersAndThePlainList() {
        assertNull(Tier.PLAYER_DAY.below());
        assertEquals(Tier.PLAYER_DAY, Tier.FACTION_WEEK.below());
        assertEquals(Tier.FACTION_WEEK, Tier.WORLD_MONTH.below());
        assertEquals(Visibility.SELF, Tier.PLAYER_DAY.visibility());
        assertEquals(Visibility.FACTION, Tier.FACTION_WEEK.visibility());
        assertEquals(Visibility.PUBLIC, Tier.WORLD_MONTH.visibility());
        assertEquals(7 * Chronicle.DAY, Tier.FACTION_WEEK.start(7 * Chronicle.DAY + 5));

        var plain = Chronicle.plain(Tier.PLAYER_DAY, WREN.toString(), 0, day(0));
        assertTrue(plain.text().startsWith("2 deaths: Wren fell into lava; Wren was killed by a Zombie [1, 2]."), plain.text());
        assertTrue(plain.text().contains("1 trade: 4 iron ingots for 6 bread [3]."), plain.text());
        assertEquals(List.of(1L, 2L, 4L, 3L), plain.cites(), "every event of its day, and only its day");
        assertFalse(plain.model());
        assertEquals("Nothing recorded.", Chronicle.plain(Tier.PLAYER_DAY, "x", 0, List.of()).text());

        // A week is written only from day summaries, its citations carried up; events or other tiers are not read.
        var days = new ArrayList<Summary>();
        for (long d = 0; d < 7; d++) days.add(Chronicle.plain(Tier.PLAYER_DAY, WREN.toString(), d * Chronicle.DAY, day(d)));
        days.add(new Summary(Tier.WORLD_MONTH, "world", 0, 30 * Chronicle.DAY, "not read", List.of(999L), false));
        var week = Chronicle.plainFrom(Tier.FACTION_WEEK, "oak", 0, days);
        assertFalse(week.text().contains("not read"));
        assertFalse(week.cites().contains(999L));
        assertEquals(28, week.cites().size());
        assertTrue(week.text().startsWith("Day 1 (" + WREN + "): 2 deaths"), week.text());
    }

    @Test
    void citationsMustExistAndNothingContradictsALedger() {
        var events = day(0);
        Set<Long> existing = Set.of(1L, 2L, 3L, 4L);
        var facts = new Facts(Map.of("Wren", 2), Map.of("chunk 12, -4", "oak"), Map.of("iron ingot", 14));
        var good = new Summary(Tier.PLAYER_DAY, WREN.toString(), 0, Chronicle.DAY,
                "A hard day: Wren died twice, once in lava, and traded iron for bread. Oak took the chunk 12, -4. Iron ingot at 14 coins.",
                List.of(1L, 2L, 3L, 4L), true);
        assertTrue(Chronicle.check(good, existing, facts).none(), Chronicle.check(good, existing, facts).toString());

        var invented = new Summary(Tier.PLAYER_DAY, WREN.toString(), 0, Chronicle.DAY, "Wren died twice.", List.of(1L, 77L), true);
        assertEquals(List.of(77L), Chronicle.check(invented, existing, facts).missing());

        for (String wrong : List.of("Wren died three times.", "Wren died once.", "A sad day; Wren had 3 deaths.",
                "Birch owns chunk 12, -4.", "Iron ingot sold for 20 coins.")) {
            var bad = new Summary(Tier.PLAYER_DAY, WREN.toString(), 0, Chronicle.DAY, wrong, List.of(1L), true);
            assertEquals(1, Chronicle.check(bad, existing, facts).contradictions().size(), wrong + " " + Chronicle.check(bad, existing, facts));
        }
        // Something the ledgers do not hold is not contradicted.
        var other = new Summary(Tier.PLAYER_DAY, WREN.toString(), 0, Chronicle.DAY, "Ash died twice. Rook owns a boat. Bread at 2 coins.", List.of(1L), true);
        assertTrue(Chronicle.check(other, existing, facts).none());

        // A failing model summary is never kept: the plain list is.
        var plain = Chronicle.plain(Tier.PLAYER_DAY, WREN.toString(), 0, events);
        assertSame(plain, Chronicle.keep(Optional.of(invented), plain, existing, facts));
        assertSame(good, Chronicle.keep(Optional.of(good), plain, existing, facts));
        assertSame(plain, Chronicle.keep(Optional.empty(), plain, existing, facts));
        assertTrue(Chronicle.check(plain, existing, facts).none(), "the plain list passes its own checks");
    }

    @Test
    void theStrategistsHistoryShrinksWithTheChronicle() {
        // Sixteen events as the campaign logs them, against yesterday's summary and the events since.
        var events = new ArrayList<String>();
        String[] what = {"died: Zombie at 118 40 -30 (2 deaths so far)", "stage changed: Iron: Smelt iron for a bucket",
                "no progress for 60s at 120 12 -40 (stuck spells 3)", "advice applied: PLAY_SAFE, focus FOOD",
                "picked up 3 raw iron", "crafted a stone pickaxe", "fled a Creeper at 101 63 -12", "ate bread (food 14)"};
        for (int i = 0; i < 16; i++) events.add("at " + (600 + i * 75) + "s: " + what[i % what.length]);
        var yesterday = new Summary(Tier.PLAYER_DAY, WREN.toString(), 0, Chronicle.DAY,
                "Wren died twice (lava, a Zombie), smelted its first iron and fled two Creepers; play-safe advice held.", List.of(1L, 2L), true);
        var before = Chronicle.reportHistory(List.of(), events, 0, 1, 4);
        var after = Chronicle.reportHistory(List.of(yesterday), events, 0, 1, 4);
        assertEquals(events, before, "no chronicle: the events as before");
        assertEquals(1 + 4, after.size(), "the summary and the last four events since it");
        assertTrue(after.getLast().equals(events.getLast()));
        int b = String.join("\n", before).length(), a = String.join("\n", after).length();
        assertTrue(a < b * 0.6, "shorter: " + a + " against " + b);
        System.out.println("F-33 strategist history: " + b + " chars with 16 events, " + a + " with the chronicle (" + (100 - a * 100 / b) + "% shorter)");
        // Events before the summary's end are not repeated.
        var oldOnly = List.of("at 10s: before the summary");
        assertEquals(1, Chronicle.reportHistory(List.of(yesterday), oldOnly, 0, 1, 4).size());
    }
}
