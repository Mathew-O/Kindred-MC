package dev.kindred.persistence;

import dev.kindred.core.ActionRecord;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a short pointer to one entry in the action ledger (the notebook of world actions), used in
 * reports about what is still unsettled.
 *
 * Technical: Diagnostic reference to an action ledger entry.
 */
public record ActionRef(UUID actionId, ActionRecord.State state, long revision) {
    public ActionRef {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(state, "state");
    }
}
