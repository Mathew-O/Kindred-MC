package dev.kindred.core.tactics;

import java.util.Objects;
import java.util.UUID;

/**
 * Bounded immutable observation of a nearby mob.
 * Visible mob health is observable under explicit adapter policy; unseen mobs or hidden
 * states remain protected.
 */
public record MobThreatObservation(
        UUID entityId,
        String entityType,
        double distance,
        double currentHealth,
        double maxHealth,
        int armor,
        double toughness,
        boolean isUndead,
        boolean isArthropod,
        boolean isVisible,
        int fuseTicksRemaining,
        boolean isIgnited,
        int estimatedTicksToAttack,
        boolean nearTeammateOrShelter
) {
    public MobThreatObservation {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(entityType, "entityType");
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("distance must be finite and >= 0: " + distance);
        }
        if (!Double.isFinite(currentHealth) || currentHealth < 0) {
            throw new IllegalArgumentException("currentHealth must be finite and >= 0: " + currentHealth);
        }
        if (!Double.isFinite(maxHealth) || maxHealth <= 0) {
            throw new IllegalArgumentException("maxHealth must be finite and > 0: " + maxHealth);
        }
        if (armor < 0) {
            throw new IllegalArgumentException("armor must be non-negative: " + armor);
        }
        if (!Double.isFinite(toughness) || toughness < 0) {
            throw new IllegalArgumentException("toughness must be finite and >= 0: " + toughness);
        }
        if (fuseTicksRemaining < -1) {
            throw new IllegalArgumentException("fuseTicksRemaining must be >= -1: " + fuseTicksRemaining);
        }
        if (estimatedTicksToAttack < 0) {
            throw new IllegalArgumentException("estimatedTicksToAttack must be non-negative: " + estimatedTicksToAttack);
        }
    }

    public boolean isCreeper() {
        return "minecraft:creeper".equalsIgnoreCase(entityType);
    }

    public boolean isZombie() {
        return entityType.toLowerCase(java.util.Locale.ROOT).contains("zombie");
    }

    public boolean isSkeleton() {
        return entityType.toLowerCase(java.util.Locale.ROOT).contains("skeleton");
    }

    public boolean isCow() {
        return "minecraft:cow".equalsIgnoreCase(entityType);
    }
}
