package dev.kindred.core.tactics;

import java.util.Locale;
import java.util.Objects;

/**
 * Whether a fight is winnable (E-41h), as the owner set it out (2026-10-25): how much each side deals the other
 * through its armour against the health each has and can win back, as a margin rather than a guess. Each side is what
 * the player can know of it: itself exactly; an opponent as seen (the weapon in its hand, the armour it wears, whether
 * either glints with enchantments it cannot read) and as believed (its health, {@link HealthBeliefs}), never from its
 * hidden state. What it cannot see is allowed for by a worst case: glints at the strongest levels they are likely to
 * stand for, and an opponent that lands its hits as criticals. Winnable when it outlasts them even at worst; losing when
 * it is outlasted even at best; even between.
 *
 * <p>And a hard hit (E-41g): a single hit from another player of more than three hearts, after armour, is lethal
 * aggression, and what the player does about it follows from its kit, the odds and whether it may strike that player at
 * all. Pure.
 */
public final class FightOdds {
    private FightOdds() { }

    public enum Verdict { WINNABLE, EVEN, LOSING }

    /**
     * One side of a fight: its health (absorption included), the armour points and toughness it wears, the Protection
     * levels it is known to have in all (an opponent's are not known: 0), whether its armour or its weapon glints with
     * enchantments not read, the weapon in its hand (its Sharpness known only for the player's own), whether it lands its
     * hits as criticals, whether it holds a shield, the health it can win back in the fight (golden apples), and the
     * hardest hit it has been seen to deal the other side, after armour (0 if none yet).
     */
    public record Side(double health, double armour, double toughness, int protection, boolean armourGlints, DamageModel.Weapon weapon,
                       boolean weaponGlints, boolean crits, boolean shield, double healing, double hardestHit) {
        public Side {
            Objects.requireNonNull(weapon);
            if (health < 0 || health > 60 || armour < 0 || armour > 30 || toughness < 0 || toughness > 20 || protection < 0 || protection > 20
                    || healing < 0 || hardestHit < 0) throw new IllegalArgumentException("A side out of range");
        }
    }

    /**
     * The odds: the verdict; how many times as long it would take them to bring the player down as the player them, at
     * best and at worst; and those seconds, at worst.
     */
    public record Odds(Verdict verdict, double best, double worst, double toWin, double toLose) {
        /** The odds in plain words, for when it explains a choice. */
        public String words() {
            return switch (verdict) {
                case WINNABLE -> "I can win this: I'd outlast them " + times(worst) + " even at worst.";
                case EVEN -> "It's even: " + times(worst) + " at worst, " + times(best) + " at best.";
                case LOSING -> "I can't win this: they'd outlast me " + times(1 / Math.max(best, 1e-6)) + " even at best.";
            };
        }

        private static String times(double ratio) {
            if (ratio >= 99) return "by far";
            return String.format(Locale.ROOT, "%.1f to one", ratio);
        }
    }

    /** Outlasting them by this much even at worst is winnable; outlasted by this much even at best is losing. */
    static final double WIN = 1.1, LOSE = 0.9;
    /** What an unread glint stands for at worst: Protection levels in all on its armour, Sharpness on its weapon. */
    static final int GLINT_PROTECTION = 8, GLINT_SHARPNESS = 3;
    /** The share of hits a shield held up takes, and against an axe, which disables it. */
    static final double SHIELD_SHARE = 0.5, SHIELD_SHARE_AXE = 0.2;

    /** The odds of a fight between the player, {@code self}, and an opponent, {@code them}. */
    public static Odds of(Side self, Side them) {
        Objects.requireNonNull(self);
        Objects.requireNonNull(them);
        double[] best = exchange(self, them, false), worst = exchange(self, them, true);
        double bestRatio = best[1] / best[0], worstRatio = worst[1] / worst[0];
        Verdict verdict = worstRatio >= WIN ? Verdict.WINNABLE : bestRatio < LOSE ? Verdict.LOSING : Verdict.EVEN;
        return new Odds(verdict, bestRatio, worstRatio, worst[0], worst[1]);
    }

    /**
     * Seconds for the player to bring them down, and for them to bring the player down: health and healing over what is
     * dealt a second. With {@code worst}, their glints at their strongest likely and their hits all criticals.
     */
    private static double[] exchange(Side self, Side them, boolean worst) {
        int theirProtection = them.protection() + (worst && them.armourGlints() ? GLINT_PROTECTION : 0);
        double ours = perSecond(self.weapon(), self.crits(), them.armour(), them.toughness(), theirProtection, them.shield(), 0);
        var theirWeapon = them.weapon();
        if (worst && them.weaponGlints() && theirWeapon.sharpness() == 0 && theirWeapon.kind() != DamageModel.Kind.HAND
                && theirWeapon.kind() != DamageModel.Kind.MACE) {
            theirWeapon = new DamageModel.Weapon(theirWeapon.kind(), theirWeapon.tier(), GLINT_SHARPNESS);
        }
        double theirs = perSecond(theirWeapon, them.crits() || worst, self.armour(), self.toughness(), self.protection(), self.shield(),
                them.hardestHit());
        double toWin = ours <= 0 ? Double.MAX_VALUE : (them.health() + them.healing()) / ours;
        double toLose = theirs <= 0 ? Double.MAX_VALUE : (self.health() + self.healing()) / theirs;
        return new double[] {Math.max(toWin, 1e-6), toLose};
    }

    /**
     * What {@code weapon} deals a second at full charge through armour, Protection and a shield held up, a hit at least
     * the hardest seen ({@code seen}, already through armour): no faster than the target's invulnerability allows.
     */
    static double perSecond(DamageModel.Weapon weapon, boolean crits, double armour, double toughness, int protection, boolean shield, double seen) {
        double hit = CombatRuleset.afterArmour(DamageModel.hit(weapon, 1.0, crits, 0, 0), armour, toughness) * (1 - protectionShare(protection));
        hit = Math.max(hit, seen);
        double interval = Math.max(weapon.cooldownTicks(), DamageModel.INVULNERABLE_TICKS);
        double blocked = shield ? (weapon.kind() == DamageModel.Kind.AXE ? SHIELD_SHARE_AXE : SHIELD_SHARE) : 0;
        return hit * 20 / interval * (1 - blocked);
    }

    /** What Protection takes off: four percent a level, at most eighty. */
    static double protectionShare(int levels) {
        return Math.min(0.8, 0.04 * levels);
    }

    /** More than three hearts in one hit, after armour: lethal aggression from another player (E-41g). */
    public static final double LETHAL_HIT = 6.0;

    public static boolean lethal(double taken) {
        return taken > LETHAL_HIT;
    }

    /** What it does about a lethal hit: defends itself, or gets away (blocking as it goes, with a shield). */
    public enum Answer { DEFEND, GET_AWAY }

    /**
     * Its answer to a lethal hit: it defends itself only where it may strike that player at all ({@code mayStrike}: a
     * match both joined, D7), equipped to (a weapon better than a hand), and the fight winnable, or even with no way
     * away ({@code cornered}); otherwise it gets away.
     */
    public static Answer answer(boolean mayStrike, boolean equipped, Odds odds, boolean cornered) {
        Objects.requireNonNull(odds);
        if (!mayStrike || !equipped) return Answer.GET_AWAY;
        if (odds.verdict() == Verdict.WINNABLE || odds.verdict() == Verdict.EVEN && cornered) return Answer.DEFEND;
        return Answer.GET_AWAY;
    }

    /**
     * Its answer to a lethal hit in a duel it joined: both came to fight, so a hard hit is the fight going on, judged by
     * the kit on each side ({@code kitOdds}: the odds with both at full health) rather than by the health the hit
     * already took, which the fight's own melee weighs as it goes (E-41b). Outclassed, it gets away, unless there is no
     * way away ({@code cornered}: a walled arena), where it fights on with what it has; else it fights on.
     */
    public static Answer answerInDuel(boolean equipped, Odds kitOdds, boolean cornered) {
        Objects.requireNonNull(kitOdds);
        return equipped && (kitOdds.verdict() != Verdict.LOSING || cornered) ? Answer.DEFEND : Answer.GET_AWAY;
    }

    /**
     * {@code side} at {@code health}, its kit as it is: the kit alone, for {@link #answerInDuel}. The hardest hit it was
     * seen to deal is left out, as one critical landed is how a fight between equals goes, not its kit.
     */
    public static Side at(Side side, double health) {
        return new Side(health, side.armour(), side.toughness(), side.protection(), side.armourGlints(), side.weapon(), side.weaponGlints(),
                side.crits(), side.shield(), side.healing(), 0);
    }
}
