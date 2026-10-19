package dev.kindred.core.attention;

/**
 * Verified knowledge of credible threats.
 *
 * <p>Delayed or missing observations must be evaluated as {@link #UNKNOWN}, never silently
 * assumed {@link #CLEAR}. Unknown threat status cannot imply safety or restore Routine attention.
 */
public enum ThreatKnowledge {
    CLEAR,
    PRESENT,
    UNKNOWN
}
