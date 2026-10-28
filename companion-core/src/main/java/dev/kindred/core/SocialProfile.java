package dev.kindred.core;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a player's chat settings, set by its owner: its personality, whether it makes small talk on
 * its own, and whether it chats in public. Chatting never gives anyone control of the player.
 *
 * Technical: Owner-controlled social preferences. Public conversation never grants body authority.
 */
public record SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled,
                            boolean publicConversation, long revision) {
    public SocialProfile {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(personality);
        if (revision < 0) throw new IllegalArgumentException("Negative profile revision");
    }

    /** The starting chat settings. */
    public static SocialProfile defaults(UUID id) {
        return new SocialProfile(id, Personality.defaults(), true, false, 0);
    }

    /** The same settings with small talk on or off. */
    public SocialProfile withAmbient(boolean enabled) {
        return new SocialProfile(companionId, personality, enabled, publicConversation, Math.incrementExact(revision));
    }

    /** The same settings with public chat on or off. */
    public SocialProfile withPublic(boolean enabled) {
        return new SocialProfile(companionId, personality, ambientEnabled, enabled, Math.incrementExact(revision));
    }

    /** The same settings with one personality trait changed. */
    public SocialProfile withTrait(Personality.Trait trait, int value) {
        return new SocialProfile(companionId, personality.withTrait(trait, value), ambientEnabled,
                publicConversation, Math.incrementExact(revision));
    }

    /** How long between bits of small talk: more sociable players talk more often. */
    public long ambientIntervalMillis() { return 180_000L - personality.sociability() * 1_200L; }
}
