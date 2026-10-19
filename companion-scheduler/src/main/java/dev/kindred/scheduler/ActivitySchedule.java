package dev.kindred.scheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/** Validated configuration. Changes to zone require a new non-overlapping accounting period. */
public record ActivitySchedule(
        ZoneId zone,
        Optional<ActivityWindow> window,
        Duration dailyCap,
        Duration operatorCap,
        boolean workWhenOwnerOffline) {

    public static final Duration DEFAULT_DAILY_CAP = Duration.ofHours(2);

    public ActivitySchedule {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(window, "window");
        requirePositiveMillis(dailyCap, "dailyCap");
        requirePositiveMillis(operatorCap, "operatorCap");
        if (dailyCap.compareTo(operatorCap) > 0) {
            throw new IllegalArgumentException("Daily cap exceeds operator limit");
        }
    }

    public static ActivitySchedule defaults(ZoneId zone, Duration operatorCap) {
        return new ActivitySchedule(zone, Optional.empty(),
                DEFAULT_DAILY_CAP.compareTo(operatorCap) > 0 ? operatorCap : DEFAULT_DAILY_CAP,
                operatorCap, false);
    }

    public ActivitySchedule withDailyCap(Duration cap) {
        return new ActivitySchedule(zone, window, cap, operatorCap, workWhenOwnerOffline);
    }

    static long requirePositiveMillis(Duration value, String field) {
        Objects.requireNonNull(value, field);
        long millis = value.toMillis();
        if (value.isNegative() || millis <= 0 || !value.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException(field + " must be positive whole milliseconds");
        }
        return millis;
    }
}
