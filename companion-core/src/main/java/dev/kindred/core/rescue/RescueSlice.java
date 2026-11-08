package dev.kindred.core.rescue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: the blocks round a stuck player as it can see them, and what follows from them: how far it could walk
 * each way and what would stop it, how far up it could climb, what is under its feet, and which blocks near it it has
 * no tool for. Blocks it cannot see are unknown, never guessed.
 *
 * Technical: F-29b. An immutable box of cells round the player's feet, {@link #RADIUS} each way across and from
 * {@link #BELOW} under its feet to {@link #ABOVE} over them, made from what the adapter read and masked here by
 * {@link #perceive}: a cell is known when the player's sight reaches it through see-through cells from where it stands,
 * so a block behind a wall is {@link Kind#UNKNOWN} however the adapter read it. The ways it can walk ({@link #walk}) are
 * the same block-by-block steps the rescue's walk executor takes (F-29c), worked out from this slice alone and never
 * from the route planner; what it can break is the adapter's verdict on each known block against the tools it carries.
 */
public final class RescueSlice {
    /** Cells each way across from its feet. */
    public static final int RADIUS = 8;
    /** Cells under its feet, and over them (its feet are 0, its head 1). */
    public static final int BELOW = 4, ABOVE = 8;
    private static final int SIDE = 2 * RADIUS + 1, HEIGHT = BELOW + ABOVE + 1;

    /** What a cell is, as far as a step cares. */
    public enum Kind {
        /** Nothing to stand in the way: air, grass, a flower, a torch. */
        OPEN,
        WATER,
        LAVA,
        /** A block a player collides with. */
        SOLID,
        /** Not seen from where it stands. */
        UNKNOWN
    }

    /**
     * One cell: its kind, the block's id (empty for open), whether sight passes through it (air, water, glass, leaves),
     * whether it can break it with what it carries, and whether it falls when what holds it up goes (sand, gravel).
     */
    public record Cell(Kind kind, String id, boolean seeThrough, boolean breakable, boolean falls) {
        public static final Cell OPEN = new Cell(Kind.OPEN, "", true, true, false);
        public static final Cell UNSEEN = new Cell(Kind.UNKNOWN, "", false, false, false);

        public Cell {
            Objects.requireNonNull(kind);
            id = id == null ? "" : id;
        }

        public static Cell solid(String id, boolean breakable) { return new Cell(Kind.SOLID, id, false, breakable, false); }

        public static Cell water() { return new Cell(Kind.WATER, "water", true, false, false); }

        public static Cell lava() { return new Cell(Kind.LAVA, "lava", false, false, false); }

        boolean passable() { return kind == Kind.OPEN; }
    }

    /** Reads a cell at an offset from the player's feet. */
    @FunctionalInterface
    public interface Reader {
        Cell at(int dx, int dy, int dz);
    }

    /** How far a step that way could go, and what stops it ("" when it was not stopped within its count). */
    public record Reach(int went, String stop, int rise) {
        public Reach {
            stop = stop == null ? "" : stop;
        }

        /** As the report says it: {@code went 2, then a two-high wall of stone}. */
        public String text() {
            String moved = went == 0 ? "not a block" : went + (went == 1 ? " block" : " blocks");
            String height = rise > 0 ? ", up " + rise : rise < 0 ? ", down " + -rise : "";
            return moved + height + (stop.isEmpty() ? "" : ", then " + stop);
        }
    }

    private final Cell[] cells;
    /** Where the player stands in the slice: its centre, but for a probe's slice (F-29c). */
    private final int px, pz;

    private RescueSlice(Cell[] cells, int px, int pz) {
        this.cells = cells;
        this.px = px;
        this.pz = pz;
    }

    /**
     * The slice as the player sees it: every cell read, then every cell its sight does not reach from its feet and head
     * through see-through cells marked unknown. A solid block is seen when it touches a cell sight reaches.
     */
    public static RescueSlice perceive(Reader reader) {
        return perceive(reader, 0, 0);
    }

    /**
     * The slice centred {@code ox}, {@code oz} from the player's feet, masked to what the player sees from where it
     * stands (F-29c's probe): sight starts from its own feet and head, at ({@code -ox}, {@code -oz}) in this slice, and
     * every cell it does not reach is unknown. The reader is given offsets from the slice's centre.
     */
    public static RescueSlice perceive(Reader reader, int ox, int oz) {
        if (Math.abs(ox) > RADIUS / 2 || Math.abs(oz) > RADIUS / 2) throw new IllegalArgumentException("A look reaches four blocks at most");
        var raw = new Cell[SIDE * SIDE * HEIGHT];
        for (int dy = -BELOW; dy <= ABOVE; dy++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    Cell cell = reader.at(dx, dy, dz);
                    raw[index(dx, dy, dz)] = cell == null ? Cell.UNSEEN : cell;
                }
        boolean[] reached = new boolean[raw.length];
        var queue = new ArrayDeque<int[]>();
        for (int dy = 0; dy <= 1; dy++) {
            reached[index(-ox, dy, -oz)] = true;
            queue.add(new int[]{-ox, dy, -oz});
        }
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        var seen = new boolean[raw.length];
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            seen[index(at[0], at[1], at[2])] = true;
            for (int[] f : faces) {
                int x = at[0] + f[0], y = at[1] + f[1], z = at[2] + f[2];
                if (!inside(x, y, z)) continue;
                int i = index(x, y, z);
                seen[i] = true;
                if (!reached[i] && raw[i].seeThrough() && raw[i].kind() != Kind.UNKNOWN) {
                    reached[i] = true;
                    queue.add(new int[]{x, y, z});
                }
            }
        }
        var kept = new Cell[raw.length];
        for (int i = 0; i < raw.length; i++) kept[i] = seen[i] ? raw[i] : Cell.UNSEEN;
        return new RescueSlice(kept, -ox, -oz);
    }

    /**
     * The slice centred {@code oy} blocks over (or, negative, under) the player's feet, masked to what it sees from where
     * it stands (F-29c's look up or down). Sight starts from its feet and head, {@code -oy} and {@code 1 - oy} in this slice.
     */
    public static RescueSlice perceiveAbove(Reader reader, int oy) {
        if (oy > BELOW || -oy > BELOW) throw new IllegalArgumentException("A look reaches four blocks at most");
        var raw = new Cell[SIDE * SIDE * HEIGHT];
        for (int dy = -BELOW; dy <= ABOVE; dy++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    Cell cell = reader.at(dx, dy, dz);
                    raw[index(dx, dy, dz)] = cell == null ? Cell.UNSEEN : cell;
                }
        int[][] starts = {{0, -oy, 0}, {0, 1 - oy, 0}};
        // The player is not in this slice's middle column at its feet: no P is drawn.
        return new RescueSlice(mask(raw, starts), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /** Every cell sight does not reach from {@code starts} through see-through cells, unknown. */
    private static Cell[] mask(Cell[] raw, int[][] starts) {
        boolean[] reached = new boolean[raw.length];
        boolean[] seen = new boolean[raw.length];
        var queue = new ArrayDeque<int[]>();
        for (int[] start : starts) {
            if (!inside(start[0], start[1], start[2])) continue;
            reached[index(start[0], start[1], start[2])] = true;
            queue.add(start);
        }
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            seen[index(at[0], at[1], at[2])] = true;
            for (int[] f : faces) {
                int x = at[0] + f[0], y = at[1] + f[1], z = at[2] + f[2];
                if (!inside(x, y, z)) continue;
                int i = index(x, y, z);
                seen[i] = true;
                if (!reached[i] && raw[i].seeThrough() && raw[i].kind() != Kind.UNKNOWN) {
                    reached[i] = true;
                    queue.add(new int[]{x, y, z});
                }
            }
        }
        var kept = new Cell[raw.length];
        for (int i = 0; i < raw.length; i++) kept[i] = seen[i] ? raw[i] : Cell.UNSEEN;
        return kept;
    }

    /** A slice of exactly these cells, none masked: for tests of the steps on a slice already as seen. */
    public static RescueSlice of(Reader reader) {
        var raw = new Cell[SIDE * SIDE * HEIGHT];
        for (int dy = -BELOW; dy <= ABOVE; dy++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++)
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    Cell cell = reader.at(dx, dy, dz);
                    raw[index(dx, dy, dz)] = cell == null ? Cell.UNSEEN : cell;
                }
        return new RescueSlice(raw, 0, 0);
    }

    /** The cell at that offset from its feet; unknown outside the slice. */
    public Cell at(int dx, int dy, int dz) {
        return inside(dx, dy, dz) ? cells[index(dx, dy, dz)] : Cell.UNSEEN;
    }

    private static boolean inside(int dx, int dy, int dz) {
        return Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS && dy >= -BELOW && dy <= ABOVE;
    }

    private static int index(int dx, int dy, int dz) {
        return ((dy + BELOW) * SIDE + (dz + RADIUS)) * SIDE + (dx + RADIUS);
    }

    /** The x and z step of each side; up and down have none. */
    public static int[] step(RescuePlan.Way way) {
        return switch (way) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST -> new int[]{1, 0};
            case WEST -> new int[]{-1, 0};
            case UP, DOWN -> new int[]{0, 0};
        };
    }

    /** One block's move of the walk that way from an offset: the new feet, or why it stops. */
    public record Move(int dx, int dy, int dz, String stop) {
        public boolean moved() { return stop.isEmpty(); }
    }

    /**
     * The walk's next block that way from feet at ({@code x}, {@code y}, {@code z}): level, up one block onto a step
     * with room to jump, or down a drop of one; otherwise what stops it: a two-high wall, a step it has no head room
     * to jump, water, lava, a drop of more than one, or what it cannot see.
     */
    public Move next(int x, int y, int z, RescuePlan.Way way) {
        int[] s = step(way);
        int nx = x + s[0], nz = z + s[1];
        Cell feet = at(nx, y, nz), head = at(nx, y + 1, nz);
        String blocked = obstacle(feet, head);
        if (blocked == null) {
            Cell floor = at(nx, y - 1, nz);
            if (floor.kind() == Kind.SOLID) return new Move(nx, y, nz, "");
            if (floor.kind() != Kind.OPEN) return new Move(x, y, z, describe(floor, "under the next block"));
            Cell lower = at(nx, y - 2, nz);
            if (lower.kind() == Kind.SOLID) return new Move(nx, y - 1, nz, "");
            int drop = 2;
            while (drop < BELOW + 1 + y && at(nx, y - drop, nz).kind() == Kind.OPEN) drop++;
            // The blocks it would fall: to stand on the bottom, one less than the bottom is under its feet.
            Cell bottom = at(nx, y - drop, nz);
            int fall = drop - 1;
            if (bottom.kind() == Kind.WATER) return new Move(x, y, z, "a drop of " + fall + " into water");
            if (bottom.kind() == Kind.LAVA) return new Move(x, y, z, "a drop of " + fall + " into lava");
            return new Move(x, y, z, "a drop of " + (bottom.kind() == Kind.SOLID ? String.valueOf(fall) : fall + " or more"));
        }
        if (feet.kind() == Kind.SOLID && head.kind() == Kind.OPEN) {
            Cell over = at(nx, y + 2, nz), room = at(x, y + 2, z);
            if (over.kind() == Kind.OPEN && room.kind() == Kind.OPEN) return new Move(nx, y + 1, nz, "");
            Cell low = room.kind() != Kind.OPEN ? room : over;
            return new Move(x, y, z, "a step of " + feet.id() + " with no room to jump (" + name(low) + " overhead)");
        }
        return new Move(x, y, z, blocked);
    }

    /** Null when both cells are open; else what is in the way. */
    private static String obstacle(Cell feet, Cell head) {
        if (feet.passable() && head.passable()) return null;
        if (feet.kind() == Kind.LAVA || head.kind() == Kind.LAVA) return "lava";
        if (feet.kind() == Kind.WATER || head.kind() == Kind.WATER) return "water";
        if (feet.kind() == Kind.UNKNOWN || head.kind() == Kind.UNKNOWN) return "what it cannot see";
        if (feet.kind() == Kind.SOLID && head.kind() == Kind.SOLID)
            return "a two-high wall of " + feet.id() + (feet.id().equals(head.id()) ? "" : " and " + head.id());
        Cell solid = feet.kind() == Kind.SOLID ? feet : head;
        return (solid == head ? "a block at head height, " : "") + solid.id();
    }

    private static String describe(Cell cell, String where) {
        return switch (cell.kind()) {
            case WATER -> "water " + where;
            case LAVA -> "lava " + where;
            case UNKNOWN -> "what it cannot see " + where;
            default -> name(cell) + " " + where;
        };
    }

    private static String name(Cell cell) {
        return switch (cell.kind()) {
            case OPEN -> "air";
            case UNKNOWN -> "something unseen";
            default -> cell.id();
        };
    }

    /** How far the walk executor could go that way from its feet, up to {@code max} blocks, and what stops it. */
    public Reach walk(RescuePlan.Way way, int max) {
        int x = 0, y = 0, z = 0, went = 0;
        String stop = "";
        while (went < max) {
            Move move = next(x, y, z, way);
            if (!move.moved()) {
                stop = move.stop();
                break;
            }
            x = move.dx();
            y = move.dy();
            z = move.dz();
            went++;
            if (Math.abs(x) >= RADIUS || Math.abs(z) >= RADIUS) {
                stop = went < max ? "the edge of what it can see" : "";
                break;
            }
        }
        return new Reach(went, stop, y);
    }

    /** Open cells over its head, up to {@code max}, and what is over them: how far a pillar could rise. */
    public Reach up(int max) {
        int clear = 0;
        while (clear < max && at(0, 2 + clear, 0).kind() == Kind.OPEN) clear++;
        Cell over = at(0, 2 + clear, 0);
        String stop = clear >= max ? "" : over.kind() == Kind.SOLID ? over.id() + (over.breakable() ? "" : ", which it cannot break")
                : describe(over, "").strip();
        return new Reach(clear, stop, clear);
    }

    /** What is under its feet and below, down to the slice's floor: the blocks a dig down would go through. */
    public String down() {
        var out = new ArrayList<String>();
        for (int dy = -1; dy >= -BELOW; dy--) {
            Cell cell = at(0, dy, 0);
            out.add(switch (cell.kind()) {
                case OPEN -> "air";
                case WATER -> "water";
                case LAVA -> "lava";
                case UNKNOWN -> "unseen";
                case SOLID -> cell.id() + (cell.breakable() ? "" : " (cannot break)");
            });
            if (cell.kind() == Kind.UNKNOWN) break;
        }
        return String.join(", ", out);
    }

    /** The ids of the known blocks within {@code reach} of its eyes it cannot break, nearest first. */
    public List<String> unbreakableWithin(double reach) {
        var found = new LinkedHashSet<String>();
        var near = new ArrayList<int[]>();
        int r = (int) Math.ceil(reach);
        for (int dy = -BELOW; dy <= ABOVE; dy++)
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    double ey = dy + 0.5 - 1.62;
                    if (dx * dx + ey * ey + dz * dz <= reach * reach) near.add(new int[]{dx, dy, dz});
                }
        near.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1] + a[2] * a[2], b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        for (int[] at : near) {
            Cell cell = at(at[0], at[1], at[2]);
            if (cell.kind() == Kind.SOLID && !cell.breakable()) found.add(cell.id());
        }
        return List.copyOf(found);
    }

    /** The ways a walk could take from its feet, up to {@code max} each, in the vocabulary's order. */
    public Map<RescuePlan.Way, Reach> ways(int max) {
        var out = new LinkedHashMap<RescuePlan.Way, Reach>();
        for (var way : List.of(RescuePlan.Way.NORTH, RescuePlan.Way.SOUTH, RescuePlan.Way.EAST, RescuePlan.Way.WEST)) out.put(way, walk(way, max));
        return out;
    }

    /**
     * The nine by nine by five round its feet, as letters: a layer for each height from two under its feet to two over,
     * rows north to south and columns west to east; P the player, . open, ~ water, ^ lava, ? unseen; each block's
     * letter is in {@code key}, filled as letters are given out.
     */
    public String grid(Map<String, Character> key) {
        var out = new StringBuilder();
        String letters = "ABCDEFGHIJKLMNOQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int dy = -2; dy <= 2; dy++) {
            out.append("y").append(dy >= 0 ? "+" : "").append(dy).append(dy == 0 ? " (its feet)" : dy == 1 ? " (its head)" : "").append(":\n");
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    if (dx == px && dz == pz && (dy == 0 || dy == 1)) {
                        out.append('P');
                        continue;
                    }
                    Cell cell = at(dx, dy, dz);
                    char mark = switch (cell.kind()) {
                        case OPEN -> '.';
                        case WATER -> '~';
                        case LAVA -> '^';
                        case UNKNOWN -> '?';
                        case SOLID -> {
                            Character letter = key.get(cell.id());
                            if (letter == null) {
                                letter = key.size() < letters.length() ? letters.charAt(key.size()) : '#';
                                key.put(cell.id(), letter);
                            }
                            yield letter;
                        }
                    };
                    out.append(mark);
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** The known blocks of the grid it cannot break, by id: what the key marks as such. */
    public Set<String> unbreakableIds() {
        var out = new LinkedHashSet<String>();
        for (Cell cell : cells) if (cell.kind() == Kind.SOLID && !cell.breakable()) out.add(cell.id());
        return out;
    }

    /** What an executor does next from the slice's centre (F-29c): break or place a cell, move a block, or stop. */
    public record Next(Act act, int dx, int dy, int dz, String stop) {
        public enum Act { BREAK, PLACE, MOVE, STOP }

        static Next stop(String why) { return new Next(Act.STOP, 0, 0, 0, why); }

        static Next of(Act act, int dx, int dy, int dz) { return new Next(act, dx, dy, dz, ""); }
    }

    /**
     * A tunnel's next action that way (F-29c): the passage's cells ahead, at its feet and head, broken a block at a time,
     * then a block's move into it. It stops at a block it cannot break, at a cell to break that touches water or lava it
     * can see (the break would let it in), at liquid or what it cannot see in the passage, and at a drop: it steps down
     * one block, never more.
     */
    public Next tunnel(RescuePlan.Way way) {
        int[] s = step(way);
        int nx = s[0], nz = s[1];
        for (int dy = 0; dy <= 1; dy++) {
            Next broken = clear(nx, dy, nz, "the passage");
            if (broken != null) return broken;
        }
        return onto(nx, 0, nz, "the passage");
    }

    /**
     * A staircase's next action that way (F-29c). Up: the cell over its head and the two over the step ahead cleared,
     * then a block up onto the step; with no block ahead to step onto it stops. Down: the step's three cells ahead
     * cleared, its head, its feet and the cell below, then, only if solid ground can be seen under that, a block down;
     * it stops at liquid, at a drop under the stair and at what it cannot see. Never straight down.
     */
    public Next stairs(RescuePlan.Way rise, RescuePlan.Way way) {
        int[] s = step(way);
        int nx = s[0], nz = s[1];
        if (rise == RescuePlan.Way.UP) {
            Next over = clear(0, 2, 0, "over its head");
            if (over != null) return over;
            for (int dy = 1; dy <= 2; dy++) {
                Next broken = clear(nx, dy, nz, "the stair");
                if (broken != null) return broken;
            }
            Cell stepCell = at(nx, 0, nz);
            if (stepCell.kind() != Kind.SOLID) return Next.stop("no block ahead to step up onto (" + name(stepCell) + ")");
            return Next.of(Next.Act.MOVE, nx, 1, nz);
        }
        for (int dy = 1; dy >= -1; dy--) {
            Next broken = clear(nx, dy, nz, "the stair");
            if (broken != null) return broken;
        }
        Cell under = at(nx, -2, nz);
        return switch (under.kind()) {
            case SOLID -> Next.of(Next.Act.MOVE, nx, -1, nz);
            case WATER -> Next.stop("water under the stair");
            case LAVA -> Next.stop("lava under the stair");
            case UNKNOWN -> Next.stop("what it cannot see under the stair");
            case OPEN -> Next.stop("a drop under the stair");
        };
    }

    /**
     * A bridge's next action that way (F-29c): with the way ahead open at its feet and head, a block of its own placed
     * where there is no ground under the next block (over air or water), then a block's move onto it. It stops at what
     * is in the way, at lava under the next block, and at what it cannot see.
     */
    public Next bridge(RescuePlan.Way way) {
        int[] s = step(way);
        int nx = s[0], nz = s[1];
        String blocked = obstacle(at(nx, 0, nz), at(nx, 1, nz));
        if (blocked != null) return Next.stop(blocked);
        Cell floor = at(nx, -1, nz);
        return switch (floor.kind()) {
            case SOLID -> Next.of(Next.Act.MOVE, nx, 0, nz);
            case OPEN, WATER -> Next.of(Next.Act.PLACE, nx, -1, nz);
            case LAVA -> Next.stop("lava under the next block");
            case UNKNOWN -> Next.stop("what it cannot see under the next block");
        };
    }

    /** The cell broken if it must be, or why it cannot be; null when it is open and nothing stops it. */
    private Next clear(int x, int y, int z, String where) {
        Cell cell = at(x, y, z);
        switch (cell.kind()) {
            case OPEN:
                return null;
            case WATER:
                return Next.stop("water in " + where);
            case LAVA:
                return Next.stop("lava in " + where);
            case UNKNOWN:
                return Next.stop("what it cannot see in " + where);
            default:
                break;
        }
        if (!cell.breakable()) return Next.stop(cell.id() + " in " + where + ", which it cannot break");
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            Cell near = at(x + f[0], y + f[1], z + f[2]);
            if (near.kind() == Kind.WATER || near.kind() == Kind.LAVA)
                return Next.stop((near.kind() == Kind.WATER ? "water" : "lava") + " beside the " + cell.id() + " in " + where + ", which breaking it would let in");
        }
        return Next.of(Next.Act.BREAK, x, y, z);
    }

    /** A move onto the open cell ahead, its floor solid, or down a drop of one; else why not. */
    private Next onto(int x, int y, int z, String where) {
        Cell floor = at(x, y - 1, z);
        if (floor.kind() == Kind.SOLID) return Next.of(Next.Act.MOVE, x, y, z);
        if (floor.kind() != Kind.OPEN) return Next.stop(describe(floor, "under " + where));
        Cell lower = at(x, y - 2, z);
        if (lower.kind() == Kind.SOLID) return Next.of(Next.Act.MOVE, x, y - 1, z);
        return Next.stop("a drop of more than one under " + where);
    }

    /** What a pickaxe of that tier (0 none, 1 wood or gold, 2 stone, 3 iron, 4 diamond or better) lets it break. */
    public static String harvests(int pickaxeTier) {
        return switch (Math.max(0, Math.min(4, pickaxeTier))) {
            case 0 -> "no pickaxe: dirt, sand, gravel, wood and leaves by hand; no stone, deepslate or ore";
            case 1 -> "stone, deepslate and coal ore; not iron, copper or lapis ore";
            case 2 -> "stone, deepslate, iron, copper and lapis ore; not gold, diamond or redstone ore";
            case 3 -> "everything but obsidian, crying obsidian, ancient debris and bedrock";
            default -> "everything but bedrock";
        };
    }

    /** The lines of the report on where it can walk, climb and dig (F-29b). */
    public String waysText(int max) {
        var out = new StringBuilder("Where it can walk (block by block, up or down one at a time; from what it can see):\n");
        ways(max).forEach((way, reach) -> out.append("- ").append(way.name().toLowerCase(Locale.ROOT)).append(": ").append(reach.text()).append('\n'));
        Reach up = up(6);
        out.append("- up: ").append(up.went()).append(up.went() == 1 ? " block" : " blocks").append(" clear over its head")
                .append(up.stop().isEmpty() ? "" : ", then " + up.stop()).append('\n');
        out.append("- down: ").append(down()).append('\n');
        return out.toString();
    }
}
