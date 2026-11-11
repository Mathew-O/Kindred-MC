package dev.kindred.core.route;

/**
 * In plain terms: what the route planners need to know about each block of the world, one plain question at a time:
 * is it loaded, is there water or lava in it, would it hurt, can a body pass through it or stand on it.
 *
 * Technical: Per-cell facts over integer coordinates, answered by whoever holds the world: the server adapter over the
 * live world, or a simulator over its own. Every answer is about the one cell's own block, read as it is; the planners
 * put the facts together (a stand is a cell a body passes through over one that supports it, with no lava about), and
 * ask a fact only of a cell they have found loaded, except where a fact says otherwise. Answers must not change while
 * a planner that asked them is in use: the planners remember what they worked out from them.
 */
public interface Terrain {
    /** The fluid a cell holds, if any: flowing or still. */
    enum Fluid { NONE, WATER, LAVA }

    /** Whether the cell is in a part of the world that is loaded and inside its border and build limits. */
    boolean loaded(int x, int y, int z);

    /** The fluid in the cell. Asked of the cell over a swimmable one and of a cell to dig, loaded or not. */
    Fluid fluid(int x, int y, int z);

    /** Whether the block would hurt or trap a body in or on it: lava, fire, a cactus, a cobweb, magma and the like. */
    boolean harms(int x, int y, int z);

    /**
     * Whether a body moves through the block: no collision at all, or only a thin one at most a fifth of a block high
     * (a carpet), or a door a hand opens when the terrain counts those as a way through.
     */
    boolean passThrough(int x, int y, int z);

    /** Whether the block's collision top is a full step's footing: between 0.8 and 1.0 of a block high. */
    boolean supports(int x, int y, int z);

    /** Whether the block's collision top is at least 0.8 of a block high, a fence's or a wall's taller top included. */
    boolean bears(int x, int y, int z);

    /** Whether the block burns a body standing on it: a magma block or fire. */
    boolean scorches(int x, int y, int z);

    /** Whether the block fills only the lower half of its cell: a bottom slab or a bottom stair. */
    boolean bottomHalf(int x, int y, int z);

    /** Whether the block is a portal's own block, which takes a body in it to another dimension. */
    boolean portal(int x, int y, int z);

    /** Whether a placed block would replace the block: air, a fluid, tall grass and the like. */
    boolean replaceable(int x, int y, int z);

    /** How hard the block is to break, as the game rates it; negative for one that cannot be broken. Asked of a cell to dig. */
    float hardness(int x, int y, int z);

    /**
     * Whether a block can be set against the block's face: loaded, not replaceable, no fluid in it, and with a
     * collision box. Asked of any cell, loaded or not.
     */
    boolean placeAgainst(int x, int y, int z);
}
