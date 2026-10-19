package dev.kindred.core.attention;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure core attention state machine for an autonomous AI-player.
 *
 * <p>Transitions between Routine, Alert, Combat and Recovery states based on environmental danger,
 * credible threats, and attack events. Stop, unavailable body and reconciliation faults make
 * optional work inactive without interrupting essential lifecycle processing.
 */
public final class AttentionStateMachine {
    public static final long COMBAT_QUIET_TICKS = 60;
    public static final long ALERT_QUIET_TICKS = 40;
    public static final long RECOVERY_QUIET_TICKS = 40;
    public static final long THREAT_OBSERVATION_FRESHNESS_TICKS = 10;

    private final UUID runtimeSession;
    private final UUID playerId;
    private long bodyGeneration = 0;
    private AttentionState state = AttentionState.ALERT;
    private boolean active = false;
    private long revision = 0;

    private Long lastProcessedAttackSequence = null;
    private long lastEncounterTick = -1;
    private long quietStartedTick = -1;
    private long lastTick = -1;

    public AttentionStateMachine(UUID runtimeSession, UUID playerId) {
        this(runtimeSession, playerId, 0L);
    }

    public AttentionStateMachine(UUID runtimeSession, UUID playerId, long initialBodyGeneration) {
        this.runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        if (initialBodyGeneration < 0) throw new IllegalArgumentException("initialBodyGeneration cannot be negative: " + initialBodyGeneration);
        this.bodyGeneration = initialBodyGeneration;
    }

    public UUID runtimeSession() { return runtimeSession; }
    public UUID playerId() { return playerId; }
    public AttentionState state() { return state; }
    public boolean isActive() { return active; }
    public long revision() { return revision; }
    public long bodyGeneration() { return bodyGeneration; }

    public synchronized AttentionSnapshot observe(SignalFrame frame) {
        Objects.requireNonNull(frame, "frame");

        // 1. Monotonic tick check within session
        if (lastTick >= 0 && frame.tick() < lastTick) {
            throw new IllegalArgumentException("Backward tick within session: " + frame.tick() + " < " + lastTick);
        }
        lastTick = frame.tick();

        // 2. Generation fencing: a new generation resets transient encounter state
        if (this.bodyGeneration != frame.bodyGeneration()) {
            this.bodyGeneration = frame.bodyGeneration();
            this.lastProcessedAttackSequence = null;
            this.lastEncounterTick = -1;
            this.quietStartedTick = -1;
            this.state = AttentionState.ALERT;
            this.revision++;
        }

        // 3. Operational status check
        if (!frame.operational()) {
            if (this.active) {
                this.active = false;
                this.revision++;
            }
            return new AttentionSnapshot(frame.tick(), this.bodyGeneration, this.revision, this.state, false);
        }

        if (!this.active) {
            this.active = true;
            this.state = AttentionState.ALERT;
            this.quietStartedTick = frame.tick();
            this.revision++;
        }

        // 4. Freshness check: delayed observations evaluate to UNKNOWN, never CLEAR
        boolean threatFresh = (frame.tick() - frame.threatObservedTick() <= THREAT_OBSERVATION_FRESHNESS_TICKS)
                && frame.threatObservedTick() <= frame.tick();
        ThreatKnowledge effectiveThreat = threatFresh ? frame.threat() : ThreatKnowledge.UNKNOWN;

        // 5. Attack event deduplication: only higher sequences count as new attacks
        boolean newAttack = false;
        if (frame.attackSequence() != null) {
            if (lastProcessedAttackSequence == null || frame.attackSequence() > lastProcessedAttackSequence) {
                lastProcessedAttackSequence = frame.attackSequence();
                lastEncounterTick = frame.tick();
                newAttack = true;
            }
        }

        AttentionState previousState = this.state;

        // 6. State transitions
        if (newAttack) {
            // Immediate escalation to Combat on new attack
            this.state = AttentionState.COMBAT;
            this.lastEncounterTick = frame.tick();
            this.quietStartedTick = -1;
        } else if (this.state == AttentionState.COMBAT) {
            if (effectiveThreat == ThreatKnowledge.PRESENT) {
                // Continued credible threat refreshes combat encounter
                this.lastEncounterTick = frame.tick();
                this.quietStartedTick = -1;
            } else {
                long elapsedQuiet = frame.tick() - this.lastEncounterTick;
                if (elapsedQuiet >= COMBAT_QUIET_TICKS) {
                    if (effectiveThreat == ThreatKnowledge.CLEAR) {
                        this.state = AttentionState.RECOVERY;
                        this.quietStartedTick = frame.tick();
                    } else {
                        // UNKNOWN threat falls back to Alert for observation, not Recovery
                        this.state = AttentionState.ALERT;
                        this.quietStartedTick = -1;
                    }
                }
            }
        } else if (frame.environmentalDanger() || effectiveThreat == ThreatKnowledge.PRESENT) {
            // Immediate escalation to Alert on danger or visible threat
            this.state = AttentionState.ALERT;
            this.quietStartedTick = -1;
        } else if (this.state == AttentionState.ALERT) {
            if (effectiveThreat == ThreatKnowledge.CLEAR && !frame.environmentalDanger()) {
                if (this.quietStartedTick < 0) {
                    this.quietStartedTick = frame.tick();
                }
                if (frame.tick() - this.quietStartedTick >= ALERT_QUIET_TICKS) {
                    this.state = AttentionState.RECOVERY;
                    this.quietStartedTick = frame.tick();
                }
            } else {
                // Threat is UNKNOWN or quiet is broken: cannot advance quiet
                this.quietStartedTick = -1;
            }
        } else if (this.state == AttentionState.RECOVERY) {
            if (effectiveThreat == ThreatKnowledge.CLEAR && !frame.environmentalDanger()) {
                if (this.quietStartedTick < 0) {
                    this.quietStartedTick = frame.tick();
                }
                if (frame.tick() - this.quietStartedTick >= RECOVERY_QUIET_TICKS) {
                    if (!frame.recoveryNeeded()) {
                        this.state = AttentionState.ROUTINE;
                        this.quietStartedTick = frame.tick();
                    }
                    // If recoveryNeeded is true, hold in RECOVERY
                }
            } else {
                this.quietStartedTick = -1;
            }
        } else if (this.state == AttentionState.ROUTINE) {
            if (this.quietStartedTick < 0) {
                this.quietStartedTick = frame.tick();
            }
        }

        if (this.state != previousState) {
            this.revision++;
        }

        return new AttentionSnapshot(frame.tick(), this.bodyGeneration, this.revision, this.state, true);
    }

    public synchronized AttentionSnapshot snapshot(long tick) {
        return new AttentionSnapshot(tick, this.bodyGeneration, this.revision, this.state, this.active);
    }

    /**
     * Explicit operator resume: end alert/recovery warm-up and allow routine goal scheduling when the body is operational.
     */
    public synchronized void engageRoutineWork(long tick) {
        if (!active) {
            return;
        }
        if (state == AttentionState.ALERT || state == AttentionState.RECOVERY) {
            state = AttentionState.ROUTINE;
            quietStartedTick = tick;
            revision++;
        }
    }
}
