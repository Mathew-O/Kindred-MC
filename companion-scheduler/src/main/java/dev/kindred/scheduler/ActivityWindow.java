package dev.kindred.scheduler;

import java.time.LocalTime;
import java.util.Objects;

/**
 * In plain terms: a window of hours in the day, like 18:00 to 23:00, when a player may be active. An end
 * before the start (22:00 to 02:00) runs past midnight.
 *
 * Technical: Local half-open activity window. No window, rather than equal endpoints, means all day.
 */
public record ActivityWindow(LocalTime start, LocalTime end) {
    public ActivityWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new IllegalArgumentException("Activity window endpoints must differ");
        }
    }

    /** Whether the window runs past midnight. */
    public boolean overnight() {
        return start.isAfter(end);
    }
}
