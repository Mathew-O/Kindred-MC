package dev.kindred.core.route;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game's perception, ported into the core (F-35f), pinned on a small hand-built scene: what the sweep remembers by a
 * face open to the air, what it remembers only in a sight line, and which logs are a tree's to chop from the ground. The
 * adapter's equivalence over the native fixtures' scenes is the evidence that nothing changed; these keep the core's own
 * reading of each rule.
 */
class SightTest {
    enum Block { AIR, STONE, IRON, LOG, LEAVES, WATER, PLANKS }

    /** Stone below Y 0 in a 64 square, air above it, but where a block is set; the square's chunks loaded. */
    static final class Scene implements SightWorld {
        private final Map<Cell, Block> blocks = new HashMap<>();

        Scene set(int x, int y, int z, Block block) {
            blocks.put(new Cell(x, y, z), block);
            return this;
        }

        Block at(int x, int y, int z) {
            Block set = blocks.get(new Cell(x, y, z));
            if (set != null) return set;
            return y < 0 && y > -64 ? Block.STONE : Block.AIR;
        }

        private static boolean cube(Block b) { return b != Block.AIR && b != Block.WATER; }

        @Override public boolean inLoadedChunk(int x, int y, int z) { return x >= -32 && x < 32 && z >= -32 && z < 32; }
        @Override public boolean columnLoaded(int cx, int cz) { return cx >= -2 && cx < 2 && cz >= -2 && cz < 2; }
        @Override public boolean sectionLoaded(int sx, int sy, int sz) { return columnLoaded(sx, sz) && sy >= -4 && sy < 8; }
        @Override public boolean sectionOfInterest(int sx, int sy, int sz) { return true; }
        @Override public Sight.Kind kind(int x, int y, int z) {
            return switch (at(x, y, z)) {
                case STONE -> Sight.Kind.STONE;
                case IRON -> Sight.Kind.IRON_ORE;
                case LOG -> Sight.Kind.LOG;
                case LEAVES -> Sight.Kind.LEAVES;
                case WATER -> Sight.Kind.WATER;
                default -> null;
            };
        }
        @Override public boolean air(int x, int y, int z) { return at(x, y, z) == Block.AIR; }
        @Override public boolean fluidEmpty(int x, int y, int z) { return at(x, y, z) != Block.WATER; }
        @Override public boolean water(int x, int y, int z) { return at(x, y, z) == Block.WATER; }
        @Override public boolean opaqueFullCube(int x, int y, int z) { Block b = at(x, y, z); return cube(b) && b != Block.LEAVES; }
        @Override public boolean log(int x, int y, int z) { return at(x, y, z) == Block.LOG; }
        @Override public boolean leaves(int x, int y, int z) { return at(x, y, z) == Block.LEAVES; }
        @Override public boolean collisionEmpty(int x, int y, int z) { return !cube(at(x, y, z)); }
        @Override public boolean built(int x, int y, int z) { return at(x, y, z) == Block.PLANKS; }
        @Override public int seaLevel() { return 63; }
        @Override public int skyLight(int x, int y, int z) { return y >= 0 ? 15 : 0; }
        @Override public int motionTop(int x, int z) {
            for (int y = 40; y > -64; y--) if (at(x, y, z) != Block.AIR) return y + 1;
            return -64;
        }
        @Override public float fluidHeight(int x, int y, int z) { return at(x, y, z) == Block.WATER ? 8 / 9f : 0; }
        /** Stepped finely along the line: the first cell with a full cube, or water with {@code fluids}. */
        @Override public Cell ray(double ax, double ay, double az, double bx, double by, double bz, boolean fluids) {
            for (int i = 0; i <= 2000; i++) {
                double t = i / 2000.0;
                int x = (int) Math.floor(ax + (bx - ax) * t), y = (int) Math.floor(ay + (by - ay) * t), z = (int) Math.floor(az + (bz - az) * t);
                Block b = at(x, y, z);
                if (cube(b) || fluids && b == Block.WATER) return new Cell(x, y, z);
            }
            return null;
        }
    }

    private static void sweep(Sight sight, Scene scene, Cell feet) {
        double[] eye = {feet.x() + 0.5, feet.y() + 1.62, feet.z() + 0.5};
        for (int i = 0; i < 1000; i++) {
            sight.scan(scene, eye, feet);
            if (sight.swept()) break;
        }
    }

    /** A cave room two high under the ground, iron in its wall and iron behind the wall; a pond and a tree on the surface. */
    private static Scene scene() {
        var scene = new Scene();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -10; y <= -9; y++) scene.set(x, y, z, Block.AIR);
        scene.set(5, -10, 0, Block.IRON).set(6, -9, 0, Block.IRON);
        scene.set(10, -1, 10, Block.WATER);
        for (int y = 0; y <= 4; y++) scene.set(-10, y, -10, Block.LOG);
        scene.set(-10, 5, -10, Block.LEAVES);
        return scene;
    }

    @Test
    void oreIsSeenInASightLineAndNotThroughRockAndLogsByTheirFacesAlone() {
        var sight = new Sight();
        var scene = scene();
        sweep(sight, scene, new Cell(0, -10, 0));
        assertTrue(sight.all(Sight.Kind.IRON_ORE).contains(new Cell(5, -10, 0)), "the ore in the room's wall");
        assertFalse(sight.all(Sight.Kind.IRON_ORE).contains(new Cell(6, -9, 0)), "the ore behind the wall: no face to the air");
        assertTrue(sight.all(Sight.Kind.LOG).contains(new Cell(-10, 2, -10)), "a log's face to the air, needing no sight line");
        assertFalse(sight.all(Sight.Kind.WATER).contains(new Cell(10, -1, 10)), "the pond, through the rock: no sight line");
        assertFalse(sight.all(Sight.Kind.CAVE).isEmpty(), "the room's floor is a cave floor");
    }

    @Test
    void theSweepIsTheSectionsRoundThePlayersAndNoFurther() {
        var sight = new Sight();
        var scene = scene();
        // Forty-eight below the tree, two sections and more down: the sweep reaches a section up and down only.
        scene.set(0, -50, 0, Block.AIR).set(0, -49, 0, Block.AIR);
        sweep(sight, scene, new Cell(0, -50, 0));
        assertTrue(sight.all(Sight.Kind.LOG).isEmpty(), "no log seen from far below");
        assertEquals(Optional.empty(), sight.nearest(scene, Sight.Kind.LOG, new Cell(0, -50, 0), c -> true));
    }

    @Test
    void aLogIsATreesToChopFromTheGroundAtMostFiveBelowIt() {
        var scene = scene();
        assertEquals(Optional.of(new Cell(-10, 0, -10)), Sight.trunkFoot(scene, new Cell(-10, 4, -10)), "four over the ground");
        scene.set(-9, 2, -10, Block.PLANKS);
        assertEquals(Optional.empty(), Sight.trunkFoot(scene, new Cell(-10, 4, -10)), "a post built into planks is not a tree");
        var hanging = new Scene().set(3, 10, 3, Block.LOG).set(3, 9, 3, Block.LEAVES);
        assertEquals(Optional.empty(), Sight.trunkFoot(hanging, new Cell(3, 10, 3)), "held up by leaves");
        var high = new Scene().set(3, 8, 3, Block.LOG);
        assertEquals(Optional.empty(), Sight.trunkFoot(high, new Cell(3, 8, 3)), "eight over the ground: out of reach");
    }
}
