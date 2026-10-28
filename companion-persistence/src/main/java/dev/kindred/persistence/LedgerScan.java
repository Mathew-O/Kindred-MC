package dev.kindred.persistence;

import java.util.List;
import java.util.Objects;

/**
 * In plain terms: the result of checking one player's action ledger for actions that never finished: how
 * many, and a few examples.
 *
 * Technical: Result of scanning the action ledger for unresolved entries belonging to one logical identity.
 */
public record LedgerScan(long unresolvedCount, List<ActionRef> sample) {
    public LedgerScan {
        if (unresolvedCount < 0) {
            throw new IllegalArgumentException("unresolvedCount must be non-negative: " + unresolvedCount);
        }
        Objects.requireNonNull(sample, "sample");
        sample = List.copyOf(sample);
    }
}
