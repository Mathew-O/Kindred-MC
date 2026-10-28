package dev.kindred.core;

/**
 * In plain terms: a player's mood towards one other player, which rises or falls after something happens
 * between them and drifts back to normal on its own. The lasting friendship score is kept elsewhere and never
 * fades.
 *
 * Technical: A per-peer mood that naturalizes. Tone moves with each applied relationship delta and decays
 * back toward zero on its own; the durable friendship/trust record never decays and is never written here.
 * Reading tone has no side effects. Clock rollback neither re-sours nor extends a tone.
 */
public record ToneDrift(double offset, long at, long latestObserved) {
    public static final int POLICY_VERSION = 1;
    public static final double MAX_OFFSET = 30;
    public static final double INCIDENT_SCALE = 2.5;
    public static final long NEGATIVE_HALF_LIFE_MILLIS = 30 * 60_000L;
    public static final long POSITIVE_HALF_LIFE_MILLIS = 20 * 60_000L;
    public static final long HARD_EXPIRY_MILLIS = 4 * 60 * 60_000L;
    public static final double SILENT_BELOW = 1;

    /** Relationship bands; the band comes only from the durable record, never from tone. */
    public enum Band {
        DISTANT(0, 19, -30, -10), WARY(20, 39, -20, 0), NEUTRAL(40, 59, -12, 12), FRIENDLY(60, 79, 0, 20), CLOSE(80, 100, 10, 30);

        public final int lowScore;
        public final int highScore;
        /** The warmth range tone may move within; it cannot push a peer into another band's envelope. */
        public final int minWarmth;
        public final int maxWarmth;

        Band(int lowScore, int highScore, int minWarmth, int maxWarmth) {
            this.lowScore = lowScore;
            this.highScore = highScore;
            this.minWarmth = minWarmth;
            this.maxWarmth = maxWarmth;
        }

        public static Band of(int friendship) {
            if (friendship < 0 || friendship > 100) throw new IllegalArgumentException("Friendship must be 0..100");
            for (Band band : values()) if (friendship >= band.lowScore && friendship <= band.highScore) return band;
            throw new IllegalStateException();
        }

        public int baseline() { return (minWarmth + maxWarmth) / 2; }
    }

    /** How the next reply should sound; dialogue selects wording from this, nothing else changes. */
    public enum Mood { CURT, COOL, ORDINARY, WARM, PLEASED }

    public ToneDrift {
        if (!Double.isFinite(offset) || Math.abs(offset) > MAX_OFFSET) throw new IllegalArgumentException("Tone offset must be within ±" + MAX_OFFSET);
        if (latestObserved < at) throw new IllegalArgumentException("Latest observation cannot precede the tone timestamp");
    }

    public static ToneDrift neutral(long now) { return new ToneDrift(0, now, now); }

    /** Apply the friendship delta the relationship policy actually applied (zero for deduplicated repeats). */
    public ToneDrift afterIncident(int appliedFriendshipDelta, long now) {
        if (appliedFriendshipDelta == 0) return observe(now);
        long clock = Math.max(now, latestObserved);
        double current = current(clock);
        double next = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, current + INCIDENT_SCALE * appliedFriendshipDelta));
        return new ToneDrift(next, clock, clock);
    }

    /** A correction that removes the source incident removes the tone it produced. */
    public ToneDrift cleared(long now) {
        long clock = Math.max(now, latestObserved);
        return new ToneDrift(0, clock, clock);
    }

    /** Advance the rollback guard without changing the stored tone. */
    public ToneDrift observe(long now) {
        return now > latestObserved ? new ToneDrift(offset, at, now) : this;
    }

    /** The tone right now; a rolled-back clock reads as if no time has passed since the latest observation. */
    public double current(long now) {
        long clock = Math.max(now, latestObserved);
        long elapsed = clock - at;
        if (elapsed >= HARD_EXPIRY_MILLIS || offset == 0) return 0;
        long halfLife = offset < 0 ? NEGATIVE_HALF_LIFE_MILLIS : POSITIVE_HALF_LIFE_MILLIS;
        double decayed = offset * Math.pow(0.5, (double) elapsed / halfLife);
        return Math.abs(decayed) < SILENT_BELOW ? 0 : decayed;
    }

    /** Baseline from the durable friendship score plus tone, clamped to that band's envelope. */
    public static int effectiveWarmth(int friendship, double tone) {
        Band band = Band.of(friendship);
        long warmth = Math.round(band.baseline() + tone);
        return (int) Math.max(band.minWarmth, Math.min(band.maxWarmth, warmth));
    }

    public static Mood mood(int warmth) {
        if (warmth <= -20) return Mood.CURT;
        if (warmth < -5) return Mood.COOL;
        if (warmth <= 5) return Mood.ORDINARY;
        if (warmth < 20) return Mood.WARM;
        return Mood.PLEASED;
    }

    /** Milliseconds until the tone is treated as zero, for inspection text such as "eases over the next hour". */
    public long millisUntilSilent(long now) {
        long clock = Math.max(now, latestObserved);
        if (current(clock) == 0) return 0;
        long halfLife = offset < 0 ? NEGATIVE_HALF_LIFE_MILLIS : POSITIVE_HALF_LIFE_MILLIS;
        double halvings = Math.log(Math.abs(offset) / SILENT_BELOW) / Math.log(2);
        long silentAt = at + Math.min(HARD_EXPIRY_MILLIS, Math.round(halvings * halfLife));
        return Math.max(0, silentAt - clock);
    }
}
