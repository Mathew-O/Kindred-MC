package dev.kindred.core.route;

/**
 * In plain terms: what a player's eyes need to know about the blocks round it: whether a stretch of the world is
 * there to look at, what kind of thing each block is, whether air, water or a solid wall is beside it, how the light
 * and the ground's top lie, and what a straight line from its eyes runs into first.
 *
 * Technical: The facts {@link Sight} reads (F-35f), over integer coordinates and, for a sight line, doubles, answered by
 * whoever holds the world: the server adapter from the live world, the simulator from its own. No Minecraft types or ids.
 */
public interface SightWorld {
    /** Whether the chunk holding the block is loaded; a read never loads one. */
    boolean inLoadedChunk(int x, int y, int z);

    /** Whether the chunk column {@code chunkX, chunkZ} (block coordinates shifted right by four) is loaded. */
    boolean columnLoaded(int chunkX, int chunkZ);

    /** Whether the 16-cube section at section coordinates is loaded and inside the world's height. */
    boolean sectionLoaded(int sectionX, int sectionY, int sectionZ);

    /**
     * Whether a loaded section may hold a block of a kind {@link Sight} keeps: false for one with no blocks at all, or
     * whose palette holds none of them.
     */
    boolean sectionOfInterest(int sectionX, int sectionY, int sectionZ);

    /** The kind of the block, as {@link Sight} keeps them; null for a block of no kind it keeps. */
    Sight.Kind kind(int x, int y, int z);

    /** Whether the block is air (of any kind: cave air too). */
    boolean air(int x, int y, int z);

    /** Whether the block holds no fluid at all. */
    boolean fluidEmpty(int x, int y, int z);

    /** Whether the block's fluid is water, still or flowing. */
    boolean water(int x, int y, int z);

    /** Whether the block is an opaque full cube: one a face beside it is hidden by. */
    boolean opaqueFullCube(int x, int y, int z);

    /** Whether the block is a log or wood, of any tree. */
    boolean log(int x, int y, int z);

    /** Whether the block is leaves, of any tree. */
    boolean leaves(int x, int y, int z);

    /** Whether the block has no collision box at all. */
    boolean collisionEmpty(int x, int y, int z);

    /**
     * Whether the block is part of something built: planks, stairs, slabs, walls, doors, trapdoors, fences, gates, wool,
     * beds, glass or panes. A log beside one is a builder's, not a tree's.
     */
    boolean built(int x, int y, int z);

    /** The world's sea level. */
    int seaLevel();

    /** The sky's light at the block, 0 to 15. */
    int skyLight(int x, int y, int z);

    /** The height of the first block over the column's top block that blocks movement or holds a fluid (leaves count). */
    int motionTop(int x, int z);

    /** The fluid's height in the block, 0 to 1, as the game reckons it for its surface. */
    float fluidHeight(int x, int y, int z);

    /**
     * The first block the straight line from {@code a} to {@code b} meets, by the blocks' collision shapes and, with
     * {@code fluids}, the fluids' shapes too; null when it meets none.
     */
    Cell ray(double ax, double ay, double az, double bx, double by, double bz, boolean fluids);
}
