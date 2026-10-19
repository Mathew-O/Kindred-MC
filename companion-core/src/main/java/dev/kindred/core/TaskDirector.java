package dev.kindred.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted director that selects executable task candidates based on personality, mode, priority,
 * and need scores (F-17).
 *
 * <p>Rules:
 * <ul>
 *   <li>Only approved, permitted, supported and affordable candidates are eligible.
 *   <li>Urgent tasks always precede project tasks, which precede routine tasks.
 *   <li>Personality preferences (combat, assist, farm, mine) apply a bounded [-5..+5] score bias.
 *   <li>Switching hysteresis: a new task in the same priority must score at least 3 points higher
 *       than the currently running task to trigger a switch.
 *   <li>Unqualified boss targets remain filtered regardless of combativeness. E-39 adds qualified
 *       expedition admission; this compatibility restriction is not a permanent product-wide ban.
 *   <li>Initiative influences whether optional/routine candidates are admitted.
 *   <li>Caution suppresses optional high-risk candidates when safety is compromised.
 * </ul>
 */
public final class TaskDirector {

    public record Selection(TaskPreferences.Candidate candidate, double score, String rationale) { }

    private TaskDirector() { }

    /**
     * Evaluates available candidates and selects the winning task candidate.
     *
     * @param candidates all known task candidates
     * @param currentId  the ID of the currently running task (if any), for switching margin
     * @param mode       operating mode (Companion vs Independent)
     * @param personality companion's 9-trait personality
     * @return non-empty selection if an eligible task exists, or empty if idle/sleeping
     */
    public static Optional<Selection> select(List<TaskPreferences.Candidate> candidates,
                                             UUID currentId,
                                             OperatingMode mode,
                                             Personality personality) {
        Objects.requireNonNull(mode, "mode");
        return select(candidates, currentId, personality);
    }

    /** Player task selection has no companion mode or controller-presence dependency. */
    public static Optional<Selection> select(List<TaskPreferences.Candidate> candidates,
                                             UUID currentId, Personality personality) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(personality, "personality");

        // Filter: only candidates that pass trusted eligibility
        List<TaskPreferences.Candidate> eligible = candidates.stream()
                .filter(c -> c.eligibility().available())
                .filter(c -> !isForbiddenBossTarget(c))
                .toList();

        if (eligible.isEmpty()) return Optional.empty();

        // Separate by priority: URGENT > PROJECT > ROUTINE
        List<TaskPreferences.Candidate> urgent = eligible.stream()
                .filter(c -> c.priority() == TaskPreferences.Priority.URGENT).toList();
        if (!urgent.isEmpty()) {
            return chooseBest(urgent, currentId, personality, "Urgent necessity");
        }

        List<TaskPreferences.Candidate> project = eligible.stream()
                .filter(c -> c.priority() == TaskPreferences.Priority.PROJECT).toList();
        if (!project.isEmpty()) {
            return chooseBest(project, currentId, personality, "Active project goal");
        }

        // Routine tasks require passing the initiative threshold
        // Low initiative (0..20) companions wait for explicit owner instructions or projects
        int initiative = personality.initiative();
        if (initiative < 20) {
            return Optional.empty();
        }

        List<TaskPreferences.Candidate> routine = eligible.stream()
                .filter(c -> c.priority() == TaskPreferences.Priority.ROUTINE).toList();
        if (!routine.isEmpty()) {
            return chooseBest(routine, currentId, personality, "Discretionary routine (initiative " + initiative + ")");
        }

        return Optional.empty();
    }

    private static Optional<Selection> chooseBest(List<TaskPreferences.Candidate> pool,
                                                  UUID currentId,
                                                  Personality personality,
                                                  String tierLabel) {
        TaskPreferences.Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestBias = 0;

        // Current candidate's score (if in the pool) for hysteresis
        double currentScore = Double.NEGATIVE_INFINITY;
        for (var c : pool) {
            double bias = TaskPreferences.bias(personality, c.affinities());
            double score = c.needScore() + bias;
            if (c.id().equals(currentId)) {
                currentScore = score;
            }
        }

        for (var c : pool) {
            double bias = TaskPreferences.bias(personality, c.affinities());
            double score = c.needScore() + bias;

            // Hysteresis: if another candidate was already running, new candidate must beat it by SWITCH_MARGIN
            if (currentId != null && !c.id().equals(currentId) && currentScore > Double.NEGATIVE_INFINITY) {
                if (score <= currentScore + TaskPreferences.SWITCH_MARGIN) {
                    continue;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                best = c;
                bestBias = bias;
            }
        }

        // If no candidate beat the current one by SWITCH_MARGIN, retain current if still in pool
        if (best == null && currentId != null) {
            for (var c : pool) {
                if (c.id().equals(currentId)) {
                    double bias = TaskPreferences.bias(personality, c.affinities());
                    return Optional.of(new Selection(c, c.needScore() + bias,
                            tierLabel + ": retained current task (switching margin " + TaskPreferences.SWITCH_MARGIN + ")"));
                }
            }
        }

        if (best == null) return Optional.empty();
        String rationale = String.format(java.util.Locale.ROOT,
                "%s: need %.1f + bias %+.1f = %.1f", tierLabel, best.needScore(), bestBias, bestScore);
        return Optional.of(new Selection(best, bestScore, rationale));
    }

    private static final java.util.Set<UUID> FORBIDDEN_BOSS_TARGETS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void markBossTarget(UUID id) {
        FORBIDDEN_BOSS_TARGETS.add(Objects.requireNonNull(id));
    }

    public static boolean isForbiddenBossTarget(TaskPreferences.Candidate c) {
        return FORBIDDEN_BOSS_TARGETS.contains(c.id());
    }
}
