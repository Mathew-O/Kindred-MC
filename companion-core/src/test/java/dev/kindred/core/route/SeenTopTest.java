package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-35m: a far column's top is known only when the player's eyes reach it. A column it cannot see, one whose ground is not
 * loaded, and one whose sight line crosses ground that is not loaded give nothing, and the last two are never even asked
 * their height.
 */
class SeenTopTest {
    /** Standing on flat ground at the middle of {@link SightTest.Scene}'s loaded square: the ground's top is Y -1 everywhere. */
    private static final double[] EYE = {0.5, 1.62, 0.5};

    /** The column's top as the scene keys it, counting each time it is asked. */
    private static IntBinaryOperator aim(SightTest.Scene scene, AtomicInteger asked) {
        return (x, z) -> {
            asked.incrementAndGet();
            return scene.motionTop(x, z);
        };
    }

    @Test
    void aTopInOpenViewIsSeenAndOneBehindAWallIsNot() {
        var scene = new SightTest.Scene();
        for (int y = 0; y <= 4; y++) for (int z = -3; z <= 3; z++) scene.set(10, y, z, SightTest.Block.STONE);
        var asked = new AtomicInteger();
        assertEquals(Optional.of(new Cell(-20, -1, 0)), Sight.seenTop(scene, EYE, -20, 0, aim(scene, asked)), "flat ground in view");
        assertEquals(Optional.of(new Cell(10, 4, 0)), Sight.seenTop(scene, EYE, 10, 0, aim(scene, asked)), "the wall's own top");
        assertEquals(Optional.empty(), Sight.seenTop(scene, EYE, 20, 0, aim(scene, asked)), "the ground behind the wall");
        assertEquals(Optional.of(new Cell(20, -1, 10)), Sight.seenTop(scene, EYE, 20, 10, aim(scene, asked)), "ground beside the wall's end");
    }

    @Test
    void anUnseenColumnsHeightIsNeverUsed() {
        // A pit's floor, ten down, behind its rim: the heightmap knows it; the player on the ground beside it does not.
        var scene = new SightTest.Scene();
        for (int y = -11; y <= -1; y++) for (int x = 18; x <= 22; x++) for (int z = -2; z <= 2; z++) scene.set(x, y, z, SightTest.Block.AIR);
        scene.set(20, -12, 0, SightTest.Block.STONE);
        for (int y = 0; y <= 3; y++) for (int z = -4; z <= 4; z++) scene.set(16, y, z, SightTest.Block.STONE);
        var asked = new AtomicInteger();
        assertEquals(-11, scene.motionTop(20, 0), "the world's top, the pit's floor");
        assertEquals(Optional.empty(), Sight.seenTop(scene, EYE, 20, 0, aim(scene, asked)), "the floor out of sight: nothing");
        assertEquals(1, asked.get(), "asked once, to aim the one sight line");
    }

    @Test
    void aColumnNotLoadedIsNeverAskedItsHeight() {
        var scene = new SightTest.Scene();
        IntBinaryOperator never = (x, z) -> { throw new AssertionError("asked the height of an unloaded column " + x + "," + z); };
        assertEquals(Optional.empty(), Sight.seenTop(scene, EYE, 40, 0, never), "past the loaded square");
        assertFalse(Sight.seenFrom(scene, EYE, new Cell(40, -1, 0)), "a block past the loaded square");
    }

    @Test
    void aSightLineOverGroundNotLoadedSeesNothingAndIsNeverCast() {
        var scene = new SightTest.Scene();
        var rays = new AtomicInteger();
        // The scene with the chunk column at 0,0 (blocks 0 to 15) not loaded: a hole between the eyes and the far column.
        SightWorld holed = (SightWorld) Proxy.newProxyInstance(SightWorld.class.getClassLoader(), new Class<?>[] {SightWorld.class}, (proxy, method, args) -> {
            if (method.getName().equals("columnLoaded") && (int) args[0] == 0 && (int) args[1] == 0) return false;
            if (method.getName().equals("ray")) rays.incrementAndGet();
            if (method.getName().equals("motionTop")) throw new AssertionError("asked a height across ground not loaded");
            return method.invoke(scene, args);
        });
        double[] eye = {-27.5, 1.62, 5.5};
        IntBinaryOperator aim = (x, z) -> { throw new AssertionError("aimed across ground not loaded"); };
        assertEquals(Optional.empty(), Sight.seenTop(holed, eye, 28, 5, aim), "the far column, across the hole");
        assertFalse(Sight.seenFrom(holed, eye, new Cell(28, -1, 5)), "a far block, across the hole");
        assertEquals(0, rays.get(), "no sight line cast across it");
        // Along the hole's edge, never crossing it, the far column is seen.
        double[] beside = {-27.5, 1.62, -5.5};
        assertTrue(Sight.seenFrom(holed, beside, new Cell(28, -1, -5)), "a far block, the line beside the hole");
    }

    @Test
    void askingWhatItLooksForFirstOnlySparesSightLinesAndNeverChangesTheAnswer() {
        // Ground at Y -1 all round, walls hiding some of it, pools on some of it: the places are the tops along a ring, and
        // what it looks for is dry ground (no water in the top), as the boat's look round for land asks.
        var scene = new SightTest.Scene();
        for (int y = 0; y <= 4; y++) for (int z = -30; z <= 30; z++) scene.set(9, y, z, SightTest.Block.STONE);
        for (int x = -6; x <= 6; x += 3) scene.set(x, -1, -12, SightTest.Block.WATER);
        var candidates = new java.util.ArrayList<Cell>();
        for (int x = -24; x <= 24; x += 3) for (int z : new int[] {-12, 12}) candidates.add(new Cell(x, -1, z));
        for (int z = -9; z <= 9; z += 3) for (int x : new int[] {-24, 24}) candidates.add(new Cell(x, -1, z));
        candidates.sort(java.util.Comparator.comparingDouble(c -> c.squaredDistance(new Cell(0, 0, 0))));
        java.util.function.Predicate<Cell> dry = c -> scene.fluidEmpty(c.x(), c.y(), c.z());
        for (int drop = 0; drop < candidates.size(); drop++) {
            // Each prefix of the ring dropped in turn, so that the winner changes: the filtered and the unfiltered agree each time.
            var places = candidates.subList(drop, candidates.size());
            Optional<Cell> unfiltered = Optional.empty();
            int unfilteredRays = 0;
            for (Cell c : places) {
                unfilteredRays++;
                if (Sight.seenFrom(scene, EYE, c) && dry.test(c)) { unfiltered = Optional.of(c); break; }
            }
            int[] rays = {1_000};
            assertEquals(unfiltered, Sight.firstSeen(scene, EYE, places, dry, rays), "the same place, " + drop + " dropped");
            assertTrue(1_000 - rays[0] <= unfilteredRays, "no more sight lines than asking every place");
        }
        // And the answer is one it can see and is dry: past the wall nothing; the pools never.
        var first = Sight.firstSeen(scene, EYE, candidates, dry, new int[] {1_000}).orElseThrow();
        assertTrue(first.x() < 9 && dry.test(first), "seen and dry: " + first);
    }

    @Test
    void theChunkGridIsWalkedThroughCorners() {
        var scene = new SightTest.Scene();
        SightWorld cornerless = (SightWorld) Proxy.newProxyInstance(SightWorld.class.getClassLoader(), new Class<?>[] {SightWorld.class}, (proxy, method, args) -> {
            if (method.getName().equals("columnLoaded") && (int) args[0] == 0 && (int) args[1] == -1) return false;
            return method.invoke(scene, args);
        });
        // From the middle of chunk -1,-1 to the middle of chunk 0,0, exactly through the corner they share with 0,-1.
        assertFalse(Sight.loadedAlong(cornerless, -8, -8, 8, 8), "a line through a corner touches the chunk beside it");
        assertTrue(Sight.loadedAlong(scene, -8, -8, 8, 8), "all four loaded");
        assertTrue(Sight.loadedAlong(cornerless, -8, -8, -8, 8), "straight along x = -8, nowhere near chunk 0,-1");
    }
}
