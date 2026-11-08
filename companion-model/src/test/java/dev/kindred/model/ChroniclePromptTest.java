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

    /** A busy day: thirty events, most of them routine, two deaths, a sale, a capture, a claim among them. */
    static List<Chronicle.Event> busyDay() {
        var out = new java.util.ArrayList<Chronicle.Event>();
        long id = 200;
        String[] routine = {"Wren mined coal", "Wren chopped an oak log", "Wren ate bread", "Wren crafted torches", "Wren mined iron ore"};
        for (int i = 0; i < 25; i++) out.add(new Chronicle.Event(++id, 100L + i * 700, routine[i % routine.length].contains("mined") ? "mine" : "work", WREN, "Wren", routine[i % routine.length]));
        out.add(new Chronicle.Event(++id, 18_000, "death", WREN, "Wren", "Wren fell into lava at 120 12 -40"));
        out.add(new Chronicle.Event(++id, 19_000, "trade", WREN, "Wren", "Wren sold 16 iron ingots at the market for 14 coins each"));
        out.add(new Chronicle.Event(++id, 20_000, "capture", WREN, "oak", "the faction oak took the chunk 12, -4 from birch"));
        out.add(new Chronicle.Event(++id, 21_000, "death", WREN, "Wren", "Wren was killed by a Zombie at night"));
        out.add(new Chronicle.Event(++id, 22_000, "said", WREN, "Mat", "Mat said he would bring bread (a claim)"));
        return out;
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
        assertTrue(ChroniclePrompt.line(page).startsWith("[101] player day: 2 deaths"), ChroniclePrompt.line(page));
        assertTrue(ChroniclePrompt.SYSTEM.contains("giving the numbers of the lines"));
        var built = ChroniclePrompt.page(List.of(new long[] {105}, new long[] {101}, new long[] {101}), List.of("Mat said he would bring bread", "Wren fell", ""), 1, 0, 1, 1);
        assertEquals("Wren fell. Mat said he would bring bread.", built.text().orElseThrow(), "the lines' order; an empty sentence dropped");
        assertEquals(List.of(101L, 105L), built.cites());
    }

    @Test
    void aRealPageCitesRealEventsAndContradictsNoLedger() throws Exception {
        Assumptions.assumeTrue(System.getenv("KINDRED_LIVE_MODEL_TEST") != null, "live model test not requested");
        // The release jar's path when KINDRED_STRATEGIST_PROVIDER is set (the OpenAI-compatible transport), else the SDK's.
        Optional<? extends Strategist> strategist;
        if (System.getenv("KINDRED_STRATEGIST_PROVIDER") != null) {
            var env = new java.util.HashMap<>(System.getenv());
            env.putIfAbsent("KINDRED_STRATEGIST_MODEL", ClaudeStrategist.DEFAULT_MODEL);
            strategist = Strategists.fromEnvironment(env).strategist();
        } else {
            strategist = ClaudeStrategist.fromEnvironment(null);
        }
        Assumptions.assumeTrue(strategist.isPresent(), "no strategist configured");
        System.out.println("chronicle via " + strategist.get().provider());
        var events = System.getenv("KINDRED_CHRONICLE_BUSY") != null ? busyDay() : day();
        Set<Long> ids = new java.util.HashSet<>(events.stream().map(Chronicle.Event::id).toList());
        String request = ChroniclePrompt.request(Chronicle.Tier.PLAYER_DAY, "Wren", 0, events.stream().map(ChroniclePrompt::line).toList());
        String lines = String.join("\n", events.stream().map(ChroniclePrompt::line).toList());
        System.out.println("chronicle input: " + events.size() + " events, " + lines.length() + " characters");
        int kept = 0, asked = 10;
        try (var s = strategist.get()) {
            for (int i = 0; i < asked; i++) {
                var page = s.chronicle(request).get(90, TimeUnit.SECONDS);
                System.out.println("chronicle page: " + page);
                assertTrue(page.failure().isEmpty(), "page: " + page.failure());
                var summary = Chronicle.fromModel(Chronicle.Tier.PLAYER_DAY, WREN.toString(), 0, page.text(), page.cites()).orElseThrow();
                var problems = Chronicle.check(summary, ids, facts());
                System.out.println("page chars " + summary.text().length() + "; checks: " + problems + "; " + summary.text().split("\\s+").length + " words; tokens in " + page.inputTokens()
                        + ", out " + page.outputTokens());
                var plain = Chronicle.plain(Chronicle.Tier.PLAYER_DAY, WREN.toString(), 0, events);
                var chosen = Chronicle.keep(Optional.of(summary), plain, ids, facts());
                assertTrue(Chronicle.check(chosen, ids, facts()).none(), "whatever is kept passes");
                if (chosen == summary) kept++;
                assertTrue(page.cites().stream().allMatch(ids::contains) || chosen == plain, "an invented citation is never kept");
            }
        }
        System.out.println("chronicle pages kept: " + kept + " of " + asked);
        assertTrue(kept > 0, "at least one model page passes");
    }
}
