package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * A factions bunker (M-06b, the owner's direction of 2026-10-23: a bunker in five stages, multi-level and multi-room,
 * an underground mini civilization grown as the game goes on, walls lined and entrances discreet). Each stage is a plan
 * the build runner digs out and lines as for any build: the cells kept clear are dug, the blocks placed. Pure.
 *
 * <p>The first stage, {@link #hideout}: a small stone shed with a door, the kind of hut nobody looks at twice, over a
 * stair of full-block steps down six blocks to a room seven by seven and three high, lined (the style's stone floor
 * and ceiling, planks round the walls), with a crafting table, a furnace, two chests and torches. The stair is lined
 * each side and roofed below ground. The origin is the shed's north-west corner on the ground (the ground's top is one
 * below); the door is in its north wall.
 */
public final class Bunkers {
    private Bunkers() { }

    /** How far below the ground the room's floor is (its feet): six blocks down. */
    public static final int DEPTH = 6;
    /** The room's inside, from the origin: west to east, north to south. */
    public static final int ROOM_WEST = -2, ROOM_EAST = 4, ROOM_NORTH = 8, ROOM_SOUTH = 14, ROOM_HEIGHT = 3;
    /** Where the stair runs down, from the origin: the column it takes, and the rows of its steps. */
    public static final int STAIR_X = 1, STAIR_TOP = 1, STAIR_FOOT = 7;

    public static Blueprint hideout(BuildStyle style) {
        String wood = style.wood(), stone = style.stone();
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_:]+")) throw new IllegalArgumentException("Not a wood or a stone");
        String s = stone.contains(":") ? stone : "minecraft:" + stone;
        String planks = "minecraft:" + wood + "_planks";
        var at = new LinkedHashMap<Pos, BlockSpec>();
        var open = new LinkedHashSet<Pos>();
        // The shed: three wide and five deep, three high, a planks roof, the door in the north wall.
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 4; z++) {
            boolean wall = x == 0 || x == 2 || z == 0 || z == 4;
            for (int y = 0; y <= 2; y++) {
                if (wall) at.put(new Pos(x, y, z), BlockSpec.parse(s));
                else open.add(new Pos(x, y, z));
            }
            at.put(new Pos(x, 3, z), BlockSpec.parse(planks));
        }
        at.put(new Pos(1, 0, 0), BlockSpec.parse("minecraft:" + wood + "_door[facing=south,half=lower,hinge=left,open=false]"));
        at.put(new Pos(1, 1, 0), BlockSpec.parse("minecraft:" + wood + "_door[facing=south,half=upper,hinge=left,open=false]"));
        at.put(new Pos(1, 2, 3), BlockSpec.parse("minecraft:wall_torch[facing=north]"));
        // The floor inside the door.
        at.put(new Pos(STAIR_X, -1, STAIR_TOP), BlockSpec.parse(s));
        // The stair: a step down each row south, from the shed's floor to the room's, three high above each step.
        for (int z = STAIR_TOP + 1; z <= STAIR_FOOT; z++) {
            int feet = -Math.min(DEPTH, z - STAIR_TOP);
            at.put(new Pos(STAIR_X, feet - 1, z), BlockSpec.parse(s));
            for (int y = feet; y <= feet + 2; y++) {
                open.add(new Pos(STAIR_X, y, z));
                // Lined each side below the ground (the shed's walls above it).
                if (y <= -1) {
                    at.putIfAbsent(new Pos(STAIR_X - 1, y, z), BlockSpec.parse(s));
                    at.putIfAbsent(new Pos(STAIR_X + 1, y, z), BlockSpec.parse(s));
                }
            }
            // Roofed where it runs under the ground, south of the shed.
            if (z > 4 && feet + 3 <= -1) at.putIfAbsent(new Pos(STAIR_X, feet + 3, z), BlockSpec.parse(s));
        }
        // The room, lined: stone floor and ceiling, planks round the walls; its way in the stair's foot.
        int floor = -DEPTH - 1, ceiling = -DEPTH + ROOM_HEIGHT;
        for (int x = ROOM_WEST - 1; x <= ROOM_EAST + 1; x++) for (int z = ROOM_NORTH - 1; z <= ROOM_SOUTH + 1; z++) {
            at.put(new Pos(x, floor, z), BlockSpec.parse(s));
            at.put(new Pos(x, ceiling, z), BlockSpec.parse(s));
            boolean wall = x == ROOM_WEST - 1 || x == ROOM_EAST + 1 || z == ROOM_NORTH - 1 || z == ROOM_SOUTH + 1;
            for (int y = -DEPTH; y < ceiling; y++) {
                var p = new Pos(x, y, z);
                if (wall && !(x == STAIR_X && z == ROOM_NORTH - 1)) at.put(p, BlockSpec.parse(planks));
                else if (!wall) open.add(p);
            }
        }
        // What a first hideout holds, against the south wall; light at each end.
        int f = -DEPTH;
        at.put(new Pos(ROOM_WEST, f, ROOM_SOUTH), BlockSpec.parse("minecraft:crafting_table"));
        at.put(new Pos(ROOM_WEST + 1, f, ROOM_SOUTH), BlockSpec.parse("minecraft:furnace[facing=north]"));
        at.put(new Pos(ROOM_EAST, f, ROOM_SOUTH), BlockSpec.parse("minecraft:chest[facing=north,type=single]"));
        at.put(new Pos(ROOM_EAST, f, ROOM_SOUTH - 2), BlockSpec.parse("minecraft:chest[facing=west,type=single]"));
        at.put(new Pos(ROOM_WEST + 1, f + 2, ROOM_NORTH), BlockSpec.parse("minecraft:wall_torch[facing=south]"));
        at.put(new Pos(ROOM_EAST - 1, f + 2, ROOM_SOUTH), BlockSpec.parse("minecraft:wall_torch[facing=north]"));
        var blocks = new ArrayList<Blueprint.Block>();
        for (var e : at.entrySet()) blocks.add(new Blueprint.Block(e.getKey(), e.getValue()));
        var clear = open.stream().filter(p -> !at.containsKey(p)).toList();
        String variant = style.variant() == 0 ? "" : "&variant=" + style.variant();
        return new Blueprint(wood + " hideout", Generators.SOURCE + "bunker?stage=hideout&wood=" + wood + "&stone=" + s + variant, "Kindred",
                "Kindred's own", blocks, clear);
    }

    /** Where in the hideout, from its origin: a cell of the room's floor (feet), and the shed's floor inside the door. */
    public static final Pos ROOM = new Pos(1, -DEPTH, 11), SHED = new Pos(STAIR_X, 0, STAIR_TOP);
}
