package dev.kindred.core.route;

/**
 * In plain terms: what a player digging its own way up, down or along through rock needs to know about each block,
 * beyond what a walk needs: whether anything at all is in the block's space, whether the block is sand or gravel that
 * falls when dug under, whether such a block would fall through it, and whether any is coming down right now.
 *
 * Technical: The per-cell facts the {@link Digger} reads on top of {@link Terrain}, over integer coordinates, answered by
 * whoever holds the world (F-35e). Each answer is about the one cell's own block as it is, except {@link #falling}, which
 * asks after falling blocks in flight in an axis-aligned box given by its corners in block units.
 */
public interface DigTerrain extends Terrain {
    /** Whether the block has no collision box at all. Unlike {@link #passThrough}, a carpet or a door does not count. */
    boolean empty(int x, int y, int z);

    /** Whether the block falls when nothing holds it up: sand, gravel and the like. */
    boolean falls(int x, int y, int z);

    /** Whether a falling block would drop through the block: air, fire, a liquid, or a block a placed one replaces. */
    boolean fallsThrough(int x, int y, int z);

    /**
     * Whether a falling block in flight touches the box from the corner {@code x0, y0, z0} to {@code x1, y1, z1}, in
     * block units: the box of the cell at 3, 4, 5 alone is 3, 4, 5 to 4, 5, 6.
     */
    boolean falling(int x0, int y0, int z0, int x1, int y1, int z1);
}
