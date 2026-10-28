package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A trading hall (E-45d): a row of cells, one for each villager kept, along a corridor the player walks and trades
 * from, as players build them. Each cell is one block wide and three deep: its bed along it, head to the back wall, and
 * the villager's floor before the bed's foot (a villager standing on a bed's top never took up a job block before it:
 * no path from there, in a fixture). Walled from the next, floored and roofed, it is open to the corridor only across
 * its front, where the villager's job block goes at its feet once the villager is in, which shuts it in: a lintel over
 * the job block leaves no room to stand on it, and the one block open between the two is where the player trades
 * across. The corridor is walled three high, lit within and shut by a door at its middle, against zombies, and open to
 * the sky: a roof seven deep over a closed hall left its middle row out of any reach to place it (a fixture's build
 * stopped short of it). Its origin is the north-west corner of the floor; the cells' fronts face south. Kindred's own
 * (D8). Pure.
 */
public final class TradingHall {
    private TradingHall() { }

    /** Cells a hall holds: a villager each. */
    public static final int MIN_CELLS = 1, MAX_CELLS = 12;

    /** North to south: the back wall, the bed's head and foot, the villager's floor, the cell's front, the corridor, the front wall. */
    static final int BACK = 0, BED_HEAD = 1, BED_FOOT = 2, FLOOR = 3, FRONT = 4, CORRIDOR = 5, FRONT_WALL = 7, ROOF = 4;

    /** A hall's ground north to south, and its height from its floor to its roof, for siting it (E-45d). */
    public static final int DEPTH = FRONT_WALL + 1, HEIGHT = ROOF + 1;

    /** A hall's ground west to east: a wall either side of each cell, shared. */
    public static int width(int cells) {
        check(cells);
        return 2 * cells + 1;
    }

    /**
     * A cell of the hall: where the villager stands (its floor, before its bed), where its job block goes (across its
     * front, at its feet), and the block open above that, where the player trades across, from the corridor's side.
     */
    public record Cell(int index, Pos stand, Pos jobBlock, Pos opening, Pos tradeFrom) { }

    /** The cells of a hall of {@code cells}, from the west. */
    public static List<Cell> cells(int cells) {
        check(cells);
        var out = new ArrayList<Cell>();
        for (int i = 0; i < cells; i++) {
            int x = 1 + 2 * i;
            out.add(new Cell(i, new Pos(x, 1, FLOOR), new Pos(x, 1, FRONT), new Pos(x, 2, FRONT), new Pos(x, 1, CORRIDOR)));
        }
        return List.copyOf(out);
    }

    /** The hall of {@code cells} cells in {@code wood} and {@code stone}: its floor, walls, cells, beds, roof, door and light. */
    public static Blueprint of(int cells, String wood, String stone) {
        check(cells);
        if (!wood.matches("[a-z_]+") || !stone.matches("[a-z_]+")) throw new IllegalArgumentException("Not a material: " + wood + ", " + stone);
        int width = width(cells);
        var at = new LinkedHashMap<Pos, BlockSpec>();
        var clear = new ArrayList<Pos>();
        BlockSpec planks = BlockSpec.of("minecraft:" + wood + "_planks"), wall = BlockSpec.of("minecraft:" + stone);
        // The floor under it all, the roof over the cells and their fronts.
        for (int x = 0; x < width; x++) for (int z = BACK; z <= FRONT_WALL; z++) {
            at.put(new Pos(x, 0, z), planks);
            if (z <= FRONT) at.put(new Pos(x, ROOF, z), planks);
        }
        for (int x = 0; x < width; x++) {
            for (int y = 1; y < ROOF; y++) {
                // The back wall and the front wall the whole width.
                at.put(new Pos(x, y, BACK), wall);
                at.put(new Pos(x, y, FRONT_WALL), wall);
                // Between the cells, and at either end, walls through the cells' depth to their fronts.
                if (x % 2 == 0) for (int z = BED_HEAD; z <= FRONT; z++) at.put(new Pos(x, y, z), wall);
            }
            // The corridor's ends.
            if (x == 0 || x == width - 1) for (int y = 1; y < ROOF; y++) for (int z = CORRIDOR; z < FRONT_WALL; z++) at.put(new Pos(x, y, z), wall);
        }
        for (Cell cell : cells(cells)) {
            int x = cell.stand().x();
            // Its bed, head to the back wall; the villager's floor before its foot.
            at.put(new Pos(x, 1, BED_HEAD), BlockSpec.parse("minecraft:white_bed[facing=north,part=head]"));
            at.put(new Pos(x, 1, BED_FOOT), BlockSpec.parse("minecraft:white_bed[facing=north,part=foot]"));
            for (int y = 2; y < ROOF; y++) {
                clear.add(new Pos(x, y, BED_HEAD));
                clear.add(new Pos(x, y, BED_FOOT));
            }
            for (int y = 1; y < ROOF; y++) clear.add(new Pos(x, y, FLOOR));
            // Its front: the job block's place left clear until the villager is in, the opening clear, the lintel solid.
            clear.add(cell.jobBlock());
            clear.add(cell.opening());
            at.put(new Pos(x, 3, FRONT), wall);
        }
        // The corridor clear, its door in the middle of the front wall, and a torch on the front wall by each cell.
        for (int x = 1; x < width - 1; x++) for (int y = 1; y < ROOF; y++) for (int z = CORRIDOR; z < FRONT_WALL; z++) clear.add(new Pos(x, y, z));
        int door = width / 2;
        at.put(new Pos(door, 1, FRONT_WALL), BlockSpec.parse("minecraft:" + wood + "_door[facing=north,half=lower,hinge=left,open=false]"));
        at.put(new Pos(door, 2, FRONT_WALL), BlockSpec.parse("minecraft:" + wood + "_door[facing=north,half=upper,hinge=left,open=false]"));
        for (Cell cell : cells(cells)) {
            int x = cell.stand().x();
            if (x != door) at.put(new Pos(x, 3, FRONT_WALL - 1), BlockSpec.parse("minecraft:wall_torch[facing=north]"));
        }
        // Torches stand in the corridor's air: what is placed is not also kept clear.
        clear.removeAll(at.keySet());
        var blocks = new ArrayList<Blueprint.Block>(at.size());
        for (Map.Entry<Pos, BlockSpec> each : at.entrySet()) blocks.add(new Blueprint.Block(each.getKey(), each.getValue()));
        return new Blueprint("trading hall of " + cells, Generators.SOURCE + "tradinghall?cells=" + cells + "&wood=" + wood + "&stone=" + stone,
                "Kindred", "Kindred's own", blocks, clear);
    }

    private static void check(int cells) {
        if (cells < MIN_CELLS || cells > MAX_CELLS) throw new IllegalArgumentException("A hall of " + MIN_CELLS + " to " + MAX_CELLS + " cells");
    }
}
