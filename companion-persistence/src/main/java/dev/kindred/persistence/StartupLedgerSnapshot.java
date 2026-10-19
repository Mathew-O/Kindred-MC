package dev.kindred.persistence;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of unresolved action ledger scans across all expected logical player identities at startup.
 */
public record StartupLedgerSnapshot(Map<UUID, LedgerScan> byLogicalPlayer) {
    public StartupLedgerSnapshot {
        Objects.requireNonNull(byLogicalPlayer, "byLogicalPlayer");
        byLogicalPlayer = Map.copyOf(byLogicalPlayer);
    }
}
