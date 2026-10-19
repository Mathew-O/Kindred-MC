package dev.kindred.persistence;

import dev.kindred.core.Companion;

/** Storage revision is independent of player intent and task revisions. */
public record StoredCompanion(Companion companion, long revision) {}
