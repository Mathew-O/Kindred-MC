package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SkillRunnerTest {
    private static final SkillDescriptor WALK = new SkillDescriptor("move_to", Set.of("x", "z"), Set.of("note"), Set.of("navigate"),
            Set.of(), Duration.ofSeconds(10), Duration.ofSeconds(2), 2);
    private final AtomicLong nanos = new AtomicLong();
    private final UUID owner = UUID.randomUUID();
    private final Actor actor = Actor.player(owner);
    private final Companion companion = Companion.create(UUID.randomUUID(), owner, "Juniper", UUID.randomUUID(), "minecraft:overworld");
    private final FakeAdapter adapter = new FakeAdapter();
    private final ActionArbiter arbiter = new ActionArbiter(companion, UUID.randomUUID(), adapter,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), nanos::get, Duration.ofMillis(500));
    private final SkillRunner runner = new SkillRunner(arbiter, nanos::get);
    private final UUID task = UUID.randomUUID();

    /** A scripted body: each tick pops the next step; records what it was told. */
    private static final class ScriptedBody implements SkillBody {
        final List<Step> script = new ArrayList<>();
        final List<Boolean> freshStarts = new ArrayList<>();
        final List<Optional<Checkpoint>> resumes = new ArrayList<>();
        int halts;
        boolean settled = true;
        Map<String, String> last = Map.of();

        /** With no scripted step the body keeps reporting its last progress: it is doing nothing new. */
        @Override public Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart) {
            freshStarts.add(freshStart);
            resumes.add(resumeFrom);
            Step step = script.isEmpty() ? Step.running(last, false) : script.removeFirst();
            last = step.progress();
            return step;
        }
        @Override public void halt() { halts++; }
        @Override public boolean settled() { return settled; }
    }

    private static final class FakeAdapter implements BodyAdapter {
        boolean settled = true;
        @Override public void start(ActionLease lease) { }
        @Override public void cancel(ActionLease lease) { }
        @Override public boolean isSettled(ActionLease lease) { return settled; }
    }

    private void advance(Duration duration) { nanos.addAndGet(duration.toNanos()); }

    @Test
    void pauseAtACheckpointRetainsProgressAndResumeContinuesFromItAfterTheBarrierSettles() {
        ScriptedBody body = new ScriptedBody();
        body.script.add(SkillBody.Step.running(Map.of("steps", "1"), false));
        body.script.add(SkillBody.Step.running(Map.of("steps", "2"), true));
        body.script.add(SkillBody.Step.running(Map.of("steps", "3"), true));
        body.script.add(SkillBody.Step.completed(Map.of("steps", "4", "arrived", "true")));
        runner.start(WALK, Map.of("x", "10", "z", "10"), body, actor, task, Optional.empty());
        runner.tick();
        assertTrue(runner.pause());
        assertEquals(SkillRunner.State.PAUSING, runner.state());
        adapter.settled = false; // the body is still coasting when the checkpoint is reached
        runner.tick();
        assertEquals(SkillRunner.State.PAUSED, runner.state());
        assertEquals(Map.of("steps", "2"), runner.checkpoint().orElseThrow().progress());
        assertEquals(1, body.halts);
        assertEquals(LifecycleState.WAITING, arbiter.snapshot().lifecycle());
        assertFalse(arbiter.snapshot().stopLatched(), "pause is not stop");
        // No replacement starts until the cancellation settles.
        assertThrows(ControlRejectedException.class, () -> runner.resume(actor));
        adapter.settled = true;
        runner.resume(actor);
        assertEquals(SkillRunner.State.RUNNING, runner.state());
        runner.tick();
        assertTrue(body.freshStarts.getLast(), "resume refreshes observations");
        assertEquals(Map.of("steps", "2"), body.resumes.getLast().orElseThrow().progress(), "resume starts from the checkpoint, not from zero");
        runner.tick();
        assertEquals(SkillRunner.State.COMPLETED, runner.state());
        assertEquals("true", runner.checkpoint().orElseThrow().progress().get("arrived"));
        assertEquals(LifecycleState.WAITING, arbiter.snapshot().lifecycle());
    }

    @Test
    void pauseWithoutACheckpointCancelsAfterFiveHundredMillisecondsKeepingTheLastCheckpoint() {
        ScriptedBody body = new ScriptedBody();
        body.script.add(SkillBody.Step.running(Map.of("steps", "1"), true));
        runner.start(WALK, Map.of("x", "1", "z", "1"), body, actor, task, Optional.empty());
        runner.tick();
        runner.pause();
        advance(Duration.ofMillis(499));
        runner.tick(); // body reports no checkpoint
        assertEquals(SkillRunner.State.PAUSING, runner.state());
        advance(Duration.ofMillis(1));
        runner.tick();
        assertEquals(SkillRunner.State.PAUSED, runner.state());
        assertTrue(runner.reason().orElseThrow().contains("cancellation"));
        assertEquals(Map.of("steps", "1"), runner.checkpoint().orElseThrow().progress());
        assertTrue(body.halts >= 1);
    }

    @Test
    void stopRevokesSynchronouslyAndNoLaterTickActsOrResumes() {
        ScriptedBody body = new ScriptedBody();
        runner.start(WALK, Map.of("x", "1", "z", "1"), body, actor, task, Optional.empty());
        runner.tick();
        arbiter.stop(actor);
        runner.abort("stopped by owner");
        assertEquals(SkillRunner.State.CANCELLED, runner.state());
        assertEquals(1, body.halts);
        int ticksBefore = body.freshStarts.size();
        runner.tick();
        assertEquals(ticksBefore, body.freshStarts.size(), "a stale tick never drives the body");
        assertThrows(IllegalStateException.class, () -> runner.resume(actor), "stay/stop never silently resume a chore");
        // Without abort, the next tick still detects the revoked lease.
        SkillRunner other = new SkillRunner(arbiter, nanos::get);
        arbiter.resume(actor);
        other.start(WALK, Map.of("x", "1", "z", "1"), new ScriptedBody(), actor, task, Optional.empty());
        arbiter.stop(actor);
        other.tick();
        assertEquals(SkillRunner.State.CANCELLED, other.state());
        assertEquals("stopped by owner", other.reason().orElseThrow());
    }

    @Test
    void noProgressUsesAtMostTwoLocalRecoveriesFromTheLastCheckpointThenBlocks() {
        ScriptedBody body = new ScriptedBody();
        body.script.add(SkillBody.Step.running(Map.of("steps", "1"), true));
        runner.start(WALK, Map.of("x", "1", "z", "1"), body, actor, task, Optional.empty());
        runner.tick();
        for (int recovery = 1; recovery <= 2; recovery++) {
            advance(Duration.ofSeconds(2));
            runner.tick();
            assertEquals(SkillRunner.State.RUNNING, runner.state());
            assertEquals(recovery, runner.attempts());
            runner.tick();
            assertTrue(body.freshStarts.getLast(), "recovery replans from the checkpoint");
            assertEquals(Map.of("steps", "1"), body.resumes.getLast().orElseThrow().progress());
        }
        advance(Duration.ofSeconds(2));
        runner.tick();
        assertEquals(SkillRunner.State.BLOCKED, runner.state());
        assertTrue(runner.reason().orElseThrow().startsWith("no progress"));
        assertEquals(LifecycleState.WAITING, arbiter.snapshot().lifecycle());
    }

    @Test
    void permanentBlockersAreNotRetriedAndTimeoutsEndTheSkill() {
        ScriptedBody body = new ScriptedBody();
        body.script.add(SkillBody.Step.blocked("permission revoked", true));
        runner.start(WALK, Map.of("x", "1", "z", "1"), body, actor, task, Optional.empty());
        runner.tick();
        assertEquals(SkillRunner.State.BLOCKED, runner.state());
        assertEquals(0, runner.attempts());
        assertEquals("permission revoked", runner.reason().orElseThrow());

        SkillRunner timing = new SkillRunner(arbiter, nanos::get);
        timing.start(WALK, Map.of("x", "1", "z", "1"), new ScriptedBody(), actor, task, Optional.empty());
        advance(Duration.ofSeconds(10));
        arbiter.tick();
        timing.tick();
        assertEquals(SkillRunner.State.CANCELLED, timing.state());
        assertEquals("skill timeout", timing.reason().orElseThrow());
    }

    @Test
    void argumentsAndCheckpointsAreValidatedStrictly() {
        ScriptedBody body = new ScriptedBody();
        assertThrows(IllegalArgumentException.class, () -> runner.start(WALK, Map.of("x", "1"), body, actor, task, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> runner.start(WALK, Map.of("x", "1", "z", "1", "dig", "yes"), body, actor, task, Optional.empty()));
        Checkpoint foreign = new Checkpoint("gather_logs", task, 0, Map.of(), Map.of(), 0);
        assertThrows(IllegalArgumentException.class, () -> runner.start(WALK, Map.of("x", "1", "z", "1"), body, actor, task, Optional.of(foreign)));
        assertThrows(IllegalArgumentException.class, () -> new SkillDescriptor("Move", Set.of(), Set.of(), Set.of(), Set.of(), Duration.ofSeconds(1), Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> new SkillDescriptor("move", Set.of(), Set.of(), Set.of(), Set.of(), Duration.ofSeconds(1), Duration.ofSeconds(1), 3));
        assertThrows(IllegalArgumentException.class, () -> new SkillDescriptor("move", Set.of(), Set.of(), Set.of(), Set.of(), Duration.ofSeconds(1), Duration.ofSeconds(2), 0));
        assertEquals(SkillRunner.State.IDLE, runner.state());
    }
}
