package dev.kindred.model;

import dev.kindred.core.chronicle.Chronicle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the request a Chronicle page is written from, and, only when someone asks for it and a key
 * is set, that a real model's page cites only real events and contradicts no ledger.
 *
 * Technical: The live case runs only with {@code KINDRED_LIVE_MODEL_TEST} and {@code ANTHROPIC_API_KEY} set; the
 * ordinary build never calls the network.
 */
class ChroniclePromptTest {
    private static final UUID WREN = UUID.randomUUID();

    static List<Chronicle.Event> day() {
        return List.of(new Chronicle.Event(101, 100, "death", WREN, "Wren", "Wren fell into lava at 120 12 -40"),
                new Chronicle.Event(102, 900, "death", WREN, "Wren", "Wren was killed by a Zombie at night"),
                new Chronicle.Event(103, 2_000, "trade", WREN, "Wren", "Wren sold 16 iron ingots at the market for 14 coins each"),
                new Chronicle.Event(104, 5_000, "capture", WREN, "oak", "the faction oak took the chunk 12, -4 from birch"),
                new Chronicle.Event(105, 8_000, "said", WREN, "Mat", "Mat said he would bring bread (a claim)"));
    }

    static Chronicle.Facts facts() {
        return new Chronicle.Facts(Map.of("Wren", 2), Map.of("chunk 12, -4", "oak"), Map.of("iron ingot", 14, "iron ingots", 14));
    }

    @Test
    void theRequestListsOnlyTheTierBelowNumbered() {
        var lines = day().stream().map(ChroniclePrompt::line).toList();
        String request = ChroniclePrompt.request(Chronicle.Tier.PLAYER_DAY, "Wren", 0, lines);
        assertTrue(request.startsWith("Write the page for the game day 1 of Wren. Its lines, in the order they happened:\n"), request);
        assertTrue(request.contains("[101] death: Wren fell into lava at 120 12 -40\n"));
        assertEquals(5, request.lines().filter(l -> l.startsWith("[")).count());
        var page = Chronicle.plain(Chronicle.Tier.PLAYER_DAY, WREN.toString(), 0, day());
        assertTrue(ChroniclePrompt.line(page).startsWith("(cites 101, 102, "), ChroniclePrompt.line(page));
        assertTrue(ChroniclePrompt.SYSTEM.contains("Cite the number of every line"));
    }

    @Test
    void aRealPageCitesRealEventsAndContradictsNoLedger() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        var strategist = ClaudeStrategist.fromEnvironment(null);
        Assumptions.assumeTrue(strategist.isPresent(), "no ANTHROPIC_API_KEY");
        var events = day();
        Set<Long> ids = Set.of(101L, 102L, 103L, 104L, 105L);
        String request = ChroniclePrompt.request(Chronicle.Tier.PLAYER_DAY, "Wren", 0, events.stream().map(ChroniclePrompt::line).toList());
        try (var s = strategist.get()) {
            for (int i = 0; i < 3; i++) {
                var page = s.chronicle(request).get(90, TimeUnit.SECONDS);
                System.out.println("chronicle page: " + page);
                assertTrue(page.failure().isEmpty(), "page: " + page.failure());
                var summary = Chronicle.fromModel(Chronicle.Tier.PLAYER_DAY, WREN.toString(), 0, page.text(), page.cites()).orElseThrow();
                var problems = Chronicle.check(summary, ids, facts());
                System.out.println("checks: " + problems + "; " + summary.text().split("\\s+").length + " words; tokens in " + page.inputTokens()
                        + ", out " + page.outputTokens());
                assertTrue(problems.none(), problems.toString());
                assertFalse(summary.cites().isEmpty());
                var plain = Chronicle.plain(Chronicle.Tier.PLAYER_DAY, WREN.toString(), 0, events);
                assertSame(summary, Chronicle.keep(Optional.of(summary), plain, ids, facts()));
            }
        }
    }
}
