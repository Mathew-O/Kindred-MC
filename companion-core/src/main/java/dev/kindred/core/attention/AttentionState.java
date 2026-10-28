package dev.kindred.core.attention;

/**
 * In plain terms: a player's attention mood: routine (all calm), alert (something might be wrong), combat
 * (fighting) or recovery (getting back to normal). The mood decides what the brain spends its thinking time
 * on; it is not a setting and gives no permissions.
 *
 * Technical: AI-player attention states that redistribute computational attention based on environmental
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
