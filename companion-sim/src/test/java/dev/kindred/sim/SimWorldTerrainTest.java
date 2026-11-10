package dev.kindred.sim;

import dev.kindred.core.route.Cell;
import dev.kindred.core.route.Navigator;
import dev.kindred.core.route.Terrain;
import dev.kindred.sim.SimWorld.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: the simulator's world answers the route planners' questions about each block as the server's world
 * does for the matching vanilla block, so a route planned in the simulator is the one the game would plan.
 *
 * Technical: F-35d step 3. {@link SimWorld}'s {@link Terrain} facts, block by block, against what the adapter's
 * {@code NativeNavigator.WorldTerrain} reads from vanilla 1.21.1's states (collision shape, fluid, replaceability,
 * hardness); and the core navigator over a staged world.
 */
class SimWorldTerrainTest {
    @Test
    void eachBlockAnswersAsItsVanillaBlockDoes() {
        var world = SimWorld.staged(1);
        int x = 10, z = 10;
        for (Block b : Block.values()) world.set(x + b.ordinal(), 0, z, b);
        for (Block b : Block.values()) {
            int bx = x + b.ordinal();
            boolean open = b == Block.AIR || b == Block.TORCH, fluid = b == Block.WATER || b == Block.LAVA;
            assertEquals(open || fluid, world.passThrough(bx, 0, z), b + " passes through");
            assertEquals(!open && !fluid, world.supports(bx, 0, z), b + " supports");
            assertEquals(!open && !fluid, world.bears(bx, 0, z), b + " bears");
            assertEquals(!open && !fluid, world.placeAgainst(bx, 0, z), b + " takes a block against it");
            assertEquals(b == Block.AIR || fluid, world.replaceable(bx, 0, z), b + " is replaced by a placed block");
            assertEquals(b == Block.LAVA, world.harms(bx, 0, z), b + " harms");
            assertEquals(b == Block.WATER ? Terrain.Fluid.WATER : b == Block.LAVA ? Terrain.Fluid.LAVA : Terrain.Fluid.NONE,
                    world.fluid(bx, 0, z), b + "'s fluid");
            assertFalse(world.scorches(bx, 0, z) || world.bottomHalf(bx, 0, z) || world.portal(bx, 0, z), b.toString());
        }
        assertEquals(1.5f, world.hardness(x + Block.STONE.ordinal(), 0, z));
        assertEquals(-1f, world.hardness(x + Block.BEDROCK.ordinal(), 0, z));
        assertEquals(50f, world.hardness(x + Block.OBSIDIAN.ordinal(), 0, z));
        assertEquals(3f, world.hardness(x + Block.IRON_ORE.ordinal(), 0, z));
        assertEquals(0.6f, world.hardness(x + Block.GRASS.ordinal(), 0, z));
        assertEquals(0.2f, world.hardness(x + Block.LEAVES.ordinal(), 0, z));
    }

    @Test
    void loadedIsTheSquareAndItsHeights() {
        var world = SimWorld.staged(1);
        assertTrue(world.loaded(0, SimWorld.MIN_Y, 0));
        assertTrue(world.loaded(SimWorld.SIZE - 1, SimWorld.MIN_Y + SimWorld.HEIGHT - 1, SimWorld.SIZE - 1));
        assertFalse(world.loaded(-1, 0, 0));
        assertFalse(world.loaded(0, 0, SimWorld.SIZE));
        assertFalse(world.loaded(0, SimWorld.MIN_Y - 1, 0));
        assertFalse(world.loaded(0, SimWorld.MIN_Y + SimWorld.HEIGHT, 0));
        assertFalse(world.placeAgainst(-1, 0, 0));
        assertEquals(Terrain.Fluid.NONE, world.fluid(-1, 0, 0));
    }

    @Test
    void theNavigatorWalksTheSimulatorsWorld() {
        var world = SimWorld.staged(1);
        for (int x = 0; x < 20; x++) for (int z = 0; z < 5; z++) world.set(x, 0, z, Block.STONE);
        // A wall two high across the way, and a step of one beside it.
        for (int z = 0; z < 5; z++) for (int y = 1; y <= 2; y++) world.set(10, y, z, Block.STONE);
        var navigator = new Navigator(world);
        var start = new Cell(5, 1, 2);
        var target = new Cell(15, 1, 2);
        assertFalse(navigator.find(start, target, target::equals, Navigator.DEFAULT_MAX_NODES, 96).complete(), "no way over a wall two high");
        world.set(10, 2, 2, Block.AIR);
        var path = new Navigator(world).find(start, target, target::equals, Navigator.DEFAULT_MAX_NODES, 96);
        assertTrue(path.complete(), "over a step of one");
        assertTrue(path.nodes().contains(new Cell(10, 2, 2)), path.nodes().toString());
    }
}
