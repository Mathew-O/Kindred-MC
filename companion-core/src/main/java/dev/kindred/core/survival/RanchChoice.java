package dev.kindred.core.survival;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: when a player starts keeping animals of its own, as players do once they have a roof over their
 * heads: with its first base standing, it pens the first kind it has seen a pair of nearby and has the food to lead
 * and breed them with. Cows first (leather and beef), then sheep (wool for beds), pigs, then chickens. It never keeps
 * a kind it has not seen, and never sets out without the food.
 *
 * Technical: The ranch chosen by the player itself (M-01a, the owner's note of 2026-10-23: a ranch with animal pens
 * and breeding as part of a regular playthrough). Pure: what the player has seen and holds goes in; the kind to keep,
 * or why not yet, comes out. A kind needs two grown animals seen and {@link #FOOD} of its food in hand: enough to lead
 * them in with, and one each to breed them.
 */
public final class RanchChoice {
    /** The food a ranch sets out with: two to breed the pair, the rest only held up to lead them. */
    public static final int FOOD = 2;

    /** The kinds in the order a player keeps them. */
    private static final Ranch.Kind[] ORDER = {Ranch.Kind.COW, Ranch.Kind.SHEEP, Ranch.Kind.PIG, Ranch.Kind.CHICKEN};

    /** The ranch to start, or none yet, and why. */
    public record Decision(Optional<Ranch.Kind> kind, String why) {
        public Decision {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(why);
        }
    }

    private RanchChoice() { }

    /**
     * The ranch to start: {@code hasBase} whether its first base stands whole; {@code seen} the grown animals of each kind
     * it has seen near home; {@code food} how many of each kind's food it holds.
     */
    public static Decision next(boolean hasBase, Map<Ranch.Kind, Integer> seen, Map<Ranch.Kind, Integer> food) {
        if (!hasBase) return new Decision(Optional.empty(), "A base first, then animals of its own");
        Ranch.Kind wantingFood = null;
        for (Ranch.Kind kind : ORDER) {
            if (seen.getOrDefault(kind, 0) < 2) continue;
            if (food.getOrDefault(kind, 0) >= FOOD) {
                return new Decision(Optional.of(kind), "A pair of " + kind.noun() + "s close by and the food to breed them: a ranch");
            }
            if (wantingFood == null) wantingFood = kind;
        }
        if (wantingFood != null) {
            return new Decision(Optional.empty(), "A pair of " + wantingFood.noun() + "s close by; " + FOOD + " " + foodName(wantingFood)
                    + " first to breed them");
        }
        return new Decision(Optional.empty(), "No pair of animals seen near home to keep");
    }

    private static String foodName(Ranch.Kind kind) {
        String id = kind.food.getFirst();
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }
}
