package dev.kindred.core;

import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java survival and self-sustenance policies (E-07).
 * Governs eating food to heal, defensive hostile engagement, and tactical retreat.
 * Strictly excludes major bosses and prevents autonomous aggression against players or pets.
 */
public final class SurvivalPolicy {

    public static final Set<String> FORBIDDEN_BOSSES = Set.of(
            "minecraft:wither",
            "minecraft:ender_dragon",
            "minecraft:warden",
            "minecraft:elder_guardian"
    );

    public static final Set<String> HAZARDOUS_BLOCKS = Set.of(
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:lava",
            "minecraft:magma_block",
            "minecraft:cactus",
            "minecraft:sweet_berry_bush",
            "minecraft:powder_snow"
    );

    public static final long EAT_COOLDOWN_MS = 3_000L;
    public static final float LOW_HEALTH_THRESHOLD = 6.0f;

    public enum Action {
        IGNORE,
        DEFEND,
        RETREAT,
        EVADE
    }

    private SurvivalPolicy() { }

    /**
     * Identifies blocks that inflict continuous damage or entrapment on physical entities.
     */
    public static boolean isHazardousBlock(String blockId) {
        if (blockId == null) return false;
        return HAZARDOUS_BLOCKS.contains(blockId.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Whether a player eats a food worth {@code nutrition} now, at {@code foodLevel} of 20, as a person does: hurt, while
     * short of full, for the healing a full bar gives; too hungry to sprint (6 or less), whatever it is; otherwise only
     * when it fills what is missing, at most a point over. Eating a second apple at 18 of 20, a full one's worth gone to
     * waste, was no way to play (suite run 33).
     */
    public static boolean worthEating(int foodLevel, int nutrition, boolean hurt) {
        if (foodLevel >= 20) return false;
        if (hurt || foodLevel <= 6) return true;
        return foodLevel + nutrition <= 21;
    }

    /**
     * Calculates urgency for eating food based on health deficit and hunger level.
     */
    public static double eatingNeed(float health, float maxHealth, int foodLevel) {
        if (health <= LOW_HEALTH_THRESHOLD) return 95.0;
        if (health < maxHealth) {
            return Math.min(94.0, Math.max(70.0 + (maxHealth - health) * 1.5, (20 - foodLevel) * 4.0 + 20.0));
        }
        return Math.min(85.0, Math.max(20.0, (20 - foodLevel) * 4.0 + 20.0));
    }

    /**
     * Determines whether the companion should consume food to recover health.
     */
    public static boolean shouldEat(float currentHealth, float maxHealth, int nutrition, long lastEatTimeMs, long nowMs) {
        if (nutrition <= 0) return false;
        if (currentHealth >= maxHealth) return false;
        return nowMs >= lastEatTimeMs + EAT_COOLDOWN_MS;
    }

    /**
     * Evaluates a nearby entity and decides whether to defend, retreat, or ignore.
     * Guaranteed: Bosses never return DEFEND; low health triggers RETREAT unless combativeness is near maximum.
     */
    public static Action evaluateThreat(String entityTypeId, double distance, float companionHealth,
                                        float companionMaxHealth, Personality personality) {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(personality, "personality");

        // Major bosses are strictly forbidden across all features
        if (FORBIDDEN_BOSSES.contains(entityTypeId.toLowerCase(java.util.Locale.ROOT))) {
            return companionHealth <= 12.0f ? Action.RETREAT : Action.IGNORE;
        }

        // Critical health triggers retreat unless exceptionally combat-inclined
        if (companionHealth <= LOW_HEALTH_THRESHOLD && personality.combativeness() < 85) {
            return Action.RETREAT;
        }

        // Standard hostile within threat range
        if (distance <= 8.0) {
            if (personality.combativeness() >= 20) {
                return Action.DEFEND;
            } else if (distance <= 4.0) {
                return Action.RETREAT;
            }
        }

        return Action.IGNORE;
    }

    /**
     * Contextual remark when consuming food.
     */
    public static String eatingRemark(String foodName, Personality personality, long seed) {
        int variant = Math.floorMod(seed, 3);
        if (personality.sociability() >= 60) {
            return switch (variant) {
                case 0 -> "That hits the spot.";
                case 1 -> "Much needed bite.";
                default -> "Good thing I had some " + foodName + ".";
            };
        } else if (personality.caution() >= 60) {
            return switch (variant) {
                case 0 -> "Keeping my strength up.";
                case 1 -> "Can't risk running low on health.";
                default -> "Eating quickly before trouble shows up.";
            };
        } else {
            return switch (variant) {
                case 0 -> "Back to full strength soon.";
                case 1 -> "Quick bite.";
                default -> "Food helps.";
            };
        }
    }

    /**
     * Contextual remark when defending against a hostile mob.
     */
    public static String defendRemark(String mobName, Personality personality, long seed) {
        int variant = Math.floorMod(seed, 2);
        if (personality.combativeness() >= 60) {
            return variant == 0 ? "Back off!" : "Hostile incoming, dealing with it!";
        } else {
            return variant == 0 ? "Watch out, " + mobName + "!" : "Defending myself!";
        }
    }

    /**
     * Contextual remark when falling back due to low health.
     */
    public static String retreatRemark(Personality personality, long seed) {
        int variant = Math.floorMod(seed, 2);
        return variant == 0 ? "I'm hurt, falling back!" : "Need to retreat and heal!";
    }
}
