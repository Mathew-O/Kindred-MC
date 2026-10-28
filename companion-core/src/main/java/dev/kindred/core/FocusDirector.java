package dev.kindred.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: decides where an older companion looks: at its owner, along its path, at someone else
 * nearby, or at a threat. It holds its gaze for a while before changing, so it looks attentive rather than
 * twitchy.
 *
 * Technical: One intentional thing to look at, held long enough to read as attention, then a deliberate
 * change. A threat or a newly noticed person can interrupt. Otherwise focus drifts between the owner and the
 * path or another nearby presence, and it stays there instead of flickering on a timer.
 */
public final class FocusDirector {
    public enum Anchor { OWNER, PATH, OTHER, THREAT }

    public record Choice(Anchor anchor, UUID subject, int dwellTicks) { }

    private Anchor anchor = Anchor.OWNER;
    private UUID subject;
    private int holdUntil = Integer.MIN_VALUE;
    private int dwellTicks = 1;
    private int drifts;
    private UUID noticed;
    private int threatQuietUntil = Integer.MIN_VALUE;

    /**
     * What to look at now, keeping the current focus until it has been held long enough unless a threat or a
     * newcomer appears.
     */
    public Choice choose(int age, boolean moving, UUID ownerId, UUID freshNotice, UUID threat,
                         List<UUID> interesting, Personality personality, Set<Mannerisms.Quirk> quirks) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(interesting, "interesting");
        Objects.requireNonNull(personality, "personality");
        Objects.requireNonNull(quirks, "quirks");
        if (freshNotice == null) noticed = null;
        if (threat != null && age >= threatQuietUntil && (anchor != Anchor.THREAT || !threat.equals(subject))) {
            return lock(Anchor.THREAT, threat, age, 25 + personality.caution() / 10);
        }
        if (anchor == Anchor.THREAT && threat == null) holdUntil = age;
        if (freshNotice != null && !freshNotice.equals(noticed) && anchor != Anchor.THREAT) {
            noticed = freshNotice;
            return lock(Anchor.OTHER, freshNotice, age, glance(personality, quirks));
        }
        if (age < holdUntil) return current();
        if (anchor == Anchor.THREAT) threatQuietUntil = age + 40;
        drifts++;
        boolean steady = quirks.contains(Mannerisms.Quirk.STEADY);
        if (moving) {
            if (anchor == Anchor.PATH) return lock(Anchor.OWNER, ownerId, age, 14 + personality.sociability() / 25);
            return lock(Anchor.PATH, null, age, steady ? 50 : 26);
        }
        boolean drift = !steady && !interesting.isEmpty() && anchor == Anchor.OWNER
                && (quirks.contains(Mannerisms.Quirk.CURIOUSGLANCE) || personality.curiosity() >= 40)
                && drifts % 2 == 0;
        if (drift) {
            UUID other = interesting.get(Math.floorMod(drifts, interesting.size()));
            return lock(Anchor.OTHER, other, age, 20 + personality.curiosity() / 5);
        }
        return lock(Anchor.OWNER, ownerId, age, 46 + (100 - personality.curiosity()) / 8);
    }

    /** Forgets the current focus, for example after a stop. */
    public void clear() {
        anchor = Anchor.OWNER;
        subject = null;
        holdUntil = Integer.MIN_VALUE;
        noticed = null;
        threatQuietUntil = Integer.MIN_VALUE;
    }

    /** Switches focus to something new and notes for how long to hold it. */
    private Choice lock(Anchor next, UUID who, int age, int dwell) {
        anchor = next;
        subject = who;
        dwellTicks = Math.max(8, dwell);
        holdUntil = age + dwellTicks;
        return current();
    }

    /** The focus being held right now. */
    private Choice current() { return new Choice(anchor, subject, dwellTicks); }

    /** How long a glance lasts, from its personality and habits. */
    private static int glance(Personality personality, Set<Mannerisms.Quirk> quirks) {
        int dwell = 16 + personality.curiosity() / 8;
        if (quirks.contains(Mannerisms.Quirk.CURIOUSGLANCE)) dwell += 8;
        return Math.min(40, dwell);
    }
}
