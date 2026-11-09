package dev.kindred.core.survival;

import java.util.Objects;

/**
 * In plain terms: when a player takes a horse of its own, as players do once they have a roof over their heads: with
 * its first base standing and no horse yet, it tames a grown wild horse it can see. One horse is enough; a horse
 * someone tamed already is never taken.
 *
 * Technical: The horse a player keeps (M-01a). Pure: whether its base stands, whether it has a horse of its own, how
 * many grown wild (untamed) horses it can see, and whether it holds a saddle go in; whether to tame one, and why, comes
 * out. A saddle is not needed to tame: it is put on once it has one, and saddles are found, never made.
 */
public final class HorseChoice {
    /** Whether to tame a horse, and why (or why not). */
    public record Decision(boolean tame, String why) {
        public Decision {
            Objects.requireNonNull(why);
        }
    }

    private HorseChoice() { }

    public static Decision next(boolean hasBase, boolean hasHorse, int wildInSight, boolean saddleHeld) {
        if (!hasBase) return new Decision(false, "A base first, then a horse");
        if (hasHorse) return new Decision(false, "A horse of its own already");
        if (wildInSight <= 0) return new Decision(false, "No wild horse in sight to tame");
        return new Decision(true, saddleHeld ? "A wild horse in sight: tamed, then saddled with the saddle it found"
                : "A wild horse in sight: tamed; a saddle when it finds one");
    }
}
