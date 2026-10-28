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
    public static final java.util.Set<String> NAMES = java.util.Set.of("hut", "tower", "pyramid", "lighthouse", "pen", "cabin", "house", "manor", "keep", "farmward", "pastureward", "hall", "walltower", "tradinghall");

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

    /**
     * A square watchtower: a floor, hollow walls of {@code material} with a doorway in the south wall, open at the top
     * with battlements round its edge, every other block. Open, since a platform over the hollow would be out of reach
     * of anywhere a builder on foot can stand. The origin is the floor's north-west corner.
     *
     * @param width  5 to 11, a side
     * @param height 4 to 16, the walls over the floor
     */
    public static Blueprint tower(int width, int height, String material) {
        if (width < 5 || width > 11 || height < 4 || height > 16) throw new IllegalArgumentException("A tower 5 to 11 a side, 4 to 16 high");
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        var stone = BlockSpec.of(material);
        int door = width / 2;
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) blocks.add(new Blueprint.Block(new Pos(x, 0, z), stone));
        for (int y = 1; y <= height; y++) for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) {
            boolean edge = x == 0 || z == 0 || x == width - 1 || z == width - 1;
            var at = new Pos(x, y, z);
            if (!edge || x == door && z == width - 1 && y <= 2) clear.add(at);
            else blocks.add(new Blueprint.Block(at, stone));
        }
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) {
            boolean edge = x == 0 || z == 0 || x == width - 1 || z == width - 1;
            if (edge && (x + z) % 2 == 0) blocks.add(new Blueprint.Block(new Pos(x, height + 1, z), stone));
        }
        return new Blueprint("tower " + width + "x" + height, SOURCE + "tower?width=" + width + "&height=" + height + "&material=" + material,
                "Kindred", "Kindred's own", blocks, clear);
    }

    /**
     * A stepped pyramid of {@code material}: each course a ring in from the one below, solid, to a single block on top.
     * The origin is the base's north-west corner.
     *
     * @param base 3 to 15, odd, a side of the bottom course
     */
    public static Blueprint pyramid(int base, String material) {
        if (base < 3 || base > 15 || base % 2 == 0) throw new IllegalArgumentException("A pyramid 3 to 15 at its base, odd");
        var blocks = new ArrayList<Blueprint.Block>();
        var stone = BlockSpec.of(material);
        for (int y = 0; y <= base / 2; y++) for (int x = y; x < base - y; x++) for (int z = y; z < base - y; z++) {
            blocks.add(new Blueprint.Block(new Pos(x, y, z), stone));
        }
        return new Blueprint("pyramid " + base, SOURCE + "pyramid?base=" + base + "&material=" + material, "Kindred", "Kindred's own", blocks);
    }

    /**
     * A lighthouse: a column three a side striped in two colours, two courses each, on a base five a side; on top a lamp
     * of glowstone in a ring of glass under a roof of slabs. Solid, so every course stands on the one below and is
     * within a builder's reach from a scaffold beside it. The origin is the base's north-west corner.
     *
     * @param height 6 to 16, the striped column over the base
     */
    public static Blueprint lighthouse(int height, String stripe, String band) {
        if (height < 6 || height > 16) throw new IllegalArgumentException("A lighthouse 6 to 16 high");
        var blocks = new ArrayList<Blueprint.Block>();
        var clear = new ArrayList<Pos>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) blocks.add(new Blueprint.Block(new Pos(x, 0, z), BlockSpec.of(band)));
        for (int y = 1; y <= height; y++) for (int x = 1; x < 4; x++) for (int z = 1; z < 4; z++) {
            blocks.add(new Blueprint.Block(new Pos(x, y, z), BlockSpec.of((y - 1) / 2 % 2 == 0 ? stripe : band)));
        }
        // The lamp room: glowstone in the middle, glass round it, slabs over all.
        for (int x = 1; x < 4; x++) for (int z = 1; z < 4; z++) {
            blocks.add(new Blueprint.Block(new Pos(x, height + 1, z), BlockSpec.of(x == 2 && z == 2 ? "minecraft:glowstone" : "minecraft:glass")));
            blocks.add(new Blueprint.Block(new Pos(x, height + 2, z), BlockSpec.parse("minecraft:smooth_stone_slab[type=bottom]")));
        }
        return new Blueprint("lighthouse " + height, SOURCE + "lighthouse?height=" + height + "&stripe=" + stripe + "&band=" + band,
                "Kindred", "Kindred's own", blocks, clear);
    }

    private static BlockSpec stair(String facing) {
        return BlockSpec.parse("minecraft:oak_stairs[facing=" + facing + ",half=bottom,shape=straight]");
    }

    /** The generator a name stands for, with its sizes: {@code hut}, {@code hut 5 5 3}. */
    /**
     * A pen for animals (M-01a): a ring of {@code wood} fences on the ground, {@code width} by {@code depth}, with a gate
     * of the same wood in the middle of the south side, hung to swing north and south and set facing north, so it is
     * hung from outside the pen. The origin is the north-west post; nothing inside is touched.
     *
     * @param width east to west, odd, 5 to 15
     * @param depth north to south, 5 to 15
     * @param wood  the wood, as its planks are named: oak, spruce, birch, ...
     */
    public static Blueprint pen(int width, int depth, String wood) {
        if (width < 5 || width > 15 || width % 2 == 0 || depth < 5 || depth > 15) {
            throw new IllegalArgumentException("A pen 5 to 15 blocks a side, an odd width so its gate is in the middle");
        }
        if (!wood.matches("[a-z_]+")) throw new IllegalArgumentException("Not a wood: " + wood);
        var blocks = new ArrayList<Blueprint.Block>();
        var fence = BlockSpec.of("minecraft:" + wood + "_fence");
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            if (x != 0 && x != width - 1 && z != 0 && z != depth - 1) continue;
            boolean gate = x == width / 2 && z == depth - 1;
            blocks.add(new Blueprint.Block(new Pos(x, 0, z), gate ? BlockSpec.parse("minecraft:" + wood + "_fence_gate[facing=north]") : fence));
        }
        return new Blueprint(wood + " pen " + width + "x" + depth, SOURCE + "pen?width=" + width + "&depth=" + depth + "&wood=" + wood,
                "Kindred", "Kindred's own", blocks);
    }

    public static Blueprint named(String name, List<Integer> sizes) {
        return switch (name) {
            case "hut" -> hut(sizes.size() > 0 ? sizes.get(0) : 5, sizes.size() > 1 ? sizes.get(1) : 5, sizes.size() > 2 ? sizes.get(2) : 3);
            case "tower" -> tower(sizes.size() > 0 ? sizes.get(0) : 5, sizes.size() > 1 ? sizes.get(1) : 8, "minecraft:stone_bricks");
            case "pyramid" -> pyramid(sizes.size() > 0 ? sizes.get(0) : 9, "minecraft:sandstone");
            case "lighthouse" -> lighthouse(sizes.size() > 0 ? sizes.get(0) : 10, "minecraft:white_concrete", "minecraft:red_concrete");
            case "pen" -> pen(sizes.size() > 0 ? sizes.get(0) : 7, sizes.size() > 1 ? sizes.get(1) : 7, "oak");
            // The three stages of a base (M-01b), of oak and cobblestone.
            case "cabin" -> Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
            case "house" -> Houses.of(Houses.Stage.HOUSE, "oak", "cobblestone");
            case "manor" -> Houses.of(Houses.Stage.MANOR, "oak", "cobblestone");
            // The first stage of a walled base (M-06a): the cabin in a walled yard.
            case "keep" -> Compounds.keep(new BuildStyle("", "oak", "cobblestone", 0, 0));
            // Its second: a farm ward east of the keep, built at the keep's own spot.
            case "farmward" -> Compounds.farmWard(new BuildStyle("", "oak", "cobblestone", 0, 0));
            // Its third: a pasture ward round a pen, south of the farm ward, built at the keep's own spot.
            case "pastureward" -> Compounds.pastureWard(new BuildStyle("", "oak", "cobblestone", 0, 0));
            // Its fourth: the hall in its own ward south of the keep, and towers at the compound's outer corners.
            case "hall" -> Compounds.hall(new BuildStyle("", "oak", "cobblestone", 0, 0));
            // And a tower at one of the compound's outer corners, from the north-west round: walltower-1 to walltower-5.
            case "walltower" -> {
                var towers = Compounds.towers(new BuildStyle("", "oak", "cobblestone", 0, 0));
                int corner = sizes.isEmpty() ? 1 : sizes.get(0);
                if (corner < 1 || corner > towers.size()) throw new IllegalArgumentException("A wall tower at corner 1 to " + towers.size() + ", from the north-west round");
                yield towers.get(corner - 1);
            }
            // A trading hall of cells for the villagers kept (E-45d), of oak and cobblestone: tradinghall-<cells>.
            case "tradinghall" -> TradingHall.of(sizes.isEmpty() ? 3 : sizes.get(0), "oak", "cobblestone");
            default -> throw new IllegalArgumentException("No generator called " + name + "; there are: hut, tower, pyramid, lighthouse, pen, cabin, house, manor, keep, farmward, pastureward, hall, walltower, tradinghall");
        };
    }
}
