package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/** Owner-controlled social preferences. Public conversation never grants body authority. */
public record SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled,
                            boolean publicConversation, long revision) {
    public SocialProfile {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(personality);
        if (revision < 0) throw new IllegalArgumentException("Negative profile revision");
    }

    public static SocialProfile defaults(UUID id) {
        return new SocialProfile(id, Personality.defaults(), true, false, 0);
    }

    public SocialProfile withAmbient(boolean enabled) {
        return new SocialProfile(companionId, personality, enabled, publicConversation, Math.incrementExact(revision));
    }

    public SocialProfile withPublic(boolean enabled) {
        return new SocialProfile(companionId, personality, ambientEnabled, enabled, Math.incrementExact(revision));
    }

    public SocialProfile withTrait(Personality.Trait trait, int value) {
        return new SocialProfile(companionId, personality.withTrait(trait, value), ambientEnabled,
                publicConversation, Math.incrementExact(revision));
    }

    public long ambientIntervalMillis() { return 180_000L - personality.sociability() * 1_200L; }
}
