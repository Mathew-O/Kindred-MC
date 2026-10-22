package dev.kindred.design.validation;

import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Conservative static checks on supplied facts, never a native buildability certificate. */
public final class StaticValidator {
    public static final String VERSION = "static-pilot-1";
    public enum Code {
        STALE_OBSERVATION, WRONG_WORLD, OUTSIDE_SITE, DUPLICATE_TARGET,
        UNKNOWN_TARGET, OCCUPIED_TARGET, PROTECTED_TARGET, CLEARANCE_CONFLICT,
        UNKNOWN_CLEARANCE, BLOCKED_CLEARANCE, NO_GROUND_SUPPORT, DISCONNECTED_PORT
    }
    public record Violation(Code code, Pos position, String detail) {}
    public record Report(List<Violation> violations) {
        public Report { violations = List.copyOf(violations); }
        public boolean valid() { return violations.isEmpty(); }
    }

    public Report validate(Design design, Observation observation) {
        List<Violation> failures = new ArrayList<>();
        if (!design.observationId().equals(observation.observationId())
                || design.observationRevision() != observation.revision()) {
            failures.add(new Violation(Code.STALE_OBSERVATION, null, "Snapshot ID/revision changed"));
        }
        if (!design.brief().worldId().equals(observation.worldId())
                || !design.brief().dimension().equals(observation.dimension())) {
            failures.add(new Violation(Code.WRONG_WORLD, null, "World/dimension changed"));
        }
        Map<Pos, Voxel> targets = new HashMap<>();
        for (Voxel voxel : design.voxels()) {
            Pos p = voxel.position();
            if (targets.put(p, voxel) != null) add(failures, Code.DUPLICATE_TARGET, p);
            if (!design.brief().site().contains(p)) add(failures, Code.OUTSIDE_SITE, p);
            Optional<Cell> old = observation.cell(p);
            if (old.isEmpty()) add(failures, Code.UNKNOWN_TARGET, p);
            else if (old.get() != Cell.AIR) add(failures, Code.OCCUPIED_TARGET, p);
            if (observation.protectedCells().contains(p)) add(failures, Code.PROTECTED_TARGET, p);
            if (design.requiredAir().contains(p)) add(failures, Code.CLEARANCE_CONFLICT, p);
            if (voxel.phase() == Phase.FLOOR_AND_PATH
                    && observation.cell(p.below()).orElse(null) != Cell.SOLID) {
                add(failures, Code.NO_GROUND_SUPPORT, p);
            }
        }
        for (Pos p : design.requiredAir()) {
            if (!design.brief().site().contains(p)) add(failures, Code.OUTSIDE_SITE, p);
            Optional<Cell> old = observation.cell(p);
            if (old.isEmpty()) add(failures, Code.UNKNOWN_CLEARANCE, p);
            else if (old.get() != Cell.AIR) add(failures, Code.BLOCKED_CLEARANCE, p);
        }

        Set<Pos> reached = new HashSet<>();
        ArrayDeque<Pos> frontier = new ArrayDeque<>();
        Pos anchor = design.brief().accessAnchor();
        if (walkable(anchor, design, observation, targets)) {
            frontier.add(anchor);
            reached.add(anchor);
        }
        // Pilot proof covers flat, two-cell-high walking only; no jump/stair claim.
        while (!frontier.isEmpty()) {
            for (Pos next : frontier.removeFirst().horizontalNeighbors()) {
                if (!reached.contains(next) && walkable(next, design, observation, targets)) {
                    reached.add(next);
                    frontier.add(next);
                }
            }
        }
        for (Port port : design.ports()) {
            if (!reached.contains(port.feet())) {
                failures.add(new Violation(Code.DISCONNECTED_PORT, port.feet(), port.portId()));
            }
        }
        return new Report(failures);
    }

    private static boolean walkable(Pos feet, Design design, Observation observation,
                                    Map<Pos, Voxel> targets) {
        if (!design.brief().site().contains(feet) || !design.brief().site().contains(feet.above())) return false;
        if (!air(feet, observation, targets) || !air(feet.above(), observation, targets)) return false;
        return targets.containsKey(feet.below()) || observation.cell(feet.below()).orElse(null) == Cell.SOLID;
    }

    private static boolean air(Pos p, Observation observation, Map<Pos, Voxel> targets) {
        return !targets.containsKey(p) && observation.cell(p).orElse(null) == Cell.AIR;
    }

    private static void add(List<Violation> failures, Code code, Pos p) {
        failures.add(new Violation(code, p, code.name()));
    }
}
