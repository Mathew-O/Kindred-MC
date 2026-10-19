package dev.kindred.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Only visible, nearby facts; no live world objects or remote player information. */
public record SocialSnapshot(boolean active, boolean raining, boolean night, boolean hurt,
                             String visibleThreat, List<Contact> contacts) {
    public SocialSnapshot {
        contacts = List.copyOf(contacts);
        if (contacts.size() > 16) throw new IllegalArgumentException("Too many social contacts");
    }

    public enum Kind { OWNER, PLAYER, COMPANION }

    public record Contact(UUID id, String name, Kind kind) {
        public Contact {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
        }
    }
}
