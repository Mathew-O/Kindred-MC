package dev.kindred.core.construction;

import java.util.Locale;

/**
 * In plain terms: the look of a player's base: the wood and stone it is built of and the shape and facing of
 * its plan, grown from a block the player was given or the wood it has, and from the world itself, so no two
 * worlds get the same house. The same start always gives the same style, so a base is rebuilt and repaired to
 * its own plan.
 *
 * Technical: A build grown from a seed block (E-29a, the owner's idea of 2026-10-23): the block a player is
 * given, or failing one the wood it has to hand, sets the build's materials, and its world sets the plan's
 * shape, size and the way it faces, so no two worlds get the same static build. Pure and deterministic: the
 * same seed block and world give the same style, so a base is always rebuilt and repaired on its own plan.
 *
 * @param seed    the block it was grown from, as given, or empty
 * @param wood    the wood it is built of: planks, logs, stairs, slabs and its door
 * @param stone   the stone of its walls
 * @param variant its plan's shape and size ({@link Houses#shape}); 0 is the first plan of each stage
 * @param turns   quarter turns clockwise from facing south
 */
public record BuildStyle(String seed, String wood, String stone, int variant, int turns) {
    /** The first plans: oak and cobblestone, as bases were built before E-29a. */
    public static final BuildStyle CLASSIC = new BuildStyle("", "oak", "cobblestone", 0, 0);
    /** Variants of a stage's plan, the first one aside. */
    public static final int VARIANTS = 32;

    public BuildStyle {
        seed = seed == null ? "" : seed;
        if (wood == null || !BuildNeeds.WOODS.contains(wood)) throw new IllegalArgumentException("Not a wood: " + wood);
        if (stone == null || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a stone: " + stone);
        if (variant < 0 || variant > VARIANTS) throw new IllegalArgumentException("No such variant: " + variant);
        turns = Math.floorMod(turns, 4);
    }

    /**
     * The style grown from {@code seedBlock} (may be empty) for a world: its wood the seed's if the seed is wood, else
     * {@code woodAtHand} if it is one, else oak; its plan one of the variants and its facing one of four, both from
     * {@code worldSeed} and the seed block, so another world or another seed grows another build.
     */
    public static BuildStyle grown(String seedBlock, String woodAtHand, long worldSeed) {
        String seed = seedBlock == null ? "" : seedBlock.strip().toLowerCase(Locale.ROOT);
        String wood = woodOf(seed);
        if (wood == null) wood = woodAtHand != null && BuildNeeds.WOODS.contains(woodAtHand) ? woodAtHand : "oak";
        long mix = mix(worldSeed ^ mix(seed.hashCode() * 0x9E3779B97F4A7C15L + wood.hashCode()));
        int variant = 1 + (int) Math.floorMod(mix, (long) VARIANTS);
        int turns = (int) Math.floorMod(mix >>> 17, 4L);
        return new BuildStyle(seed, wood, "cobblestone", variant, turns);
    }

    /** The wood a block is of (a log, planks, stairs, a slab, a door, wood, stripped or not), or null. */
    public static String woodOf(String block) {
        if (block == null) return null;
        String id = block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
        if (id.startsWith("stripped_")) id = id.substring("stripped_".length());
        for (String suffix : new String[] {"_log", "_wood", "_planks", "_stairs", "_slab", "_door", "_fence", "_trapdoor", "_sapling", "_leaves"}) {
            if (!id.endsWith(suffix)) continue;
            String wood = id.substring(0, id.length() - suffix.length());
            if (BuildNeeds.WOODS.contains(wood)) return wood;
        }
        return null;
    }

    /** SplitMix64's finalizer: every bit of the input moves every bit of the output. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
