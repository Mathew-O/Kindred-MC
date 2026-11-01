package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player makes a farm field: it pours water down the middle channel first, then
 * tills and sows every square from the edges without stepping on the farmland, and when it lacks water, a hoe
 * or seeds, or a square cannot be worked, it does what it can and says why.
 */
class FieldWorkTest {
    /** A nine-square field, its soil from (10, 64, 20) to (18, 64, 28), its channel down x 14. */
    private static FieldWork field() {
        return new FieldWork(new Pos(10, 64, 20), 9);
    }

    /** A player working the field as the steps say, the world answering as vanilla would. */
    private static final class World {
        final FieldWork work;
        final Map<Pos, FieldWork.Soil> soil = new HashMap<>();
        final Set<Pos> wet = new HashSet<>();
        Pos feet = new Pos(14, 65, 34);
        boolean hoe = true;
        int seeds = 80, water = 1, steps, crossings;
        long tick;
        Long poured;
        final Set<Pos> walkedOn = new HashSet<>();
        final Set<Pos> footing = new HashSet<>();

        World(FieldWork work) {
            this.work = work;
            for (var cell : work.cells()) soil.put(cell, FieldWork.Soil.DRY);
            footing.addAll(work.around());
        }

        FieldWork.Observation seen() {
            return new FieldWork.Observation(feet, Map.copyOf(soil), Set.copyOf(wet), hoe, seeds, water, Set.copyOf(footing), tick);
        }

        FieldWork.Step.Done run() {
            while (steps++ < 1000) {
                tick += 5;
                // Water poured at the middle runs a block every five ticks either way.
                if (poured != null) for (var cell : work.channel()) if (Math.abs(cell.z() - 24) * 5 <= tick - poured) wet.add(cell);
                var step = work.next(seen());
                switch (step) {
                    case FieldWork.Step.Done done -> { return done; }
                    case FieldWork.Step.Go go -> {
                        if (!work.cells().contains(go.target().add(0, -1, 0))) {
                            assertTrue(footing.contains(go.target()), "only where it can stand: " + go.target());
                        }
                        boolean margin = feet.x() == 9 || feet.x() == 19, across = go.target().x() == 9 || go.target().x() == 19;
                        if (margin && across && feet.x() != go.target().x()) {
                            assertTrue(feet.z() == go.target().z() && (feet.z() == 19 || feet.z() == 29),
                                    "round the end of the field, not across it: " + feet + " to " + go.target());
                            crossings++;
                        }
                        feet = go.target();
                        walkedOn.add(feet.add(0, -1, 0));
                    }
                    case FieldWork.Step.Pour pour -> {
                        assertTrue(water > 0, "water to pour");
                        assertTrue(flat(feet, pour.at()) <= 1.5, "the channel in reach: " + feet);
                        water--;
                        poured = tick;
                    }
                    case FieldWork.Step.Till till -> {
                        assertEquals(FieldWork.Soil.DRY, soil.get(till.at()));
                        assertTrue(flat(feet, till.at()) <= 4 && (feet.x() == 9 || feet.x() == 19), "tilled from the margin, in reach: " + feet + " for " + till.at());
                        assertTrue(wet.containsAll(work.channel()), "the channel wet before any is tilled");
                        soil.put(till.at(), FieldWork.Soil.FARMLAND);
                    }
                    case FieldWork.Step.Sow sow -> {
                        assertEquals(FieldWork.Soil.FARMLAND, soil.get(sow.at()));
                        assertTrue(flat(feet, sow.at()) <= 4 && (feet.x() == 9 || feet.x() == 19), "sown from the margin, in reach: " + feet + " for " + sow.at());
                        seeds--;
                        soil.put(sow.at(), FieldWork.Soil.SOWN);
                    }
                    case FieldWork.Step.Wait wait -> { }
                }
                // Farmland is never walked on.
                if (soil.get(feet.add(0, -1, 0)) == FieldWork.Soil.FARMLAND || soil.get(feet.add(0, -1, 0)) == FieldWork.Soil.SOWN) {
                    fail("stood on farmland at " + feet);
                }
            }
            return fail("never done");
        }
    }

    private static double flat(Pos a, Pos b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2));
    }

    @Test
    void itPoursTheChannelFirstThenTillsAndSowsTheWholeFieldFromItsMargins() {
        var world = new World(field());
        var done = world.run();
        assertEquals(72, done.sown(), done.outcome());
        assertEquals("The field tilled and sown, 72 cells", done.outcome());
        assertTrue(world.soil.values().stream().allMatch(s -> s == FieldWork.Soil.SOWN));
        assertEquals(8, world.seeds);
        assertEquals(0, world.water);
        // Off the margins only once, beside the channel's middle to pour, while that ground was still soil.
        var onField = world.walkedOn.stream().filter(world.soil::containsKey).toList();
        assertEquals(1, onField.size(), "on the field: " + onField);
        assertEquals(1, Math.abs(onField.getFirst().x() - 14));
        assertEquals(1, world.crossings, "one side, then round to the other");
        assertTrue(world.steps < 250, "steps: " + world.steps);
    }

    @Test
    void withTheRowPastOneEndAStairItGoesRoundByTheOtherEnd() {
        for (int z : new int[] {19, 29}) {
            var world = new World(field());
            // The farm ward's way in: a stair down along the row past the field's south end, east of it.
            for (int x = 16; x <= 19; x++) world.footing.remove(new Pos(x, 65, z));
            var done = world.run();
            assertEquals(72, done.sown(), done.outcome());
            assertEquals(1, world.crossings);
        }
        // Neither end walkable: the side it began on, and the other left.
        var world = new World(field());
        for (int z : new int[] {19, 29}) world.footing.remove(new Pos(14, 65, z));
        var done = world.run();
        assertEquals(36, done.sown());
        assertEquals("36 of 72 cells sown: 36 it could not work", done.outcome());
        assertEquals(0, world.crossings);
    }

    @Test
    void withNoWaterItSaysSoAndTillsNothing() {
        var world = new World(field());
        world.water = 0;
        var done = world.run();
        assertEquals("No water for the channel", done.outcome());
        assertTrue(world.soil.values().stream().allMatch(s -> s == FieldWork.Soil.DRY));
    }

    @Test
    void withNoHoeOrTooFewSeedsItDoesWhatItCanAndSaysWhy() {
        var noHoe = new World(field());
        noHoe.hoe = false;
        assertEquals("0 of 72 cells sown: no hoe to till the rest", noHoe.run().outcome());
        var few = new World(field());
        few.seeds = 30;
        var done = few.run();
        assertEquals(30, done.sown());
        assertEquals("30 of 72 cells sown: out of seeds", done.outcome());
        assertEquals(42, few.soil.values().stream().filter(s -> s == FieldWork.Soil.FARMLAND).count(), "the rest tilled");
    }

    @Test
    void itCarriesOnFromAFieldPartlyWorkedAndLeavesWhatIsNotSoil() {
        var world = new World(field());
        world.wet.addAll(world.work.channel());
        world.soil.put(new Pos(10, 64, 20), FieldWork.Soil.SOWN);
        world.soil.put(new Pos(11, 64, 20), FieldWork.Soil.FARMLAND);
        world.soil.put(new Pos(18, 64, 28), FieldWork.Soil.OTHER);
        world.water = 0;
        var done = world.run();
        assertEquals(71, done.sown());
        assertEquals("71 of 72 cells sown: 1 not soil", done.outcome());
    }

    @Test
    void aCellItCannotWorkIsLeftAfterTwoTriesAndTheRestDone() {
        var work = field();
        var world = new World(work);
        world.wet.addAll(work.channel());
        Pos stuck = new Pos(12, 64, 24);
        int tries = 0;
        for (int i = 0; i < 1000; i++) {
            world.tick += 5;
            var step = work.next(world.seen());
            if (step instanceof FieldWork.Step.Done done) {
                assertEquals(2, tries);
                assertEquals(71, done.sown());
                assertEquals("71 of 72 cells sown: 1 it could not work", done.outcome());
                return;
            }
            switch (step) {
                case FieldWork.Step.Go go -> world.feet = go.target();
                case FieldWork.Step.Till till when till.at().equals(stuck) -> {
                    tries++;
                    work.failed();
                }
                case FieldWork.Step.Till till -> world.soil.put(till.at(), FieldWork.Soil.FARMLAND);
                case FieldWork.Step.Sow sow -> world.soil.put(sow.at(), FieldWork.Soil.SOWN);
                default -> { }
            }
        }
        fail("never done");
    }

    /** Asked again before the water has run, it pours again instead of waiting for water that never came. */
    @Test
    void aPourAskedForAgainBeforeItRunsIsStillPoured() {
        var work = field();
        var world = new World(work);
        FieldWork.Step step;
        do {
            step = work.next(world.seen());
            if (step instanceof FieldWork.Step.Go go) world.feet = go.target();
        } while (!(step instanceof FieldWork.Step.Pour));
        // Asked again, the world as it was: the same pour, not a wait for water that was never poured.
        world.tick += 5;
        assertEquals(step, work.next(world.seen()));
    }

    @Test
    void aPourThatDoesNotComeOffStopsIt() {
        var work = field();
        var world = new World(work);
        FieldWork.Step step;
        do {
            step = work.next(world.seen());
            if (step instanceof FieldWork.Step.Go go) world.feet = go.target();
        } while (!(step instanceof FieldWork.Step.Pour));
        work.failed();
        assertEquals(new FieldWork.Step.Done("Could not pour water into the channel", 0), work.next(world.seen()));
    }
}
