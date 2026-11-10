package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The campaign's dig decisions and its giving up, ported into the core (F-35e), pinned on small hand-built scenes: the
 * steps the campaign's {@code climb}, {@code digDown}, {@code tunnel} and {@code descendOrTunnel} chose, and what its
 * {@code avoid} and {@code unreachable} skipped. The adapter's equivalence over the native fixtures' scenes is the
 * evidence that nothing changed; these keep the core's own reading of each rule.
 */
class DiggerTest {
    enum Block { AIR, STONE, WATER, LAVA, GRAVEL }

    /** A scene in memory: stone everywhere in its box but where a block is set, air outside it. */
    static final class Scene implements DigTerrain {
        private final Map<Cell, Block> blocks = new HashMap<>();

        Scene set(int x, int y, int z, Block block) {
            blocks.put(new Cell(x, y, z), block);
            return this;
        }

        Block at(int x, int y, int z) {
            if (Math.abs(x) > 10 || Math.abs(z) > 10 || y < -10 || y > 10) return Block.AIR;
            return blocks.getOrDefault(new Cell(x, y, z), Block.STONE);
        }

        @Override public boolean loaded(int x, int y, int z) { return Math.abs(x) <= 10 && Math.abs(z) <= 10 && y >= -10 && y <= 10; }
        @Override public Fluid fluid(int x, int y, int z) {
            return switch (at(x, y, z)) {
                case WATER -> Fluid.WATER;
                case LAVA -> Fluid.LAVA;
                default -> Fluid.NONE;
            };
        }
        @Override public boolean harms(int x, int y, int z) { return at(x, y, z) == Block.LAVA; }
        @Override public boolean passThrough(int x, int y, int z) { return empty(x, y, z); }
        @Override public boolean supports(int x, int y, int z) { return !empty(x, y, z); }
        @Override public boolean bears(int x, int y, int z) { return !empty(x, y, z); }
        @Override public boolean scorches(int x, int y, int z) { return false; }
        @Override public boolean bottomHalf(int x, int y, int z) { return false; }
        @Override public boolean portal(int x, int y, int z) { return false; }
        @Override public boolean replaceable(int x, int y, int z) { return empty(x, y, z); }
        @Override public float hardness(int x, int y, int z) { return empty(x, y, z) ? 0 : 1.5f; }
        @Override public boolean placeAgainst(int x, int y, int z) { return !empty(x, y, z); }
        @Override public boolean empty(int x, int y, int z) {
            Block b = at(x, y, z);
            return b == Block.AIR || b == Block.WATER || b == Block.LAVA;
        }
        @Override public boolean falls(int x, int y, int z) { return at(x, y, z) == Block.GRAVEL; }
        @Override public boolean fallsThrough(int x, int y, int z) { return empty(x, y, z); }
        @Override public boolean falling(int x0, int y0, int z0, int x1, int y1, int z1) { return false; }
    }

    /** A body at {@code feet} with a pickaxe that breaks any stone in reach (three blocks), and the steps as their labels. */
    static final class Body implements Digger.Body<String> {
        final Scene world;
        final Cell feet;
        final Map<Long, Long> aside = new HashMap<>();
        boolean pickaxe = true;

        Body(Scene world, Cell feet) {
            this.world = world;
            this.feet = feet;
        }

        private boolean reach(Cell cell) { return Math.sqrt(cell.squaredDistance(feet.up())) <= 3; }

        @Override public DigTerrain world() { return world; }
        @Override public Cell feet() { return feet; }
        @Override public long now() { return 100; }
        @Override public boolean onGround() { return !world.empty(feet.x(), feet.y() - 1, feet.z()); }
        @Override public boolean swimming() { return false; }
        @Override public boolean groundBelow() { return true; }
        @Override public boolean blocked(Cell cell) { return aside.getOrDefault(cell.asLong(), 0L) > now(); }
        @Override public void setAside(Cell cell, long until) { aside.merge(cell.asLong(), until, Math::max); }
        @Override public boolean breakableNow(Cell cell) { return pickaxe && reach(cell) && !world.empty(cell.x(), cell.y(), cell.z()); }
        @Override public boolean handClearable(Cell cell) { return false; }
        @Override public boolean snareClearable(Cell cell) { return false; }
        @Override public boolean opensOverLava(Cell cell) { return breakableNow(cell); }
        @Override public boolean harvestable(Cell cell) { return pickaxe; }
        @Override public boolean islandEdge(Cell cell) { return false; }
        @Override public boolean dangerBelow(Cell cell) { return false; }
        @Override public int wayBlockSlot() { return 0; }
        @Override public String go(Cell to, double range, boolean partial, String label) { return "go " + Digger.at(to) + " " + label; }
        @Override public String clear(Cell cell, String label) { return "break " + label; }
        @Override public String openOverLava(Cell cell, String label) { return "open " + label; }
        @Override public String settle(String label) { return "wait " + label; }
        @Override public String pillar(Cell feet, int slot, String label) { return "pillar " + label; }
        @Override public String swap(int slot, String label) { return "swap " + label; }
        @Override public Optional<String> fill(Cell cell, String label) { return Optional.of("place " + label); }
    }

    /** A pocket two high at the origin, in solid stone. */
    private static Scene pocket() {
        return new Scene().set(0, 0, 0, Block.AIR).set(0, 1, 0, Block.AIR);
    }

    @Test
    void aClimbClearsItsHeadRoomFirstThenTheStepsCellsThenStepsUp() {
        var scene = pocket();
        var digger = new Digger();
        var body = new Body(scene, new Cell(0, 0, 0));
        assertEquals(Optional.of("break Up: clear 0, 2, 0"), digger.climb(body, "Up"));
        scene.set(0, 2, 0, Block.AIR);
        assertEquals(Optional.of("break Up: clear 0, 1, -1"), digger.climb(body, "Up"), "the landing ahead, north");
        scene.set(0, 1, -1, Block.AIR).set(0, 2, -1, Block.AIR);
        assertEquals(Optional.of("go 0, 1, -1 Up: step up"), digger.climb(body, "Up"));
    }

    @Test
    void aClimbTurnsFromWaterOverTheStepAndDigsAnotherWay() {
        var scene = pocket().set(0, 2, -1, Block.WATER).set(0, 2, 0, Block.AIR);
        var digger = new Digger();
        var body = new Body(scene, new Cell(0, 0, 0));
        // North's landing has water over it: refused, and east is dug instead.
        assertEquals(Optional.of("break Up: clear 1, 1, 0"), digger.climb(body, "Up"));
        assertEquals(Side.EAST, digger.heading());
    }

    @Test
    void aClimbNeverDigsTheBlockGravelRestsOnOverItsHead() {
        var scene = pocket().set(0, 3, 0, Block.GRAVEL);
        var digger = new Digger();
        var body = new Body(scene, new Cell(0, 0, 0));
        Optional<String> step = digger.climb(body, "Up");
        assertFalse(step.equals(Optional.of("break Up: clear 0, 2, 0")), "not the block under the gravel: " + step);
        assertTrue(step.isPresent() && step.get().contains("beside it"), "out from beside it: " + step);
    }

    @Test
    void aStaircaseDownTurnsFromWaterUnderItsStep() {
        var scene = pocket().set(0, -2, -1, Block.WATER);
        var digger = new Digger();
        var body = new Body(scene, new Cell(0, 0, 0));
        assertEquals(Optional.of("break Down: clear 1, 1, 0"), digger.digDown(body, -5, "Down"));
        assertEquals(Side.EAST, digger.heading());
    }

    @Test
    void aStaircaseDownStepsOnceItsCellsAreOpen() {
        var scene = pocket().set(0, 1, -1, Block.AIR).set(0, 0, -1, Block.AIR).set(0, -1, -1, Block.AIR);
        var digger = new Digger();
        assertEquals(Optional.of("go 0, -1, -1 Down: step down"), digger.digDown(new Body(scene, new Cell(0, 0, 0)), -5, "Down"));
    }

    @Test
    void aBranchMineAtItsDepthTunnelsTwoHighAndTurnsAtTheLegsEnd() {
        var scene = pocket();
        var digger = new Digger();
        var body = new Body(scene, new Cell(0, 0, 0));
        assertEquals(Optional.of("break Branch mine for iron: clear 0, 1, -1"), digger.descendOrTunnel(body, 2, 1, "iron"));
        assertEquals(Optional.of("break Dig down toward iron: clear 0, 1, -1"), digger.descendOrTunnel(body, 56, -10, "iron"),
                "above its depth, a staircase down");
        for (int i = 0; i < Digger.BRANCH_LEG - 1; i++) digger.branchAdvanced(false);
        assertEquals(Side.NORTH, digger.heading());
        digger.branchAdvanced(false);
        assertEquals(Side.EAST, digger.heading(), "a quarter turn at the leg's end");
    }

    @Test
    void lavaUnderTheCellAheadIsOpenedFromWhereItStands() {
        var scene = pocket().set(0, 1, -1, Block.AIR).set(0, -1, -1, Block.LAVA);
        var digger = new Digger();
        assertEquals(Optional.of("open Lava: open 0, 0, -1 over lava"), digger.tunnel(new Body(scene, new Cell(0, 0, 0)), "Lava", true));
    }

    @Test
    void aTargetNoWalkReachedIsSkippedWithItsColumnUntilItsBackOffEndsAndThePlayerHasMovedOff() {
        var ledger = new SetAside();
        Cell ore = new Cell(5, 10, 5), feet = new Cell(0, 10, 0);
        ledger.avoid(ore, true, false, 1000, feet);
        assertTrue(ledger.blocked(ore.asLong(), 1000));
        assertTrue(ledger.unreachable(new Cell(6, 16, 4), 1000, feet), "the column round it, six up");
        assertFalse(ledger.unreachable(new Cell(5, 17, 5), 1000, feet), "seven up is not in it");
        assertTrue(ledger.unreachable(ore, 3399, new Cell(40, 10, 40)), "until its back-off ends, from anywhere");
        assertTrue(ledger.unreachable(ore, 3400, new Cell(7, 10, 0)), "then while it is still within eight of where it failed");
        assertFalse(ledger.unreachable(ore, 3400, new Cell(8, 10, 0)));
        assertFalse(ledger.blocked(ore.asLong(), 3400));
        // A second failure there backs off twice as long.
        ledger.avoid(ore, false, false, 4000, feet);
        assertTrue(ledger.blocked(ore.asLong(), 8799));
        assertFalse(ledger.blocked(ore.asLong(), 8800));
        ledger.clear();
        assertFalse(ledger.unreachable(ore, 0, feet));
    }
}
