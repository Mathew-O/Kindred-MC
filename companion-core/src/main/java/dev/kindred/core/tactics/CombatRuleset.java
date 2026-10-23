package dev.kindred.core.tactics;

/**
 * Vanilla Java Edition 1.21.1's melee rules as the combat controller relies on them (E-41a), each read from the game's
 * own code on the pinned stack, where no mod changes them:
 * <ul>
 *   <li>how far a swing has charged, and when a hit is strong, critical, sweeping or knocks back further;</li>
 *   <li>how damage immunity after a hit works;</li>
 *   <li>when a raised shield blocks, and how long an axe puts it out of use;</li>
 *   <li>how far a player reaches.</li>
 * </ul>
 * The damage a hit deals is {@link DamageModel}'s.
 */
public final class CombatRuleset {
    /** The ruleset every combat number here belongs to. */
    public static final String ID = "java-1.21.1-vanilla";

    /** A swing's charge above this is a strong hit, the only kind that can be critical, sweep or knock back by sprinting. */
    public static final float STRONG = 0.9f;
    /** A critical hit's multiplier. */
    public static final double CRITICAL = 1.5;
    /**
     * Ticks of a hit's damage immunity (vanilla sets {@code timeUntilRegen} to twenty), and the first half of them, in
     * which another hit lands only by what it exceeds the last one by.
     */
    public static final int REGEN_TICKS = 20, IMMUNE_TICKS = DamageModel.INVULNERABLE_TICKS;
    /** Ticks a shield must be held up before it blocks. */
    public static final int SHIELD_RAISE_TICKS = 5;
    /** Ticks an axe's hit on a raised shield puts every shield the player holds out of use. */
    public static final int SHIELD_DISABLED_TICKS = 100;
    /** How far a player reaches from its eyes: to an entity's hitbox, and to a block. */
    public static final double ENTITY_REACH = 3.0, BLOCK_REACH = 4.5;
    /** The strength of the knockback any hit gives; a strong hit while sprinting adds a level of the attack's own. */
    public static final double KNOCKBACK = 0.4;

    private CombatRuleset() { }

    /**
     * A swing's charge {@code ticks} after the last, as vanilla reads it when the attack lands (half a tick on): the
     * ticks over twenty divided by the attack speed, at most one.
     */
    public static float charge(int ticks, double attackSpeed) {
        if (attackSpeed <= 0) throw new IllegalArgumentException("Attack speed must be positive");
        double full = 20.0 / attackSpeed;
        return (float) Math.max(0, Math.min(1, (ticks + 0.5) / full));
    }

    /** The fewest ticks after a swing for the next to be strong. */
    public static int ticksToStrong(double attackSpeed) {
        int ticks = 0;
        while (!strong(charge(ticks, attackSpeed))) ticks++;
        return ticks;
    }

    public static boolean strong(float charge) { return charge > STRONG; }

    /** The attacker as it swings: what decides a critical hit, a sweep and sprint knockback. */
    public record Swing(float charge, double fallDistance, boolean onGround, boolean climbing, boolean inWater,
                        boolean blind, boolean riding, boolean sprinting) {
        /** Standing still on the ground at full charge. */
        public static Swing standing(float charge) {
            return new Swing(charge, 0, true, false, false, false, false, false);
        }
    }

    /** Strong, falling and in the air, not climbing, in water, blind or riding, and not sprinting. */
    public static boolean critical(Swing swing) {
        return strong(swing.charge()) && swing.fallDistance() > 0 && !swing.onGround() && !swing.climbing() && !swing.inWater()
                && !swing.blind() && !swing.riding() && !swing.sprinting();
    }

    /** A strong hit while sprinting knocks back a level more. */
    public static boolean sprintKnockback(Swing swing) {
        return strong(swing.charge()) && swing.sprinting();
    }

    /**
     * A sword's strong hit on the ground, moving no faster than walking, that is neither critical nor a sprint's knockback,
     * sweeps the mobs beside the target.
     */
    public static boolean sweeps(Swing swing, boolean sword, boolean slow) {
        return sword && slow && strong(swing.charge()) && swing.onGround() && !critical(swing) && !sprintKnockback(swing);
    }

    /**
     * What a hit of {@code amount} takes from a target hit {@code ticksSince} ago by {@code last}: all of it once the first
     * half of its immunity is over; within it only what the hit exceeds the last one by, and nothing for one no larger.
     */
    public static double landed(double amount, double last, int ticksSince) {
        if (ticksSince >= IMMUNE_TICKS) return amount;
        return Math.max(0, amount - last);
    }

    /**
     * The damage a hit leaves through armour, as vanilla works it out (a weapon's Breach aside): the armour points less the
     * hit over two and a quarter of the toughness, at least a fifth of the points and at most twenty, each worth four
     * percent off.
     */
    public static double afterArmour(double damage, double armour, double toughness) {
        double divisor = 2 + toughness / 4;
        double effective = Math.max(armour * 0.2, Math.min(20, armour - damage / divisor));
        return damage * (1 - effective / 25);
    }

    /**
     * Whether a shield held up {@code ticksRaised} ticks blocks a hit from a source whose direction from the defender has
     * {@code towardSource} (cosine) with the way the defender faces: held long enough, and the source anywhere in front.
     */
    public static boolean blocks(int ticksRaised, double towardSource) {
        return ticksRaised >= SHIELD_RAISE_TICKS && towardSource > 0;
    }
}
