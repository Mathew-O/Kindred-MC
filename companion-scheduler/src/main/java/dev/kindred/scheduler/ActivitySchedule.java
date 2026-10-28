package dev.kindred.scheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: the settings for how much an AI player may play: its timezone, an optional window of hours,
 * its daily cap and the server's highest allowed cap.
 *
 * Technical: Validated configuration. Changes to zone require a new non-overlapping accounting period.
 */
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

    /**
     * The starting settings: two hours a day (or less if the server allows less), all day, no offline work.
     */
    public static ActivitySchedule defaults(ZoneId zone, Duration operatorCap) {
        return new ActivitySchedule(zone, Optional.empty(),
                DEFAULT_DAILY_CAP.compareTo(operatorCap) > 0 ? operatorCap : DEFAULT_DAILY_CAP,
                operatorCap, false);
    }

    /** The same settings with a different daily cap. */
    public ActivitySchedule withDailyCap(Duration cap) {
        return new ActivitySchedule(zone, window, cap, operatorCap, workWhenOwnerOffline);
    }

    /** Checks a length of time is positive and whole milliseconds, and returns it in milliseconds. */
    static long requirePositiveMillis(Duration value, String field) {
        Objects.requireNonNull(value, field);
        long millis = value.toMillis();
        if (value.isNegative() || millis <= 0 || !value.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException(field + " must be positive whole milliseconds");
        }
        return millis;
    }
}
