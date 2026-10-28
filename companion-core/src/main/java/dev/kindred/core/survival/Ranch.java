package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A ranch in Co-op Survival (M-01a): a fenced pen with a gate, a pair of animals led into it by the food they follow,
 * shut in and fed until a young one is born, and the gate shut behind the player as it leaves. One pass at a time from
 * what the player sees, so it carries on from wherever the world stands; it remembers only which animal it is leading,
 * which ones it gave up on, and how long it has waited. Pure.
 *
 * <p>The pen is a ring of fences {@code width} by {@code depth} from its north-west corner, the gate in the middle of
 * its south side. Inside is everything within the ring.
 */
public final class Ranch {
    /** The animals a pen is for, each with the food it follows and is bred with, as vanilla has them. */
    public enum Kind {
        COW("minecraft:cow", List.of("minecraft:wheat")),
        SHEEP("minecraft:sheep", List.of("minecraft:wheat")),
        PIG("minecraft:pig", List.of("minecraft:carrot", "minecraft:potato", "minecraft:beetroot")),
        CHICKEN("minecraft:chicken", List.of("minecraft:wheat_seeds", "minecraft:melon_seeds", "minecraft:pumpkin_seeds",
                "minecraft:beetroot_seeds", "minecraft:torchflower_seeds"));

        public final String entity;
        public final List<String> food;

        Kind(String entity, List<String> food) {
            this.entity = entity;
            this.food = food;
        }

        public String noun() { return name().toLowerCase(java.util.Locale.ROOT); }

        public static Optional<Kind> of(String entity) {
            for (Kind kind : values()) if (kind.entity.equals(entity)) return Optional.of(kind);
            return Optional.empty();
        }
    }

    /** One animal seen: grown, ready to breed (no cooldown, not in love), in love. */
    public record Animal(UUID id, Kind kind, Pos at, boolean adult, boolean ready, boolean inLove) { }

    /**
     * What the player sees on a pass.
     *
     * @param feet     the block its feet are in
     * @param built    every fence and the gate of the pen stand
     * @param gateOpen the pen's gate stands open
     * @param food     how many of the kind's food it carries
     * @param animals  the animals of every kind in sight round the pen
     * @param tick     the server's tick, for how long it has waited
     */
    public record Observation(Pos feet, boolean built, boolean gateOpen, int food, List<Animal> animals, long tick) { }

    /** What to do next. */
    public sealed interface Step {
        /** Build the pen: its fences and gate. */
        record Build() implements Step { }

        /** Walk toward {@code target} until within {@code range}, holding the food (or not), at most {@code leg} blocks. */
        record Go(Pos target, double range, boolean holdFood, int leg, String why) implements Step { }

        /** Open or shut the pen's gate, from within reach of it. */
        record Gate(boolean open, String why) implements Step { }

        /** Feed the animal, in reach, so it breeds. */
        record Feed(UUID animal, String why) implements Step { }

        /** Stand a second, holding the food (or not). */
        record Wait(boolean holdFood, String why) implements Step { }

        /** The ranch is done, or can go no further; the outcome in words. */
        record Done(String outcome, boolean bred) implements Step { }
    }

    /** How far it looks for animals round the pen, and leads one from. */
    public static final int RANGE = 32;
    /** Close enough that the animal follows the food; farther, it walks up to it again. */
    static final double FOLLOWING = 6;
    /** How far it walks before looking back for the animal it leads. */
    static final int LEG = 4;
    /** Behind by more than this, it waits for the animal to catch up. */
    static final double LAGGING = 4.5;
    /** Ticks it waits for a led animal to come into the pen, and for the young one, before giving up. */
    static final long COME_IN = 300, BIRTH = 600;
    /** Ticks it draws a following animal from one spot before trying the other. */
    static final long DRAW = 60;
    /** Ticks it waits for animals to move off the gate before opening it to go out anyway. */
    static final long CLEAR = 200;
    /** Times it goes back out for one animal hung on the fence beside the gate, to bring it round in line again. */
    static final int RELINES = 2;
    /** Within this of an animal it feeds it (a player reaches three blocks to an entity's box). */
    static final double FEEDING = 2.5;
    /** Within this of the gate it works it. */
    static final double AT_GATE = 3;

    enum Phase { BUILD, FETCH, SHUT, BREED, LEAVE, RETURN, DONE }

    /** How many times it brings back animals that got out as it left, before it says one got away. */
    static final int RETURNS = 3;

    private final Pos origin;
    private final int width, depth;
    private final Kind kind;
    /**
     * Where the pen is walled in (M-06a's pasture ward): the spot two out from the doorway in the wall, in line with
     * it, that a led animal is brought up to from outside; null in the open.
     */
    private final Pos approach;
    private long alignSince = -1;
    /** When it began to wait for the animal it leads to catch up; -1 while it is close enough. */
    private long catchSince = -1;
    private Phase phase = Phase.BUILD;
    private UUID leading;
    private int relined;
    private long waitingSince = -1, clearSince = -1;
    /** Whether the animal it leads has been brought round to the front of the gate. */
    private boolean lined;
    private final Set<UUID> givenUp = new HashSet<>();
    private int youngBefore = -1;
    private String outcome = "";
    private boolean bred;
    /** The animals it shut in, and the young born to them: those it checks are still in as it leaves. */
    private final Set<UUID> penned = new HashSet<>();
    private int returns;

    public Ranch(Pos origin, int width, int depth, Kind kind) {
        this(origin, width, depth, kind, null);
    }

    /** A pen walled in, its animals led in by a doorway, from {@code approach} two out from it (or null, in the open). */
    public Ranch(Pos origin, int width, int depth, Kind kind, Pos approach) {
        if (width < 5 || depth < 5 || width > 15 || depth > 15 || width % 2 == 0) {
            throw new IllegalArgumentException("A pen 5 to 15 blocks a side, an odd width so its gate is in the middle");
        }
        this.origin = origin;
        this.width = width;
        this.depth = depth;
        this.kind = kind;
        this.approach = approach;
    }

    public Kind kind() { return kind; }

    public Pos origin() { return origin; }

    public int width() { return width; }

    public int depth() { return depth; }

    /** The gate's block, in the middle of the south side. */
    public Pos gate() { return origin.add(width / 2, 0, depth - 1); }

    /** Just outside the gate, to the south. */
    public Pos outside() { return gate().add(0, 0, 1); }

    /** Out in front of the gate, a few blocks off: where a led animal is lined up with it. */
    public Pos front() { return gate().add(0, 0, 4); }

    /** Just inside the gate. */
    public Pos inner() { return gate().add(0, 0, -1); }

    /** Inside, across from the gate: where it stands for the animals to come in. */
    public Pos far() { return origin.add(width / 2, 0, 1); }

    /** Within the ring of fences. */
    public boolean inside(Pos at) {
        return at.x() > origin.x() && at.x() < origin.x() + width - 1 && at.z() > origin.z() && at.z() < origin.z() + depth - 1
                && Math.abs(at.y() - origin.y()) <= 2;
    }

    /** What it is doing, in words. */
    public String phase() {
        return switch (phase) {
            case BUILD -> "building the pen";
            case FETCH -> "leading " + kind.noun() + "s into the pen";
            case SHUT -> "shutting the gate";
            case BREED -> "breeding the " + kind.noun() + "s";
            case LEAVE -> "leaving the pen";
            case RETURN -> "bringing a " + kind.noun() + " back into the pen";
            case DONE -> outcome;
        };
    }

    public boolean done() { return phase == Phase.DONE; }

    public boolean bred() { return bred; }

    public Step next(Observation seen) {
        if (phase == Phase.DONE) return new Step.Done(outcome, bred);
        if (!seen.built()) {
            phase = Phase.BUILD;
            return new Step.Build();
        }
        if (phase == Phase.BUILD) phase = Phase.FETCH;
        var adultsIn = seen.animals().stream().filter(a -> a.kind() == kind && a.adult() && inside(a.at())).toList();
        boolean in = inside(seen.feet());
        if (phase == Phase.RETURN) {
            var out = escaped(seen);
            if (out.isEmpty()) {
                phase = Phase.SHUT;
                leading = null;
                waitingSince = -1;
            } else {
                return fetch(seen, in, (int) seen.animals().stream().filter(a -> penned.contains(a.id()) && inside(a.at())).count(),
                        a -> out.contains(a.id()));
            }
        }
        if (phase == Phase.FETCH) {
            if (adultsIn.size() >= 2) {
                phase = Phase.SHUT;
                leading = null;
                waitingSince = -1;
            } else {
                return fetch(seen, in, adultsIn.size(), Animal::adult);
            }
        }
        if (phase == Phase.SHUT) {
            if (!in) {
                // Pushed out, or the pair walked in on its own: in through the gate first.
                if (!seen.gateOpen()) return gate(seen, true, outside(), false, "Open the gate");
                // Two in from the gate: within a block of the cell just inside it, it stood in the gateway, not in the
                // pen, and walked there again and again (one brought back that walked in on its own, the ranch
                // fixture on 2026-10-24).
                return go(inner().add(0, 0, -1), 1, false, 0, "Into the pen");
            }
            // Food put away first, so the animals stop following it to the gate.
            if (seen.gateOpen()) return gate(seen, false, inner(), false, "Shut the " + kind.noun() + "s in");
            seen.animals().stream().filter(a -> a.kind() == kind && inside(a.at())).forEach(a -> penned.add(a.id()));
            // Brought back in after breeding: out again, the gate shut behind.
            if (bred) return leave(seen, outcome);
            phase = Phase.BREED;
            youngBefore = (int) seen.animals().stream().filter(a -> a.kind() == kind && !a.adult() && inside(a.at())).count();
            waitingSince = -1;
        }
        if (phase == Phase.BREED) {
            long young = seen.animals().stream().filter(a -> a.kind() == kind && !a.adult() && inside(a.at())).count();
            if (young > youngBefore) {
                bred = true;
                seen.animals().stream().filter(a -> a.kind() == kind && inside(a.at())).forEach(a -> penned.add(a.id()));
                return leave(seen, "A young " + kind.noun() + " born in the pen");
            }
            var inLove = adultsIn.stream().filter(Animal::inLove).toList();
            var ready = adultsIn.stream().filter(Animal::ready).sorted(Comparator.comparingLong(a -> a.at().distanceSquared(seen.feet()))).toList();
            if (inLove.size() >= 2) {
                if (waitingSince < 0) waitingSince = seen.tick();
                if (seen.tick() - waitingSince > BIRTH) return leave(seen, "No young one came");
                return new Step.Wait(false, "Wait for the young " + kind.noun());
            }
            if (inLove.size() + ready.size() < 2) return leave(seen, "The " + kind.noun() + "s are not ready to breed again yet");
            if (seen.food() <= 0) return leave(seen, "Out of " + foodName() + " to breed them");
            var next = ready.getFirst();
            if (Math.sqrt(next.at().distanceSquared(seen.feet())) > FEEDING) return go(next.at(), FEEDING - 0.5, true, 0, "Walk up to a " + kind.noun());
            return new Step.Feed(next.id(), "Feed a " + kind.noun() + " " + foodName());
        }
        // Leaving: out through the gate and shut it behind, the food put away so nothing follows.
        if (in) return out(seen, "Open the gate to leave", "Out of the pen");
        if (seen.gateOpen()) return gate(seen, false, outside(), false, "Shut the gate behind");
        // The gate shut, it looks back: one that slipped out as it left is brought back in, a few times at most.
        var out = escaped(seen);
        if (!out.isEmpty() && returns < RETURNS && seen.food() > 0) {
            returns++;
            phase = Phase.RETURN;
            return next(seen);
        }
        if (!out.isEmpty()) outcome = outcome + "; " + out.size() + " got out";
        phase = Phase.DONE;
        return new Step.Done(outcome, bred);
    }

    /** Those it shut in, now outside the pen within its reach. */
    private Set<UUID> escaped(Observation seen) {
        var out = new HashSet<UUID>();
        for (var a : seen.animals()) {
            if (penned.contains(a.id()) && !inside(a.at()) && a.at().distanceSquared(gate()) <= (long) RANGE * RANGE) out.add(a.id());
        }
        return out;
    }

    private Step fetch(Observation seen, boolean in, int adultsIn, java.util.function.Predicate<Animal> wanted) {
        var candidates = seen.animals().stream()
                .filter(a -> a.kind() == kind && wanted.test(a) && !inside(a.at()) && !givenUp.contains(a.id())
                        && a.at().distanceSquared(gate()) <= (long) RANGE * RANGE)
                .sorted(Comparator.comparingLong(a -> a.at().distanceSquared(seen.feet())))
                .toList();
        var lead = candidates.stream().filter(a -> a.id().equals(leading)).findFirst().or(() -> candidates.stream().findFirst());
        if (lead.isEmpty()) {
            return leave(seen, adultsIn == 1 ? "Only one " + kind.noun() + " in the pen; no other in sight" : "No " + kind.noun() + "s in sight to bring in");
        }
        if (seen.food() <= 0) return leave(seen, "No " + foodName() + " to lead " + kind.noun() + "s with");
        var animal = lead.get();
        if (!animal.id().equals(leading)) {
            leading = animal.id();
            waitingSince = -1;
            lined = false;
            relined = 0;
        }
        double apart = Math.sqrt(animal.at().distanceSquared(seen.feet()));
        if (in) lined = false;
        if (in) {
            if (apart > FOLLOWING + 4) {
                // Not following: out for it with the food put away, so the one inside stays.
                waitingSince = -1;
                return out(seen, "Open the gate", "Out for another " + kind.noun());
            }
            // Following: across the pen for it to come in. One that will not come in is left, and another fetched.
            if (waitingSince < 0) waitingSince = seen.tick();
            // One hung on the fence beside the gate, after a while drawn from inside, is brought round in line with
            // the gate again from outside, a couple of times, rather than given up: following the food it had walked
            // into the fence there and stayed (the ranch fixture, about one suite run in three).
            boolean beside = animal.at().z() == outside().z() && animal.at().x() != outside().x()
                    && Math.abs(animal.at().x() - outside().x()) <= 2;
            if (beside && seen.tick() - waitingSince > 2 * DRAW && relined < RELINES) {
                relined++;
                waitingSince = -1;
                return out(seen, "Open the gate", "Out to bring the " + kind.noun() + " round to the gate");
            }
            if (seen.tick() - waitingSince > COME_IN) {
                givenUp.add(animal.id());
                leading = null;
                waitingSince = -1;
                return new Step.Wait(false, "That " + kind.noun() + " will not come in");
            }
            if (!seen.gateOpen()) return gate(seen, true, inner(), true, "Open the gate for the " + kind.noun());
            // An animal following the food goes straight for it and can hang against the fence beside the gate; drawn
            // from across the pen, then from just inside the gate, by turns, it finds its way in.
            boolean fromGate = (seen.tick() - waitingSince) / DRAW % 2 == 1;
            Pos stand = fromGate ? inner() : far();
            return Math.sqrt(stand.distanceSquared(seen.feet())) > 1.5
                    ? go(stand, 1, true, LEG, fromGate ? "Just inside the gate to draw the " + kind.noun() + " in" : "Across the pen for the " + kind.noun() + " to follow")
                    : new Step.Wait(true, "Wait for the " + kind.noun() + " to come in");
        }
        waitingSince = -1;
        if (apart > FOLLOWING) {
            // The one already in is shut in before it walks off for the next.
            if (seen.gateOpen() && adultsIn > 0) return gate(seen, false, outside(), false, "Shut the gate on the " + kind.noun() + " inside");
            return go(animal.at(), 3, true, 0, "Walk up to a " + kind.noun() + " with " + foodName());
        }
        if (apart > LAGGING) {
            // Waited for a while, at most: an animal that stops just out of reach (in the pasture's doorway, behind the
            // player that went on through it) was waited on until the fixture ran out, 382 times. Back to it, then.
            if (catchSince < 0) catchSince = seen.tick();
            if (seen.tick() - catchSince <= 2 * DRAW) return new Step.Wait(true, "Let the " + kind.noun() + " catch up");
            // Still outside a walled pasture after that, in line with its doorway or at the wall's foot beside it (gone
            // for the food straight into the wall): back out through the doorway, to bring it up in line and through
            // again. Walked back to it across the wall, the player went on to the gate without it, again and again (the
            // cattle fixture, a cow a block beside the doorway outside).
            // One walk, all the way out: in legs, the first ended by the front of the gate, where it took the animal
            // for lined up again and went back to the gate without it.
            if (approach != null && animal.at().z() >= approach.z() - 2 && seen.feet().z() < approach.z() - 2) {
                lined = false;
                return go(approach, 1, true, 0, "Back out through the doorway for the " + kind.noun());
            }
            return go(animal.at(), 2, true, 0, "Back to the " + kind.noun() + ", which has stopped following");
        }
        catchSince = -1;
        // Walled in, from outside: up to the doorway, the animal brought up behind in line with it, then straight
        // through without stopping in it. Stopped to let it catch up there, the player stood in the doorway and the
        // animal could not pass it (the pasture fixture, 2026-10-24); led straight for the food, it walked into the wall.
        if (approach != null && seen.feet().z() >= approach.z() - 1) {
            if (Math.sqrt(approach.distanceSquared(seen.feet())) > 1.5) {
                alignSince = -1;
                return go(approach, 1, true, LEG, "Lead the " + kind.noun() + " up to the doorway");
            }
            boolean behind = Math.abs(animal.at().x() - approach.x()) <= 1 && animal.at().z() > approach.z();
            if (alignSince < 0) alignSince = seen.tick();
            if (!behind && seen.tick() - alignSince <= DRAW) return new Step.Wait(true, "Let the " + kind.noun() + " come up behind, in line with the doorway");
            // The wait is not begun again here: asked again before the walk through has run, it is the same walk. Begun
            // again, the second asking waited, and the walk was never made (the pasture fixture, 2026-10-24).
            lined = false;
            return go(front(), 1, true, 0, "Through the doorway, the " + kind.noun() + " behind");
        }
        // Round to the front of the gate first, so the animal comes up behind it in line with the gate rather than
        // along the fence, where going straight for the food walks it into the fence.
        if (!lined) {
            if (Math.sqrt(front().distanceSquared(seen.feet())) > 1.5) return go(front(), 1, true, LEG, "Lead the " + kind.noun() + " round to the front of the gate");
            lined = true;
        }
        if (!seen.gateOpen()) return gate(seen, true, outside(), true, "Open the gate");
        return go(far(), 1, true, LEG, "Lead the " + kind.noun() + " into the pen");
    }

    /**
     * Out of the pen with the food put away: the gate opened only once no animal stands just inside it (or after a
     * while), then one step out, so the gate stands open as briefly as it can.
     */
    private Step out(Observation seen, String open, String step) {
        if (!seen.gateOpen()) {
            boolean crowded = seen.animals().stream().anyMatch(a -> a.kind() == kind && inside(a.at()) && a.at().distanceSquared(inner()) <= 2);
            if (crowded && Math.sqrt(gate().distanceSquared(seen.feet())) <= AT_GATE) {
                if (clearSince < 0) clearSince = seen.tick();
                if (seen.tick() - clearSince <= CLEAR) return new Step.Wait(false, "Wait for the " + kind.noun() + "s to move off the gate");
            }
            return gate(seen, true, inner(), false, open);
        }
        clearSince = -1;
        return go(outside(), 0.6, false, 0, step);
    }

    /** Work the gate from within reach of it, else walk to {@code from} beside it first. */
    private Step gate(Observation seen, boolean open, Pos from, boolean food, String why) {
        if (Math.sqrt(gate().distanceSquared(seen.feet())) <= AT_GATE) return new Step.Gate(open, why);
        return go(from, 1, food, food ? LEG : 0, "To the gate: " + why.toLowerCase(java.util.Locale.ROOT));
    }

    private Step leave(Observation seen, String why) {
        phase = Phase.LEAVE;
        outcome = why;
        return next(seen);
    }

    private static Step go(Pos target, double range, boolean food, int leg, String why) {
        return new Step.Go(target, range, food, leg, why);
    }

    private String foodName() {
        return kind.food.getFirst().substring("minecraft:".length()).replace('_', ' ');
    }
}
