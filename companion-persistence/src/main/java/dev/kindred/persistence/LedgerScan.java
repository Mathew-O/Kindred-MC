package dev.kindred.persistence;

import java.util.List;
import java.util.Objects;

/**
 * Result of scanning the action ledger for unresolved entries belonging to one logical identity.
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
