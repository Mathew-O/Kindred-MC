package dev.kindred.core;

import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The daily awake cap, whether owner-offline work is allowed, the accounting timezone and, optionally, the local
 * hours activity is allowed in. Default is two hours, offline disabled, UTC, all day.
 */
public record ActivityLimit(UUID companionId, long capMillis, boolean offlineWork, String timezone, long revision,
                            Optional<Window> window) {
    public static final long DEFAULT_CAP_MILLIS = 2L * 60 * 60 * 1000;

    /** Local half-open hours in the accounting timezone; an end before the start runs overnight (F-11f). */
    public record Window(LocalTime start, LocalTime end) {
        public Window {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.equals(end)) throw new IllegalArgumentException("An activity window's start and end must differ");
        }
    }

    public ActivityLimit {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(window, "window");
        if (timezone == null || timezone.isBlank()) throw new IllegalArgumentException("Timezone is required");
        if (capMillis < 1 || capMillis > 24L * 60 * 60 * 1000) throw new IllegalArgumentException("Cap must be within 24 hours");
        if (revision < 0) throw new IllegalArgumentException("Negative limit revision");
    }

    public ActivityLimit(UUID companionId, long capMillis, boolean offlineWork, String timezone, long revision) {
        this(companionId, capMillis, offlineWork, timezone, revision, Optional.empty());
    }

    public static ActivityLimit defaults(UUID companionId) {
        return new ActivityLimit(companionId, DEFAULT_CAP_MILLIS, false, "UTC", 0);
    }

    /** The next revision with this cap; every other field kept. */
    public ActivityLimit editCap(long millis) {
        return new ActivityLimit(companionId, millis, offlineWork, timezone, Math.incrementExact(revision), window);
    }

    public ActivityLimit editOfflineWork(boolean allowed) {
        return new ActivityLimit(companionId, capMillis, allowed, timezone, Math.incrementExact(revision), window);
    }

    public ActivityLimit editTimezone(String zone) {
        return new ActivityLimit(companionId, capMillis, offlineWork, zone, Math.incrementExact(revision), window);
    }

    public ActivityLimit editWindow(Optional<Window> hours) {
        return new ActivityLimit(companionId, capMillis, offlineWork, timezone, Math.incrementExact(revision), hours);
    }
}
