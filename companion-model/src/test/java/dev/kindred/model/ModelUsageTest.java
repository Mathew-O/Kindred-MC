package dev.kindred.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** In plain terms: each call's log line gives what it cost, a cache write the provider did not report as "n/a" (F-19g). */
class ModelUsageTest {
    @Test
    void eachCallsLineGivesItsTokensAndAnUnreportedWriteIsNotAZero() {
        var page = new Strategist.Page(Optional.of("Wren mined coal."), List.of(7L), 2_400, 0, 90, 1_200, Optional.empty());
        assertEquals(Strategist.NOT_REPORTED, page.cacheWriteTokens(), "the old shape reports no write");
        assertEquals("in 2400, cache read 0, cache write n/a, out 90 tokens, 1200 ms", ModelUsage.of(page));
        var written = page.withCacheWrite(2_100);
        assertEquals(2_100, written.cacheWriteTokens());
        assertEquals(page.text(), written.text());
        assertEquals("in 2400, cache read 0, cache write 2100, out 90 tokens, 1200 ms", ModelUsage.of(written));

        var stance = new Strategist.Outcome(Optional.empty(), 6_852, 6_500, 108, 1_931, Optional.empty()).withCacheWrite(0);
        assertEquals("in 6852, cache read 6500, cache write 0, out 108 tokens, 1931 ms", ModelUsage.of(stance));

        var line = new Strategist.Line(Optional.of("Hello."), "greeting", 900, 0, 20, 800, Optional.empty()).withCacheWrite(0);
        assertEquals("in 900, cache read 0, cache write 0, out 20 tokens, 800 ms", ModelUsage.of(line));
        assertEquals(Strategist.Ask.NONE, line.ask(), "the rest kept");
    }
}
