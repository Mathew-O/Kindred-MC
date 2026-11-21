package dev.kindred.core.knowledge;

import dev.kindred.core.knowledge.KnowledgeWeb.Relation;
import dev.kindred.core.knowledge.KnowledgeWeb.Verb;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Visibility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: the knowledge web finds the fact a new one replaces without looking through every fact it holds, and
 * holds exactly what it held before that change, fact for fact, whatever it is told.
 *
 * Technical: F-32d. The same random changes (adds that replace and do not, confirmations, ends, corrections; ticks
 * mostly rising, sometimes back) made to the indexed web and to {@link KnowledgeWebBefore}, main's code before the index,
 * compared relation for relation (every field) throughout, past the bound and below it; then 80,000 adds in time linear
 * in their number, where the code before took 176 s.
 */
class KnowledgeWebIndexTest {
    private static final Verb[] VERBS = Verb.values();
    private static final HowKnown[] HOW = HowKnown.values();
    private static final Visibility[] VISIBILITY = {Visibility.PUBLIC, Visibility.TEAM, Visibility.FACTION, Visibility.SELF};

    @Test
    void theIndexedWebHoldsExactlyWhatTheWebBeforeItHeld() {
        for (int seed = 1; seed <= 12; seed++) {
            // Bounds of 300 and 3,000 reach the bound with few facts; a bound past every add never does.
            int max = seed % 3 == 0 ? 3_000 : seed % 3 == 1 ? 300 : 1_000_000;
            var rnd = new Random(seed);
            var after = new KnowledgeWeb(null, max);
            var before = new KnowledgeWebBefore(max);
            long tick = 0;
            var ids = new ArrayList<Long>();
            for (int op = 0; op < 6_000; op++) {
                // Mostly forward, a game day at most; one change in twenty a little back (events read late).
                tick += rnd.nextInt(20) == 0 ? -rnd.nextInt(2_000) : rnd.nextInt(3_000);
                tick = Math.max(0, tick);
                int kind = rnd.nextInt(100);
                if (kind < 80) {
                    String subject = "player:" + rnd.nextInt(6);
                    Verb verb = VERBS[rnd.nextInt(VERBS.length)];
                    // A small pool of objects, so the same fact comes again; now and then none.
                    String object = rnd.nextInt(50) == 0 ? null : "item:" + rnd.nextInt(8);
                    HowKnown how = HOW[rnd.nextInt(HOW.length)];
                    Visibility visibility = VISIBILITY[rnd.nextInt(VISIBILITY.length)];
                    String group = rnd.nextInt(10) == 0 ? null : "g" + rnd.nextInt(3);
                    String value = Integer.toString(rnd.nextInt(64));
                    var events = List.of((long) op);
                    var a = after.add(subject, verb, object, value, how, events, tick, visibility, group);
                    var b = before.add(subject, verb, object, value, how, events, tick, visibility, group);
                    assertEquals(b, a, "seed " + seed + ", change " + op + ": the fact added");
                    ids.add(a.id());
                } else if (kind < 88 && !ids.isEmpty()) {
                    long id = ids.get(rnd.nextInt(ids.size()));
                    assertEquals(before.confirm(id, 1_000_000L + op), after.confirm(id, 1_000_000L + op), "seed " + seed + ", change " + op);
                } else if (kind < 95) {
                    String subject = "player:" + rnd.nextInt(6);
                    Verb verb = VERBS[rnd.nextInt(VERBS.length)];
                    java.util.function.Predicate<Relation> which = r -> r.subject().equals(subject) && r.verb() == verb;
                    assertEquals(before.end(which, tick), after.end(which, tick), "seed " + seed + ", change " + op);
                } else if (!ids.isEmpty()) {
                    long id = ids.get(rnd.nextInt(ids.size()));
                    assertEquals(before.correct(id, 2_000_000L + op, tick), after.correct(id, 2_000_000L + op, tick), "seed " + seed + ", change " + op);
                }
                if (op % 500 == 0) assertEquals(before.all(), List.copyOf(after.all()), "seed " + seed + ", change " + op);
            }
            assertEquals(before.all(), List.copyOf(after.all()), "seed " + seed + ": every relation, in order");
            long kept = before.all().stream().filter(r -> r.closedBy() != null || r.until() == Long.MAX_VALUE).count();
            assertEquals(kept, after.keptForGood(), "seed " + seed + ": the facts the bound can never drop");
            if (max == 300) assertTrue(after.size() > 300, "seed " + seed + ": past the bound, by the facts it keeps for good");
        }
    }

    /** A month's told facts and more, at the real bound: none lapses, so the bound drops none (the row's question). */
    @Test
    void eightyThousandAddsTakeTimeLinearInTheirNumber() {
        added(20_000); // Warm: compiled before it is timed.
        long twenty = best(20_000), eighty = best(80_000);
        System.out.printf("F-32d: 20,000 adds %d ms, 80,000 adds %d ms (best of three)%n", twenty / 1_000_000, eighty / 1_000_000);
        // Linear is four times as long; the code before took sixteen (7.7 s, then 176 s). Eight allows for noise.
        assertTrue(eighty < 8 * Math.max(twenty, 20_000_000L), "80,000 adds " + eighty / 1_000_000 + " ms, 20,000 " + twenty / 1_000_000 + " ms");
        var web = added(80_000);
        // The 800 told again end the 800 before them, which then lapse and are the only ones the bound drops: told facts
        // never lapse, so 79,200 stay, four times the bound of 20,000.
        assertEquals(79_200, web.size(), "the bound drops only the 800 replaced");
        assertEquals(79_200, web.keptForGood());
    }

    private static long best(int n) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t = System.nanoTime();
            added(n);
            best = Math.min(best, System.nanoTime() - t);
        }
        return best;
    }

    /** {@code n} told facts, each its own, with every hundredth told again (a replacement found by the index). */
    private static KnowledgeWeb added(int n) {
        var web = new KnowledgeWeb(java.util.UUID.randomUUID());
        for (int i = 0; i < n; i++) {
            int fact = i % 100 == 99 ? i - 50 : i;
            web.add("player:" + (fact % 8), Verb.HAS_SPARE, "item:" + fact, "1", HowKnown.TOLD, List.of((long) i), i, Visibility.TEAM, "oak");
        }
        return web;
    }
}
