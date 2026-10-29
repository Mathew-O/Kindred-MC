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
}
