package dev.kindred.core.attention;

import java.util.Map;

/**
 * In plain terms: the counters that show how the background thinking was shared out: slots started, skipped
 * and over time, for checking performance.
 *
 * Technical: Immutable snapshot of attention scheduler metrics and diagnostic counters.
 */
public record SchedulerDiagnostics(
        long totalSlicesStarted,
        long totalSlicesCompleted,
        long totalSlicesAbandoned,
        long totalDetailedScans,
        long staleTokenRejections,
        long overruns,
        long softLimitExceededEvents,
        long totalOptionalNanos,
        Map<WorkKind, Long> slicesByKind,
        Map<WorkKind, Long> invalidationsByKind,
        Map<WorkKind, Long> overrunsByKind) {

    public SchedulerDiagnostics {
        slicesByKind = Map.copyOf(slicesByKind);
        invalidationsByKind = Map.copyOf(invalidationsByKind);
        overrunsByKind = Map.copyOf(overrunsByKind);
    }
}
