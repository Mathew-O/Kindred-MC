package dev.kindred.core.construction;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the trading hall a player builds for villagers: each villager's cell has a bed, room
 * to stand and a front that closes once its work block is in, and the corridor is walled, lit and shut by a
 * door, open to the sky while the cells are roofed.
 */
class TradingHallTest {
    private static Map<Pos, BlockSpec> blocks(Blueprint hall) {
        var out = new HashMap<Pos, BlockSpec>();
        for (var b : hall.blocks()) out.put(b.at(), b.spec());
        return out;
    }

    @Test
    void eachCellHasABedHeadroomAndAFrontThatShutsOnceItsJobBlockIsIn() {
        var hall = TradingHall.of(3, "oak", "cobblestone");
        var at = blocks(hall);
        var clear = new HashSet<>(hall.clear());
        var cells = TradingHall.cells(3);
        assertEquals(3, cells.size());
        for (var cell : cells) {
            int x = cell.stand().x();
            assertEquals("head", at.get(new Pos(x, 1, 1)).properties().get("part"), "Its bed, head to the back wall");
            assertEquals("foot", at.get(new Pos(x, 1, 2)).properties().get("part"));
            // Its floor before the bed's foot, clear to stand on, not the bed's top.
            assertTrue(clear.contains(cell.stand()) && clear.contains(cell.stand().add(0, 1, 0)) && !at.containsKey(cell.stand()));
            assertEquals(cell.stand().z() + 1, cell.jobBlock().z(), "Its job block at its feet");
            // Its front: the job block's place and the opening over it clear, the lintel over that solid, walls either side.
            assertTrue(clear.contains(cell.jobBlock()) && !at.containsKey(cell.jobBlock()), "The job block goes in once the villager is in");
            assertTrue(clear.contains(cell.opening()) && !at.containsKey(cell.opening()), "Where the player trades across");
            assertEquals("minecraft:cobblestone", at.get(cell.opening().add(0, 1, 0)).block(), "No room to stand on the job block");
            for (int y = 1; y <= 3; y++) {
                for (int z = 1; z <= cell.jobBlock().z(); z++) {
                    assertTrue(at.containsKey(new Pos(x - 1, y, z)) && at.containsKey(new Pos(x + 1, y, z)), "Walled from the next cell");
                }
            }
            // The player's side: the corridor, clear, a step south of the job block.
            assertTrue(clear.contains(cell.tradeFrom()) && cell.tradeFrom().z() == cell.jobBlock().z() + 1);
        }
    }

    @Test
    void theCorridorIsWalledLitAndShutByADoorInItsMiddleTheCellsRoofed() {
        var hall = TradingHall.of(4, "spruce", "stone_bricks");
        var at = blocks(hall);
        int width = TradingHall.width(4);
        // What it covers is what it is sited by: its width, depth and height.
        for (var b : hall.blocks()) {
            assertTrue(b.at().x() >= 0 && b.at().x() < width && b.at().z() >= 0 && b.at().z() < TradingHall.DEPTH
                    && b.at().y() >= 0 && b.at().y() < TradingHall.HEIGHT, "Within its site: " + b.at());
        }
        assertEquals("minecraft:spruce_door", at.get(new Pos(width / 2, 1, 7)).block());
        assertEquals("upper", at.get(new Pos(width / 2, 2, 7)).properties().get("half"));
        long torches = at.values().stream().filter(s -> s.block().equals("minecraft:wall_torch")).count();
        assertTrue(torches >= 3, "Lit: " + torches);
        for (int x = 0; x < width; x++) for (int z = 0; z <= 7; z++) {
            assertTrue(at.containsKey(new Pos(x, 0, z)), "Floored at " + x + ", " + z);
            assertEquals(z <= 4, at.containsKey(new Pos(x, 4, z)), "The cells roofed, the corridor open to the sky, at " + x + ", " + z);
        }
        for (int z = 5; z <= 6; z++) for (int y = 1; y <= 3; y++) {
            assertEquals("minecraft:stone_bricks", at.get(new Pos(0, y, z)).block());
            assertEquals("minecraft:stone_bricks", at.get(new Pos(width - 1, y, z)).block());
        }
        assertTrue(new HashSet<>(hall.clear()).stream().noneMatch(at::containsKey), "Nothing placed is also kept clear");
        assertThrows(IllegalArgumentException.class, () -> TradingHall.of(0, "oak", "cobblestone"));
        assertThrows(IllegalArgumentException.class, () -> TradingHall.of(13, "oak", "cobblestone"));
    }

    /**
     * F-22c122: as halls are built now, a villager claims its own cell's bed and cannot sleep in another's. It claims a bed
     * only by a path ending within a block of its head (vanilla's home search distance 1), and sleeps in the bed it has
     * claimed from anywhere within 2.0 of the bed's middle, through a wall. Where it can stand: its floor, or a bed's top with
     * its height (1.95) of room, anywhere across its cell (it is 0.6 wide).
     */
    @Test
    void asHallsAreBuiltNowEachVillagerClaimsItsOwnBedAndNoSpotIsInSleepingReachOfAnothers() {
        for (int n = TradingHall.MIN_CELLS; n <= TradingHall.MAX_CELLS; n++) {
            var at = blocks(TradingHall.of(n, "oak", "cobblestone"));
            var cells = TradingHall.cells(n);
            double closest = Double.MAX_VALUE;
            for (var cell : cells) {
                int gap = claimGap(at, cell);
                assertTrue(gap <= 1, "Its bed's head a block from a spot it can stand on, " + n + " cells, cell " + cell.index()
                        + ": the nearest " + gap + " blocks off, a margin of " + (1 - gap));
                for (var other : cells) if (other != cell) closest = Math.min(closest, closest(at, cell, other));
            }
            if (n > 1) assertTrue(closest >= 2.0, "Out of sleeping reach of the next cell's bed, " + n + " cells: closest " + closest
                    + ", a margin of " + (closest - 2.0));
        }
    }

    /**
     * F-22c122: a hall built before, one wall between its cells, is read from what stands and repaired as it was built: its
     * villager claims its own bed, but stands within 1.81 of the next cell's bed, a legacy hall's limit until rebuilt.
     */
    @Test
    void aLegacyHallKeepsItsPitchAndItsLimitAsMeasured() {
        for (int n = 2; n <= TradingHall.MAX_CELLS; n++) {
            var at = blocks(TradingHall.of(n, "oak", "cobblestone", TradingHall.Layout.LEGACY));
            var cells = TradingHall.cells(n, TradingHall.Layout.LEGACY);
            double closest = Double.MAX_VALUE;
            for (var cell : cells) {
                assertTrue(claimGap(at, cell) <= 1, "A legacy cell claims its own bed, " + n + " cells, cell " + cell.index());
                assertEquals(1 + 2 * cell.index(), cell.stand().x(), "At its old pitch");
                for (var other : cells) if (other != cell) closest = Math.min(closest, closest(at, cell, other));
            }
            assertEquals(1.812, closest, 0.0005, "A legacy hall's limit, as measured, " + n + " cells");
            assertEquals(2 * n + 1, TradingHall.width(n, TradingHall.Layout.LEGACY));
        }
    }

    /** F-22c122: two-thick walls, as measured: the width and block count of a hall of 3 and of 12 cells, and the old ones. */
    @Test
    void theHallsWidthAndBlocksAreAsMeasured() {
        assertEquals(9, TradingHall.width(3));
        assertEquals(36, TradingHall.width(12));
        assertEquals(266, TradingHall.of(3, "oak", "cobblestone").blocks().size());
        assertEquals(1032, TradingHall.of(12, "oak", "cobblestone").blocks().size());
        assertEquals(7, TradingHall.width(3, TradingHall.Layout.LEGACY));
        assertEquals(204, TradingHall.of(3, "oak", "cobblestone", TradingHall.Layout.LEGACY).blocks().size());
        assertEquals(691, TradingHall.of(12, "oak", "cobblestone", TradingHall.Layout.LEGACY).blocks().size());
        assertEquals(TradingHall.width(1), TradingHall.width(1, TradingHall.Layout.LEGACY), "One cell is laid the same either way");
    }

    /**
     * F-22c122: what stands says how a hall is laid out: a legacy hall as legacy, damaged or not, and one built now, or
     * open ground, as halls are built now.
     */
    @Test
    void aHallsLayoutIsReadFromWhatStands() {
        for (int n = 2; n <= 4; n++) {
            var legacy = blocks(TradingHall.of(n, "oak", "cobblestone", TradingHall.Layout.LEGACY));
            var current = blocks(TradingHall.of(n, "oak", "cobblestone"));
            assertEquals(TradingHall.Layout.LEGACY, TradingHall.standing(n, "oak", "cobblestone", legacy::get));
            assertEquals(TradingHall.Layout.CURRENT, TradingHall.standing(n, "oak", "cobblestone", current::get));
            assertEquals(TradingHall.Layout.CURRENT, TradingHall.standing(n, "oak", "cobblestone", p -> null), "Open ground");
            // A legacy hall with its roof and a cell's walls gone, as a creeper would leave it.
            var damaged = new HashMap<>(legacy);
            damaged.keySet().removeIf(p -> p.y() == 4 || p.x() == 2 && p.y() >= 1);
            assertEquals(TradingHall.Layout.LEGACY, TradingHall.standing(n, "oak", "cobblestone", damaged::get), "A damaged legacy hall, " + n + " cells");
        }
    }

    /** The closest a villager standing in {@code from} comes to the middle of {@code to}'s bed head. */
    private static double closest(Map<Pos, BlockSpec> at, TradingHall.Cell from, TradingHall.Cell to) {
        int x = from.stand().x();
        int head = headZ(at, to.stand().x());
        double best = Double.MAX_VALUE;
        for (int z = TradingHall.BED_HEAD; z <= TradingHall.FLOOR; z++) {
            double feet = standingFeet(at, x, z);
            if (Double.isNaN(feet)) continue;
            for (double fx : new double[] {x + 0.3, x + 0.7}) for (double fz : new double[] {z + 0.3, z + 0.7}) {
                double dx = to.stand().x() + 0.5 - fx, dy = 1.5 - feet, dz = head + 0.5 - fz;
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return best;
    }

    /** How far along its cell its bed head is from the nearest spot in it a villager can stand on. */
    private static int claimGap(Map<Pos, BlockSpec> at, TradingHall.Cell cell) {
        int x = cell.stand().x();
        int head = headZ(at, x);
        int nearest = Integer.MAX_VALUE;
        for (int z = TradingHall.BED_HEAD; z <= TradingHall.FLOOR; z++) {
            if (z != head && !Double.isNaN(standingFeet(at, x, z))) nearest = Math.min(nearest, Math.abs(z - head));
        }
        return nearest;
    }

    /** Where a villager's feet are standing at {@code (x, z)} in a cell: on its floor or a bed's top, with 1.95 of room; NaN if it cannot. */
    private static double standingFeet(Map<Pos, BlockSpec> at, int x, int z) {
        var under = at.get(new Pos(x, 1, z));
        double feet = under == null ? 1.0 : under.block().endsWith("_bed") ? 1.5625 : Double.NaN;
        if (Double.isNaN(feet)) return feet;
        int ceiling = TradingHall.ROOF;
        for (int y = 2; y < TradingHall.ROOF; y++) if (at.containsKey(new Pos(x, y, z))) { ceiling = y; break; }
        return ceiling - feet >= 1.95 ? feet : Double.NaN;
    }

    /** The z of the bed head in the cell at {@code x}, from the plan. */
    private static int headZ(Map<Pos, BlockSpec> at, int x) {
        for (int z = TradingHall.BACK; z <= TradingHall.FRONT; z++) {
            var spec = at.get(new Pos(x, 1, z));
            if (spec != null && spec.block().endsWith("_bed") && "head".equals(spec.properties().get("part"))) return z;
        }
        throw new AssertionError("No bed head in the cell at x " + x);
    }
}
