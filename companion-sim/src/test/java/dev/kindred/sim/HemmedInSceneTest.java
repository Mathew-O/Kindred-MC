package dev.kindred.sim;

import dev.kindred.core.atlas.Priors;
import dev.kindred.sim.SimWorld.Block;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: the first staged scene for the simulator (F-35d step 3): Echo on seed 505 in E-40's iteration 15,
 * hemmed in on a ledge at Y 47 between an aquifer and a cavern, its way to iron refused on every side. With the
 * campaign's own climb, dig down and giving up on a target in the simulator (F-35e), the iron stage gets off the ledge,
 * as the game's own fixture of the ledge ({@code NativeCavernLedgeGameTests}) does: that test is flipped. Echo's own kit,
 * no pickaxe, still stays: that test still reproduces, now through the climb's own steps.
 *
 * Technical: the run's place (x 16 to 18, Y 47, z -48 to -44; F-25c4cp's `ledge-505`) staged in a {@link SimWorld} at
 * the same x and Y, z shifted by 128 into the simulator's square: a ledge of five by three dug cells open on its east
 * to a cavern whose floor is fifteen below, the aquifer behind it, on both sides and over it with one block of stone
 * between, and iron ore in the cavern's far wall. The walk there is planned by the core's own {@code Navigator} and
 * {@code RoutePlanner} over the scene: no drop deeper than three, no step that digs down (the planner digs only at the
 * body's own level), and every dig at the ledge's level refused as the campaign refuses it, for the water beside or
 * over it. The step the run logged, "Mine iron ore - Tunnel past liquid toward iron: advance", is the campaign's own
 * ({@code descendOrTunnel}, then {@code tunnel}: an ore not in view, water under every way down), now the core's
 * {@code Digger}, which the simulator asks as the adapter does.
 */
class HemmedInSceneTest {
    /** Where Echo stood, in the simulator's square. */
    private static final int X = 18, Y = 47, Z = 80;
    private static final long TICKS = 12_000;

    private static SimWorld ledge505() {
        var world = SimWorld.staged(505);
        for (int x = 0; x < SimWorld.SIZE; x++) for (int z = 0; z < SimWorld.SIZE; z++) {
            world.set(x, SimWorld.MIN_Y, z, Block.BEDROCK);
            for (int y = SimWorld.MIN_Y + 1; y <= 66; y++) world.set(x, y, z, Block.STONE);
            for (int y = 67; y <= 69; y++) world.set(x, y, z, Block.DIRT);
            world.set(x, 70, z, Block.GRASS);
        }
        // The cavern east of the ledge, its floor fifteen below the ledge's.
        for (int x = 19; x <= 29; x++) for (int y = 32; y <= 50; y++) for (int z = 72; z <= 90; z++) world.set(x, y, z, Block.AIR);
        // The aquifer round the ledge: behind it, on both sides and over it, one block of stone between.
        for (int x = 8; x <= 18; x++) for (int y = 44; y <= 53; y++) for (int z = 72; z <= 90; z++) {
            if (x >= 15 && z >= 77 && z <= 83 && y <= 49) continue;
            world.set(x, y, z, Block.WATER);
        }
        // The ledge: the cells Echo dug, open to the cavern.
        for (int x = 16; x <= 18; x++) for (int y = 47; y <= 48; y++) for (int z = 78; z <= 82; z++) world.set(x, y, z, Block.AIR);
        // Iron ore in the cavern's far wall, in view from the ledge.
        world.set(30, 36, 82, Block.IRON_ORE);
        return world;
    }

    private static SimRun on505(Map<String, Integer> kit) {
        return new SimRun(new SimRun.Config("scene", 505, TICKS, SimRun.Start.FRESH, "Echo"), Priors.standard(), ledge505(),
                new int[] {X, Y, Z}, kit);
    }

    /**
     * Still where it stood, through the watchdog's spells: within a level of the ledge (its climb steps a level down and
     * back up), and within a few blocks of where it stood.
     */
    private static void stillOnTheLedge(SimRun run, SimRun.Result result) {
        int[] at = run.at();
        assertTrue(Math.abs(at[1] - Y) <= 1, "still at the ledge's level, or a level under it: " + java.util.Arrays.toString(at) + "\n" + result.log());
        assertTrue(Math.abs(at[0] - X) <= 3 && Math.abs(at[2] - Z) <= 3, "within a few blocks of where it stood: "
                + java.util.Arrays.toString(at) + "\n" + result.log());
        assertTrue(result.stuck() >= 2, "stuck spells " + result.stuck() + "\n" + result.log());
        assertEquals(0, result.deaths(), result.log());
    }

    /**
     * The iron stage as F-25c4cp's fixture has it, a stone pickaxe in hand, the ore in view down in the cavern: the walk to
     * it is still "No walkable route", and every made way too. That ore is then given up, with the column round it, as the
     * campaign gives a target up after a walk to it fails, and with no other ore in view its staircase goes down toward
     * iron's depth, off the ledge. Flipped by F-35e (in step 3 it stood on the ledge through three spells): it passes as
     * the game's own fixture does, on ground eight or more from where it stood, alive. Not within that fixture's 3,000
     * ticks: five blocks down its staircase the cavern's floor is in view, and the simulator's walks to it (its stand-in
     * for the campaign's cave walk) are refused one after another, each choice let be half a minute, until the watchdog's
     * spell at 3:32; then the staircase goes on down.
     */
    @Test
    void hemmedInByLiquidTowardIronOn505GetsOffTheLedge() {
        var run = on505(Map.of("stone_pickaxe", 1, "stick", 4, "crafting_table", 1, "cobblestone", 32, "torch", 16, "bread", 8));
        var result = run.run();
        int[] at = run.at();
        assertTrue(result.log().contains("Echo goal at 18 47 80: Iron: Mine iron ore"), result.log());
        assertTrue(result.log().contains("Echo native_break BLOCKED: No walkable route toward 29, 32, 82 [Mine iron ore"), result.log());
        assertTrue(result.log().contains("Dig down toward iron: step down [Mine iron ore"), "its staircase down: " + result.log());
        assertTrue(at[1] < Y && sq(at[0] - X) + sq(at[1] - Y) + sq(at[2] - Z) >= 8 * 8, "off the ledge: " + java.util.Arrays.toString(at) + "\n" + result.log());
        assertEquals(0, result.deaths(), result.log());
    }

    private static int sq(int v) { return v * v; }

    /**
     * Echo's kit as the run ended it: an iron ingot and no pickaxe. The plan asks for logs for sticks; walks toward the
     * surface are refused, and the campaign's climb, by hand, meets the aquifer's water over every way up: its way out
     * from under the water steps a level down, and its climb steps back up onto the ledge, by turns. This still
     * reproduces the stuck spell (F-35e: within a level of the ledge, where step 3 stood at its level).
     */
    @Test
    void hemmedInWithEchosKitNoPickaxeOn505Reproduces() {
        var run = on505(Map.of("iron_ingot", 1, "cobblestone", 32, "torch", 16, "bread", 8));
        var result = run.run();
        stillOnTheLedge(run, result);
        assertTrue(result.log().contains("Echo goal at 18 47 80: Wood: Gather logs for sticks"), result.log());
        assertTrue(result.log().contains("Echo native_goto BLOCKED: No walkable route toward"), result.log());
        assertTrue(result.log().contains("Climb toward the surface for logs: out from under the water, a level lower: step down"), result.log());
        assertTrue(result.log().contains("Climb toward the surface for logs: step up"), result.log());
        assertEquals(1, run.holding("iron_ingot"), result.log());
        assertEquals(0, run.holding("wooden_pickaxe") + run.holding("stone_pickaxe"), "still no pickaxe: " + result.log());
    }
}
