package dev.kindred.core.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TacticalPriorityPolicyTest {

    private final UUID companionId = UUID.randomUUID();
    private final StandardCombatPredictor predictor = new StandardCombatPredictor();

    private TacticalObservation createBaseObservation(float health, int heldSlot, boolean foodAuth, boolean shelterAuth) {
        return new TacticalObservation(
                companionId,
                100L,
                1L,
                "minecraft:overworld",
                health,
                20.0f,
                18,
                heldSlot,
                "minecraft:iron_sword",
                List.of(
                        new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true),
                        new WeaponProfile(1, "minecraft:iron_axe", 2L, 9.0, 0.9, 250, Map.of(), true, false)
                ),
                List.of(),
                true,
                false,
                foodAuth,
                shelterAuth
        );
    }

    @Test
    @DisplayName("Self-preservation strictly filters: high property/loot reward cannot beat a superior survival class")
    void selfPreservationStrictlyOutranksMaterialLoss() {
        var obs = createBaseObservation(10.0f, 0, false, true);

        UUID optionA = UUID.randomUUID(); // Property protection option, but EMERGENCY_ONLY (high danger)
        UUID optionB = UUID.randomUUID(); // Safe retreat option, SURVIVABLE

        var outcomeA = new OutcomeEnvelope(
                SurvivalClass.EMERGENCY_ONLY,
                DamageInterval.of(10.0),
                DamageInterval.of(8.0),
                TimeInterval.of(5),
                true, false, false,
                TacticalObjective.PREVENT_IRREVERSIBLE_MATERIAL_LOSS,
                1000.0, // Arbitrarily huge prevented harm / property reward
                1.0,
                false
        );

        var outcomeB = new OutcomeEnvelope(
                SurvivalClass.SURVIVABLE,
                DamageInterval.zero(),
                DamageInterval.zero(),
                TimeInterval.of(8),
                false, true, false,
                TacticalObjective.RESOLVE_IMMEDIATE_SERIOUS_HARM,
                15.0,
                0.0,
                true
        );

        var optA = new TacticalOption(optionA, TacticalSequence.single(ActionStep.NORMAL_STRIKE, null, null), outcomeA, "Save property at lethal risk");
        var optB = new TacticalOption(optionB, TacticalSequence.single(ActionStep.RETREAT_TO_SAFE_CORRIDOR, null, null), outcomeB, "Retreat to safety");

        var decision = TacticalPriorityPolicy.evaluate(obs, List.of(optA, optB));

        assertTrue(decision.hasViableOption());
        assertEquals(SurvivalClass.SURVIVABLE, decision.achievedSurvivalClass());
        assertEquals(1, decision.winningFrontier().size());
        assertEquals(optionB, decision.winningFrontier().get(0).optionId(), "Survivable retreat must strictly defeat emergency property protection");
        assertTrue(decision.rejectedReasons().containsKey(optionA));
        assertTrue(decision.rejectedReasons().get(optionA).contains("self-preservation"));
    }

    @Test
    @DisplayName("Lexicographic ordering: Immediate serious harm beats material loss, which beats safe resolution")
    void lexicographicObjectiveOrdering() {
        var obs = createBaseObservation(20.0f, 0, false, true);

        UUID idImmediate = UUID.randomUUID();
        UUID idMaterial = UUID.randomUUID();
        UUID idSafeEnd = UUID.randomUUID();

        var optImmediate = new TacticalOption(
                idImmediate,
                TacticalSequence.single(ActionStep.RETREAT_TO_SAFE_CORRIDOR, null, null),
                new OutcomeEnvelope(SurvivalClass.SURVIVABLE, DamageInterval.zero(), DamageInterval.zero(),
                        TimeInterval.of(8), false, true, false,
                        TacticalObjective.RESOLVE_IMMEDIATE_SERIOUS_HARM, 20.0, 0.0, true),
                "Evade immediate blast"
        );

        var optMaterial = new TacticalOption(
                idMaterial,
                TacticalSequence.single(ActionStep.NORMAL_STRIKE, null, null),
                new OutcomeEnvelope(SurvivalClass.SURVIVABLE, DamageInterval.of(10.0), DamageInterval.of(2.0),
                        TimeInterval.of(5), true, false, false,
                        TacticalObjective.PREVENT_IRREVERSIBLE_MATERIAL_LOSS, 50.0, 1.0, true),
                "Protect shelter"
        );

        var optSafeEnd = new TacticalOption(
                idSafeEnd,
                TacticalSequence.single(ActionStep.NORMAL_STRIKE, null, null),
                new OutcomeEnvelope(SurvivalClass.SURVIVABLE, DamageInterval.of(8.0), DamageInterval.of(1.0),
                        TimeInterval.of(3), true, false, false,
                        TacticalObjective.END_ENCOUNTER_SAFELY, 15.0, 0.5, true),
                "Kill distant zombie"
        );

        var decision = TacticalPriorityPolicy.evaluate(obs, List.of(optSafeEnd, optMaterial, optImmediate));

        assertEquals(TacticalObjective.RESOLVE_IMMEDIATE_SERIOUS_HARM, decision.selectedObjective());
        assertEquals(idImmediate, decision.bestOption().orElseThrow().optionId());
    }

    @Test
    @DisplayName("Collateral risk strictly excludes candidate: sword sweep near teammate/shelter is ineligible")
    void collateralRiskExcludesOption() {
        var obs = createBaseObservation(20.0f, 0, false, true);

        UUID idCollateral = UUID.randomUUID();
        UUID idSafe = UUID.randomUUID();

        var optCollateral = new TacticalOption(
                idCollateral,
                TacticalSequence.single(ActionStep.NORMAL_STRIKE, null, null),
                new OutcomeEnvelope(SurvivalClass.SURVIVABLE, DamageInterval.of(12.0), DamageInterval.zero(),
                        TimeInterval.of(1), true, false, true, // Collateral risk!
                        TacticalObjective.END_ENCOUNTER_SAFELY, 25.0, 1.0, true),
                "Sweep attack with collateral risk"
        );

        var optSafe = new TacticalOption(
                idSafe,
                TacticalSequence.single(ActionStep.STAY_AND_GUARD, null, null),
                new OutcomeEnvelope(SurvivalClass.SURVIVABLE, DamageInterval.zero(), DamageInterval.zero(),
                        TimeInterval.of(1), false, false, false,
                        TacticalObjective.END_ENCOUNTER_SAFELY, 5.0, 0.0, true),
                "Guard safely"
        );

        var decision = TacticalPriorityPolicy.evaluate(obs, List.of(optCollateral, optSafe));

        assertEquals(idSafe, decision.bestOption().orElseThrow().optionId());
        assertTrue(decision.rejectedReasons().get(idCollateral).contains("collateral"));
    }

    @Test
    @DisplayName("Input-order independence: evaluation produces identical winner regardless of input list permutation")
    void inputOrderIndependence() {
        var obs = createBaseObservation(20.0f, 0, false, false);

        List<TacticalOption> options = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.nameUUIDFromBytes(("opt:" + i).getBytes());
            options.add(new TacticalOption(
                    id,
                    TacticalSequence.single(ActionStep.NORMAL_STRIKE, null, null),
                    new OutcomeEnvelope(SurvivalClass.SURVIVABLE, DamageInterval.of(i * 2.0), DamageInterval.zero(),
                            TimeInterval.of(10 - i), i % 2 == 0, false, false,
                            TacticalObjective.END_ENCOUNTER_SAFELY, i * 5.0, 0.5, true),
                    "Option " + i
            ));
        }

        var dec1 = TacticalPriorityPolicy.evaluate(obs, options);

        List<TacticalOption> shuffled = new ArrayList<>(options);
        Collections.reverse(shuffled);
        var dec2 = TacticalPriorityPolicy.evaluate(obs, shuffled);

        assertEquals(dec1.bestOption().orElseThrow().optionId(), dec2.bestOption().orElseThrow().optionId());
    }

    @Test
    @DisplayName("Cow hunting is strictly INELIGIBLE unless food task is explicitly authorized")
    void cowHuntingRequiresFoodTaskAuthorization() {
        UUID cowId = UUID.randomUUID();
        var cowThreat = new MobThreatObservation(cowId, "minecraft:cow", 2.0, 10.0, 10.0, 0, 0.0, false, false, true, -1, false, 0, false);

        // 1. Unauthorized food task
        var obsUnauthorized = new TacticalObservation(
                companionId, 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 18, 0, "minecraft:iron_sword",
                List.of(new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true)),
                List.of(cowThreat), true, false, false, false
        );

        var seq = TacticalSequence.single(ActionStep.NORMAL_STRIKE, cowId, obsUnauthorized.availableWeapons().get(0));
        var outcomeUnauthorized = predictor.evaluateSequence(obsUnauthorized, seq);
        assertEquals(SurvivalClass.INELIGIBLE, outcomeUnauthorized.survivalClass(), "Cow attack without food task authorization must be INELIGIBLE");

        // 2. Authorized food task
        var obsAuthorized = new TacticalObservation(
                companionId, 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 18, 0, "minecraft:iron_sword",
                List.of(new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true)),
                List.of(cowThreat), true, false, true, false
        );
        var outcomeAuthorized = predictor.evaluateSequence(obsAuthorized, seq);
        assertEquals(SurvivalClass.SURVIVABLE, outcomeAuthorized.survivalClass(), "Cow attack with food task authorization is SURVIVABLE");
        assertEquals(TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS, outcomeAuthorized.primaryObjective());
    }

    @Test
    @DisplayName("Candidate generation strictly bounds search space to MAX_CANDIDATE_OPTIONS")
    void candidateGenerationBoundsSearchSpace() {
        List<MobThreatObservation> mobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            mobs.add(new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 3.0, 20.0, 20.0, 2, 0.0, true, false, true, -1, false, 20, false));
        }

        List<WeaponProfile> weapons = List.of(
                new WeaponProfile(0, "minecraft:diamond_sword", 1L, 7.0, 1.6, 1500, Map.of(), false, true),
                new WeaponProfile(1, "minecraft:diamond_axe", 2L, 9.0, 1.0, 1500, Map.of(), true, false),
                new WeaponProfile(2, "minecraft:iron_sword", 3L, 6.0, 1.6, 250, Map.of(), false, true)
        );

        var obs = new TacticalObservation(
                companionId, 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20, 0, "minecraft:diamond_sword",
                weapons, mobs, true, false, false, false
        );

        var candidates = TacticalPriorityPolicy.generateCandidateOptions(obs, predictor);
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.size() <= TacticalPriorityPolicy.MAX_CANDIDATE_OPTIONS,
                "Candidate options count (" + candidates.size() + ") must not exceed " + TacticalPriorityPolicy.MAX_CANDIDATE_OPTIONS);
    }

    @Test
    @DisplayName("Dead companion with 0 health returns INELIGIBLE decision immediately")
    void deadCompanionYieldsIneligibleDecision() {
        var obsDead = new TacticalObservation(
                companionId, 100L, 1L, "minecraft:overworld", 0.0f, 20.0f, 0, 0, "minecraft:iron_sword",
                List.of(new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true)),
                List.of(), true, false, false, false
        );

        var decision = TacticalPriorityPolicy.evaluate(obsDead, List.of());
        assertEquals(SurvivalClass.INELIGIBLE, decision.achievedSurvivalClass());
        assertFalse(decision.hasViableOption());
    }

    @Test
    @DisplayName("Cow hunting prefers normal strike over critical strike when normal strike already reliably kills")
    void cowHuntingNormalStrikeBeatsCriticalStrikeWhenNormalStrikeReliablyKills() {
        UUID cowId = UUID.randomUUID();
        var cow = new MobThreatObservation(cowId, "minecraft:cow", 2.0, 10.0, 10.0, 0, 0.0, false, false, true, -1, false, 0, false);

        // Netherite axe with base damage 10.0 -> normal strike at full charge deals 10.0, which one-shots cow!
        var axe = new WeaponProfile(0, "minecraft:netherite_axe", 1L, 10.0, 1.0, 2000, Map.of(), true, false);

        var obs = new TacticalObservation(
                companionId, 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20, 0, "minecraft:netherite_axe",
                List.of(axe),
                List.of(cow),
                true, false, true, false // foodTaskAuthorized = true
        );

        var normalSeq = TacticalSequence.single(ActionStep.NORMAL_STRIKE, cowId, axe);
        var critSeq = TacticalSequence.single(ActionStep.CRITICAL_STRIKE, cowId, axe);

        var optNormal = new TacticalOption(UUID.randomUUID(), normalSeq, predictor.evaluateSequence(obs, normalSeq), "Normal axe strike on cow");
        var optCrit = new TacticalOption(UUID.randomUUID(), critSeq, predictor.evaluateSequence(obs, critSeq), "Jump critical on cow");

        assertTrue(optNormal.outcome().killsTargetReliably(), "Normal strike reliably kills 10 HP cow with 10 dmg");
        assertTrue(optCrit.outcome().killsTargetReliably(), "Crit strike reliably kills cow with 15 dmg");
        assertTrue(optNormal.outcome().executionTime().maxTicks() < optCrit.outcome().executionTime().maxTicks(),
                "Normal strike takes fewer ticks (1) than critical jump setup (9)");

        var decision = TacticalPriorityPolicy.evaluate(obs, List.of(optCrit, optNormal));

        assertEquals(optNormal.optionId(), decision.bestOption().orElseThrow().optionId(),
                "Normal strike must win over critical strike because jumping is unnecessary when normal hit already suffices");
    }

    @Test
    @DisplayName("Candidate generation omits critical strike when player cannot jump-crit (e.g. water, climbing, blind)")
    void candidateGenerationOmitsCriticalStrikeWhenCanPerformCriticalIsFalse() {
        var zombie = new MobThreatObservation(UUID.randomUUID(), "minecraft:zombie", 2.0, 20.0, 20.0, 2, 0.0, true, false, true, -1, false, 20, false);
        var sword = new WeaponProfile(0, "minecraft:iron_sword", 1L, 6.0, 1.6, 250, Map.of(), false, true);

        // canPerformCritical = false
        var obsRestricted = new TacticalObservation(
                companionId, 100L, 1L, "minecraft:overworld", 20.0f, 20.0f, 20, 0, "minecraft:iron_sword",
                List.of(sword),
                List.of(zombie),
                true, false, false, false,
                false // canPerformCritical = false
        );

        var candidates = TacticalPriorityPolicy.generateCandidateOptions(obsRestricted, predictor);

        boolean hasCritStep = candidates.stream()
                .anyMatch(c -> c.sequence().containsStep(ActionStep.CRITICAL_STRIKE));
        assertFalse(hasCritStep, "Candidate generator must not propose CRITICAL_STRIKE when canPerformCritical is false");
    }
}
