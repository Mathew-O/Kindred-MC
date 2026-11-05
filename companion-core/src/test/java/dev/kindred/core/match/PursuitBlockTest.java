package dev.kindred.core.match;

import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks when a runner shuts the way behind it: only in a way one block wide with the pursuer
 * coming along it, neither too near nor too far, and always the gap toward the pursuer, the lower block first.
 */
class PursuitBlockTest {
    /** Solid everywhere but the open cells given; nothing is both. */
    private static PursuitBlock.Ground ground(Set<Pos> open) {
        return new PursuitBlock.Ground() {
            @Override public boolean open(Pos cell) { return open.contains(cell); }
            @Override public boolean solid(Pos cell) { return !open.contains(cell); }
        };
    }

    /** A tunnel one wide and two high along x at z 0, from x -20 to 20, its floor at y 0. */
    private static Set<Pos> tunnel() {
        var open = new HashSet<Pos>();
        for (int x = -20; x <= 20; x++) for (int y = 1; y <= 2; y++) open.add(new Pos(x, y, 0));
        return open;
    }

    @Test
    void inATunnelTheGapTowardThePursuerIsShutFeetFirst() {
        var cells = PursuitBlock.behind(new Pos(0, 1, 0), -6, 0.3, ground(tunnel()));
        assertEquals(List.of(new Pos(-1, 1, 0), new Pos(-1, 2, 0)), cells);
        // With the lower block in, the head cell is left; with both in, nothing.
        var half = tunnel();
        half.remove(new Pos(-1, 1, 0));
        assertEquals(List.of(new Pos(-1, 2, 0)), PursuitBlock.behind(new Pos(0, 1, 0), -6, 0, ground(half)));
        half.remove(new Pos(-1, 2, 0));
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -6, 0, ground(half)).isEmpty());
        // The other way along the tunnel.
        assertEquals(List.of(new Pos(1, 1, 0), new Pos(1, 2, 0)), PursuitBlock.behind(new Pos(0, 1, 0), 8, 0, ground(tunnel())));
    }

    @Test
    void notWhenThePursuerIsUponItOrFarOffOrOffToTheSide() {
        var g = ground(tunnel());
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -3, 0, g).isEmpty(), "too near: no time to place");
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -17, 0, g).isEmpty(), "too far: not worth a block");
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -5, 6, g).isEmpty(), "coming from the side, not along the way");
    }

    @Test
    void notWhereTheWayIsWiderThanOneOrHasNoFloor() {
        var wide = tunnel();
        wide.add(new Pos(-1, 1, 1));
        wide.add(new Pos(-1, 2, 1));
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -6, 0, ground(wide)).isEmpty(), "an open side: it would only be walked round");
        var headOnly = tunnel();
        headOnly.add(new Pos(-1, 2, -1));
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -6, 0, ground(headOnly)).isEmpty(), "open beside the head cell");
        var pit = tunnel();
        pit.add(new Pos(-1, 0, 0));
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -6, 0, ground(pit)).isEmpty(), "no floor under the gap");
        var open = new HashSet<Pos>();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = 1; y <= 2; y++) open.add(new Pos(x, y, z));
        assertTrue(PursuitBlock.behind(new Pos(0, 1, 0), -6, 0, ground(open)).isEmpty(), "open ground");
    }

    @Test
    void fillersAreFullBlocksThatDoNotFall() {
        assertTrue(PursuitBlock.FILLERS.contains("minecraft:cobblestone"));
        assertFalse(PursuitBlock.FILLERS.contains("minecraft:sand"));
        assertFalse(PursuitBlock.FILLERS.contains("minecraft:gravel"));
    }
}
