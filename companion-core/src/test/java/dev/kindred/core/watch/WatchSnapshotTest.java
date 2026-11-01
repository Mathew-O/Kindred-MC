package dev.kindred.core.watch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchSnapshotTest {
    private static WatchSnapshot.Player player(String name, int lines) {
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < lines; i++) many.add("line " + i);
        return new WatchSnapshot.Player("id-" + name, name, true, 17.5f, 18, "NETHER_PORTAL", "Iron: Mine iron ore (1/3)", "Mine iron ore at 3, 12, -4",
                "Working", "minecraft:overworld", 3, 12, -4,
                List.of(new WatchSnapshot.Candidate("Mine iron ore at 3, 12, -4", "PROJECT", 64.04), new WatchSnapshot.Candidate("native_eat", "URGENT", 90)),
                many, List.of("Morning.", "Off to find iron, \"properly\" this time.\nTwo lines?"), List.of("MORE_FOOD"), List.of("Twice dead hungry; carry more food."), 1234);
    }

    private static WatchSnapshot snapshot() {
        return new WatchSnapshot(42, "Server thread", List.of(player("Echo", 3)),
                List.of(new WatchSnapshot.Highlight(40, "Echo", "stage", "Iron: reached")), null);
    }

    /** Every key a JSON text has, at any depth. */
    private static Set<String> keys(String json) {
        var keys = new HashSet<String>();
        var m = Pattern.compile("\"(\\w+)\": ").matcher(json);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    @Test
    void thePlayersJsonCarriesEachPlayerWithItsTextEscaped() {
        String json = snapshot().playersJson();
        assertTrue(json.startsWith("{\"tick\": 42, \"takenOn\": \"Server thread\", \"players\": [{\"id\": \"id-Echo\""), json);
        assertTrue(json.contains("\"health\": 17.5, \"food\": 18"), json);
        assertTrue(json.contains("{\"what\": \"Mine iron ore at 3, 12, -4\", \"priority\": \"PROJECT\", \"score\": 64.0}"), json);
        assertTrue(json.contains("\"lastWords\": \"Off to find iron, \\\"properly\\\" this time.\\nTwo lines?\""), "Quotes and newlines escaped: " + json);
        assertTrue(json.contains("\"habits\": [\"MORE_FOOD\"], \"notes\": [\"Twice dead hungry; carry more food.\"], \"tokensToday\": 1234"), json);
        assertEquals(json.chars().filter(c -> c == '{').count(), json.chars().filter(c -> c == '}').count(), "Balanced: " + json);
    }

    @Test
    void onePlayerByIdOrNameAndNoneForAStranger() {
        var s = snapshot();
        assertTrue(s.playerJson("id-Echo").orElseThrow().contains("\"name\": \"Echo\""));
        assertTrue(s.playerJson("echo").isPresent(), "By name, whatever its case");
        assertTrue(s.playerJson("Steve").isEmpty());
    }

    @Test
    void theHighlightsAndTheEvalAreServedAsTheyAre() {
        var s = snapshot();
        assertEquals("{\"tick\": 42, \"highlights\": [{\"tick\": 40, \"player\": \"Echo\", \"kind\": \"stage\", \"text\": \"Iron: reached\"}]}", s.eventsJson());
        assertEquals("{\"eval\": null}", s.evalJson());
        var running = new WatchSnapshot(1, "Server thread", List.of(), List.of(), "{\"seed\": 7}");
        assertEquals("{\"eval\": {\"seed\": 7}}", running.evalJson());
    }

    @Test
    void itIsBoundedAndCopied() {
        var lines = new java.util.ArrayList<>(List.of("a"));
        var p = new WatchSnapshot.Player("i", "N", false, 0, 0, null, null, null, null, null, 0, 0, 0, List.of(), lines, List.of(), List.of(), List.of(), 0);
        lines.add("b");
        assertEquals(List.of("a"), p.lines(), "A copy: what it was given changing later changes nothing");
        assertEquals(WatchSnapshot.LINES, player("Sol", 100).lines().size());
        assertEquals("line 99", player("Sol", 100).lines().getLast(), "The latest kept");
        var many = new java.util.ArrayList<WatchSnapshot.Highlight>();
        for (int i = 0; i < 80; i++) many.add(new WatchSnapshot.Highlight(i, "Sol", "death", "died " + i));
        var s = new WatchSnapshot(80, "Server thread", List.of(), many, null);
        assertEquals(WatchSnapshot.HIGHLIGHTS, s.highlights().size());
        assertEquals(79, s.highlights().getLast().tick());
        assertTrue(new WatchSnapshot.Player("i", "N", false, 0, 0, null, null, null, null, null, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0)
                .goal() == null && snapshot().playersJson().contains("\"goal\": \"NETHER_PORTAL\""));
    }

    /** The page reads only what the snapshot gives: every field it binds is one of the JSON's. */
    @Test
    void everyFieldThePageReadsIsOneTheSnapshotWrites() throws IOException {
        String page;
        try (var in = WatchSnapshot.class.getResourceAsStream("watch.html")) {
            assertTrue(in != null, "The page is beside the snapshot");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var s = snapshot();
        var written = keys(s.playersJson());
        written.addAll(keys(s.eventsJson()));
        var read = new HashSet<String>();
        var m = Pattern.compile("\\b(?:p|c|h|data)\\.(\\w+)").matcher(page);
        while (m.find()) read.add(m.group(1));
        assertFalse(read.isEmpty());
        read.removeIf(written::contains);
        assertEquals(Set.of(), read, "Fields the page reads that the snapshot does not write");
        assertFalse(page.contains("innerHTML"), "Nothing from the snapshot is put in as markup");
        assertFalse(Pattern.compile("(?i)<script[^>]+src=|<link[^>]+href=|https?://").matcher(page).find(), "No CDN, nothing from elsewhere");
        assertFalse(Pattern.compile("method:\\s*\"(POST|PUT|DELETE|PATCH)\"").matcher(page).find(), "It only reads");
    }
}
