package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** The daily awake cap and whether owner-offline work is allowed. Default is two hours, offline disabled. */
public record ActivityLimit(UUID companionId, long capMillis, boolean offlineWork, String timezone, long revision) {
    public static final long DEFAULT_CAP_MILLIS = 2L * 60 * 60 * 1000;

    public ActivityLimit {
        Objects.requireNonNull(companionId);
        if (timezone == null || timezone.isBlank()) throw new IllegalArgumentException("Timezone is required");
        if (capMillis < 1 || capMillis > 24L * 60 * 60 * 1000) throw new IllegalArgumentException("Cap must be within 24 hours");
        if (revision < 0) throw new IllegalArgumentException("Negative limit revision");
    }

    public static ActivityLimit defaults(UUID companionId) {
        return new ActivityLimit(companionId, DEFAULT_CAP_MILLIS, false, "UTC", 0);
    }
}
