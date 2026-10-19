package dev.kindred.core.tactics;

import dev.kindred.core.CanonicalRoster;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects the optimal owned weapon for the current combat tick with per-roster signature bias.
 */
public final class SignatureWeaponPolicy {

    public record CombatContext(
            String playerName,
            double distanceToTarget,
            double playerFallDistance,
            boolean playerTouchingWaterOrRain,
            boolean targetTouchingWaterOrRain,
            boolean targetBlockingWithShield,
            int nearbyHostileCount,
            boolean overVoidOrLava
    ) { }

    public static double calculateMaceSmashDamage(double baseDamage, double fallDistance, int densityLevel) {
        if (fallDistance < 1.5) {
            return baseDamage;
        }
        double firstTier = Math.min(3.0, fallDistance) * 4.0;
        double secondTier = Math.max(0.0, Math.min(5.0, fallDistance - 3.0)) * 2.0;
        double thirdTier = Math.max(0.0, fallDistance - 8.0) * 1.0;
        double densityBonus = fallDistance * (0.5 * Math.max(0, densityLevel));
        return baseDamage + firstTier + secondTier + thirdTier + densityBonus;
    }

    public static Optional<WeaponProfile> selectOptimalWeapon(
            List<WeaponProfile> ownedWeapons,
            CombatContext ctx
    ) {
        Objects.requireNonNull(ownedWeapons, "ownedWeapons");
        Objects.requireNonNull(ctx, "ctx");
        if (ownedWeapons.isEmpty()) {
            return Optional.empty();
        }

        boolean isVesp = CanonicalRoster.VESP_NAME.equalsIgnoreCase(ctx.playerName());
        boolean isSol = CanonicalRoster.SOL_NAME.equalsIgnoreCase(ctx.playerName());

        WeaponProfile best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (WeaponProfile wp : ownedWeapons) {
            if (wp.durability() <= 2) {
                continue;
            }
            double score = scoreWeapon(wp, ctx, isVesp, isSol);
            if (score > bestScore) {
                bestScore = score;
                best = wp;
            }
        }
        return Optional.ofNullable(best);
    }

    private static double scoreWeapon(WeaponProfile wp, CombatContext ctx, boolean isVesp, boolean isSol) {
        String id = wp.normalizedItemId();
        boolean isTrident = wp.isTrident();
        boolean isMace = wp.isMace();
        boolean isCrossbow = wp.isCrossbow();
        boolean isBow = wp.isBow();
        boolean isSword = wp.isSword();
        boolean isAxe = wp.isAxe();

        double dps = wp.baseDamage() * wp.attackSpeed();
        double score = dps;

        if (ctx.targetBlockingWithShield()) {
            if (isAxe && ctx.distanceToTarget() <= 3.5) {
                score += 45.0;
            } else if (isCrossbow && wp.enchantmentLevel("piercing") > 0) {
                score += 40.0;
            }
        }

        if (isMace) {
            if (ctx.playerFallDistance() >= 1.5 && ctx.distanceToTarget() <= 4.0) {
                double smashDmg = calculateMaceSmashDamage(
                        wp.baseDamage(), ctx.playerFallDistance(), wp.enchantmentLevel("density"));
                score = smashDmg * 3.0 + (isSol ? 35.0 : 15.0);
            } else {
                score = dps - 6.0;
            }
        }

        if (isCrossbow) {
            if (ctx.distanceToTarget() > 4.0 && ctx.distanceToTarget() <= 24.0) {
                score = 22.0 + (isSol ? 14.0 : 4.0) + wp.enchantmentLevel("quick_charge") * 3.0;
                if (wp.crossbowCharged()) {
                    score += 8.0;
                }
            } else {
                score = 5.0;
            }
        }

        if (isTrident) {
            int impaling = wp.enchantmentLevel("impaling");
            int loyalty = wp.enchantmentLevel("loyalty");
            int riptide = wp.enchantmentLevel("riptide");
            double wetBonus = ctx.targetTouchingWaterOrRain() ? impaling * 2.5 : 0.0;

            if (ctx.distanceToTarget() <= 3.5) {
                double crowdPenalty = (ctx.nearbyHostileCount() >= 3 && !ctx.targetTouchingWaterOrRain()) ? -5.0 : 0.0;
                score = (wp.baseDamage() + wetBonus) * wp.attackSpeed() + (isVesp ? 12.0 : 3.0) + crowdPenalty;
            } else if (ctx.distanceToTarget() <= 20.0) {
                if (riptide > 0 && ctx.playerTouchingWaterOrRain()) {
                    score = 32.0 + (isVesp ? 15.0 : 5.0);
                } else if (loyalty > 0 && !ctx.overVoidOrLava()) {
                    score = 24.0 + wetBonus + (isVesp ? 14.0 : 4.0);
                } else {
                    score = -100.0;
                }
            }
        }

        if (isBow) {
            if (ctx.distanceToTarget() > 4.5 && ctx.distanceToTarget() <= 32.0) {
                score = 21.0 + wp.enchantmentLevel("power") * 2.5 + (isVesp ? 6.0 : 10.0);
            } else {
                score = 2.0;
            }
        }

        if (isSword) {
            if (ctx.distanceToTarget() <= 4.5) {
                double crowdBonus = ctx.nearbyHostileCount() >= 3
                        ? 8.0 + wp.enchantmentLevel("sweeping_edge") * 3.0
                        : 0.0;
                score = dps + crowdBonus + (!isVesp && !isSol ? 10.0 : 6.0);
            } else {
                score = 4.0;
            }
        }

        return score;
    }

    private SignatureWeaponPolicy() { }
}
