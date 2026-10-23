package dev.kindred.core.match;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MatchPresentationTest {
    private final UUID ada = UUID.randomUUID(), bram = UUID.randomUUID(), cy = UUID.randomUUID();
    private final Function<UUID, String> names = Map.of(ada, "Ada", bram, "Bram", cy, "Cy")::get;

    private MatchRegistry.Match started(MatchRegistry registry, long tick) {
        var match = registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, bram)).match().orElseThrow();
        registry.join(match.id(), ada);
        registry.join(match.id(), bram);
        return registry.start(match.id(), tick).match().orElseThrow();
    }

    @Test
    void theCountdownShowsEachWholeSecondLeftThenTheFight() {
        var registry = new MatchRegistry();
        var counting = started(registry, 100);
        assertEquals("3", MatchPresentation.countdown(counting, 100).orElseThrow().title(), "three seconds at the start");
        assertEquals("3", MatchPresentation.countdown(counting, 101).orElseThrow().title(), "the same for the whole second");
        assertEquals("2", MatchPresentation.countdown(counting, 120).orElseThrow().title());
        assertEquals("1", MatchPresentation.countdown(counting, 159).orElseThrow().title());
        assertEquals("1", MatchPresentation.countdown(counting, 140).orElseThrow().title());
        assertTrue(MatchPresentation.countdown(counting, 160).isEmpty(), "none at zero: the fight's own title instead");
        assertEquals(MatchPresentation.Cue.COUNT, MatchPresentation.countdown(counting, 120).orElseThrow().cue());
        assertEquals("Fight!", MatchPresentation.start().title());
        assertEquals("Duel: Ada vs Bram - starting in 3", MatchPresentation.bar(counting, 100, names).text());
        assertEquals("Duel: Ada vs Bram - starting in 1", MatchPresentation.bar(counting, 141, names).text(), "rounded up");
    }

    @Test
    void theBarCountsTheTimeLeftDown() {
        var registry = new MatchRegistry();
        started(registry, 0);
        var running = registry.tick(MatchRegistry.COUNTDOWN_TICKS).getFirst();
        var full = MatchPresentation.bar(running, MatchRegistry.COUNTDOWN_TICKS, names);
        assertEquals("Duel: Ada vs Bram - 5:00", full.text());
        assertEquals(1f, full.progress(), 1e-6);
        var half = MatchPresentation.bar(running, MatchRegistry.COUNTDOWN_TICKS + MatchRegistry.DUEL_TICKS / 2, names);
        assertEquals("Duel: Ada vs Bram - 2:30", half.text());
        assertEquals(0.5f, half.progress(), 1e-6);
        assertEquals("0:01", MatchPresentation.clock(1), "0:00 only at the end");
        assertEquals("0:00", MatchPresentation.clock(0));
    }

    @Test
    void eachPlayerSeesItsOwnResult() {
        var registry = new MatchRegistry();
        started(registry, 0);
        registry.tick(MatchRegistry.COUNTDOWN_TICKS);
        var ended = registry.fallen(bram).orElseThrow();
        assertEquals("Victory", MatchPresentation.result(ended, ada, names).title());
        assertEquals("Ada won: Bram fell", MatchPresentation.result(ended, ada, names).subtitle());
        assertEquals(MatchPresentation.Cue.WIN, MatchPresentation.result(ended, ada, names).cue());
        assertEquals("Defeat", MatchPresentation.result(ended, bram, names).title());
        assertEquals(MatchPresentation.Cue.LOSE, MatchPresentation.result(ended, bram, names).cue());
        assertEquals("Ada wins", MatchPresentation.result(ended, cy, names).title(), "an onlooker");
        var stopped = registry.stop(started(registry, 10_000).id()).match().orElseThrow();
        assertEquals("Stopped", MatchPresentation.result(stopped, ada, names).title());
        assertEquals("No winner", MatchPresentation.result(stopped, ada, names).subtitle());
        started(registry, 20_000);
        registry.tick(20_000 + MatchRegistry.COUNTDOWN_TICKS);
        var draw = registry.tick(20_000 + MatchRegistry.COUNTDOWN_TICKS + MatchRegistry.DUEL_TICKS).getLast();
        assertEquals("Draw", MatchPresentation.result(draw, bram, names).title());
    }

    @Test
    void aRaceCountsItsTimeUpAndShowsEachSplit() {
        var registry = new MatchRegistry();
        var race = registry.propose(MatchRegistry.Mode.SPEEDRUN, List.of(ada, bram)).match().orElseThrow();
        registry.join(race.id(), ada);
        registry.join(race.id(), bram);
        registry.start(race.id(), 0);
        var running = registry.tick(MatchRegistry.COUNTDOWN_TICKS).getFirst();
        var bar = MatchPresentation.bar(running, MatchRegistry.COUNTDOWN_TICKS + 20 * 75, names, 0.5f);
        assertEquals("Speedrun: Ada vs Bram - 1:15", bar.text(), "the time since the start");
        assertEquals(0.5f, bar.progress(), 1e-6, "as far as the leader is");
        var splits = new SpeedrunSplits(MatchRegistry.COUNTDOWN_TICKS);
        var nether = splits.reach(bram, SpeedrunSplits.ANY_PERCENT.get(1), MatchRegistry.COUNTDOWN_TICKS + 20 * 252 + 6).orElseThrow();
        assertEquals("Bram: Nether at 4:12.30", MatchPresentation.split(nether, names));
        var won = registry.finished(bram).orElseThrow();
        assertEquals("Victory", MatchPresentation.result(won, bram, names).title());
        assertEquals("Bram freed the End first", MatchPresentation.result(won, ada, names).subtitle());
        assertEquals("Defeat", MatchPresentation.result(won, ada, names).title());
    }
}
