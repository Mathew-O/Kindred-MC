package dev.kindred.scheduler;

import java.time.LocalTime;
import java.util.Objects;

/** Local half-open activity window. No window, rather than equal endpoints, means all day. */
public record ActivityWindow(LocalTime start, LocalTime end) {
    public ActivityWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.equals(end)) {
            throw new IllegalArgumentException("Activity window endpoints must differ");
        }
    }

    public boolean overnight() {
        return start.isAfter(end);
    }
}
