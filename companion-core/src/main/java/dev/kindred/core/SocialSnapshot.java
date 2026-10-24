package dev.kindred.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Only visible, nearby facts; no live world objects or remote player information. {@code harm} is what hurt it, as the
 * game recorded it, when it was hurt; null when it was not, or nothing is known.
 */
public record SocialSnapshot(boolean active, boolean raining, boolean night, boolean hurt,
                             String visibleThreat, List<Contact> contacts, Harm harm) {
    public SocialSnapshot {
        contacts = List.copyOf(contacts);
        if (contacts.size() > 16) throw new IllegalArgumentException("Too many social contacts");
    }

    public SocialSnapshot(boolean active, boolean raining, boolean night, boolean hurt, String visibleThreat, List<Contact> contacts) {
        this(active, raining, night, hurt, visibleThreat, contacts, null);
    }

    public enum Kind { OWNER, PLAYER, COMPANION }

    public record Contact(UUID id, String name, Kind kind) {
        public Contact {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
        }
    }

    /**
     * What hurt it: a player who struck it, or whose arrow did; a mob; or the world (a fall, fire, lava, drowning, a
     * cactus). Only the game's own record of the damage says which: a person nearby is never taken for the cause.
     */
    public record Harm(Source source, String name) {
        public enum Source { PLAYER, MOB, WORLD }

        public Harm {
            Objects.requireNonNull(source);
            Objects.requireNonNull(name);
        }
    }
}
