package dev.kindred.core.expedition;

/**
 * In plain terms: what an exploring player knows about itself and its surroundings right now (health, food,
 * how full its bags are, the time of day, the weather and how far it is from home), used to decide whether to
 * keep going or head back.
 *
 * Technical: Immutable observation of player state and environmental conditions relevant to exploration.
 */
public record ExpeditionObservation(
        int x,
        int y,
        int z,
        String dimension,
        long timeOfDay, // 0..24000
        boolean isThundering,
        boolean isRaining,
        float health,
        float maxHealth,
        int foodCount, // count of edible food items in inventory
        int foodLevel, // hunger level 0..20
        int occupiedSlots, // occupied inventory slots (out of 36)
        int totalSlots, // 36
        boolean hasWeapon
) {
    /** Whether it is getting dark or already night. */
    public boolean isNightOrDusk() {
        long tod = timeOfDay % 24000;
        return tod >= 11500 && tod <= 23000;
    }
}
