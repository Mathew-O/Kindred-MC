package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Direct pilot block-item counts only. No recipes, reservation writes, or native inventory. */
public final class MaterialPlanner {
    public record Budget(Map<Material, Long> required, Map<Material, Long> available,
                         Map<Material, Long> deficits) {
        public Budget {
            required = Map.copyOf(required);
            available = Map.copyOf(available);
            deficits = Map.copyOf(deficits);
        }
        public boolean hasAllDirectItems() { return deficits.isEmpty(); }
    }

    public Budget evaluate(Design design, Map<Material, Long> observedStock,
                           Map<Material, Long> reservedStock) {
        checkCounts(observedStock);
        checkCounts(reservedStock);
        Map<Material, Long> required = new EnumMap<>(Material.class);
        Map<Material, Long> available = new EnumMap<>(Material.class);
        Map<Material, Long> deficits = new EnumMap<>(Material.class);
        for (Voxel voxel : design.voxels()) required.merge(voxel.material(), 1L, Math::addExact);
        for (Material material : Material.values()) {
            long stock = observedStock.getOrDefault(material, 0L);
            long reserved = reservedStock.getOrDefault(material, 0L);
            if (reserved > stock) throw new IllegalArgumentException("Reservations exceed observed stock: " + material);
            long free = stock - reserved;
            available.put(material, free);
            long deficit = Math.max(0, required.getOrDefault(material, 0L) - free);
            if (deficit > 0) deficits.put(material, deficit);
        }
        return new Budget(required, available, deficits);
    }

    private static void checkCounts(Map<Material, Long> counts) {
        Objects.requireNonNull(counts);
        counts.forEach((key, value) -> {
            if (key == null || value == null || value < 0) throw new IllegalArgumentException("Invalid item count");
        });
    }
}
