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
 * always lined and its way in discreet. Its first stage is the keep: the cabin of its style inside a walled yard.
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
        passage(at, open, wall.west(), wall.wayIn(), s);
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
     * The way in: from the ground three blocks west of the wall, a stair of three steps down east, a passage of four
     * under the wall and its lining at {@link #DEEP} below the yard, three steps up into the yard. Each step is a full
     * block, a step up a jump; a stone floor under every step, stone each side below the ground, and stone over the
     * passage under the lining as under the wall.
     */
    private static void passage(Map<Pos, BlockSpec> at, Set<Pos> open, int west, int z, String stone) {
        var s = BlockSpec.parse(stone);
        // Feet below the yard's floor at each column, west to east: down three, along four, up three.
        int[] feet = {-1, -2, -3, -3, -3, -3, -2, -1};
        for (int i = 0; i < feet.length; i++) {
            int x = west - 3 + i, f = feet[i];
            boolean under = x == west || x == west + 1;
            at.put(new Pos(x, f - 1, z), s);
            // Head room: up to the yard's floor where open to the sky, below the wall's foundation where under it.
            for (int y = f; y <= (under ? -2 : 0); y++) open.add(new Pos(x, y, z));
            // Stone each side, from the floor up to the ground's top.
            for (int side : new int[] {-1, 1}) for (int y = f; y <= -1; y++) at.putIfAbsent(new Pos(x, y, z + side), s);
        }
        at.put(new Pos(west + 1, -1, z), s);
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
