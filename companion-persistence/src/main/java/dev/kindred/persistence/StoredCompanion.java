package dev.kindred.persistence;

import dev.kindred.core.Companion;

/**
 * In plain terms: a saved AI player record together with its save counter, which goes up on every change so
 * two writers never overwrite each other.
 *
 * Technical: Storage revision is independent of player intent and task revisions.
 */
public record StoredCompanion(Companion companion, long revision) {}
