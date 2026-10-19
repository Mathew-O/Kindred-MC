package dev.kindred.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Small personality biases over authoritative task candidates, not a skill catalog or action executor.
 * The director supplies eligibility and need scores; the arbiter and adapter still authorize execution.
 */
public final class TaskPreferences {
    public static final double MAX_PERSONALITY_BIAS = 5;
    public static final double SWITCH_MARGIN = 3;

    /** Explicit player controls bypass this discretionary ranking altogether. */
    public enum Priority { URGENT, PROJECT, ROUTINE }

    /** All facts must come from trusted checks, never from a model's asserted permission. */
    public record Eligibility(boolean approved, boolean permitted, boolean supported, boolean affordable) {
        public boolean available() { return approved && permitted && supported && affordable; }
    }

    /** A task may serve several interests; none of these features enable a physical skill. */
    public record Affinities(double combat, double assistance, double farming, double mining) {
        public Affinities {
            unit(combat);
            unit(assistance);
            unit(farming);
            unit(mining);
        }
        public static Affinities none() { return new Affinities(0, 0, 0, 0); }
    }

    public record Candidate(UUID id, Priority priority, double needScore, Affinities affinities, Eligibility eligibility) {
        public Candidate {
            Objects.requireNonNull(id);
            Objects.requireNonNull(priority);
            Objects.requireNonNull(affinities);
            Objects.requireNonNull(eligibility);
            if (!Double.isFinite(needScore) || needScore < 0 || needScore > 100) {
                throw new IllegalArgumentException("Task need score must be 0..100");
            }
        }
    }

    /** Keep need and preference separately visible for diagnostics and tuning. */
    public record Ranked(Candidate task, double personalityBias) {
        public double score() { return task.needScore() + personalityBias; }
    }

    private TaskPreferences() { }

    public static double bias(Personality personality, Affinities affinities) {
        Objects.requireNonNull(personality);
        Objects.requireNonNull(affinities);
        double total = affinities.combat() + affinities.assistance() + affinities.farming() + affinities.mining();
        if (total == 0) return 0;
        double sum = centered(personality.combativeness()) * affinities.combat()
                + centered(personality.helpfulness()) * affinities.assistance()
                + centered(personality.farming()) * affinities.farming()
                + centered(personality.mining()) * affinities.mining();
        // Normalize the blend: adding more task tags cannot stack personality bonuses beyond the cap.
        return MAX_PERSONALITY_BIAS * sum / Math.max(1, total);
    }

    public static List<Ranked> rankAvailable(List<Candidate> candidates, Personality personality) {
        Objects.requireNonNull(personality);
        HashSet<UUID> ids = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (!ids.add(candidate.id())) throw new IllegalArgumentException("Duplicate task candidate identity");
        }
        return candidates.stream().filter(task -> task.eligibility().available())
                .map(task -> new Ranked(task, bias(personality, task.affinities())))
                .sorted(Comparator.comparing((Ranked ranked) -> ranked.task().priority())
                        .thenComparing(Comparator.comparingDouble(Ranked::score).reversed())
                        .thenComparing(ranked -> ranked.task().id()))
                .toList();
    }

    /** Call only at a safe decision boundary, then recheck permissions/budget and acquire body authority. */
    public static Optional<Ranked> chooseAtCheckpoint(List<Candidate> candidates, Personality personality, UUID currentTask) {
        List<Ranked> ranked = rankAvailable(candidates, personality);
        if (ranked.isEmpty()) return Optional.empty();
        Ranked best = ranked.getFirst();
        Optional<Ranked> current = ranked.stream().filter(task -> task.task().id().equals(currentTask)).findFirst();
        if (current.isPresent() && current.orElseThrow().task().priority() == best.task().priority()
                && best.score() - current.orElseThrow().score() <= SWITCH_MARGIN) return current;
        return Optional.of(best);
    }

    private static double centered(int value) { return (value - 50) / 50.0; }

    private static void unit(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Task affinities must be 0..1");
    }
}
