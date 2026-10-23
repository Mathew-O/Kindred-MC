package dev.kindred.core.match;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchRegistryTest {
    private final UUID ada = UUID.randomUUID(), bram = UUID.randomUUID(), cy = UUID.randomUUID();

    @Test
    void playersMayAttackOneAnotherOnlyWhileAMatchTheyJoinedRuns() {
        var registry = new MatchRegistry();
        var match = registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, bram)).match().orElseThrow();
        assertFalse(registry.mayAttack(ada, bram), "proposed, not joined");
        registry.join(match.id(), ada);
        assertFalse(registry.start(match.id(), 0).accepted(), "not until every invited player joins");
        registry.join(match.id(), bram);
        assertTrue(registry.start(match.id(), 100).accepted());
        assertFalse(registry.mayAttack(ada, bram), "not during the countdown");
        assertTrue(registry.tick(159).isEmpty());
        assertEquals(MatchRegistry.State.RUNNING, registry.tick(160).getFirst().state());
        assertTrue(registry.mayAttack(ada, bram) && registry.mayAttack(bram, ada), "both ways, while it runs");
        assertFalse(registry.mayAttack(ada, cy), "never anyone outside it");
        assertFalse(registry.mayAttack(cy, ada));
        assertFalse(registry.mayAttack(ada, ada));
        registry.stop(match.id());
        assertFalse(registry.mayAttack(ada, bram), "stopped: the leave to attack ends at once");
    }

    @Test
    void onlyTheInvitedJoinAndAPlayerIsInOneMatchAtATime() {
        var registry = new MatchRegistry();
        var match = registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, bram)).match().orElseThrow();
        assertFalse(registry.join(match.id(), cy).accepted(), "not invited");
        assertFalse(registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, cy)).accepted(), "already invited elsewhere");
        assertFalse(registry.propose(MatchRegistry.Mode.DUEL, List.of(cy, cy)).accepted(), "two different players");
        registry.join(match.id(), ada);
        assertTrue(registry.leave(match.id(), ada).accepted(), "left before the start");
        assertFalse(registry.get(match.id()).orElseThrow().participant(ada));
    }

    @Test
    void aDuelEndsWhenOneFallsOrForfeitsOrTimeRunsOut() {
        var registry = new MatchRegistry();
        var first = running(registry, ada, bram, 0);
        var fell = registry.fallen(ada).orElseThrow();
        assertEquals(bram, fell.winner());
        assertEquals("fell", fell.result());
        assertFalse(registry.mayAttack(bram, ada));
        assertTrue(registry.fallen(ada).isEmpty(), "no running match left");
        var second = running(registry, ada, bram, 1000);
        var forfeited = registry.leave(second.id(), bram).match().orElseThrow();
        assertEquals(ada, forfeited.winner());
        var third = running(registry, ada, bram, 2000);
        var draw = registry.tick(2000 + MatchRegistry.COUNTDOWN_TICKS + MatchRegistry.DUEL_TICKS);
        assertEquals(1, draw.size());
        assertNull(draw.getFirst().winner(), "a draw");
        assertNotEquals(first.id(), third.id());
        registry.stopAll();
        assertTrue(registry.list().stream().allMatch(m -> m.state() == MatchRegistry.State.ENDED));
    }

    private static MatchRegistry.Match running(MatchRegistry registry, UUID a, UUID b, long tick) {
        var match = registry.propose(MatchRegistry.Mode.DUEL, List.of(a, b)).match().orElseThrow();
        registry.join(match.id(), a);
        registry.join(match.id(), b);
        registry.start(match.id(), tick);
        registry.tick(tick + MatchRegistry.COUNTDOWN_TICKS);
        return registry.running(a).orElseThrow();
    }

    @Test
    void leavingTheGameForfeitsAStartedDuelAndCallsOffOneNotYetStarted() {
        var registry = new MatchRegistry();
        var lobby = registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, bram)).match().orElseThrow();
        registry.join(lobby.id(), ada);
        var calledOff = registry.departed(bram).orElseThrow();
        assertEquals(MatchRegistry.State.ENDED, calledOff.state());
        assertNull(calledOff.winner(), "never fought, nobody wins");
        assertTrue(registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, cy)).accepted(), "Ada is free for another");
        var other = new MatchRegistry();
        var started = running(other, ada, bram, 0);
        var forfeit = other.departed(ada).orElseThrow();
        assertEquals(bram, forfeit.winner());
        assertEquals(started.id(), forfeit.id());
        assertFalse(other.mayAttack(bram, ada));
        assertTrue(other.departed(ada).isEmpty(), "nothing left to leave");
    }

    @Test
    void aMatchIsFoundByTheStartOfItsId() {
        var registry = new MatchRegistry();
        var match = registry.propose(MatchRegistry.Mode.DUEL, List.of(ada, bram)).match().orElseThrow();
        String id = match.id().toString();
        assertEquals(match.id(), registry.find(MatchRegistry.shortId(match.id())).orElseThrow().id());
        assertEquals(match.id(), registry.find(id.toUpperCase()).orElseThrow().id(), "the whole id, in any case");
        assertTrue(registry.find(id.substring(0, 3)).isEmpty(), "too short to tell");
        assertTrue(registry.find("zzzz").isEmpty());
        assertEquals(8, MatchRegistry.shortId(match.id()).length());
    }

    @Test
    void aSpeedrunIsARaceWithNoStrikingThatTheFirstToFinishWins() {
        var registry = new MatchRegistry();
        assertTrue(registry.propose(MatchRegistry.Mode.SPEEDRUN, List.of(cy)).accepted(), "one runner alone");
        registry.stopAll();
        var nine = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 9; i++) nine.add(UUID.randomUUID());
        assertFalse(registry.propose(MatchRegistry.Mode.SPEEDRUN, nine).accepted(), "at most eight");
        assertFalse(registry.propose(MatchRegistry.Mode.SPEEDRUN, List.of(ada, ada)).accepted(), "each once");
        var race = registry.propose(MatchRegistry.Mode.SPEEDRUN, List.of(ada, bram)).match().orElseThrow();
        registry.join(race.id(), ada);
        registry.join(race.id(), bram);
        registry.start(race.id(), 0);
        var running = registry.tick(MatchRegistry.COUNTDOWN_TICKS).getFirst();
        assertEquals(MatchRegistry.State.RUNNING, running.state());
        assertFalse(registry.mayAttack(ada, bram), "a race is no fight");
        assertTrue(registry.fallen(ada).isEmpty(), "a fall is only a setback");
        assertTrue(registry.tick(10_000_000).isEmpty(), "no time limit");
        var finished = registry.finished(bram).orElseThrow();
        assertEquals(bram, finished.winner());
        assertEquals("finished", finished.result());
        assertTrue(registry.finished(ada).isEmpty(), "over once the first finishes");
    }

    @Test
    void aRunnerWhoDropsOutLeavesTheRestRacingUntilNoneIsLeft() {
        var registry = new MatchRegistry();
        var race = registry.propose(MatchRegistry.Mode.SPEEDRUN, List.of(ada, bram)).match().orElseThrow();
        registry.join(race.id(), ada);
        registry.join(race.id(), bram);
        registry.start(race.id(), 0);
        registry.tick(MatchRegistry.COUNTDOWN_TICKS);
        var on = registry.leave(race.id(), ada).match().orElseThrow();
        assertEquals(MatchRegistry.State.RUNNING, on.state(), "Bram runs on");
        assertFalse(on.playing(ada));
        assertTrue(registry.active(ada).isEmpty(), "Ada is free for another match");
        assertFalse(registry.leave(race.id(), ada).accepted(), "already out");
        assertTrue(registry.finished(ada).isEmpty(), "out, so no finish");
        var over = registry.departed(bram).orElseThrow();
        assertEquals(MatchRegistry.State.ENDED, over.state());
        assertNull(over.winner());
        assertEquals("Every runner left the game", over.result());
    }
}
