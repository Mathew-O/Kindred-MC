package dev.kindred.core.attention;

/**
 * In plain terms: what a player knows about threats: none, a credible threat, or unknown. Not knowing is
 * never treated as safe.
 *
 * Technical: Verified knowledge of credible threats.
 *
 * <p>Delayed or missing observations must be evaluated as {@link #UNKNOWN}, never silently
 * assumed {@link #CLEAR}. Unknown threat status cannot imply safety or restore Routine attention.
 */
public enum ThreatKnowledge {
    CLEAR,
    PRESENT,
    UNKNOWN
}
