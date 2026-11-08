package dev.kindred.core.rescue;

import dev.kindred.core.ProposalGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: a short way out of a spot a player is stuck in, suggested by the optional adviser and checked before
 * anything happens: at most six steps, each from a short fixed list (walk, dig, pillar, place a block, wait, eat, back
 * off), each with small bounded numbers. Anything else turns the whole suggestion down.
 *
 * Technical: F-29. A rescue plan as the model's reply gives it: one to {@link ProposalGate#MAX_STEPS} steps of the fixed
 * vocabulary, parsed and bounded here, never in the model's own code. Each verb is carried out by exactly one installed
 * skill ({@link #skill}), which {@link ProposalGate} checks is installed; the adapter turns each step into that skill's
 * arguments where the player stands when the step comes up, and dispatches it as an ordinary recorded skill.
 */
public record RescuePlan(List<Step> steps, String reason) {
    /** The longest reason kept. */
    public static final int MAX_REASON = 300;

    public RescuePlan {
        steps = List.copyOf(steps);
        Objects.requireNonNull(reason);
        if (steps.isEmpty() || steps.size() > ProposalGate.MAX_STEPS) throw new IllegalArgumentException("A plan has 1.." + ProposalGate.MAX_STEPS + " steps");
    }

    /** What a step does. */
    public enum Verb {
        /** Walk that many blocks that way (1 to 8), block by block, up or down one at a time; it stops at the first obstruction (F-29c). */
        WALK,
        /** Dig that many blocks that way (1 to 4): a passage two high sideways, a shaft up or down. */
        DIG,
        /** Pillar up that many blocks (1 to 6), a block of its own placed under its feet at the top of each jump. */
        PILLAR,
        /** Place one block of its own beside its feet that way. */
        PLACE,
        /** Wait that many seconds (1 to 4). */
        WAIT,
        /** Eat what it carries. */
        EAT,
        /** Back off that many blocks that way (1 to 3). */
        RETREAT,
        /** Break a passage two high that way and walk it, block by block (1 to 6); it stops at liquid it would let in or a drop of more than two (F-29c). */
        TUNNEL,
        /** A staircase that way, up or down a block a step (1 to 6), never straight down; down stops at liquid or a drop (F-29c). */
        STAIRS,
        /** Walk that way over a gap, a block of its own placed under each step where there is none (1 to 6) (F-29c). */
        BRIDGE,
        /** Place a torch at its feet (F-29c). */
        TORCH,
        /** No action: the next round's report shows the blocks four that way, as far as it can see them (F-29c). */
        LOOK
    }

    /** A way to go: the four sides, up and down. */
    public enum Way { NORTH, SOUTH, EAST, WEST, UP, DOWN }

    /**
     * One step: its verb, its way (none for those that have none), its count (1 for those that have none), and for a
     * staircase whether it goes up or down.
     */
    public record Step(Verb verb, Way way, int count, Way rise) {
        public Step {
            Objects.requireNonNull(verb);
        }

        public Step(Verb verb, Way way, int count) {
            this(verb, way, count, null);
        }

        /** The step as the vocabulary writes it: {@code walk north 2}, {@code pillar 3}, {@code eat}. */
        public String text() {
            String name = verb.name().toLowerCase(Locale.ROOT);
            return switch (verb) {
                case WALK, DIG, RETREAT, TUNNEL, BRIDGE -> name + " " + way.name().toLowerCase(Locale.ROOT) + " " + count;
                case STAIRS -> name + " " + rise.name().toLowerCase(Locale.ROOT) + " " + way.name().toLowerCase(Locale.ROOT) + " " + count;
                case PLACE, LOOK -> name + " " + way.name().toLowerCase(Locale.ROOT);
                case PILLAR, WAIT -> name + " " + count;
                case EAT, TORCH -> name;
            };
        }
    }

    /** What a reply came to: the plan, or every reason it was refused. */
    public record Parsed(Optional<RescuePlan> plan, List<String> refusals) {
        public Parsed {
            Objects.requireNonNull(plan);
            refusals = List.copyOf(refusals);
        }
    }

    /** The vocabulary as the model is told it. */
    public static final String VOCABULARY = String.join("\n",
            "walk <north|south|east|west> <1-8>",
            "dig <north|south|east|west|up|down> <1-4>",
            "pillar <1-6>",
            "place <north|south|east|west>",
            "wait <1-4>",
            "eat",
            "retreat <north|south|east|west> <1-3>",
            "tunnel <north|south|east|west> <1-6>",
            "stairs <up|down> <north|south|east|west> <1-6>",
            "bridge <north|south|east|west> <1-6>",
            "torch",
            "look <north|south|east|west|up|down>");

    /**
     * The reply's steps and reason parsed against the vocabulary and its bounds. Every step that does not parse is
     * named; one bad step refuses the whole plan.
     */
    public static Parsed parse(List<String> steps, String reason) {
        var refusals = new ArrayList<String>();
        if (steps == null || steps.isEmpty() || steps.size() > ProposalGate.MAX_STEPS) {
            refusals.add("a plan has 1.." + ProposalGate.MAX_STEPS + " steps, not " + (steps == null ? 0 : steps.size()));
            return new Parsed(Optional.empty(), refusals);
        }
        var parsed = new ArrayList<Step>();
        for (String raw : steps) {
            String why = null;
            Step step = null;
            String[] words = raw == null ? new String[0] : raw.strip().toLowerCase(Locale.ROOT).split("\\s+");
            Verb verb = words.length == 0 ? null : verb(words[0]);
            if (verb == null) {
                why = "unknown step '" + (raw == null ? "" : raw.strip()) + "'";
            } else {
                try {
                    step = switch (verb) {
                        case WALK -> new Step(verb, side(words, 1), count(words, 2, 1, 8, 3));
                        case DIG -> new Step(verb, way(words, 1), count(words, 2, 1, 4, 3));
                        case RETREAT -> new Step(verb, side(words, 1), count(words, 2, 1, 3, 3));
                        case PILLAR -> new Step(verb, null, count(words, 1, 1, 6, 2));
                        case WAIT -> new Step(verb, null, count(words, 1, 1, 4, 2));
                        case PLACE -> {
                            if (words.length != 2) throw new IllegalArgumentException("place takes a side");
                            yield new Step(verb, side(words, 1), 1);
                        }
                        case EAT, TORCH -> {
                            if (words.length != 1) throw new IllegalArgumentException(words[0] + " takes nothing more");
                            yield new Step(verb, null, 1);
                        }
                        case TUNNEL -> new Step(verb, side(words, 1), count(words, 2, 1, 6, 3));
                        case BRIDGE -> new Step(verb, side(words, 1), count(words, 2, 1, 6, 3));
                        case STAIRS -> {
                            Way rise = way(words, 1);
                            if (rise != Way.UP && rise != Way.DOWN) throw new IllegalArgumentException("stairs go up or down, not " + words[1]);
                            yield new Step(verb, side(words, 2), count(words, 3, 1, 6, 4), rise);
                        }
                        case LOOK -> {
                            if (words.length != 2) throw new IllegalArgumentException("look takes a way");
                            yield new Step(verb, way(words, 1), 1);
                        }
                    };
                } catch (IllegalArgumentException bad) {
                    why = "'" + raw.strip() + "': " + bad.getMessage();
                }
            }
            if (why != null) refusals.add(why);
            else parsed.add(step);
        }
        if (parsed.stream().filter(step -> step.verb() == Verb.LOOK).count() > 1) refusals.add("one look a round, not "
                + parsed.stream().filter(step -> step.verb() == Verb.LOOK).count());
        if (!refusals.isEmpty()) return new Parsed(Optional.empty(), refusals);
        String why = reason == null ? "" : reason.strip().replaceAll("\\s+", " ");
        if (why.length() > MAX_REASON) why = why.substring(0, MAX_REASON);
        return new Parsed(Optional.of(new RescuePlan(parsed, why)), List.of());
    }

    /**
     * The installed skill each verb is carried out by: since F-29c the first a step's local executor gives out (a walk,
     * a tunnel's and a staircase's moves are {@code native_traverse} up or down a block and {@code native_walk} on the
     * level, a block at a time; their breaks {@code native_break};
     * a bridge's blocks {@code native_place}). A look is a moment's look that way, {@code native_wait}.
     */
    public static String skill(Verb verb) {
        return switch (verb) {
            case WALK -> "native_traverse";
            case TUNNEL, STAIRS -> "native_break";
            case BRIDGE, TORCH -> "native_place";
            case LOOK -> "native_wait";
            case DIG -> "native_break";
            case PILLAR -> "native_pillar";
            case PLACE -> "native_place";
            case WAIT -> "native_wait";
            case EAT -> "native_eat";
            case RETREAT -> "native_retreat";
        };
    }

    /** The skill of each step, in order: what {@link ProposalGate} checks. */
    public List<String> skills() {
        return steps.stream().map(step -> skill(step.verb())).toList();
    }

    /** Every installed skill a verb's executor may give out, the first of them {@link #skill}'s (F-29c). */
    public static List<String> uses(Verb verb) {
        return switch (verb) {
            case WALK -> List.of("native_traverse", "native_walk");
            case TUNNEL, STAIRS -> List.of("native_break", "native_traverse", "native_walk");
            case BRIDGE -> List.of("native_place", "native_traverse", "native_walk");
            default -> List.of(skill(verb));
        };
    }

    /** Whether the plan's only step is a look: a probe, which costs no round of its own (F-29c). */
    public boolean probeOnly() {
        return steps.stream().allMatch(step -> step.verb() == Verb.LOOK);
    }

    /** The plan as the vocabulary writes it: {@code pillar 3, walk north 2}. */
    public String text() {
        return String.join(", ", steps.stream().map(Step::text).toList());
    }

    private static Verb verb(String word) {
        for (Verb verb : Verb.values()) if (verb.name().toLowerCase(Locale.ROOT).equals(word)) return verb;
        return null;
    }

    private static Way way(String[] words, int at) {
        if (words.length <= at) throw new IllegalArgumentException("a way is missing");
        for (Way way : Way.values()) if (way.name().toLowerCase(Locale.ROOT).equals(words[at])) return way;
        throw new IllegalArgumentException("'" + words[at] + "' is not a way");
    }

    private static Way side(String[] words, int at) {
        Way way = way(words, at);
        if (way == Way.UP || way == Way.DOWN) throw new IllegalArgumentException("a side is north, south, east or west, not " + words[at]);
        return way;
    }

    private static int count(String[] words, int at, int min, int max, int length) {
        if (words.length != length) throw new IllegalArgumentException("the step's words are not as the vocabulary writes them");
        int n;
        try {
            n = Integer.parseInt(words[at]);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("'" + words[at] + "' is not a number");
        }
        if (n < min || n > max) throw new IllegalArgumentException(n + " is outside " + min + ".." + max);
        return n;
    }
}
