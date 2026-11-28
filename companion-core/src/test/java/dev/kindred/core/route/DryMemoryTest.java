package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dry ground a player last stood on, as its swim back reads it (M-06m3). */
class DryMemoryTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final Cell SHORE = new Cell(0, 64, 0);
    private static final Cell OUT = new Cell(34, 63, 0);

    @Test
    void farOutItSwimsBackToWhereItStoodDry() {
        var memory = new DryMemory();
        memory.stoodDry(OVERWORLD, SHORE);
        assertEquals(Optional.of(SHORE), memory.swimBack(OVERWORLD, OUT));
    }

    @Test
    void anotherDimensionsCellIsNoWayBack() {
        var memory = new DryMemory();
        memory.stoodDry(OVERWORLD, SHORE);
        assertTrue(memory.swimBack(NETHER, OUT).isEmpty(), "never toward an Overworld cell from the Nether");
        memory.in(NETHER);
        assertTrue(memory.cell().isEmpty(), "a dimension change forgets it");
        assertTrue(memory.swimBack(OVERWORLD, OUT).isEmpty(), "and back in the Overworld it is gone");
    }

    @Test
    void aDeathOrARespawnForgetsIt() {
        var memory = new DryMemory();
        memory.stoodDry(OVERWORLD, SHORE);
        memory.forget();
        assertTrue(memory.swimBack(OVERWORLD, OUT).isEmpty(), "never toward a pre-death cell");
    }

    @Test
    void beyondTheBoundOrThereAlreadyNoSwimBack() {
        var memory = new DryMemory();
        memory.stoodDry(OVERWORLD, SHORE);
        assertTrue(memory.swimBack(OVERWORLD, new Cell(DryMemory.BOUND + 1, 64, 0)).isEmpty(), "beyond the bound");
        assertTrue(memory.swimBack(OVERWORLD, new Cell(2, 64, 0)).isEmpty(), "there already");
    }

    @Test
    void aSwimBackThatFailedOrLeftItNoNearerGivesTheCellUpUntilItStandsDryAgain() {
        var memory = new DryMemory();
        memory.stoodDry(OVERWORLD, SHORE);
        assertTrue(memory.swimBack(OVERWORLD, OUT).isPresent());
        assertTrue(memory.swimBack(OVERWORLD, new Cell(20, 63, 0)).isPresent(), "nearer: on");
        assertTrue(memory.swimBack(OVERWORLD, new Cell(20, 63, 0)).isEmpty(), "no nearer: given up");
        assertTrue(memory.swimBack(OVERWORLD, new Cell(10, 63, 0)).isEmpty(), "given up stays given up");
        memory.stoodDry(OVERWORLD, new Cell(1, 64, 0));
        assertTrue(memory.swimBack(OVERWORLD, OUT).isPresent(), "standing dry again: a new cell");
        memory.swimFailed();
        assertTrue(memory.swimBack(OVERWORLD, new Cell(10, 63, 0)).isEmpty(), "a failed swim gives it up");
    }
}
