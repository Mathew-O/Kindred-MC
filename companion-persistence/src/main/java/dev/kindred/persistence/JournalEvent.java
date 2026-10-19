package dev.kindred.persistence;

import java.time.Instant;
import java.util.UUID;

/** Observed metadata event. This is never evidence that a world action succeeded. */
public record JournalEvent(long sequence, UUID companionId, Instant observedAt, String type, String detail) {}
