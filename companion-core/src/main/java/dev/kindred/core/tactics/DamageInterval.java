package dev.kindred.core.tactics;

import java.util.Objects;

/**
 * Immutable bounded interval representing a range of predicted or dealt damage.
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

    public static DamageInterval zero() {
        return new DamageInterval(0.0, 0.0);
    }

    public static DamageInterval of(double exact) {
        return new DamageInterval(exact, exact);
    }

    public DamageInterval add(DamageInterval other) {
        Objects.requireNonNull(other, "other");
        return new DamageInterval(this.minDamage + other.minDamage, this.maxDamage + other.maxDamage);
    }

    public boolean covers(double amount) {
        return minDamage >= amount;
    }
}
