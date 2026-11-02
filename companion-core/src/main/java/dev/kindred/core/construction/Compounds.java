package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A walled base for an unfriendly world (M-06a, the owner's note of 2026-10-23): a walled, restricted, castle-like
 * compound that a player grows in five stages as its needs grow, enclosing its house, farms and cattle, its walls
 * always lined and its way in discreet. Its first stage is the keep: the cabin of its style inside a walled yard. Its
 * second is a farm ward east of the keep, sharing its east wall ({@link #farmWard}); its third a pasture ward south of
 * the farm ward round a pen for its cattle ({@link #pastureWard}); its fourth the hall, the two-floor house in its own
 * ward south of the keep ({@link #hall}), with towers at the compound's outer corners ({@link #towers}).
 * <ul>
 *   <li><b>The wall.</b> A stone course four high from a foundation in the ground, three above it, too high to jump
 *   and high enough to hide a player standing behind it.</li>
 *   <li><b>Lined.</b> Planks line the wall's inside face its whole height, and torches on the lining light the yard.</li>
 *   <li><b>Discreet.</b> No gate: the way in is a narrow stair down beside the west wall, a passage under it and a stair
 *   up inside, all lined with stone, so that nothing in the wall shows where it is.</li>
 * </ul>
 * The origin is the north-west corner of the ground the keep covers, at the height of the yard's floor: the wall's
 * west face stands {@link #WEST} blocks east of it, to leave the outside stair in the plan. Pure.
 */
public final class Compounds {
    /** How far east of the plan's west edge the wall stands, with the outside stair and the ground before it west of it. */
    public static final int WEST = 4;
    /** The wall's height above the yard's floor, and how deep the passage under it runs below it. */
    static final int HEIGHT = 3, DEEP = 3;

    private Compounds() { }

    /** The keep's wall: its west and east faces, then its north and south ones, in the plan's frame. */
    public record Wall(int west, int east, int north, int south) {
        public boolean on(int x, int z) { return x >= west && x <= east && z >= north && z <= south && (x == west || x == east || z == north || z == south); }
        public boolean lining(int x, int z) {
            return x >= west + 1 && x <= east - 1 && z >= north + 1 && z <= south - 1
                    && (x == west + 1 || x == east - 1 || z == north + 1 || z == south - 1);
        }
        /** The row the way in runs along, under the west wall: three in from the south wall, south of the cabin. */
        public int wayIn() { return south - 3; }
    }

    /**
     * The wall a keep of {@code style} stands in: room for a scaffold two wide north and east of its cabin, four west of
     * it for the stair up, and three rows south of it, its door's and the way in's.
     */
    public static Wall wall(BuildStyle style) {
        var cabin = Houses.shape(Houses.Stage.CABIN, style.variant());
        return new Wall(WEST, WEST + cabin.width() + 9, 0, cabin.depth() + 8);
    }

    /** The keep in a style's materials and its cabin's variant; its turns are for placing it. */
    public static Blueprint keep(BuildStyle style) {
        String wood = style.wood(), stone = style.stone();
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a wood or a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        String planks = "minecraft:" + wood + "_planks";
        var wall = wall(style);
        var at = new LinkedHashMap<Pos, BlockSpec>();
        var open = new LinkedHashSet<Pos>();
        // The wall, from its foundation in the ground, and its lining.
        for (int x = wall.west(); x <= wall.east(); x++) for (int z = wall.north(); z <= wall.south(); z++) {
            if (wall.on(x, z)) for (int y = -1; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(s));
            else if (wall.lining(x, z)) for (int y = 0; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(planks));
        }
        // The cabin, two in from the lining north and east, four west of it for the stair up.
        var cabin = Houses.of(Houses.Stage.CABIN, style);
        int cx = wall.west() + 6, cz = wall.north() + 4;
        for (var b : cabin.blocks()) at.put(b.at().add(cx, 0, cz), b.spec());
        for (var p : cabin.clear()) open.add(p.add(cx, 0, cz));
        // The way in, under the west wall in the south of the yard.
        passage(at, open, wall.west(), wall.west() + 1, wall.wayIn(), s);
        // Torches on the lining, every fourth block along each side, facing into the yard.
        torches(at, open, wall);
        // The yard kept open, three high.
        for (int x = wall.west() + 2; x <= wall.east() - 2; x++) for (int z = wall.north() + 2; z <= wall.south() - 2; z++) {
            for (int y = 0; y < HEIGHT; y++) {
                var p = new Pos(x, y, z);
                if (!at.containsKey(p)) open.add(p);
            }
        }
        var blocks = new ArrayList<Blueprint.Block>();
        for (var e : at.entrySet()) blocks.add(new Blueprint.Block(e.getKey(), e.getValue()));
        var clear = open.stream().filter(p -> !at.containsKey(p)).toList();
        String variant = style.variant() == 0 ? "" : "&variant=" + style.variant();
        return new Blueprint(wood + " keep", Generators.SOURCE + "compound?stage=keep&wood=" + wood + "&stone=" + s + variant, "Kindred",
                "Kindred's own", blocks, clear);
    }

    /**
     * A way under a wall and its lining, the columns {@code first} to {@code last} west to east, along row {@code z}:
     * from the ground three blocks west of them a stair of three steps down east, a passage under them at
     * {@link #DEEP} below the yard, three steps up east of them. Each step is a full block, a step up a jump; a stone
     * floor under every step, stone each side below the ground, and stone over the passage where no foundation is.
     */
    private static void passage(Map<Pos, BlockSpec> at, Set<Pos> open, int first, int last, int z, String stone) {
        var s = BlockSpec.parse(stone);
        for (int x = first - 3; x <= last + 3; x++) {
            // Feet below the yard's floor: down three, along under the wall, up three.
            int f = x == first - 3 || x == last + 3 ? -1 : x == first - 2 || x == last + 2 ? -2 : -DEEP;
            boolean under = x >= first && x <= last;
            at.put(new Pos(x, f - 1, z), s);
            // Head room: up to the yard's floor where open to the sky, below the wall's foundation where under it.
            for (int y = f; y <= (under ? -2 : 0); y++) open.add(new Pos(x, y, z));
            // Stone each side, from the floor up to the ground's top.
            for (int side : new int[] {-1, 1}) for (int y = f; y <= -1; y++) at.putIfAbsent(new Pos(x, y, z + side), s);
            if (under) at.putIfAbsent(new Pos(x, -1, z), s);
        }
    }

    /**
     * The farm ward's wall (M-06a's second stage): east of the keep's, its west face the keep's east wall, room inside
     * for the stair up from the keep and a field nine square with a margin round it.
     */
    public static Wall farmWall(BuildStyle style) {
        var keep = wall(style);
        return new Wall(keep.east(), keep.east() + 17, keep.north(), keep.south());
    }

    /** The farm ward's field: its north-west corner and side, nine square, and the channel for its water down its middle. */
    public record Field(int west, int north, int size) {
        public int channel() { return west + size / 2; }
    }

    /**
     * The row of the farm ward's own way in, under its east wall: against its south lining, the row past the field's
     * south end clear of it. Three in from the south wall, as the keep's is, the stone along its stair took the
     * field's two south-east cells.
     */
    public static int farmWayIn(BuildStyle style) {
        return farmWall(style).south() - 2;
    }

    public static Field field(BuildStyle style) {
        var ward = farmWall(style);
        return new Field(ward.west() + 6, ward.north() + 3, 9);
    }

    /**
     * The farm ward in a style's materials, built onto its keep ({@link #keep}): a ward walled in stone and lined as
     * the keep is, sharing the keep's east wall, reached from the keep's yard by a way under that wall and the lining
     * either side of it, and from outside by its own way under its east wall. Inside, a field nine square left as the
     * ground lies, for tilling, and a channel for water down its middle, dug and lined with stone below and at its
     * ends: water there wets the field four blocks either side, all of it. The keep's own blocks are not in it, but for
     * the stone over the way under its wall.
     */
    public static Blueprint farmWard(BuildStyle style) {
        String wood = style.wood(), stone = style.stone();
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a wood or a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        String planks = "minecraft:" + wood + "_planks";
        var keep = wall(style);
        var ward = farmWall(style);
        var at = new LinkedHashMap<Pos, BlockSpec>();
        var open = new LinkedHashSet<Pos>();
        // Its own three walls and their lining; its west wall is the keep's.
        for (int x = ward.west() + 1; x <= ward.east(); x++) for (int z = ward.north(); z <= ward.south(); z++) {
            if (ward.on(x, z)) for (int y = -1; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(s));
            else if (ward.lining(x, z)) for (int y = 0; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(planks));
        }
        // The way through from the keep's yard, under the shared wall and the lining on both sides of it; and its own
        // way in from outside, under its east wall, as discreet as the keep's. Built from outside, its walls shut a
        // player out of the ward a layer at a time, and the way round through the keep was too far for it to find.
        passage(at, open, keep.east() - 1, keep.east() + 1, keep.wayIn(), s);
        passage(at, open, ward.east() - 1, ward.east(), farmWayIn(style), s);
        // The channel down the field's middle.
        var field = field(style);
        var stoneSpec = BlockSpec.parse(s);
        for (int z = field.north(); z < field.north() + field.size(); z++) {
            at.put(new Pos(field.channel(), -2, z), stoneSpec);
            open.add(new Pos(field.channel(), -1, z));
        }
        at.put(new Pos(field.channel(), -1, field.north() - 1), stoneSpec);
        at.put(new Pos(field.channel(), -1, field.north() + field.size()), stoneSpec);
        torches(at, open, ward);
        // The ward kept open, three high: the field's crops grow in its lowest row.
        for (int x = ward.west() + 2; x <= ward.east() - 2; x++) for (int z = ward.north() + 2; z <= ward.south() - 2; z++) {
            for (int y = 0; y < HEIGHT; y++) {
                var p = new Pos(x, y, z);
                if (!at.containsKey(p)) open.add(p);
            }
        }
        var blocks = new ArrayList<Blueprint.Block>();
        for (var e : at.entrySet()) blocks.add(new Blueprint.Block(e.getKey(), e.getValue()));
        var clear = open.stream().filter(p -> !at.containsKey(p)).toList();
        String variant = style.variant() == 0 ? "" : "&variant=" + style.variant();
        return new Blueprint(wood + " farm ward", Generators.SOURCE + "compound?stage=farm&wood=" + wood + "&stone=" + s + variant, "Kindred",
                "Kindred's own", blocks, clear);
    }

    /**
     * The pasture ward's wall (M-06a's third stage): south of the farm ward, its north face the farm ward's south wall,
     * room inside for a pen seven square with a margin round it, and the rows south of the pen its cattle come in by.
     */
    public static Wall pastureWall(BuildStyle style) {
        var farm = farmWall(style);
        return new Wall(farm.west(), farm.east(), farm.south(), farm.south() + 14);
    }

    /** The pasture's pen: its north-west corner, seven square, its gate in the middle of its south side. */
    public static Pos pen(BuildStyle style) {
        var ward = pastureWall(style);
        return new Pos(ward.west() + (ward.east() - ward.west()) / 2 - 3, 0, ward.north() + 2);
    }

    /**
     * The cattle's doorway: two high and two wide through the pasture's south wall and its lining, its west column in
     * line with the pen's gate. Two wide, as a pair led in comes up to it together: through one a block wide, the two
     * pressed into its mouth side by side and neither got in (the cattle fixture, half its runs). It is built shut, of
     * the wall's own stone and lining, so that nothing shows where it is; it is opened only to bring animals in, and
     * shut behind them.
     */
    public static List<Pos> doorway(BuildStyle style) {
        var ward = pastureWall(style);
        int x = pen(style).x() + 3;
        var cells = new java.util.ArrayList<Pos>();
        for (int dx = 0; dx <= 1; dx++) {
            cells.addAll(List.of(new Pos(x + dx, 0, ward.south() - 1), new Pos(x + dx, 1, ward.south() - 1),
                    new Pos(x + dx, 0, ward.south()), new Pos(x + dx, 1, ward.south())));
        }
        return List.copyOf(cells);
    }

    /** The doorway shut again: the pasture ward's own blocks there, its lining's planks and its wall's stone. */
    public static Blueprint doorwayShut(BuildStyle style) {
        var ward = Blueprint.byPosition(pastureWard(style).blocks());
        var blocks = doorway(style).stream().map(p -> new Blueprint.Block(p, ward.get(p))).toList();
        return new Blueprint(style.wood() + " pasture doorway shut", Generators.SOURCE + "compound?stage=pasture-doorway-shut", "Kindred",
                "Kindred's own", blocks);
    }

    /** The row of the pasture ward's own way in, under its east wall, against its south lining. */
    public static int pastureWayIn(BuildStyle style) {
        return pastureWall(style).south() - 2;
    }

    /**
     * The pasture ward in a style's materials, built onto its farm ward ({@link #farmWard}): walled in stone and lined
     * as the other wards are, sharing the farm ward's south wall; its pen of the style's wood inside; the cattle's
     * doorway built shut in its south wall ({@link #doorway}); and its own way in for the player under its east wall,
     * as discreet as the others. The farm ward's blocks are not in it, but for the stone of the shared wall it lines.
     */
    public static Blueprint pastureWard(BuildStyle style) {
        String wood = style.wood(), stone = style.stone();
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a wood or a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        String planks = "minecraft:" + wood + "_planks";
        var ward = pastureWall(style);
        var at = new LinkedHashMap<Pos, BlockSpec>();
        var open = new LinkedHashSet<Pos>();
        // Its own three walls and their lining; its north wall is the farm ward's.
        for (int x = ward.west(); x <= ward.east(); x++) for (int z = ward.north() + 1; z <= ward.south(); z++) {
            if (ward.on(x, z)) for (int y = -1; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(s));
            else if (ward.lining(x, z)) for (int y = 0; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(planks));
        }
        passage(at, open, ward.east() - 1, ward.east(), pastureWayIn(style), s);
        var pen = pen(style);
        for (var b : Generators.pen(7, 7, wood).blocks()) at.put(b.at().add(pen.x(), 0, pen.z()), b.spec());
        torches(at, open, ward);
        // The ward kept open, three high, but for its pen.
        for (int x = ward.west() + 2; x <= ward.east() - 2; x++) for (int z = ward.north() + 2; z <= ward.south() - 2; z++) {
            for (int y = 0; y < HEIGHT; y++) {
                var p = new Pos(x, y, z);
                if (!at.containsKey(p)) open.add(p);
            }
        }
        var blocks = new ArrayList<Blueprint.Block>();
        for (var e : at.entrySet()) blocks.add(new Blueprint.Block(e.getKey(), e.getValue()));
        var clear = open.stream().filter(p -> !at.containsKey(p)).toList();
        String variant = style.variant() == 0 ? "" : "&variant=" + style.variant();
        return new Blueprint(wood + " pasture ward", Generators.SOURCE + "compound?stage=pasture&wood=" + wood + "&stone=" + s + variant, "Kindred",
                "Kindred's own", blocks, clear);
    }

    /**
     * The hall's ward (M-06a's fourth stage): south of the keep, its north face the keep's south wall and its east face,
     * as far as the pasture runs, the pasture's west wall; as wide as the keep, which leaves the two-floor house two
     * blocks each side for a scaffold, and as deep as the house with room north of it and three rows south of it for
     * its door and the way in.
     */
    public static Wall hallWall(BuildStyle style) {
        var keep = wall(style);
        var house = Houses.shape(Houses.Stage.HOUSE, style.variant());
        return new Wall(keep.west(), keep.east(), keep.south(), keep.south() + house.depth() + 8);
    }

    /** How high a tower stands above the yard's floor: three above the wall. */
    static final int TOWER = HEIGHT + 3;

    /**
     * The hall in a style's materials, built onto its keep, farm ward and pasture ward: its ward walled and lined as the
     * others, sharing the keep's south wall and the pasture's west wall; the base's two-floor house (M-01b) inside it;
     * and its own way in under its west wall in the south of the ward, as the keep's is. None of the other wards' blocks
     * are in it.
     */
    public static Blueprint hall(BuildStyle style) {
        String wood = style.wood(), stone = style.stone();
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a wood or a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        String planks = "minecraft:" + wood + "_planks";
        var ward = hallWall(style);
        var pasture = pastureWall(style);
        var at = new LinkedHashMap<Pos, BlockSpec>();
        var open = new LinkedHashSet<Pos>();
        // Its own walls and their lining; its north wall is the keep's, and its east wall the pasture's as far as that runs.
        for (int x = ward.west(); x <= ward.east(); x++) for (int z = ward.north() + 1; z <= ward.south(); z++) {
            if (x == ward.east() && z <= pasture.south()) continue;
            if (ward.on(x, z)) for (int y = -1; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(s));
            else if (ward.lining(x, z)) for (int y = 0; y < HEIGHT; y++) at.put(new Pos(x, y, z), BlockSpec.parse(planks));
        }
        // The house, two in from the lining west and east and north, three rows south of it.
        var house = Houses.of(Houses.Stage.HOUSE, style);
        int hx = ward.west() + 4, hz = ward.north() + 4;
        for (var b : house.blocks()) at.put(b.at().add(hx, 0, hz), b.spec());
        for (var p : house.clear()) open.add(p.add(hx, 0, hz));
        passage(at, open, ward.west(), ward.west() + 1, ward.wayIn(), s);
        torches(at, open, ward);
        for (int x = ward.west() + 2; x <= ward.east() - 2; x++) for (int z = ward.north() + 2; z <= ward.south() - 2; z++) {
            for (int y = 0; y < HEIGHT; y++) {
                var p = new Pos(x, y, z);
                if (!at.containsKey(p)) open.add(p);
            }
        }
        var blocks = new ArrayList<Blueprint.Block>();
        for (var e : at.entrySet()) blocks.add(new Blueprint.Block(e.getKey(), e.getValue()));
        var clear = open.stream().filter(p -> !at.containsKey(p)).toList();
        String variant = style.variant() == 0 ? "" : "&variant=" + style.variant();
        return new Blueprint(wood + " hall", Generators.SOURCE + "compound?stage=hall&wood=" + wood + "&stone=" + s + variant, "Kindred",
                "Kindred's own", blocks, clear);
    }

    /**
     * A tower at each of the compound's outer corners, once its hall stands: three by three out from the corner, from
     * the foundation to three above the wall, its top crenellated. Each is a build of its own, as one plan reaching
     * every corner covered the whole compound, and every step of its build looked over all of it. Each holds the
     * corner of the wall it rises from, which agrees with the ward's.
     */
    public static List<Blueprint> towers(BuildStyle style) {
        String stone = style.stone();
        if (!stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        var keep = wall(style);
        var farm = farmWall(style);
        var pasture = pastureWall(style);
        var hall = hallWall(style);
        int[][] corners = {{keep.west(), keep.north(), -1, -1}, {farm.east(), farm.north(), 1, -1}, {pasture.east(), pasture.south(), 1, 1},
                {hall.east(), hall.south(), 1, 1}, {hall.west(), hall.south(), -1, 1}};
        String[] names = {"north-west", "north-east", "south-east", "south", "south-west"};
        var towers = new ArrayList<Blueprint>();
        for (int i = 0; i < corners.length; i++) {
            var at = new LinkedHashMap<Pos, BlockSpec>();
            tower(at, corners[i][0], corners[i][1], corners[i][2], corners[i][3], s);
            var blocks = at.entrySet().stream().map(e -> new Blueprint.Block(e.getKey(), e.getValue())).toList();
            towers.add(new Blueprint(names[i] + " tower", Generators.SOURCE + "compound?stage=tower&corner=" + names[i] + "&stone=" + s, "Kindred",
                    "Kindred's own", blocks));
        }
        return towers;
    }

    /**
     * A tower three by three from the corner {@code (x, z)} out the way {@code (dx, dz)}: stone from the foundation up to
     * {@link #TOWER} above the floor, its four corners a block higher still.
     */
    private static void tower(Map<Pos, BlockSpec> at, int x, int z, int dx, int dz, String stone) {
        var s = BlockSpec.parse(stone);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            int tx = x + dx * i, tz = z + dz * j;
            for (int y = -1; y < TOWER; y++) at.put(new Pos(tx, y, tz), s);
            if ((i == 0 || i == 2) && (j == 0 || j == 2)) at.put(new Pos(tx, TOWER, tz), s);
        }
    }

    /** Wall torches on the lining's inside face, every fourth block along each side, where the yard is open there. */
    private static void torches(Map<Pos, BlockSpec> at, Set<Pos> open, Wall wall) {
        int y = 1;
        for (int x = wall.west() + 3; x <= wall.east() - 3; x += 4) {
            torch(at, new Pos(x, y, wall.north() + 2), "south");
            torch(at, new Pos(x, y, wall.south() - 2), "north");
        }
        for (int z = wall.north() + 3; z <= wall.south() - 3; z += 4) {
            torch(at, new Pos(wall.west() + 2, y, z), "east");
            torch(at, new Pos(wall.east() - 2, y, z), "west");
        }
    }

    private static void torch(Map<Pos, BlockSpec> at, Pos p, String facing) {
        if (at.containsKey(p)) return;
        at.put(p, BlockSpec.parse("minecraft:wall_torch[facing=" + facing + "]"));
    }
}
