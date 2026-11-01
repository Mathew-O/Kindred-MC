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
        /** Walk that many blocks that way (1 to 8), round what is in the way. */
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
        RETREAT
    }

    /** A way to go: the four sides, up and down. */
    public enum Way { NORTH, SOUTH, EAST, WEST, UP, DOWN }

    /** One step: its verb, its way (none for those that have none), and its count (1 for those that have none). */
    public record Step(Verb verb, Way way, int count) {
        public Step {
            Objects.requireNonNull(verb);
        }

        /** The step as the vocabulary writes it: {@code walk north 2}, {@code pillar 3}, {@code eat}. */
        public String text() {
            String name = verb.name().toLowerCase(Locale.ROOT);
            return switch (verb) {
                case WALK, DIG, RETREAT -> name + " " + way.name().toLowerCase(Locale.ROOT) + " " + count;
                case PLACE -> name + " " + way.name().toLowerCase(Locale.ROOT);
                case PILLAR, WAIT -> name + " " + count;
                case EAT -> name;
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
            "retreat <north|south|east|west> <1-3>");

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
                        case EAT -> {
                            if (words.length != 1) throw new IllegalArgumentException(words[0] + " takes nothing more");
                            yield new Step(verb, null, 1);
                        }
                    };
                } catch (IllegalArgumentException bad) {
                    why = "'" + raw.strip() + "': " + bad.getMessage();
                }
            }
            if (why != null) refusals.add(why);
            else parsed.add(step);
        }
        if (!refusals.isEmpty()) return new Parsed(Optional.empty(), refusals);
        String why = reason == null ? "" : reason.strip().replaceAll("\\s+", " ");
        if (why.length() > MAX_REASON) why = why.substring(0, MAX_REASON);
        return new Parsed(Optional.of(new RescuePlan(parsed, why)), List.of());
    }

    /** The installed skill each verb is carried out by. */
    public static String skill(Verb verb) {
        return switch (verb) {
            case WALK -> "native_goto";
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
