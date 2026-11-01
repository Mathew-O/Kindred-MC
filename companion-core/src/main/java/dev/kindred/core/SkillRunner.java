package dev.kindred.core;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * In plain terms: carries out one skill, such as mining or crafting, a tick at a time while the player holds
 * control of its body. A pause keeps its progress so it can carry on later; a stop halts it at once; and
 * nothing starts again on its own.
 *
 * Technical: Drives one {@link SkillBody} under one arbiter lease, one tick at a time, on the owning server
 * thread. Pause is distinct from stop: it ends the lease at a checkpoint (or cancels after the pause
 * deadline) while retaining progress for an explicit resume. Stop comes from the arbiter; the runner then
 * observes a stale lease and halts without starting anything else. There is no queue here, so nothing can
 * restart on its own.
 */
public final class SkillRunner {
    public static final Duration PAUSE_DEADLINE = Duration.ofMillis(500);

    public enum State { IDLE, RUNNING, PAUSING, PAUSED, COMPLETING, COMPLETED, BLOCKED, CANCELLED }

    private final ActionArbiter arbiter;
    private final LongSupplier nanoTime;
    private State state = State.IDLE;
    private SkillDescriptor skill;
    private Map<String, String> arguments;
    private SkillBody body;
    private UUID taskId;
    private ActionLease lease;
    private Optional<Checkpoint> resumeFrom = Optional.empty();
    private Checkpoint lastCheckpoint;
    private Map<String, String> lastProgress = Map.of();
    private long lastProgressAt;
    private long pauseRequestedAt;
    private int attempts;
    private boolean freshStart;
    private String reason;

    /** A runner for skills under the given body gatekeeper. */
    public SkillRunner(ActionArbiter arbiter, LongSupplier nanoTime) {
        this.arbiter = Objects.requireNonNull(arbiter);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public State state() { return state; }
    public Optional<String> reason() { return Optional.ofNullable(reason); }
    /** What the skill last reported: on completion, its result (an eye's flight, a block's position). */
    public Map<String, String> lastProgress() { return lastProgress; }
    public Optional<Checkpoint> checkpoint() { return Optional.ofNullable(lastCheckpoint); }
    public Optional<ActionLease> lease() { return Optional.ofNullable(lease); }
    public SkillBody body() { return body; }
    public int attempts() { return attempts; }

    /**
     * Start a skill. {@code actor} null means trusted autonomous admission already happened. A checkpoint
     * from an earlier pause may be supplied; it must belong to the same skill and task.
     */
    public ActionLease start(SkillDescriptor descriptor, Map<String, String> args, SkillBody skillBody, Actor actor,
                             UUID task, Optional<Checkpoint> from) {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(skillBody);
        Objects.requireNonNull(task);
        Objects.requireNonNull(from);
        if (state == State.RUNNING || state == State.PAUSING || state == State.COMPLETING) {
            throw new IllegalStateException("A skill is already running; pause or cancel it first");
        }
        Map<String, String> validated = descriptor.validate(args);
        from.ifPresent(checkpoint -> {
            if (!checkpoint.skillId().equals(descriptor.id()) || !checkpoint.taskId().equals(task)) {
                throw new IllegalArgumentException("Checkpoint belongs to another skill or task");
            }
        });
        ActionLease acquired = actor == null
                ? arbiter.acquireAutonomous(task, UUID.randomUUID(), descriptor.timeout())
                : arbiter.acquire(actor, task, UUID.randomUUID(), descriptor.timeout());
        skill = descriptor;
        arguments = validated;
        body = skillBody;
        taskId = task;
        lease = acquired;
        resumeFrom = from;
        lastCheckpoint = from.orElse(null);
        lastProgress = from.map(Checkpoint::progress).orElse(Map.of());
        lastProgressAt = nanoTime.getAsLong();
        attempts = 0;
        freshStart = true;
        reason = null;
        state = State.RUNNING;
        return acquired;
    }

    /** Request a pause; the next checkpoint within 500 ms ends the lease cleanly, otherwise the skill is cancelled with its progress kept. */
    public boolean pause() {
        if (state != State.RUNNING) return false;
        state = State.PAUSING;
        pauseRequestedAt = nanoTime.getAsLong();
        return true;
    }

    /** Resume from the retained checkpoint with fresh observations. Fails while a cancellation is still settling. */
    public ActionLease resume(Actor actor) {
        if (state != State.PAUSED) throw new IllegalStateException("Nothing is paused");
        return start(skill, arguments, body, actor, taskId, Optional.ofNullable(lastCheckpoint));
    }

    /** Called synchronously by stop/removal paths so no late tick can act; the arbiter has already revoked the lease. */
    public void abort(String why) {
        if (state == State.IDLE || state == State.COMPLETED || state == State.BLOCKED || state == State.CANCELLED) return;
        if (body != null) body.halt();
        finish(State.CANCELLED, why);
    }

    /** Moves the running skill on by one tick, or finishes it once its end has settled. */
    public void tick() {
        long now = nanoTime.getAsLong();
        switch (state) {
            case RUNNING, PAUSING -> step(now);
            case COMPLETING -> {
                if (!arbiter.isCurrent(lease)) { body.halt(); finish(State.CANCELLED, "authority lost before completion settled"); }
                else if (arbiter.complete(lease)) finish(State.COMPLETED, null);
            }
            default -> { }
        }
    }

    private void step(long now) {
        if (!arbiter.isCurrent(lease)) {
            body.halt();
            LifecycleState lifecycle = arbiter.snapshot().lifecycle();
            finish(State.CANCELLED, arbiter.snapshot().stopLatched() ? "stopped by owner"
                    : lifecycle == LifecycleState.BLOCKED ? "skill timeout" : "authority lost");
            return;
        }
        if (state == State.PAUSING && now - pauseRequestedAt >= PAUSE_DEADLINE.toNanos()) {
            body.halt();
            arbiter.cancel(lease);
            finish(State.PAUSED, "paused by cancellation; progress retained from the last checkpoint");
            return;
        }
        SkillBody.Step result = body.tick(arguments, resumeFrom, freshStart);
        freshStart = false;
        if (!result.progress().equals(lastProgress)) {
            lastProgress = result.progress();
            lastProgressAt = now;
        }
        if (result.checkpoint()) {
            lastCheckpoint = new Checkpoint(skill.id(), taskId, lease.taskRevision(), arguments, lastProgress, now);
        }
        switch (result.status()) {
            case COMPLETED -> {
                state = State.COMPLETING;
                if (arbiter.complete(lease)) finish(State.COMPLETED, null);
            }
            case BLOCKED -> {
                if (result.permanent()) {
                    body.halt();
                    arbiter.cancel(lease);
                    finish(State.BLOCKED, result.reason());
                } else retry(result.reason(), now);
            }
            case RUNNING -> {
                if (state == State.PAUSING && result.checkpoint()) {
                    body.halt();
                    arbiter.cancel(lease);
                    finish(State.PAUSED, "paused at a checkpoint");
                } else if (now - lastProgressAt >= skill.noProgressLimit().toNanos()) {
                    retry("no progress", now);
                }
            }
        }
    }

    /** Local recovery: at most the descriptor's budget, always from the last checkpoint with a fresh plan. */
    private void retry(String why, long now) {
        attempts++;
        body.halt();
        if (attempts > skill.retryBudget()) {
            arbiter.cancel(lease);
            finish(State.BLOCKED, why + " after " + skill.retryBudget() + " local recoveries");
            return;
        }
        resumeFrom = Optional.ofNullable(lastCheckpoint);
        freshStart = true;
        lastProgressAt = now;
    }

    private void finish(State terminal, String why) {
        state = terminal;
        reason = why;
        lease = null; // Whatever ended the skill, the old token is no longer authority.
    }
}
