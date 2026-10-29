package dev.kindred.core.guide;

import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: something a player wants a certain amount of for one of its larger tasks, and why, from the
 * task's own needs or from an optional AI adviser. A want never gives anything; it only says what to gather
 * or craft.
 *
 * Technical: At least {@code count} of a need in hand, for an objective, and why. From the objective's own
 * requirements or from the optional strategist; a want never gives anything, it only says what to gather or
 * craft.
 */
public record Want(Need need, int count, Objective objective, String why) {
    public static final int MAX_WHY = 120;

    public Want {
        Objects.requireNonNull(need);
        Objects.requireNonNull(objective);
        Objects.requireNonNull(why);
        if (count < 1 || count > need.cap()) throw new IllegalArgumentException(need + " count out of bounds: " + count);
    }

    /**
     * A want from a model's raw reply, or nothing when it names no known need or objective. The count is held to
     * one through the need's cap, and the reason to {@link #MAX_WHY} plain characters on one line.
     */
    public static Optional<Want> parse(String need, long count, String objective, String why) {
        Optional<Need> n = Need.parse(need);
        Optional<Objective> o = Objective.parse(objective);
        if (n.isEmpty() || o.isEmpty()) return Optional.empty();
        int held = (int) Math.max(1, Math.min(n.get().cap(), count));
        return Optional.of(new Want(n.get(), held, o.get(), plain(why)));
    }

    /** Text on one line with control characters taken out, cut to the longest a reason may be. */
    static String plain(String text) {
        if (text == null) return "";
        var out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < MAX_WHY; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }
}
