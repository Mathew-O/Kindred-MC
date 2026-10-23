package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bases that grow with the world (M-01b, the owner's note of 2026-10-23): three stages, each a functional build a
 * player makes from earned blocks, each room with a purpose. The origin is the ground floor's north-west corner; the
 * floor lies on the ground and the entrance is in the middle of the south wall.
 * <ul>
 *   <li>{@link Stage#CABIN}: one room to start the world: a bed, a crafting table, a furnace, two chests, light.</li>
 *   <li>{@link Stage#HOUSE}: two floors. Below, a storage wall of chests and a workshop of a table and two furnaces;
 *   a staircase up to a bedroom of two beds.</li>
 *   <li>{@link Stage#MANOR}: two floors on a larger plan. Below, a storage hall, a workshop and an enchanting room, the
 *   table ringed by fifteen bookshelves; above, bedrooms and a library.</li>
 * </ul>
 * Windows are open, glass coming later. Pure.
 */
public final class Houses {
    public enum Stage {
        CABIN("cabin", 7, 7), HOUSE("house", 9, 11), MANOR("manor", 13, 15);

        public final String noun;
        public final int width, depth;

        Stage(String noun, int width, int depth) {
            this.noun = noun;
            this.width = width;
            this.depth = depth;
        }

        /** The stage after this one, or this one when it is the last. */
        public Stage next() { return this == CABIN ? HOUSE : MANOR; }
    }

    /** The functional blocks a base of each stage holds, by kind: what makes it more than walls. */
    public static Map<String, Integer> functions(Blueprint base) {
        var out = new LinkedHashMap<String, Integer>();
        for (var b : base.blocks()) {
            String id = b.spec().block();
            if (FUNCTIONAL.contains(id) || id.endsWith("_bed") && "foot".equals(b.spec().property("part"))
                    || id.endsWith("_door") && "lower".equals(b.spec().property("half"))) {
                out.merge(id.endsWith("_bed") ? "bed" : id.endsWith("_door") ? "door" : id, 1, Integer::sum);
            }
        }
        return out;
    }

    private static final Set<String> FUNCTIONAL = Set.of("minecraft:chest", "minecraft:crafting_table", "minecraft:furnace",
            "minecraft:enchanting_table", "minecraft:bookshelf");

    private Houses() { }

    public static Blueprint of(Stage stage, String wood, String stone) {
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a wood or a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        var b = new Builder(stage.width, stage.depth, wood, s);
        switch (stage) {
            case CABIN -> cabin(b);
            case HOUSE -> house(b);
            case MANOR -> manor(b);
        }
        return new Blueprint(wood + " " + stage.noun, Generators.SOURCE + "house?stage=" + stage.noun + "&wood=" + wood + "&stone=" + s,
                "Kindred", "Kindred's own", b.blocks(), b.clear());
    }

    /** One room: walls three high under a gabled roof, a bed, a table, a furnace, two chests and torches. */
    private static void cabin(Builder b) {
        b.floor(0);
        b.walls(1, 3);
        b.door(b.width / 2, 1, b.depth - 1);
        b.window(1, 2, 0);
        b.window(b.width - 2, 2, 0);
        b.window(0, 2, b.depth / 2);
        b.window(b.width - 1, 2, b.depth / 2);
        b.gableRoof(4);
        // Furniture along the walls, nothing in the way of the door.
        b.bed(1, 1, 2, "north");
        b.put(b.width - 2, 1, 1, "minecraft:crafting_table");
        b.put(b.width - 2, 1, 2, "minecraft:furnace[facing=west]");
        b.put(b.width - 2, 1, 4, "minecraft:chest[facing=west]");
        b.put(1, 1, 4, "minecraft:chest[facing=east]");
        b.put(b.width / 2, 2, 1, "minecraft:wall_torch[facing=south]");
        b.clearInside(1, 3);
    }

    /**
     * Two floors: storage and a workshop below, a bedroom above, stairs between, a flat roof laid from the floor below
     * it (a gable this wide puts its middle out of every builder's reach).
     */
    private static void house(Builder b) {
        b.floor(0);
        b.walls(1, 8);
        b.upperFloor(5, b.width - 2, 5);
        b.door(b.width / 2, 1, b.depth - 1);
        for (int y : new int[] {2, 7}) {
            b.window(2, y, 0);
            b.window(b.width - 3, y, 0);
            b.window(0, y, 3);
            b.window(0, y, b.depth - 4);
            b.window(b.width - 1, y, b.depth - 4);
        }
        b.window(b.width / 2 - 2, 7, b.depth - 1);
        b.window(b.width / 2 + 2, 7, b.depth - 1);
        // The staircase up the east wall, rising north to the upper floor's landing.
        int sx = b.width - 2;
        b.stairs(sx, 1, 6, "north", 4);
        // Below: a storage wall of chests along the west wall, one apart so none join into a double chest, and the workshop.
        for (int z = 1; z < b.depth - 2; z += 2) b.put(1, 1, z, "minecraft:chest[facing=east]");
        b.put(sx - 1, 1, 1, "minecraft:crafting_table");
        b.put(sx - 2, 1, 1, "minecraft:furnace[facing=south]");
        b.put(sx - 3, 1, 1, "minecraft:furnace[facing=south]");
        b.put(b.width / 2, 3, b.depth - 2, "minecraft:wall_torch[facing=north]");
        b.put(1, 3, 2, "minecraft:wall_torch[facing=east]");
        // Above: the bedroom.
        b.bed(1, 6, 2, "north");
        b.bed(3, 6, 2, "north");
        b.put(1, 6, b.depth - 3, "minecraft:chest[facing=east]");
        b.put(b.width / 2, 7, 1, "minecraft:wall_torch[facing=south]");
        b.clearInside(1, 4);
        b.clearInside(6, 8);
        b.flatRoof(9);
    }

    /**
     * Two floors on a larger plan: a storage hall, a workshop and an enchanting room below; bedrooms and a library above;
     * a flat roof.
     */
    private static void manor(Builder b) {
        b.floor(0);
        b.walls(1, 8);
        b.upperFloor(5, b.width - 2, 5);
        b.door(b.width / 2, 1, b.depth - 1);
        for (int y : new int[] {2, 7}) {
            for (int x = 2; x < b.width - 2; x += 4) b.window(x, y, 0);
            for (int z = 3; z < b.depth - 2; z += 4) {
                b.window(0, y, z);
                b.window(b.width - 1, y, z);
            }
        }
        int sx = b.width - 2;
        b.stairs(sx, 1, 6, "north", 4);
        // The storage hall along the west wall.
        for (int z = 1; z < b.depth - 1; z += 2) b.put(1, 1, z, "minecraft:chest[facing=east]");
        // The workshop by the stairs.
        b.put(sx - 1, 1, 8, "minecraft:crafting_table");
        b.put(sx - 1, 1, 9, "minecraft:furnace[facing=west]");
        b.put(sx - 1, 1, 10, "minecraft:furnace[facing=west]");
        // The enchanting room in the north-west: the table, the ring of bookshelves two out with its gap facing south.
        int ex = 5, ez = 3;
        b.put(ex, 1, ez, "minecraft:enchanting_table");
        int shelves = 0;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
            if (dz == 2 && dx == 0) continue; // The way in.
            if (shelves++ < 15) b.put(ex + dx, 1, ez + dz, "minecraft:bookshelf");
        }
        b.put(b.width / 2, 3, b.depth - 2, "minecraft:wall_torch[facing=north]");
        b.put(1, 3, b.depth - 2, "minecraft:wall_torch[facing=east]");
        // Above: two bedrooms and a library of shelves along the north wall.
        b.bed(1, 6, 2, "north");
        b.bed(3, 6, 2, "north");
        b.bed(1, 6, b.depth - 3, "south");
        for (int x = 5; x < b.width - 3; x++) b.put(x, 6, 1, "minecraft:bookshelf");
        b.put(b.width / 2, 7, b.depth - 2, "minecraft:wall_torch[facing=north]");
        b.clearInside(1, 4);
        b.clearInside(6, 8);
        b.flatRoof(9);
    }

    /** Lays a base out cell by cell; later blocks at a cell replace earlier ones. */
    private static final class Builder {
        final int width, depth;
        final String wood, stone;
        private final Map<Pos, BlockSpec> at = new LinkedHashMap<>();
        private final List<Pos> open = new ArrayList<>();

        Builder(int width, int depth, String wood, String stone) {
            this.width = width;
            this.depth = depth;
            this.wood = wood;
            this.stone = stone;
        }

        String planks() { return "minecraft:" + wood + "_planks"; }

        void put(int x, int y, int z, String spec) { at.put(new Pos(x, y, z), BlockSpec.parse(spec)); }

        void remove(int x, int y, int z) { at.remove(new Pos(x, y, z)); }

        boolean edge(int x, int z) { return x == 0 || z == 0 || x == width - 1 || z == depth - 1; }

        void floor(int y) {
            for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) put(x, y, z, planks());
        }

        /** Stone walls with upright logs at the corners, from {@code from} to {@code to}. */
        void walls(int from, int to) {
            for (int y = from; y <= to; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
                if (!edge(x, z)) continue;
                boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
                put(x, y, z, corner ? "minecraft:" + wood + "_log[axis=y]" : stone);
            }
        }

        /**
         * An upper floor of planks at {@code y} inside the walls, open over the top three steps of the stairs coming up
         * from the south ({@code holeFrom} the southernmost): head room for each step, as a step up is a jump.
         */
        void upperFloor(int y, int stairX, int holeFrom) {
            for (int x = 1; x < width - 1; x++) for (int z = 1; z < depth - 1; z++) {
                if (x == stairX && z >= holeFrom - 2 && z <= holeFrom) continue;
                put(x, y, z, planks());
            }
            for (int z = holeFrom - 2; z <= holeFrom; z++) open.add(new Pos(stairX, y, z));
        }

        /** A door in the wall at ({@code x}, {@code z}), hung from outside facing in. */
        void door(int x, int y, int z) {
            String facing = z == depth - 1 ? "north" : z == 0 ? "south" : x == 0 ? "east" : "west";
            put(x, y, z, "minecraft:" + wood + "_door[facing=" + facing + ",half=lower,hinge=left,open=false]");
            put(x, y + 1, z, "minecraft:" + wood + "_door[facing=" + facing + ",half=upper,hinge=left,open=false]");
        }

        void window(int x, int y, int z) {
            remove(x, y, z);
            open.add(new Pos(x, y, z));
        }

        /** A bed, its foot at the cell given and its head the way it faces. */
        void bed(int x, int y, int z, String facing) {
            var foot = new Pos(x, y, z);
            var head = foot.offset(Placement.face(facing));
            at.put(foot, BlockSpec.parse("minecraft:red_bed[facing=" + facing + ",part=foot]"));
            at.put(head, BlockSpec.parse("minecraft:red_bed[facing=" + facing + ",part=head]"));
        }

        /** A flight of {@code steps} stairs up from ({@code x}, {@code y}, {@code z}), rising toward {@code facing}. */
        void stairs(int x, int y, int z, String facing, int steps) {
            var way = Placement.face(facing);
            var at = new Pos(x, y, z);
            for (int k = 0; k < steps; k++) {
                put(at.x(), at.y(), at.z(), "minecraft:" + wood + "_stairs[facing=" + facing + ",half=bottom]");
                // Head room over each step.
                for (int h = 1; h <= 2; h++) open.add(at.add(0, h, 0));
                at = at.offset(way).up();
            }
        }

        void flatRoof(int y) {
            for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) put(x, y, z, planks());
        }

        /** Stairs along the north and south eaves facing in, planks under each course, slabs along an odd ridge. */
        void gableRoof(int eaves) {
            String stair = "minecraft:" + wood + "_stairs[facing=%s,half=bottom]", slab = "minecraft:" + wood + "_slab[type=bottom]";
            for (int x = 0; x < width; x++) {
                put(x, eaves, 0, stair.formatted("south"));
                put(x, eaves, depth - 1, stair.formatted("north"));
                for (int z = 1; z < depth - 1; z++) put(x, eaves, z, planks());
            }
            for (int k = 1; ; k++) {
                int north = k, south = depth - 1 - k, y = eaves + k;
                if (south < north) break;
                for (int x = 0; x < width; x++) {
                    if (south == north) {
                        put(x, y, north, slab);
                        continue;
                    }
                    put(x, y, north, stair.formatted("south"));
                    put(x, y, south, stair.formatted("north"));
                    if (south - north == 2) put(x, y, north + 1, slab);
                    else if (south - north > 2) {
                        put(x, y, north + 1, planks());
                        put(x, y, south - 1, planks());
                    }
                }
                if (south - north <= 2) break;
            }
        }

        /** Every cell inside the walls from {@code from} to {@code to} not taken is kept clear. */
        void clearInside(int from, int to) {
            for (int y = from; y <= to; y++) for (int x = 1; x < width - 1; x++) for (int z = 1; z < depth - 1; z++) {
                if (!at.containsKey(new Pos(x, y, z))) open.add(new Pos(x, y, z));
            }
        }

        List<Blueprint.Block> blocks() {
            var out = new ArrayList<Blueprint.Block>();
            for (var e : at.entrySet()) out.add(new Blueprint.Block(e.getKey(), e.getValue()));
            return out;
        }

        List<Pos> clear() {
            return open.stream().filter(p -> !at.containsKey(p)).distinct().toList();
        }
    }
}
