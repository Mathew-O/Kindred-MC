package dev.kindred.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * In plain terms: one line in a player's journal, a log of what happened to it, such as a skill finishing or
 * being blocked. It is a record for people to read, not proof that something happened in the world.
 *
 * Technical: Observed metadata event. This is never evidence that a world action succeeded.
 */
public record JournalEvent(long sequence, UUID companionId, Instant observedAt, String type, String detail) {}
