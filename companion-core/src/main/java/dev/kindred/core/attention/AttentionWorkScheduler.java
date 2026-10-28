package dev.kindred.core.attention;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: shares the AI's background thinking fairly between up to three players. Each tick it hands
 * out a small, fixed number of thinking slots (look for threats, check gear, plan goals, look for
 * resources...), taking turns between players and putting the most urgent work first, and it stops when the
 * tick's time budget is used.
 *
 * Technical: Shared fair-budget scheduler for up to three native AI-players.
 *
 * <p>Redistributes computational attention across Routine, Alert, Combat and Recovery states.
 * Guarantees round-robin fairness across eligible players, coalesced per-category jobs, single-use
 * tokens, deterministic slice caps, and measured soft time limits.
 */
public final class AttentionWorkScheduler {

    public record PlayerEntrySnapshot(
            UUID playerId,
            long tick,
            long bodyGeneration,
            long revision,
            AttentionState state,
            boolean optionalWorkActive) {

        public PlayerEntrySnapshot {
            Objects.requireNonNull(playerId, "playerId");
            if (tick < 0) throw new IllegalArgumentException("tick cannot be negative: " + tick);
            if (bodyGeneration < 0) throw new IllegalArgumentException("bodyGeneration cannot be negative: " + bodyGeneration);
            if (revision < 0) throw new IllegalArgumentException("revision cannot be negative: " + revision);
            Objects.requireNonNull(state, "state");
        }
    }

    private static final class JobEntry {
        final WorkKind kind;
        long requestSequence = 1;
        long invalidationRevision = 0;
        long dueTick;
        long startedNano = 0;
        WorkToken inFlightToken = null;

        JobEntry(WorkKind kind, long initialDueTick) {
            this.kind = kind;
            this.dueTick = initialDueTick;
        }
    }

    private static final class RegisteredPlayer {
        final UUID playerId;
        long bodyGeneration;
        long attentionRevision;
        AttentionState state;
        boolean optionalWorkActive;
        final Map<WorkKind, JobEntry> jobs = new EnumMap<>(WorkKind.class);

        RegisteredPlayer(UUID playerId, long bodyGeneration, long attentionRevision,
                         AttentionState state, boolean optionalWorkActive, long tick) {
            this.playerId = playerId;
            this.bodyGeneration = bodyGeneration;
            this.attentionRevision = attentionRevision;
            this.state = state;
            this.optionalWorkActive = optionalWorkActive;
            resetJobs(tick);
        }

        void resetJobs(long tick) {
            jobs.clear();
            for (WorkKind kind : WorkKind.values()) {
                jobs.put(kind, new JobEntry(kind, tick));
            }
        }

        void cancelInFlight() {
            for (JobEntry job : jobs.values()) {
                job.inFlightToken = null;
            }
        }
    }

    private final UUID runtimeSession;
    private final AttentionSchedulePolicy policy;
    private final Map<UUID, RegisteredPlayer> players = new HashMap<>();

    private long currentTick = -1;
    private long tickStartNanos = -1;
    private int slicesStartedThisTick = 0;
    private int detailedScansThisTick = 0;
    private int lastServedPlayerIndex = -1;

    // Diagnostic counters
    private long totalSlicesStarted = 0;
    private long totalSlicesCompleted = 0;
    private long totalSlicesAbandoned = 0;
    private long totalDetailedScans = 0;
    private long staleTokenRejections = 0;
    private long overruns = 0;
    private long softLimitExceededEvents = 0;
    private long totalOptionalNanos = 0;
    private final Map<WorkKind, Long> slicesByKind = new EnumMap<>(WorkKind.class);
    private final Map<WorkKind, Long> invalidationsByKind = new EnumMap<>(WorkKind.class);
    private final Map<WorkKind, Long> overrunsByKind = new EnumMap<>(WorkKind.class);

    /** A scheduler with its limits and timings. */
    public AttentionWorkScheduler(UUID runtimeSession, AttentionSchedulePolicy policy) {
        this.runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        this.policy = Objects.requireNonNull(policy, "policy");
        for (WorkKind kind : WorkKind.values()) {
            slicesByKind.put(kind, 0L);
            invalidationsByKind.put(kind, 0L);
            overrunsByKind.put(kind, 0L);
        }
    }

    public UUID runtimeSession() { return runtimeSession; }
    public AttentionSchedulePolicy policy() { return policy; }
    public synchronized long currentTick() { return currentTick; }
    public synchronized int slicesStartedThisTick() { return slicesStartedThisTick; }
    public synchronized int detailedScansThisTick() { return detailedScansThisTick; }

    /**
     * Synchronizes registered player entries from an immutable batch snapshot.
     * Rejects duplicate IDs, excessive players or negative revisions before mutating any internal state.
     */
    public synchronized void synchronizeEntries(Collection<PlayerEntrySnapshot> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.size() > policy.maxPlayers()) {
            throw new IllegalArgumentException("Batch size " + batch.size() + " exceeds maxPlayers " + policy.maxPlayers());
        }

        // Validate entire batch before applying
        Set<UUID> seenIds = new HashSet<>();
        for (PlayerEntrySnapshot snapshot : batch) {
            if (!seenIds.add(snapshot.playerId())) {
                throw new IllegalArgumentException("Duplicate player ID in batch: " + snapshot.playerId());
            }
        }

        // Remove players no longer present in batch
        players.keySet().removeIf(id -> !seenIds.contains(id));

        // Apply validated entries
        for (PlayerEntrySnapshot snapshot : batch) {
            RegisteredPlayer existing = players.get(snapshot.playerId());
            if (existing == null) {
                players.put(snapshot.playerId(), new RegisteredPlayer(
                        snapshot.playerId(), snapshot.bodyGeneration(), snapshot.revision(),
                        snapshot.state(), snapshot.optionalWorkActive(), snapshot.tick()));
            } else {
                if (existing.bodyGeneration != snapshot.bodyGeneration()) {
                    // New body/session generation discards pending work and resets clock domain
                    existing.bodyGeneration = snapshot.bodyGeneration();
                    existing.attentionRevision = snapshot.revision();
                    existing.state = snapshot.state();
                    existing.optionalWorkActive = snapshot.optionalWorkActive();
                    existing.resetJobs(snapshot.tick());
                } else {
                    existing.attentionRevision = snapshot.revision();
                    existing.state = snapshot.state();
                    existing.optionalWorkActive = snapshot.optionalWorkActive();
                    if (!snapshot.optionalWorkActive()) {
                        existing.cancelInFlight();
                    }
                }
            }
        }
    }

    /**
     * Invalidates cached proposals for a category, bumping invalidation revision and dropping
     * in-flight tokens while retaining due age.
     */
    public synchronized void invalidate(UUID playerId, WorkKind kind) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kind, "kind");

        RegisteredPlayer player = players.get(playerId);
        if (player == null) return;

        JobEntry job = player.jobs.get(kind);
        if (job == null) return;

        job.invalidationRevision++;
        job.requestSequence++;
        job.inFlightToken = null; // Stale running token will be rejected on completion

        invalidationsByKind.merge(kind, 1L, Long::sum);
    }

    /**
     * Begins a new server tick. Resets deterministic work slice counters and starts elapsed time measurement.
     */
    public synchronized void beginTick(long tick, long nanoTime) {
        if (currentTick >= 0 && tick < currentTick) {
            throw new IllegalArgumentException("Backward tick: " + tick + " < " + currentTick);
        }
        currentTick = tick;
        tickStartNanos = nanoTime;
        slicesStartedThisTick = 0;
        detailedScansThisTick = 0;
    }

    /**
     * Selects the next eligible optional work slice according to round-robin fairness across players,
     * oldest due time, and shared deterministic and soft-time budget limits.
     */
    public synchronized Optional<WorkToken> selectNext() {
        if (slicesStartedThisTick >= policy.maxSlicesPerTick()) {
            return Optional.empty();
        }

        long elapsedNanos = System.nanoTime() - tickStartNanos;
        if (elapsedNanos >= policy.softTimeLimitNanos()) {
            softLimitExceededEvents++;
            return Optional.empty();
        }

        List<RegisteredPlayer> activePlayers = new ArrayList<>();
        for (RegisteredPlayer player : players.values()) {
            if (player.optionalWorkActive) activePlayers.add(player);
        }
        if (activePlayers.isEmpty()) return Optional.empty();

        // Stable tie-break by player UUID
        activePlayers.sort(Comparator.comparing(p -> p.playerId));

        int count = activePlayers.size();
        for (int step = 0; step < count; step++) {
            int index = (lastServedPlayerIndex + 1 + step) % count;
            RegisteredPlayer candidatePlayer = activePlayers.get(index);

            JobEntry bestJob = null;
            for (JobEntry job : candidatePlayer.jobs.values()) {
                if (job.inFlightToken != null) continue; // Already in-flight
                if (!policy.isEligible(candidatePlayer.state, job.kind)) continue; // Deferred/disabled
                if (job.dueTick > currentTick) continue; // Not due yet
                if (job.kind == WorkKind.RESOURCE_DISCOVERY && detailedScansThisTick >= policy.maxDetailedScansPerTick()) {
                    continue; // Detailed scan limit reached for this tick
                }

                if (bestJob == null || job.dueTick < bestJob.dueTick
                        || (job.dueTick == bestJob.dueTick && job.kind.ordinal() < bestJob.kind.ordinal())) {
                    bestJob = job;
                }
            }

            if (bestJob != null) {
                lastServedPlayerIndex = index;
                WorkToken token = new WorkToken(
                        runtimeSession,
                        candidatePlayer.playerId,
                        candidatePlayer.bodyGeneration,
                        candidatePlayer.attentionRevision,
                        bestJob.kind,
                        bestJob.invalidationRevision,
                        bestJob.requestSequence,
                        currentTick,
                        currentTick // Single tick expiration
                );
                return Optional.of(token);
            }
        }

        return Optional.empty();
    }

    /**
     * Validates and starts execution of an optional work token, consuming the single-use token
     * and incrementing the budget counter.
     */
    public synchronized boolean tryStart(WorkToken token, long nowNano) {
        Objects.requireNonNull(token, "token");

        // 1. Expiration check
        if (token.expiresTick() < currentTick) {
            staleTokenRejections++;
            return false;
        }

        // 2. Session check
        if (!token.runtimeSession().equals(this.runtimeSession)) {
            staleTokenRejections++;
            return false;
        }

        // 3. Player registration and operational check
        RegisteredPlayer player = players.get(token.playerId());
        if (player == null || !player.optionalWorkActive) {
            staleTokenRejections++;
            return false;
        }

        // 4. Generation and attention revision check
        if (player.bodyGeneration != token.bodyGeneration()
                || player.attentionRevision != token.attentionRevision()) {
            staleTokenRejections++;
            return false;
        }

        // 5. Job request sequence and invalidation check
        JobEntry job = player.jobs.get(token.kind());
        if (job == null
                || job.invalidationRevision != token.invalidationRevision()
                || job.requestSequence != token.requestSequence()) {
            staleTokenRejections++;
            return false;
        }

        // 6. Single-use: cannot start if already in flight
        if (job.inFlightToken != null) {
            staleTokenRejections++;
            return false;
        }

        // 7. Budget limits check
        if (slicesStartedThisTick >= policy.maxSlicesPerTick()) {
            return false;
        }
        long elapsedNanos = nowNano - tickStartNanos;
        if (elapsedNanos >= policy.softTimeLimitNanos()) {
            softLimitExceededEvents++;
            return false;
        }
        if (token.kind() == WorkKind.RESOURCE_DISCOVERY && detailedScansThisTick >= policy.maxDetailedScansPerTick()) {
            return false;
        }

        // Passed all checks: consume token
        job.inFlightToken = token;
        job.startedNano = nowNano;
        slicesStartedThisTick++;
        if (token.kind() == WorkKind.RESOURCE_DISCOVERY) {
            detailedScansThisTick++;
            totalDetailedScans++;
        }

        totalSlicesStarted++;
        slicesByKind.merge(token.kind(), 1L, Long::sum);
        return true;
    }

    /**
     * Completes an optional work slice, advancing the due tick by the category cadence.
     */
    public synchronized boolean complete(WorkToken token) {
        Objects.requireNonNull(token, "token");

        RegisteredPlayer player = players.get(token.playerId());
        if (player == null) {
            staleTokenRejections++;
            return false;
        }

        JobEntry job = player.jobs.get(token.kind());
        if (job == null || job.inFlightToken == null || !job.inFlightToken.equals(token)) {
            staleTokenRejections++;
            return false;
        }

        if (job.invalidationRevision != token.invalidationRevision()
                || job.requestSequence != token.requestSequence()) {
            job.inFlightToken = null;
            staleTokenRejections++;
            return false;
        }

        long nowNano = System.nanoTime();
        long sliceDuration = Math.max(0, nowNano - job.startedNano);
        totalOptionalNanos += sliceDuration;

        if (sliceDuration > policy.softTimeLimitNanos()) {
            overruns++;
            overrunsByKind.merge(token.kind(), 1L, Long::sum);
        }

        int cadence = policy.cadenceTicks(player.state, token.kind());
        job.dueTick = currentTick + Math.max(1, cadence);
        job.inFlightToken = null;
        job.requestSequence++;

        totalSlicesCompleted++;
        return true;
    }

    /**
     * Abandons an in-flight slice without advancing its due tick.
     */
    public synchronized void abandon(WorkToken token) {
        Objects.requireNonNull(token, "token");

        RegisteredPlayer player = players.get(token.playerId());
        if (player == null) return;

        JobEntry job = player.jobs.get(token.kind());
        if (job != null && job.inFlightToken != null && job.inFlightToken.equals(token)) {
            job.inFlightToken = null;
            totalSlicesAbandoned++;
        }
    }

    /** Counters showing how the thinking time was spent, for diagnosing performance. */
    public synchronized SchedulerDiagnostics diagnostics() {
        return new SchedulerDiagnostics(
                totalSlicesStarted,
                totalSlicesCompleted,
                totalSlicesAbandoned,
                totalDetailedScans,
                staleTokenRejections,
                overruns,
                softLimitExceededEvents,
                totalOptionalNanos,
                slicesByKind,
                invalidationsByKind,
                overrunsByKind
        );
    }
}
