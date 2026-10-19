package dev.kindred.core;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-companion runtime view of its habits: the saved profile, the traits, and bounded temporary
 * accommodations made after someone's remark. Accommodations expire on their own, are never extended
 * by clock rollback or repetition, and are replaced by an explicit owner command.
 */
public final class MannerismState {
    public enum Gait { WALK, SPRINT, SPRINT_JUMP }

    /** Facts the adapter checks on the server thread immediately before choosing a gait. */
    public record MovementContext(boolean leased, boolean onGround, boolean lowClearance, boolean narrowPassage,
                                  boolean hazardousLanding, boolean carefulPayload) {
        public static MovementContext openGround(boolean leased) {
            return new MovementContext(leased, true, false, false, false, false);
        }
        public boolean permitsJump() {
            return leased && onGround && !lowClearance && !narrowPassage && !hazardousLanding && !carefulPayload;
        }
    }

    private record Accommodation(long grantedAt, long durationMillis) {
        long expiresAt() { return grantedAt + durationMillis; }
    }

    private Mannerisms profile;
    private final Map<Mannerisms.Quirk, Accommodation> accommodations = new EnumMap<>(Mannerisms.Quirk.class);
    private long latestObserved = Long.MIN_VALUE;

    public MannerismState(Mannerisms profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public Mannerisms profile() { return profile; }

    /** An owner command: the durable setting replaces any temporary promise about that quirk, even if unchanged. */
    public Mannerisms edit(Mannerisms.Quirk quirk, Mannerisms.Setting setting) {
        profile = profile.with(quirk, setting);
        accommodations.remove(quirk);
        return profile;
    }

    /** A reloaded or externally saved profile; promises about quirks whose setting changed are dropped. */
    public void replaceProfile(Mannerisms next) {
        Objects.requireNonNull(next, "next");
        if (!next.companionId().equals(profile.companionId())) throw new IllegalArgumentException("Profile belongs to another companion");
        if (next.revision() < profile.revision()) throw new IllegalArgumentException("Mannerism revision cannot move backwards");
        for (Mannerisms.Quirk quirk : Mannerisms.Quirk.values()) {
            if (next.setting(quirk) != profile.setting(quirk)) accommodations.remove(quirk);
        }
        profile = next;
    }

    /**
     * Suspend a quirk for a bounded time. Repeating inside the window keeps the earlier expiry; it does not
     * push it out. Returns false when nothing changed.
     */
    public boolean accommodate(Mannerisms.Quirk quirk, long now, long durationMillis) {
        Objects.requireNonNull(quirk, "quirk");
        if (durationMillis <= 0 || durationMillis > 30 * 60_000L) throw new IllegalArgumentException("Accommodations last 1 ms to 30 minutes");
        observe(now);
        Accommodation current = accommodations.get(quirk);
        if (current != null && !expired(current)) return false;
        accommodations.put(quirk, new Accommodation(now, durationMillis));
        return true;
    }

    /** Milliseconds until a temporary accommodation ends, if one is active. */
    public Optional<Long> accommodationRemaining(Mannerisms.Quirk quirk, long now) {
        observe(now);
        Accommodation current = accommodations.get(Objects.requireNonNull(quirk));
        if (current == null || expired(current)) return Optional.empty();
        return Optional.of(current.expiresAt() - latestObserved);
    }

    /** Quirks the body exhibits right now: the resolved profile minus unexpired accommodations. */
    public Set<Mannerisms.Quirk> active(Personality personality, long now) {
        observe(now);
        EnumSet<Mannerisms.Quirk> active = EnumSet.noneOf(Mannerisms.Quirk.class);
        active.addAll(profile.resolve(personality));
        accommodations.entrySet().removeIf(entry -> expired(entry.getValue()));
        active.removeAll(accommodations.keySet());
        return Set.copyOf(active);
    }

    /** Hard bounds are checked here every time; a habit that would be unsafe is simply not performed. */
    public Gait gait(Personality personality, long now, MovementContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.leased()) return Gait.WALK;
        if (!active(personality, now).contains(Mannerisms.Quirk.SPRINTJUMP)) return Gait.WALK;
        return context.permitsJump() ? Gait.SPRINT_JUMP : Gait.SPRINT;
    }

    /** Restore a persisted accommodation after restart without promoting it to a saved setting. */
    public void restoreAccommodation(Mannerisms.Quirk quirk, long grantedAt, long durationMillis, long now) {
        Objects.requireNonNull(quirk, "quirk");
        if (durationMillis <= 0 || durationMillis > 30 * 60_000L) throw new IllegalArgumentException("Accommodations last 1 ms to 30 minutes");
        observe(now);
        Accommodation restored = new Accommodation(grantedAt, durationMillis);
        if (!expired(restored)) accommodations.put(quirk, restored);
    }

    private void observe(long now) { if (now > latestObserved) latestObserved = now; }

    /** Expiry is judged against the latest time ever observed, so a rolled-back clock cannot revive a promise. */
    private boolean expired(Accommodation accommodation) { return latestObserved >= accommodation.expiresAt(); }
}
