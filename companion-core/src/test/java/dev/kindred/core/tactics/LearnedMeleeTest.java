package dev.kindred.core.tactics;

import dev.kindred.core.tactics.Melee.Move;
import dev.kindred.core.tactics.Melee.Sight;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LearnedMeleeTest {
    private static Sight random(Random r) {
        return new Sight(0.5 + r.nextDouble() * 9.5, r.nextDouble(), 1 + r.nextDouble() * 19, r.nextBoolean(), r.nextBoolean(), r.nextBoolean(),
                r.nextDouble() < 0.1, 1 + r.nextDouble() * 19, r.nextDouble() < 0.95, r.nextDouble(), r.nextDouble(), r.nextDouble() < 0.3);
    }

    /** A demonstrator that plays as the hand-set melee scores the moves, without its commitment. */
    private static List<LearnedMelee.Sample> demonstrations(long seed, int count) {
        var r = new Random(seed);
        var samples = new ArrayList<LearnedMelee.Sample>();
        while (samples.size() < count) {
            var s = random(r);
            var best = Melee.utilities(s).entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            samples.add(new LearnedMelee.Sample(s, best));
        }
        return samples;
    }

    @Test
    void aModelLearnedFromDemonstrationsMakesTheDemonstratorsMoveOnSightsItNeverSaw() {
        var model = LearnedMelee.train(demonstrations(1, 6000), 40, 0.05, 1e-5);
        assertTrue(model.heldOut() > 0.85, "held out: " + model.heldOut());
        // Scored again on demonstrations drawn apart from its training.
        var fresh = demonstrations(2, 2000);
        long agree = fresh.stream().filter(s -> model.choose(s.sight()) == s.move()).count();
        assertTrue(agree > 0.85 * fresh.size(), "unseen: " + agree + " of " + fresh.size());
        // It never picks a move the player cannot make.
        var r = new Random(3);
        for (int i = 0; i < 500; i++) {
            var s = random(r);
            assertTrue(LearnedMelee.open(s).contains(model.choose(s)));
        }
        // The same demonstrations in the same order: the same model.
        assertEquals(model.version(), LearnedMelee.train(demonstrations(1, 6000), 40, 0.05, 1e-5).version());
    }

    @Test
    void aModelIsKeptAsTextUnderItsVersionAndADamagedOneIsRefused() {
        var model = LearnedMelee.train(demonstrations(4, 800), 10, 0.05, 1e-5);
        String text = LearnedMelee.write(model);
        var back = LearnedMelee.read(text);
        assertEquals(model.version(), back.version());
        var r = new Random(5);
        for (int i = 0; i < 200; i++) {
            var s = random(r);
            assertEquals(model.choose(s), back.choose(s));
        }
        // One weight changed: it no longer gives its version.
        String[] lines = text.split("\n");
        var damaged = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("STRIKE=")) line = "STRIKE=9.5" + line.substring(line.indexOf(','));
            damaged.append(line).append('\n');
        }
        assertThrows(IllegalArgumentException.class, () -> LearnedMelee.read(damaged.toString()));
        assertThrows(IllegalArgumentException.class, () -> LearnedMelee.read(text.replace("schema=1", "schema=2")));
        // A demonstration as a line of its file, and back.
        var sample = demonstrations(8, 1).getFirst();
        var again = LearnedMelee.sample(LearnedMelee.line(sample));
        assertEquals(sample.move(), again.move());
        assertEquals(sample.sight().distance(), again.sight().distance(), 1e-3);
        assertEquals(sample.sight().shield(), again.sight().shield());
        assertThrows(IllegalArgumentException.class, () -> LearnedMelee.sample(LearnedMelee.SAMPLE_HEADER));
    }

    @Test
    void withNoModelOrOneForAnotherRulesetItIsTheHandSetMelee() {
        var none = new LearnedMelee(null);
        assertFalse(none.learned());
        assertEquals("hand-set", none.version());
        var r = new Random(6);
        var handSet = new Melee();
        for (int t = 0; t < 200; t++) {
            var s = random(r);
            assertEquals(handSet.next(s, t), none.next(s, t));
        }
        var model = LearnedMelee.train(demonstrations(7, 200), 5, 0.05, 1e-5);
        var other = new LearnedMelee(new LearnedMelee.Model("1.20.4", model.trainedOn(), model.heldOut(), model.weights()));
        assertFalse(other.learned(), "trained for another ruleset: the way back");
        assertTrue(new LearnedMelee(model).learned());
        assertTrue(new LearnedMelee(model).next(random(r), 0).why().startsWith("learned (" + model.version()));
    }

    @Test
    void aDemonstratorsMoveIsReadFromWhatItDid() {
        assertEquals(Move.EAT, MoveLabels.label(5, 5, 0, false, false, true).orElseThrow());
        assertEquals(Move.STRIKE, MoveLabels.label(2.5, 2.4, 0, true, false, false).orElseThrow());
        assertEquals(Move.BLOCK, MoveLabels.label(2.5, 2.5, 0, false, true, false).orElseThrow());
        assertEquals(Move.APPROACH, MoveLabels.label(6, 5.75, 0, false, false, false).orElseThrow());
        assertEquals(Move.RETREAT, MoveLabels.label(4, 4.28, 0, false, false, false).orElseThrow());
        assertEquals(Move.BACK_OFF, MoveLabels.label(2.5, 2.6, 0, false, false, false).orElseThrow());
        assertEquals(Move.STRAFE, MoveLabels.label(2.5, 2.52, 0.2, false, false, false).orElseThrow());
        assertTrue(MoveLabels.label(2.5, 2.5, 0.01, false, false, false).isEmpty(), "standing still is no label");
    }
}
