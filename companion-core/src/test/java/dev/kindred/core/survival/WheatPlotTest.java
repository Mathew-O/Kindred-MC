package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks a small wheat plot by a player's base: tilled with its hoe, sown with its seeds, left to grow
 * while it does other things, the ripe wheat harvested and the plot sown again, until it has the wheat it wants.
 */
class WheatPlotTest {
    /** A two-by-two plot from (0, 64, 0): its soil at y 64. */
    private static WheatPlot plot() {
        return new WheatPlot(new Pos(0, 64, 0), 2);
    }

    private static Map<Pos, WheatPlot.Soil> all(WheatPlot.Soil soil) {
        var map = new HashMap<Pos, WheatPlot.Soil>();
        for (var cell : plot().cells()) map.put(cell, soil);
        return map;
    }

    private static WheatPlot.Observation seen(Map<Pos, WheatPlot.Soil> soil, boolean hoe, int seeds, int wheat) {
        return new WheatPlot.Observation(new Pos(0, 65, 3), soil, hoe, seeds, wheat);
    }

    @Test
    void itTillsThenSowsThenLeavesItToGrowThenHarvestsAndSowsAgain() {
        var plot = plot();
        assertEquals(4, plot.cells().size());
        // Dry soil and no hoe: a hoe first.
        assertInstanceOf(WheatPlot.Step.NeedHoe.class, plot.next(seen(all(WheatPlot.Soil.DRY), false, 4, 0), 2));
        // With a hoe: tilled, nearest first.
        var till = assertInstanceOf(WheatPlot.Step.Till.class, plot.next(seen(all(WheatPlot.Soil.DRY), true, 4, 0), 2));
        assertTrue(plot.cells().contains(till.at()));
        // Farmland bare: sown.
        assertInstanceOf(WheatPlot.Step.Sow.class, plot.next(seen(all(WheatPlot.Soil.FARMLAND), true, 4, 0), 2));
        // Bare farmland and no seeds: seeds first.
        assertInstanceOf(WheatPlot.Step.NeedSeeds.class, plot.next(seen(all(WheatPlot.Soil.FARMLAND), true, 0, 0), 2));
        // All sown, none ripe: it leaves the plot to grow.
        var growing = assertInstanceOf(WheatPlot.Step.Growing.class, plot.next(seen(all(WheatPlot.Soil.SOWN), true, 0, 0), 2));
        assertTrue(growing.why().contains("ripen"), growing.why());
        // Ripe: harvested, then sown again with the seeds that came with it.
        var ripe = all(WheatPlot.Soil.SOWN);
        ripe.put(new Pos(1, 64, 1), WheatPlot.Soil.RIPE);
        assertEquals(new Pos(1, 64, 1), assertInstanceOf(WheatPlot.Step.Harvest.class, plot.next(seen(ripe, true, 0, 0), 2)).at());
        var bare = all(WheatPlot.Soil.SOWN);
        bare.put(new Pos(1, 64, 1), WheatPlot.Soil.FARMLAND);
        assertEquals(new Pos(1, 64, 1), assertInstanceOf(WheatPlot.Step.Sow.class, plot.next(seen(bare, true, 2, 1), 2)).at());
        // With the wheat it wants and nothing left to work: done.
        assertInstanceOf(WheatPlot.Step.Done.class, plot.next(seen(all(WheatPlot.Soil.SOWN), true, 1, 2), 2));
    }

    @Test
    void ripeWheatIsHarvestedEvenWithTheWheatInHandAndWhatIsNotSoilIsLeftAlone() {
        var plot = plot();
        var ripe = all(WheatPlot.Soil.RIPE);
        assertInstanceOf(WheatPlot.Step.Harvest.class, plot.next(seen(ripe, true, 0, 5), 2), "ripe wheat is never left to stand");
        var stone = all(WheatPlot.Soil.OTHER);
        assertInstanceOf(WheatPlot.Step.Done.class, plot.next(seen(stone, true, 4, 2), 2));
        var lost = plot.next(seen(stone, true, 4, 0), 2);
        assertInstanceOf(WheatPlot.Step.Done.class, lost, "nothing it can grow on: it says so");
        assertTrue(((WheatPlot.Step.Done) lost).outcome().contains("nothing to grow"), ((WheatPlot.Step.Done) lost).outcome());
    }

    @Test
    void aCropPlantedFromItsFoodIsPlantedOneAtATimeOnlyWhileItHasFewerThanItWants() {
        var plot = new WheatPlot(new Pos(0, 64, 0), 2, true);
        // One carrot, the plot bare: planted, the one it has.
        assertInstanceOf(WheatPlot.Step.Sow.class, plot.next(seen(all(WheatPlot.Soil.FARMLAND), true, 1, 1), 4));
        // Planted, none left: left to grow; it does not ask for another to plant.
        var one = all(WheatPlot.Soil.FARMLAND);
        one.put(new Pos(0, 64, 0), WheatPlot.Soil.SOWN);
        assertInstanceOf(WheatPlot.Step.Growing.class, plot.next(seen(one, true, 0, 0), 4));
        // Harvested to three, nothing growing: one planted again toward four.
        assertInstanceOf(WheatPlot.Step.Sow.class, plot.next(seen(all(WheatPlot.Soil.FARMLAND), true, 3, 3), 4));
        // Three held, one growing: not planted again, so the three are kept; it waits.
        assertInstanceOf(WheatPlot.Step.Growing.class, plot.next(seen(one, true, 3, 3), 4), "planting never eats what it grew");
        // What it wants held: done, the surplus kept, not planted (a plot ripening fast was farmed without end).
        assertInstanceOf(WheatPlot.Step.Done.class, plot.next(seen(all(WheatPlot.Soil.FARMLAND), true, 5, 5), 4));
        assertInstanceOf(WheatPlot.Step.Done.class, plot.next(seen(one, true, 4, 4), 4));
        // None held and nothing growing: one to plant first, never one from nothing.
        assertInstanceOf(WheatPlot.Step.NeedSeeds.class, plot.next(seen(all(WheatPlot.Soil.FARMLAND), true, 0, 0), 4));
    }
}
