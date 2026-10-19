package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** A remembered fact with a source. Conversation alone cannot create one. */
public record MemoryNote(UUID id, UUID companionId, Kind kind, String text, String provenance, long revision) {
    public enum Kind { PREFERENCE, FACT, EPISODE }

    public MemoryNote {
        Objects.requireNonNull(id);
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(kind);
        if (text == null || text.isBlank() || text.length() > 280) throw new IllegalArgumentException("Memory text must be 1..280 characters");
        if (provenance == null || provenance.isBlank() || provenance.length() > 200) throw new IllegalArgumentException("Memory needs a provenance reference");
        if (revision < 0) throw new IllegalArgumentException("Negative memory revision");
    }
}
