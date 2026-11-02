package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel;
import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Validates a reviewed, data-only component before it can inform local planning. */
public final class ReferenceImporter {
    public record Reference(String referenceId, String credit, Box bounds, List<Voxel> voxels,
                            List<Port> ports, Set<Pos> clearance, boolean executablePayload,
                            boolean blockEntityPayload, boolean functionAnnotated) {
        public Reference {
            referenceId = DesignModel.id(referenceId); credit = DesignModel.id(credit); Objects.requireNonNull(bounds);
            voxels = List.copyOf(voxels); ports = List.copyOf(ports); clearance = Set.copyOf(clearance);
            if (voxels.isEmpty() || voxels.size() > 4096 || ports.size() > 16 || clearance.size() > DesignModel.MAX_CELLS)
                throw new IllegalArgumentException("Reference exceeds import bounds");
        }
    }
    public record Imported(String referenceId, String credit, List<Voxel> voxels, List<Port> ports, Set<Pos> clearance,
                           Set<String> unverifiedMechanics) {
        public Imported { voxels = List.copyOf(voxels); ports = List.copyOf(ports); clearance = Set.copyOf(clearance); unverifiedMechanics = Set.copyOf(unverifiedMechanics); }
        public boolean executionEligible() { return unverifiedMechanics.isEmpty(); }
    }
    public Imported importReviewed(Reference reference) {
        if (reference.executablePayload() || reference.blockEntityPayload()) throw new IllegalArgumentException("Executable or block-entity payload rejected");
        Set<Pos> seen = new HashSet<>();
        for (Voxel voxel : reference.voxels()) {
            if (!reference.bounds().contains(voxel.position()) || !seen.add(voxel.position())) throw new IllegalArgumentException("Invalid reference geometry");
        }
        if (!reference.clearance().stream().allMatch(reference.bounds()::contains) || reference.clearance().stream().anyMatch(seen::contains))
            throw new IllegalArgumentException("Invalid reference clearance");
        for (Port port : reference.ports()) if (!reference.bounds().contains(port.feet())) throw new IllegalArgumentException("Port outside reference bounds");
        return new Imported(reference.referenceId(), reference.credit(), reference.voxels(), reference.ports(), reference.clearance(),
                reference.functionAnnotated() ? Set.of() : Set.of("missing-functional-annotation"));
    }
}
