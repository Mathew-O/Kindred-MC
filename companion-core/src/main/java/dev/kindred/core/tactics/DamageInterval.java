package dev.kindred.core.tactics;

import java.util.Objects;

/**
 * In plain terms: a range of damage, from the least to the most a hit could do, because a prediction is never
 * exact.
 *
 * Technical: Immutable bounded interval representing a range of predicted or dealt damage.
 * Enforces non-negative, finite bounds where minDamage <= maxDamage.
 */
public record DamageInterval(double minDamage, double maxDamage) {

    public DamageInterval {
        if (!Double.isFinite(minDamage) || minDamage < 0) {
            throw new IllegalArgumentException("minDamage must be finite and non-negative: " + minDamage);
        }
        if (!Double.isFinite(maxDamage) || maxDamage < minDamage) {
            throw new IllegalArgumentException("maxDamage must be finite and >= minDamage (" + minDamage + "): " + maxDamage);
        }
    }

    /** No damage at all. */
    public static DamageInterval zero() {
        return new DamageInterval(0.0, 0.0);
    }

    /** One exact amount of damage. */
    public static DamageInterval of(double exact) {
        return new DamageInterval(exact, exact);
    }

    /** Two damage ranges added together. */
    public DamageInterval add(DamageInterval other) {
        Objects.requireNonNull(other, "other");
        return new DamageInterval(this.minDamage + other.minDamage, this.maxDamage + other.maxDamage);
    }

    /** Whether an amount falls within the range. */
    public boolean covers(double amount) {
        return minDamage >= amount;
    }
}
