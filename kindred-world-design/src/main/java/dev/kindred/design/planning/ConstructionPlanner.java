package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel;
import dev.kindred.design.model.DesignModel.*;
import dev.kindred.design.validation.StaticValidator;
import java.util.*;

/**
 * Produces a bounded, immutable description of the work still needed. This is a
 * compilation boundary, not a native action dispatcher: every action is marked
 * unqualified until a server adapter supplies the missing mechanics evidence.
 */
public final class ConstructionPlanner {
    public enum BeforeState { KNOWN_AIR, UNKNOWN }
    public enum Capability { NATIVE_PLACEMENT, STANCE_AND_REACH, INTERACTION_FACE, TEMPORARY_ACCESS,
        POLICY_SCOPE, ITEM_CONSERVATION, ACTION_RECONCILIATION }
    public record Context(Pos stance, Pos support, BeforeState expectedBefore, Material intendedAfter,
                          Set<String> claims, Set<String> expectedEvidence) {
        public Context {
            Objects.requireNonNull(support); Objects.requireNonNull(expectedBefore); Objects.requireNonNull(intendedAfter);
            claims = Set.copyOf(claims); expectedEvidence = Set.copyOf(expectedEvidence);
            if (stance != null && stance.equals(support)) throw new IllegalArgumentException("Worker stance cannot occupy support block");
        }
    }
    public record Action(String actionId, Phase phase, Pos target, Material item, Context context,
                         Set<String> dependencies) {
        public Action { actionId = DesignModel.id(actionId); Objects.requireNonNull(phase); Objects.requireNonNull(target);
            Objects.requireNonNull(item); Objects.requireNonNull(context); dependencies = Set.copyOf(dependencies); }
    }
    public record WorkPhase(String phaseId, Phase kind, List<Action> actions, Map<Material, Long> required,
                            Map<Material, Long> deficits, boolean supplyFeasible) {
        public WorkPhase { phaseId = DesignModel.id(phaseId); actions = List.copyOf(actions); required = Map.copyOf(required);
            deficits = Map.copyOf(deficits); }
    }
    public record Plan(String designHash, String observationId, long observationRevision, List<WorkPhase> phases,
                       Set<Capability> missingCapabilities) {
        public Plan { designHash = Objects.requireNonNull(designHash); observationId = DesignModel.id(observationId);
            phases = List.copyOf(phases); missingCapabilities = Set.copyOf(missingCapabilities); }
        public boolean executionQualified() { return false; }
    }

    public Plan compile(Design design, Observation observation, Map<Material, Long> stock, Map<Material, Long> reserved) {
        var report = new StaticValidator().validate(design, observation);
        if (!report.valid()) throw new IllegalArgumentException("Static validation failed: " + report.violations());
        var supports = new SupportCompiler().compile(design, observation);
        var budget = new MaterialPlanner().evaluate(design, stock, reserved);
        Map<Pos, SupportCompiler.Step> byPosition = new HashMap<>();
        for (SupportCompiler.Step step : supports.steps()) byPosition.put(step.proposedBlock().position(), step);
        Map<String, String> actionIds = new HashMap<>();
        for (SupportCompiler.Step step : supports.steps()) {
            Pos p = step.proposedBlock().position();
            actionIds.put(step.stepId(), "place." + p.x() + "." + p.y() + "." + p.z());
        }
        List<WorkPhase> phases = new ArrayList<>();
        Map<Material, Long> remaining = new EnumMap<>(budget.available());
        for (Phase phase : Phase.values()) {
            List<Action> actions = new ArrayList<>(); Map<Material, Long> needed = new EnumMap<>(Material.class);
            for (SupportCompiler.Step step : supports.steps()) {
                if (step.proposedBlock().phase() != phase) continue;
                Voxel voxel = step.proposedBlock(); needed.merge(voxel.material(), 1L, Math::addExact);
                Context context = new Context(null, step.support(), BeforeState.KNOWN_AIR, voxel.material(),
                        Set.of("cell:" + voxel.position().key()), Set.of("target-after-state", "item-conservation-receipt"));
                Pos p = voxel.position();
                Set<String> dependencies = new TreeSet<>();
                for (String dependency : step.dependencies()) dependencies.add(actionIds.get(dependency));
                actions.add(new Action(actionIds.get(step.stepId()), phase, p, voxel.material(), context, dependencies));
            }
            Map<Material, Long> deficits = new EnumMap<>(Material.class);
            for (Map.Entry<Material, Long> entry : needed.entrySet()) {
                long free = remaining.getOrDefault(entry.getKey(), 0L); long missing = Math.max(0L, entry.getValue() - free);
                if (missing > 0) deficits.put(entry.getKey(), missing);
                remaining.put(entry.getKey(), Math.max(0L, free - entry.getValue()));
            }
            phases.add(new WorkPhase("phase." + phase.name().toLowerCase(Locale.ROOT), phase, actions, needed, deficits, deficits.isEmpty()));
        }
        return new Plan(DesignDigest.sha256(design), observation.observationId(), observation.revision(), phases,
                Set.of(Capability.NATIVE_PLACEMENT, Capability.STANCE_AND_REACH, Capability.INTERACTION_FACE,
                        Capability.TEMPORARY_ACCESS, Capability.POLICY_SCOPE, Capability.ITEM_CONSERVATION,
                        Capability.ACTION_RECONCILIATION));
    }
}
