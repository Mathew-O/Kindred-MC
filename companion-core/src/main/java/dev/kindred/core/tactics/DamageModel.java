package dev.kindred.core.tactics;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How much damage a player deals, as vanilla 1.21.1 reckons it (E-39e), so the player can choose the weapon, the
 * timing and the target that deal the most. Pure: the adapter says what is held and what is struck.
 *
 * <p>A melee hit is the weapon's attack damage (one for the hand, plus the item's and its material's bonus, plus three
 * a level of Strength) scaled by the swing's charge ({@code 0.2 + 0.8 * charge²}), with a mace's fall bonus added after
 * that; a critical hit (charge above 0.9, falling, not sprinting) multiplies it by one and a half; Sharpness adds
 * {@code 0.5 * level + 0.5}, scaled by the charge but not by the critical. A struck mob takes no more for ten ticks
 * after a hit unless the new hit is larger, and then only the difference, so hits are worth spacing at least that far
 * apart. The Ender Dragon takes the full blow only on its head; every other part takes a quarter of it plus up to one.
 * While it sits on its perch, arrows do it no harm at all.
 */
public final class DamageModel {
    /** Ticks after a hit in which a mob takes only what a larger hit adds. */
    public static final int INVULNERABLE_TICKS = 10;
    /** The dragon leaves its perch once it has taken this share of its health there. */
    public static final double PERCH_SHARE = 0.25;

    public enum Kind { HAND, SWORD, AXE, MACE }

    public enum Tier { NONE, WOOD, STONE, GOLD, IRON, DIAMOND, NETHERITE }

    /** A melee weapon as held: its kind, its material and its Sharpness level. */
    public record Weapon(Kind kind, Tier tier, int sharpness) {
        public Weapon {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(tier);
            if (sharpness < 0 || sharpness > 255) throw new IllegalArgumentException("Sharpness out of range");
            if ((kind == Kind.HAND || kind == Kind.MACE) != (tier == Tier.NONE)) throw new IllegalArgumentException("A hand or a mace has no tier; a sword or an axe has one");
        }

        public static final Weapon HAND = new Weapon(Kind.HAND, Tier.NONE, 0);

        /** Attack damage, the player's one included. */
        public double attackDamage() {
            return 1 + switch (kind) {
                case HAND -> 0;
                case SWORD -> 3 + material(tier);
                case AXE -> switch (tier) {
                    case WOOD, GOLD -> 6;
                    case STONE -> 7 + 1;
                    case IRON -> 6 + 2;
                    case DIAMOND -> 5 + 3;
                    case NETHERITE -> 5 + 4;
                    case NONE -> 0;
                };
                case MACE -> 5;
            };
        }

        /** Swings a second at full charge. */
        public double attackSpeed() {
            return switch (kind) {
                case HAND -> 4.0;
                case SWORD -> 1.6;
                case AXE -> switch (tier) {
                    case WOOD, STONE -> 0.8;
                    case IRON -> 0.9;
                    default -> 1.0;
                };
                case MACE -> 0.6;
            };
        }

        /** Ticks from one swing to the next at full charge. */
        public double cooldownTicks() {
            return 20.0 / attackSpeed();
        }

        private static int material(Tier tier) {
            return switch (tier) {
                case NONE, WOOD, GOLD -> 0;
                case STONE -> 1;
                case IRON -> 2;
                case DIAMOND -> 3;
                case NETHERITE -> 4;
            };
        }
    }

    private DamageModel() { }

    /**
     * One melee hit: {@code charge} 0..1 is the swing's charge, {@code critical} whether it lands falling (counted only
     * above 0.9 charge), {@code strength} the Strength effect's level (0 for none), {@code fallen} the blocks fallen so
     * far (a mace's smash).
     */
    public static double hit(Weapon weapon, double charge, boolean critical, int strength, double fallen) {
        Objects.requireNonNull(weapon);
        double h = Math.clamp(charge, 0, 1);
        double f = (weapon.attackDamage() + 3.0 * Math.max(0, strength)) * (0.2 + h * h * 0.8);
        if (weapon.kind() == Kind.MACE) f += maceBonus(fallen);
        if (critical && h > 0.9) f *= 1.5;
        double g = (weapon.sharpness() > 0 ? 0.5 * weapon.sharpness() + 0.5 : 0) * h;
        return f + g;
    }

    /** A mace's smash: four a block for the first three fallen, two a block to eight, then one a block. */
    public static double maceBonus(double fallen) {
        if (fallen <= 1.5) return 0;
        if (fallen <= 3) return 4 * fallen;
        if (fallen <= 8) return 12 + 2 * (fallen - 3);
        return 22 + fallen - 8;
    }

    /** What the dragon takes of {@code amount}: all of it on the head, else a quarter plus up to one; none from an arrow while it sits. */
    public static double toDragon(double amount, boolean head, boolean projectile, boolean sitting) {
        if (projectile && sitting) return 0;
        if (head) return amount;
        return amount / 4 + Math.min(amount, 1);
    }

    /**
     * Damage a tick from swinging {@code weapon} again and again at full charge, each hit landing as a critical when
     * {@code critical}: the cooldown, or the target's ten ticks of invulnerability if those are longer.
     */
    public static double perTick(Weapon weapon, boolean critical, int strength) {
        double interval = Math.max(weapon.cooldownTicks(), INVULNERABLE_TICKS);
        return hit(weapon, 1.0, critical, strength, 0) / interval;
    }

    /**
     * The held weapon that deals the most at full charge over time; between two that deal as much, the harder single
     * hit. A swinging fight is decided by damage a tick: a diamond sword's crits (10.5 each, every 12.5 ticks) beat a
     * diamond axe's (13.5, every 20).
     */
    public static Optional<Weapon> best(List<Weapon> held, boolean critical, int strength) {
        return held.stream().max(Comparator.<Weapon>comparingDouble(w -> Math.round(perTick(w, critical, strength) * 1000))
                .thenComparingDouble(w -> hit(w, 1.0, critical, strength, 0)));
    }

    /**
     * The hit to finish with: the weapon whose single fully charged hit is the largest, for when one more hit is all
     * the time allows (the dragon about to take off, a mob about to escape).
     */
    public static Optional<Weapon> hardest(List<Weapon> held, boolean critical, int strength) {
        return held.stream().max(Comparator.comparingDouble(w -> hit(w, 1.0, critical, strength, 0)));
    }

    /**
     * An arrow's damage on landing at {@code speed} blocks a tick: twice the speed (more with Power, half a point and
     * a half a level), rounded up, and with a full draw's critical up to half as much again plus one; the expected value.
     */
    public static double arrow(double speed, int power, boolean critical) {
        double base = 2.0 + (power > 0 ? 0.5 * power + 0.5 : 0);
        int i = (int) Math.ceil(Math.max(0, speed) * base);
        return critical ? i + (i / 2 + 1) / 2.0 : i;
    }
}
