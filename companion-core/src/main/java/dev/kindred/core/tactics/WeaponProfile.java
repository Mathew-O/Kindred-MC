package dev.kindred.core.tactics;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * In plain terms: what the player knows about one weapon it owns: which slot it is in, what it is, its damage
 * and speed, how worn it is and its enchantments.
 *
 * Technical: Immutable snapshot of an owned weapon or tool, including slot, item type, attributes,
 * durability, and applicable enchantments.
 */
public record WeaponProfile(
        int slot,
        String itemId,
        long fingerprint,
        double baseDamage,
        double attackSpeed,
        int durability,
        Map<String, Integer> enchantments,
        boolean isAxe,
        boolean isSword,
        boolean crossbowCharged
) {
    public enum WeaponCategory {
        SWORD,
        AXE,
        TRIDENT,
        MACE,
        BOW,
        CROSSBOW,
        OTHER_TOOL
    }

    /** Backward-compatible constructor for existing call sites (crossbow not pre-charged). */
    public WeaponProfile(
            int slot,
            String itemId,
            long fingerprint,
            double baseDamage,
            double attackSpeed,
            int durability,
            Map<String, Integer> enchantments,
            boolean isAxe,
            boolean isSword) {
        this(slot, itemId, fingerprint, baseDamage, attackSpeed, durability, enchantments, isAxe, isSword, false);
    }
    public WeaponProfile {
        if (slot < 0 || slot > 35) {
            throw new IllegalArgumentException("Slot must be between 0 and 35: " + slot);
        }
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId cannot be blank");
        }
        if (!Double.isFinite(baseDamage) || baseDamage < 0) {
            throw new IllegalArgumentException("baseDamage must be finite and >= 0: " + baseDamage);
        }
        if (!Double.isFinite(attackSpeed) || attackSpeed <= 0) {
            throw new IllegalArgumentException("attackSpeed must be finite and > 0: " + attackSpeed);
        }
        if (durability < 0) {
            throw new IllegalArgumentException("durability cannot be negative: " + durability);
        }
        enchantments = enchantments == null ? Map.of() : Collections.unmodifiableMap(enchantments);
    }

    /**
     * Ticks required to reach full charge (20 / attackSpeed rounded up).
     */
    public int fullChargeTicks() {
        return (int) Math.ceil(20.0 / attackSpeed);
    }

    /**
     * Returns the level of the requested enchantment, or 0 if absent.
     */
    public int enchantmentLevel(String enchantmentId) {
        if (enchantmentId == null) return 0;
        return enchantments.getOrDefault(enchantmentId.toLowerCase(java.util.Locale.ROOT), 0);
    }

    public static final java.util.Set<String> SUPPORTED_ENCHANTMENTS = java.util.Set.of(
            "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
            "sweeping", "sweeping_edge", "unbreaking", "mending", "looting",
            "impaling", "loyalty", "riptide", "channeling",
            "density", "breach", "wind_burst",
            "power", "punch", "flame", "infinity", "quick_charge", "multishot", "piercing"
    );

    /** The item's id in a standard form. */
    public String normalizedItemId() {
        String clean = itemId.toLowerCase(java.util.Locale.ROOT);
        if (clean.startsWith("minecraft:")) {
            return clean;
        }
        return "minecraft:" + clean;
    }

    /** What kind of weapon it is (sword, axe, bow...). */
    public WeaponCategory category() {
        return switch (normalizedItemId()) {
            case "minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword",
                    "minecraft:golden_sword", "minecraft:diamond_sword", "minecraft:netherite_sword" -> WeaponCategory.SWORD;
            case "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe",
                    "minecraft:golden_axe", "minecraft:diamond_axe", "minecraft:netherite_axe" -> WeaponCategory.AXE;
            case "minecraft:trident" -> WeaponCategory.TRIDENT;
            case "minecraft:mace" -> WeaponCategory.MACE;
            case "minecraft:bow" -> WeaponCategory.BOW;
            case "minecraft:crossbow" -> WeaponCategory.CROSSBOW;
            default -> isSword ? WeaponCategory.SWORD : (isAxe ? WeaponCategory.AXE : WeaponCategory.OTHER_TOOL);
        };
    }

    /** Whether it is a trident. */
    public boolean isTrident() {
        return category() == WeaponCategory.TRIDENT;
    }

    /** Whether it is a mace. */
    public boolean isMace() {
        return category() == WeaponCategory.MACE;
    }

    /** Whether it is a bow. */
    public boolean isBow() {
        return category() == WeaponCategory.BOW;
    }

    /** Whether it is a crossbow. */
    public boolean isCrossbow() {
        return category() == WeaponCategory.CROSSBOW;
    }

    /** Whether it has a certain enchantment. */
    public boolean hasEnchantment(String enchantmentId) {
        return enchantmentLevel(enchantmentId) > 0;
    }

    /** Whether it has enchantments the combat maths does not know. */
    public boolean hasUnknownEnchantments() {
        for (String ench : enchantments.keySet()) {
            String clean = ench.toLowerCase(java.util.Locale.ROOT);
            if (clean.startsWith("minecraft:")) {
                clean = clean.substring("minecraft:".length());
            }
            if (!SUPPORTED_ENCHANTMENTS.contains(clean)) {
                return true;
            }
        }
        return false;
    }
}
