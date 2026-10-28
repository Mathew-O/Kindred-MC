package dev.kindred.persistence;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: what the action ledger said for every AI player when the server started, so the game knows
 * which players have unfinished actions to check before they may act.
 *
 * Technical: Immutable snapshot of unresolved action ledger scans across all expected logical player
 * identities at startup.
 */
public record StartupLedgerSnapshot(Map<UUID, LedgerScan> byLogicalPlayer) {
    public StartupLedgerSnapshot {
        Objects.requireNonNull(byLogicalPlayer, "byLogicalPlayer");
        byLogicalPlayer = Map.copyOf(byLogicalPlayer);
    }
}
