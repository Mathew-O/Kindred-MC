package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel.*;
import dev.kindred.design.validation.StaticValidator;
import java.util.*;

/** Preliminary adjacency DAG. No stance, reach, scaffolding, or native authority is inferred. */
public final class SupportCompiler {
    public record Step(String stepId, Voxel proposedBlock, Pos support,
                       Set<String> dependencies) {
        public Step { dependencies = Set.copyOf(dependencies); }
    }
    public record Plan(String designHash, String observationId, long observationRevision,
                       List<Step> steps, Set<String> missingQualification) {
        public Plan {
            steps = List.copyOf(steps);
            missingQualification = Set.copyOf(missingQualification);
        }
        public boolean executionQualified() { return false; }
    }

    public Plan compile(Design design, Observation observation) {
        var validation = new StaticValidator().validate(design, observation);
        if (!validation.valid()) throw new IllegalArgumentException("Static validation failed: " + validation.violations());
        Map<Pos, Voxel> pending = new TreeMap<>();
        design.voxels().forEach(v -> pending.put(v.position(), v));
        Map<Pos, String> planned = new HashMap<>();
        List<Step> steps = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            boolean progress;
            do {
                progress = false;
                for (var entry : new ArrayList<>(pending.entrySet())) {
                    Voxel voxel = entry.getValue();
                    if (voxel.phase() != phase) continue;
                    List<Pos> candidates = new ArrayList<>();
                    candidates.add(voxel.position().below());
                    candidates.addAll(voxel.position().horizontalNeighbors());
                    Pos support = candidates.stream().filter(p -> planned.containsKey(p)
                            || observation.cell(p).orElse(null) == Cell.SOLID).findFirst().orElse(null);
                    if (support == null) continue;
                    String stepId = "place:" + voxel.position().key();
                    Set<String> dependencies = planned.containsKey(support)
                            ? Set.of(planned.get(support)) : Set.of();
                    steps.add(new Step(stepId, voxel, support, dependencies));
                    planned.put(voxel.position(), stepId);
                    pending.remove(voxel.position());
                    progress = true;
                }
            } while (progress);
            if (pending.values().stream().anyMatch(v -> v.phase() == phase)) {
                throw new IllegalArgumentException("No supported adjacency order for phase " + phase);
            }
        }
        return new Plan(DesignDigest.sha256(design), observation.observationId(), observation.revision(), steps,
                Set.of("native-placement-mechanics", "worker-stance-and-reach", "construction-access",
                        "temporary-supports", "runtime-authority", "durable-action-reconciliation"));
    }
}
