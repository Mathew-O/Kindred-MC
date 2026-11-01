package dev.kindred.core.guide;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: the fixed list of things a player can find itself short of for what it plans to do later,
 * such as a bow, arrows, a water bucket, a bed, blocks or food. Every one is something the player knows how
 * to gather or craft itself.
 *
 * Technical: Things a player can find itself short of for what it means to do later, from a fixed set. Each
 * is one the local planner knows how to gather or craft from what it finds, so a need queued by the planner
 * or by the optional strategist is always one the player can act on. Declared so that what is crafted comes
 * before what it is crafted from ({@link NeedsAssessment} relies on it).
 */
public enum Need {
    BOW("bow", 1),
    ARROWS("arrow", 64),
    SHIELD("shield", 1),
    WATER_BUCKET("water bucket", 1),
    BED("bed", 4),
    STRING("string", 16),
    FEATHERS("feather", 16),
    WOOL("wool", 16),
    FLINT("flint", 16),
    STICKS("stick", 32),
    BLOCKS("block", 128),
    FOOD("food", 32),
    /** One piece of gold armour worn keeps piglins from attacking (E-07e); gold does not calm hoglins. */
    GOLD_ARMOR("piece of gold armour", 1),
    /** Gold ingots: nether gold ore mined with any pickaxe gives nuggets, nine to an ingot. */
    GOLD("gold ingot", 16),
    /** A manhunt hunter's weapon (M-02a): two iron ingots and a stick, from what the run's iron gives. */
    IRON_SWORD("iron sword", 1);

    private final String word;
    private final int cap;

    Need(String word, int cap) {
        this.word = word;
        this.cap = cap;
    }

    /** What the report and the log call it ("arrow", "water bucket"). */
    public String word() { return word; }

    /** What the report calls {@code count} of it ("8 feathers", "3 string"). */
    public String words(int count) {
        if (count == 1 || this == STRING || this == FLINT || this == FOOD || this == WOOL) return word;
        return word + "s";
    }

    /** The most of it a queue may ask for. */
    public int cap() { return cap; }

    /** A need by its name in any case ("arrows", " BOW "), or nothing for one not in the set. */
    public static Optional<Need> parse(String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_')));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
