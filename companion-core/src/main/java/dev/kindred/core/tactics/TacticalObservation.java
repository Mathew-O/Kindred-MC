package dev.kindred.core.tactics;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a snapshot of a fight at one moment: the player's health, armour and weapons, and the mobs
 * around it (only a limited number, so thinking stays quick).
 *
 * Technical: Immutable threat context observed at a specific server tick and body generation.
 * Limits the number of observed entities and weapon candidates to prevent compute spikes.
 */
public record TacticalObservation(
        UUID companionId,
        long tick,
        long bodyGeneration,
        String dimension,
        float companionHealth,
        float companionMaxHealth,
        int foodLevel,
        int heldItemSlot,
        String heldItemId,
        List<WeaponProfile> availableWeapons,
        List<MobThreatObservation> observedMobs,
        boolean hasSafeEscapeCorridor,
        boolean isStandingInHazard,
        boolean foodTaskAuthorized,
        boolean shelterProtectionAuthorized,
        boolean canPerformCritical
) {
    public static final int MAX_OBSERVED_MOBS = 8;
    public static final int MAX_WEAPONS = 6;

    /**
     * Backwards-compatible 15-argument constructor defaulting canPerformCritical to true.
     */
    public TacticalObservation(
            UUID companionId,
            long tick,
            long bodyGeneration,
            String dimension,
            float companionHealth,
            float companionMaxHealth,
            int foodLevel,
            int heldItemSlot,
            String heldItemId,
            List<WeaponProfile> availableWeapons,
            List<MobThreatObservation> observedMobs,
            boolean hasSafeEscapeCorridor,
            boolean isStandingInHazard,
            boolean foodTaskAuthorized,
            boolean shelterProtectionAuthorized
    ) {
        this(companionId, tick, bodyGeneration, dimension, companionHealth, companionMaxHealth,
                foodLevel, heldItemSlot, heldItemId, availableWeapons, observedMobs,
                hasSafeEscapeCorridor, isStandingInHazard, foodTaskAuthorized, shelterProtectionAuthorized, true);
    }

    public TacticalObservation {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(heldItemId, "heldItemId");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be non-negative: " + tick);
        }
        if (bodyGeneration < 0) {
            throw new IllegalArgumentException("bodyGeneration must be non-negative: " + bodyGeneration);
        }
        if (!Float.isFinite(companionHealth) || companionHealth < 0) {
            throw new IllegalArgumentException("companionHealth must be finite and >= 0: " + companionHealth);
        }
        if (!Float.isFinite(companionMaxHealth) || companionMaxHealth <= 0) {
            throw new IllegalArgumentException("companionMaxHealth must be finite and > 0: " + companionMaxHealth);
        }
        if (foodLevel < 0 || foodLevel > 20) {
            throw new IllegalArgumentException("foodLevel must be 0..20: " + foodLevel);
        }
        if (heldItemSlot < 0 || heldItemSlot > 8) {
            throw new IllegalArgumentException("heldItemSlot must be 0..8: " + heldItemSlot);
        }

        availableWeapons = availableWeapons == null ? List.of() :
                List.copyOf(availableWeapons.subList(0, Math.min(availableWeapons.size(), MAX_WEAPONS)));
        observedMobs = observedMobs == null ? List.of() :
                List.copyOf(observedMobs.subList(0, Math.min(observedMobs.size(), MAX_OBSERVED_MOBS)));
    }

    /** Whether this snapshot is out of date. */
    public boolean isStale(long currentTick, long currentBodyGeneration) {
        return this.bodyGeneration != currentBodyGeneration || currentTick < this.tick || (currentTick - this.tick > 20);
    }
}
