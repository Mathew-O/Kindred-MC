package dev.kindred.core.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Advice for an AI player's standing goal from an optional model strategist (F-19b): a stance the local route keeps
 * for a while, places to keep away from, and a note for the player's own memory. Advice is never an order: the
 * planner and the body's rules still decide every step, and the advice only shifts how careful they are. Places
 * must lie near something the player itself reported (a death, a danger zone), so a model cannot point the player
 * at hidden resources or anywhere it has not been. Pure and immutable.
 *
 * @param stance how careful the route is for a while
 * @param avoid  places to keep away from, at most {@link #MAX_AVOID}
 * @param note   a short note the player keeps in its memory for this world, possibly empty
 * @param reason why, in a sentence, for the log and the memory
 */
public record StrategyAdvice(Stance stance, List<Place> avoid, String note, String reason) {
    /** How careful the route is. */
    public enum Stance {
        /** Carry on with the route as the local planner sees fit. */
        PRESS_ON,
        /** No caving; hole up and back off sooner; leave fights it is not sure to win. */
        PLAY_SAFE,
        /** Get to the surface, out of danger, and heal before the next job below ground. */
        SURFACE_AND_RECOVER
    }

    public record Place(int x, int y, int z) {
        double distanceTo(Place other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static final int MAX_AVOID = 3;
    public static final int MAX_TEXT = 240;
    /** An avoided place must lie this close to a place the player reported. */
    public static final int REPORTED_RADIUS = 24;

    public StrategyAdvice {
        Objects.requireNonNull(stance);
        avoid = List.copyOf(avoid);
        note = Objects.requireNonNull(note);
        reason = Objects.requireNonNull(reason);
    }

    /**
     * Advice from a model's raw reply, or nothing when the reply does not name a known stance. Places not near any
     * of {@code reported} are dropped, and text is trimmed to {@link #MAX_TEXT} plain characters.
     */
    public static Optional<StrategyAdvice> validated(String stance, List<Place> avoid, String note, String reason, List<Place> reported) {
        if (stance == null) return Optional.empty();
        Stance parsed;
        try {
            parsed = Stance.valueOf(stance.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        var kept = new ArrayList<Place>();
        if (avoid != null) {
            for (Place place : avoid) {
                if (place == null || kept.size() >= MAX_AVOID) continue;
                if (reported.stream().anyMatch(known -> known.distanceTo(place) <= REPORTED_RADIUS)) kept.add(place);
            }
        }
        return Optional.of(new StrategyAdvice(parsed, kept, plain(note), plain(reason)));
    }

    /** Printable text on one line, at most {@link #MAX_TEXT} characters. */
    static String plain(String text) {
        if (text == null) return "";
        var out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().trim();
    }
}
