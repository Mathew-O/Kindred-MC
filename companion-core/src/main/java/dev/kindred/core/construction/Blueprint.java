package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Something to build (E-08a): blocks in exact states at positions in its own frame, from a schematic, the user's
 * library or a local generator, with where it came from and under what license (D8). Air in a blueprint is space to
 * keep clear, not a block to place. Bounded, immutable and pure.
 *
 * @param name    a short name for the log and the player's speech
 * @param source  where it came from: a file, a link, or the generator that made it
 * @param creator who made it, or empty
 * @param license the terms it is used under, or empty
 * @param blocks  the blocks to place, one a position
 * @param clear   positions to keep clear (air in the blueprint)
 */
public record Blueprint(String name, String source, String creator, String license, List<Block> blocks, List<Pos> clear) {
    /** Largest blueprint taken: a big house, not a city; larger builds are several. */
    public static final int MAX_BLOCKS = 32_768;
    public static final int MAX_SPAN = 96;

    /** One block of it. */
    public record Block(Pos at, BlockSpec spec) {
        public Block {
            Objects.requireNonNull(at);
            Objects.requireNonNull(spec);
            if (spec.air()) throw new IllegalArgumentException("Air is clearance, not a block");
        }
    }

    public Blueprint {
        name = Objects.requireNonNull(name).strip();
        source = Objects.requireNonNullElse(source, "").strip();
        creator = Objects.requireNonNullElse(creator, "").strip();
        license = Objects.requireNonNullElse(license, "").strip();
        blocks = List.copyOf(blocks);
        clear = List.copyOf(clear);
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("A blueprint has a short name");
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS || clear.size() > MAX_BLOCKS * 4) throw new IllegalArgumentException("Blueprint size out of bounds");
        var seen = new java.util.HashSet<Pos>();
        for (Block b : blocks) if (!seen.add(b.at())) throw new IllegalArgumentException("Two blocks at " + b.at());
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Block b : blocks) {
            min[0] = Math.min(min[0], b.at().x()); min[1] = Math.min(min[1], b.at().y()); min[2] = Math.min(min[2], b.at().z());
            max[0] = Math.max(max[0], b.at().x()); max[1] = Math.max(max[1], b.at().y()); max[2] = Math.max(max[2], b.at().z());
        }
        for (int i = 0; i < 3; i++) if ((long) max[i] - min[i] + 1 > MAX_SPAN) throw new IllegalArgumentException("Blueprint wider than " + MAX_SPAN);
    }

    /**
     * This blueprint in other materials: each block whose kind {@code palette} names becomes the kind it maps to, in the
     * same state (an oak stair facing north becomes a spruce stair facing north). Nothing else changes.
     */
    public Blueprint recolored(Map<String, String> palette, String name) {
        var out = new ArrayList<Block>(blocks.size());
        for (Block b : blocks) {
            String kind = palette.getOrDefault(b.spec().block(), b.spec().block());
            out.add(new Block(b.at(), new BlockSpec(kind, b.spec().properties())));
        }
        return new Blueprint(name, source, creator, license, out, clear);
    }

    /** A blueprint with no clearance kept. */
    public Blueprint(String name, String source, String creator, String license, List<Block> blocks) {
        this(name, source, creator, license, blocks, List.of());
    }

    /**
     * This blueprint turned clockwise (seen from above) by {@code quarterTurns} about its origin, its blocks' states
     * turned with it, then placed with its origin at {@code origin}: the blocks as the world will hold them.
     */
    public List<Block> placed(Pos origin, int quarterTurns) {
        var out = new ArrayList<Block>(blocks.size());
        for (Block b : blocks) out.add(new Block(origin.add(turn(b.at(), quarterTurns)), b.spec().rotated(quarterTurns)));
        return List.copyOf(out);
    }

    /** As {@link #placed}, for the cells to keep clear. */
    public List<Pos> placedClear(Pos origin, int quarterTurns) {
        var out = new ArrayList<Pos>(clear.size());
        for (Pos p : clear) out.add(origin.add(turn(p, quarterTurns)));
        return List.copyOf(out);
    }

    /** A position turned clockwise seen from above (north to east): x, z becomes -z, x, a quarter at a time. */
    static Pos turn(Pos p, int quarterTurns) {
        int x = p.x(), z = p.z();
        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {
            int nx = Math.negateExact(z);
            z = x;
            x = nx;
        }
        return new Pos(x, p.y(), z);
    }

    /** The blocks by position, as placed. */
    public static Map<Pos, BlockSpec> byPosition(List<Block> placed) {
        var map = new HashMap<Pos, BlockSpec>(placed.size() * 2);
        for (Block b : placed) map.put(b.at(), b.spec());
        return map;
    }
}
