package dev.kindred.core.tactics;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: chooses how to handle a fight: drops plans that are impossible or not allowed, keeps only
 * the safest ones, then picks the one that best serves the most important goal. Safety always comes first; no
 * loot or kill can buy a riskier plan.
 *
 * Technical: Pure policy for ranking tactical options according to the strict self-preservation hierarchy.
 *
 * Decision pipeline:
 * 1. Eligibility filter: mechanical feasibility, authority, collateral safety.
 * 2. Self-preservation filter: strictly restricts selection to the best available survival class.
 *    Property, kills, loot or personality cannot buy a worse survival class.
 * 3. Lexicographic objective ranking within surviving frontier:
 *    (Immediate serious harm > Irreversible material loss > Safe encounter resolution > Tasks).
 */
public final class TacticalPriorityPolicy {

    public static final int MAX_CANDIDATE_OPTIONS = 16;

    private TacticalPriorityPolicy() { }

    /**
     * Evaluates and ranks a set of tactical options for the given observation.
     */
    public static TacticalDecision evaluate(TacticalObservation obs, List<TacticalOption> candidates) {
        Objects.requireNonNull(obs, "obs");
        Objects.requireNonNull(candidates, "candidates");

        UUID decisionId = UUID.nameUUIDFromBytes(("tactical_decision:" + obs.companionId() + ":" + obs.tick()).getBytes(StandardCharsets.UTF_8));
        Map<UUID, String> rejectedReasons = new HashMap<>();

        if (obs.companionHealth() <= 0.0f) {
            rejectedReasons.put(decisionId, "Ineligible: companion is dead or has zero health");
            return new TacticalDecision(decisionId, obs.tick(), obs.bodyGeneration(),
                    TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS, SurvivalClass.INELIGIBLE, List.of(), rejectedReasons);
        }

        if (candidates.isEmpty()) {
            return new TacticalDecision(decisionId, obs.tick(), obs.bodyGeneration(),
                    TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS, SurvivalClass.INELIGIBLE, List.of(), Map.of());
        }

        // 1. Eligibility filter
        List<TacticalOption> eligible = new ArrayList<>();
        for (var option : candidates) {
            if (option.outcome().survivalClass() == SurvivalClass.INELIGIBLE) {
                rejectedReasons.put(option.optionId(), "Ineligible: unauthorized action, mechanics failure, or impossible execution");
            } else if (option.outcome().collateralRisk()) {
                rejectedReasons.put(option.optionId(), "Ineligible: unacceptable collateral risk to bystanders, teammates, or protected property");
            } else {
                eligible.add(option);
            }
        }

        if (eligible.isEmpty()) {
            return new TacticalDecision(decisionId, obs.tick(), obs.bodyGeneration(),
                    TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS, SurvivalClass.INELIGIBLE, List.of(), rejectedReasons);
        }

        // 2. Self-preservation filter: find best available survival class
        SurvivalClass bestClass = SurvivalClass.UNKNOWN;
        for (var opt : eligible) {
            if (opt.outcome().survivalClass().isBetterThan(bestClass)) {
                bestClass = opt.outcome().survivalClass();
            }
        }

        List<TacticalOption> surviving = new ArrayList<>();
        for (var opt : eligible) {
            if (opt.outcome().survivalClass() == bestClass) {
                surviving.add(opt);
            } else {
                rejectedReasons.put(opt.optionId(), "Rejected by self-preservation: survival class "
                        + opt.outcome().survivalClass() + " is inferior to best available class " + bestClass);
            }
        }

        // 3. Lexicographic objective ranking within surviving set
        TacticalObjective bestObjective = TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS;
        for (var opt : surviving) {
            if (opt.outcome().primaryObjective().hasHigherPriorityThan(bestObjective)) {
                bestObjective = opt.outcome().primaryObjective();
            }
        }

        List<TacticalOption> highestObjectiveOptions = new ArrayList<>();
        for (var opt : surviving) {
            if (opt.outcome().primaryObjective() == bestObjective) {
                highestObjectiveOptions.add(opt);
            } else {
                rejectedReasons.put(opt.optionId(), "Rejected by objective hierarchy: objective "
                        + opt.outcome().primaryObjective() + " is lower than highest achieved objective " + bestObjective);
            }
        }

        // Deterministic ranking within best objective:
        // a) Prevented harm score (descending)
        // b) Reliable target elimination / hazard escape (true before false)
        // c) Conservative received self-damage (ascending)
        // d) Execution time (ascending)
        // e) Resource cost (ascending)
        // f) Tie-breaker: optionId string comparison (input-order-independent)
        Comparator<TacticalOption> comparator = Comparator
                .<TacticalOption>comparingDouble(o -> -o.outcome().preventedHarmScore())
                .thenComparing(o -> !o.outcome().killsTargetReliably())
                .thenComparing(o -> !o.outcome().escapesHazardReliably())
                .thenComparingDouble(o -> o.outcome().receivedSelfDamage().maxDamage())
                .thenComparingInt(o -> o.outcome().executionTime().maxTicks())
                .thenComparingDouble(o -> o.outcome().resourceCost())
                .thenComparing(o -> o.optionId().toString());

        highestObjectiveOptions.sort(comparator);

        return new TacticalDecision(decisionId, obs.tick(), obs.bodyGeneration(),
                bestObjective, bestClass, highestObjectiveOptions, rejectedReasons);
    }

    /**
     * Generates a bounded catalog of candidate tactical sequences for an observation.
     */
    public static List<TacticalOption> generateCandidateOptions(TacticalObservation obs, CombatEstimateModel model) {
        Objects.requireNonNull(obs, "obs");
        Objects.requireNonNull(model, "model");

        List<TacticalOption> options = new ArrayList<>();

        // Baseline 1: Stay and guard
        var staySeq = TacticalSequence.single(ActionStep.STAY_AND_GUARD, null, null);
        UUID stayId = UUID.nameUUIDFromBytes(("stay:" + obs.companionId() + ":" + obs.tick()).getBytes(StandardCharsets.UTF_8));
        options.add(new TacticalOption(stayId, staySeq, model.evaluateSequence(obs, staySeq), "Hold ground and guard"));

        // Baseline 2: Retreat to safe corridor
        if (obs.hasSafeEscapeCorridor()) {
            var retreatSeq = TacticalSequence.single(ActionStep.RETREAT_TO_SAFE_CORRIDOR, null, null);
            UUID retreatId = UUID.nameUUIDFromBytes(("retreat:" + obs.companionId() + ":" + obs.tick()).getBytes(StandardCharsets.UTF_8));
            options.add(new TacticalOption(retreatId, retreatSeq, model.evaluateSequence(obs, retreatSeq), "Disengage to verified safe corridor"));
        }

        // Tactical sequences for observed mobs
        for (var mob : obs.observedMobs()) {
            for (var weapon : obs.availableWeapons()) {
                if (options.size() >= MAX_CANDIDATE_OPTIONS) break;

                boolean needsPrepare = weapon.slot() != obs.heldItemSlot();

                // Candidate A: Normal strike
                List<ActionStep> normalSteps = needsPrepare
                        ? List.of(ActionStep.PREPARE_WEAPON, ActionStep.NORMAL_STRIKE)
                        : List.of(ActionStep.NORMAL_STRIKE);
                var normalSeq = new TacticalSequence(normalSteps, mob.entityId(), weapon);
                UUID normalId = UUID.nameUUIDFromBytes(("normal:" + mob.entityId() + ":" + weapon.slot() + ":" + obs.tick()).getBytes(StandardCharsets.UTF_8));
                options.add(new TacticalOption(normalId, normalSeq, model.evaluateSequence(obs, normalSeq),
                        "Normal strike on " + mob.entityType() + " with " + weapon.itemId()));

                if (options.size() >= MAX_CANDIDATE_OPTIONS) break;

                // Candidate B: Critical strike (only if player is in valid state to perform a jump critical)
                if (obs.canPerformCritical()) {
                    List<ActionStep> critSteps = needsPrepare
                            ? List.of(ActionStep.PREPARE_WEAPON, ActionStep.CRITICAL_STRIKE)
                            : List.of(ActionStep.CRITICAL_STRIKE);
                    var critSeq = new TacticalSequence(critSteps, mob.entityId(), weapon);
                    UUID critId = UUID.nameUUIDFromBytes(("crit:" + mob.entityId() + ":" + weapon.slot() + ":" + obs.tick()).getBytes(StandardCharsets.UTF_8));
                    options.add(new TacticalOption(critId, critSeq, model.evaluateSequence(obs, critSeq),
                            "Critical strike on " + mob.entityType() + " with " + weapon.itemId()));

                    if (options.size() >= MAX_CANDIDATE_OPTIONS) break;
                }

                // Candidate C: Strike then retreat
                if (obs.hasSafeEscapeCorridor()) {
                    List<ActionStep> strikeRetreatSteps = needsPrepare
                            ? List.of(ActionStep.PREPARE_WEAPON, ActionStep.NORMAL_STRIKE, ActionStep.RETREAT_TO_SAFE_CORRIDOR)
                            : List.of(ActionStep.NORMAL_STRIKE, ActionStep.RETREAT_TO_SAFE_CORRIDOR);
                    var strikeRetreatSeq = new TacticalSequence(strikeRetreatSteps, mob.entityId(), weapon);
                    UUID strikeRetreatId = UUID.nameUUIDFromBytes(("strike_retreat:" + mob.entityId() + ":" + weapon.slot() + ":" + obs.tick()).getBytes(StandardCharsets.UTF_8));
                    options.add(new TacticalOption(strikeRetreatId, strikeRetreatSeq, model.evaluateSequence(obs, strikeRetreatSeq),
                            "Strike " + mob.entityType() + " with " + weapon.itemId() + " and retreat"));
                }
            }
            if (options.size() >= MAX_CANDIDATE_OPTIONS) break;
        }

        return options;
    }
}
