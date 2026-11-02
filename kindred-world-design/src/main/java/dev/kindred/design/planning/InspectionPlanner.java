package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Compares intended geometry with observations without assigning provenance or authorizing repair. */
public final class InspectionPlanner {
    public enum Finding { PRESENT, MISSING, CHANGED, UNKNOWN, PROTECTED }
    public record Result(Map<Pos, Finding> findings, boolean repairProposalAllowed, String blockedReason) {
        public Result { findings = Map.copyOf(findings); blockedReason = blockedReason == null ? "" : blockedReason; }
    }
    public Result inspect(Design design, Observation observation) {
        if (!design.brief().worldId().equals(observation.worldId()) || !design.brief().dimension().equals(observation.dimension()))
            throw new IllegalArgumentException("Wrong world or dimension");
        Map<Pos, Finding> results = new TreeMap<>(); boolean uncertain = false;
        for (Voxel voxel : design.voxels()) {
            Pos p = voxel.position(); Finding finding;
            if (observation.protectedCells().contains(p)) finding = Finding.PROTECTED;
            else {
                Cell observed = observation.cell(p).orElse(null);
                if (observed == null) { finding = Finding.UNKNOWN; uncertain = true; }
                else if (observed == Cell.AIR) finding = Finding.MISSING;
                else if (observed == Cell.SOLID) finding = Finding.PRESENT; // Material/state provenance is intentionally unavailable in pilot observation.
                else { finding = Finding.CHANGED; uncertain = true; }
            }
            results.put(p, finding);
        }
        return new Result(results, !uncertain && results.values().stream().noneMatch(f -> f == Finding.PROTECTED),
                uncertain ? "fresh-observation-or-reconciliation-required" : "");
    }
}
