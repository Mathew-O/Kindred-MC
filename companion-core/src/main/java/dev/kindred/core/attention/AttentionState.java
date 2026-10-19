package dev.kindred.core.attention;

/**
 * AI-player attention states that redistribute computational attention based on environmental
 * danger and credible threat.
 *
 * <p>This is internal scheduling policy, not a player-facing companion/independent mode, a
 * permission toggle or a second body controller.
 */
public enum AttentionState {
    ROUTINE,
    ALERT,
    COMBAT,
    RECOVERY
}
