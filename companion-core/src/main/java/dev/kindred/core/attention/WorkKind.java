package dev.kindred.core.attention;

/**
 * In plain terms: the kinds of background thinking a player does: watching for threats, checking its gear,
 * planning its goals, looking for resources, deciding what to say, and reviewing what it learned.
 *
 * Technical: Optional computational categories scheduled according to the AI-player's current attention
 * state.
 */
public enum WorkKind {
    BROAD_THREAT,
    EQUIPMENT_READINESS,
    ROUTINE_GOALS,
    RESOURCE_DISCOVERY,
    SOCIAL_DELIBERATION,
    LEARNING_REVIEW
}
