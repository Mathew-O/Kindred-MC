package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.List;

/**
 * Blueprints made here rather than read from a schematic (E-08b): Kindred's own, so there is no one else's work to
 * credit (D8). Pure.
 */
public final class Generators {
    public static final String SOURCE = "kindred:generator/";
    /** The generators there are, by name. */
    public static final java.util.Set<String> NAMES = java.util.Set.of("hut");

    private Generators() { }

    /**
     * A hut a survival player builds early: a plank floor, cobblestone walls with log corners, a doorway in the south
     * wall, a torch inside on the north wall, and a gabled roof of oak stairs over a plank ceiling, each course of
     * stairs on planks, ridged with slabs when the span is odd. The origin is the floor's north-west corner; the floor lies on the ground.
     *
     * @param width  east to west, 3 to 15
     * @param depth  north to south, 3 to 15
     * @param walls  wall height over the floor, 2 to 6
     */
    public static Blueprint hut(int width, int depth, int walls) {
        if (width < 3 || width > 15 || depth < 3 || depth > 15 || walls < 2 || walls > 6) {
            throw new IllegalArgumentException("A hut 3 to 15 blocks a side, walls 2 to 6 high");
        }
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        var planks = BlockSpec.of("minecraft:oak_planks");
        int door = width / 2;
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) blocks.add(new Blueprint.Block(new Pos(x, 0, z), planks));
        for (int y = 1; y <= walls; y++) for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            boolean edgeX = x == 0 || x == width - 1, edgeZ = z == 0 || z == depth - 1;
            var at = new Pos(x, y, z);
            if (!edgeX && !edgeZ || x == door && z == depth - 1 && y <= 2) {
                clear.add(at);
            } else {
                blocks.add(new Blueprint.Block(at, edgeX && edgeZ ? BlockSpec.parse("minecraft:oak_log[axis=y]")
                        : BlockSpec.of("minecraft:cobblestone")));
            }
        }
        if (depth > 2 && width > 2) {
            var torch = new Pos(door, Math.min(2, walls), 1);
            clear.remove(torch);
            blocks.add(new Blueprint.Block(torch, BlockSpec.parse("minecraft:wall_torch[facing=south]")));
        }
        // The roof: stairs along the north and south eaves facing in, planks between them over the walls, then up a
        // row a level until the two sides meet.
        int eaves = walls + 1;
        for (int x = 0; x < width; x++) {
            blocks.add(new Blueprint.Block(new Pos(x, eaves, 0), stair("south")));
            blocks.add(new Blueprint.Block(new Pos(x, eaves, depth - 1), stair("north")));
            for (int z = 1; z < depth - 1; z++) blocks.add(new Blueprint.Block(new Pos(x, eaves, z), planks));
        }
        // Each stair above the eaves rests on a plank under it, and the ridge slab beside the last stairs: nothing a
        // player could not place against.
        for (int k = 1; ; k++) {
            int north = k, south = depth - 1 - k, y = eaves + k;
            if (south < north) break;
            for (int x = 0; x < width; x++) {
                if (south == north) {
                    blocks.add(new Blueprint.Block(new Pos(x, y, north), BlockSpec.parse("minecraft:oak_slab[type=bottom]")));
                    continue;
                }
                blocks.add(new Blueprint.Block(new Pos(x, y, north), stair("south")));
                blocks.add(new Blueprint.Block(new Pos(x, y, south), stair("north")));
                if (south - north == 2) {
                    blocks.add(new Blueprint.Block(new Pos(x, y, north + 1), BlockSpec.parse("minecraft:oak_slab[type=bottom]")));
                } else if (south - north > 2) {
                    blocks.add(new Blueprint.Block(new Pos(x, y, north + 1), planks));
                    blocks.add(new Blueprint.Block(new Pos(x, y, south - 1), planks));
                }
            }
            if (south - north <= 2) break;
        }
        return new Blueprint("hut " + width + "x" + depth, SOURCE + "hut?width=" + width + "&depth=" + depth + "&walls=" + walls,
                "Kindred", "Kindred's own", blocks, clear);
    }

    private static BlockSpec stair(String facing) {
        return BlockSpec.parse("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=straight]");
    }

    /** The generator a name stands for, with its sizes: {@code hut}, {@code hut 5 5 3}. */
    public static Blueprint named(String name, List<Integer> sizes) {
        return switch (name) {
            case "hut" -> hut(sizes.size() > 0 ? sizes.get(0) : 5, sizes.size() > 1 ? sizes.get(1) : 5, sizes.size() > 2 ? sizes.get(2) : 3);
            default -> throw new IllegalArgumentException("No generator called " + name + "; there is: hut");
        };
    }
}
