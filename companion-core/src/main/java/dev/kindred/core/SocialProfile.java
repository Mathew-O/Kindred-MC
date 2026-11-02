package dev.kindred.core;

import dev.kindred.core.social.Expression;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a player's chat settings, set by its owner: its personality, whether it makes small talk on
 * its own, whether it chats in public, and how it sounds (plain, dry or playful). Chatting never gives anyone control
 * of the player.
 *
 * Technical: Owner-controlled social preferences. Public conversation never grants body authority. The expression preset
 * (E-18a) is null until one is set: such a player sounds as it did before presets, by its humour.
 */
public record SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled,
                            boolean publicConversation, long revision, Expression expression) {
    public SocialProfile {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(personality);
        if (revision < 0) throw new IllegalArgumentException("Negative profile revision");
    }

    /** The settings from before presets: none set, so it sounds by its humour. */
    public SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled, boolean publicConversation, long revision) {
        this(companionId, personality, ambientEnabled, publicConversation, revision, null);
    }

    /** The starting chat settings. */
    public static SocialProfile defaults(UUID id) {
        return new SocialProfile(id, Personality.defaults(), true, false, 0);
    }

    /** The same settings with small talk on or off. */
    public SocialProfile withAmbient(boolean enabled) {
        return new SocialProfile(companionId, personality, enabled, publicConversation, Math.incrementExact(revision), expression);
    }

    /** The same settings with public chat on or off. */
    public SocialProfile withPublic(boolean enabled) {
        return new SocialProfile(companionId, personality, ambientEnabled, enabled, Math.incrementExact(revision), expression);
    }

    /** The same settings with one personality trait changed. */
    public SocialProfile withTrait(Personality.Trait trait, int value) {
        return new SocialProfile(companionId, personality.withTrait(trait, value), ambientEnabled,
                publicConversation, Math.incrementExact(revision), expression);
    }

    /** The same settings with a whole personality, as a starter's preset gives a player when it is made (C13). */
    public SocialProfile withPersonality(Personality next) {
        Objects.requireNonNull(next);
        var kept = new Personality(next.initiative(), next.caution(), next.curiosity(), next.sociability(), next.humor(),
                next.combativeness(), next.helpfulness(), next.farming(), next.mining(), Math.incrementExact(personality.revision()));
        return new SocialProfile(companionId, kept, ambientEnabled, publicConversation, Math.incrementExact(revision), expression);
    }

    /** The same settings with an expression preset (E-18a), set by its controller; null goes back to sounding by its humour. */
    public SocialProfile withExpression(Expression next) {
        return new SocialProfile(companionId, personality, ambientEnabled, publicConversation, Math.incrementExact(revision), next);
    }

    /** How it sounds: its preset, or, with none set, by its humour as before presets. */
    public Expression effectiveExpression() {
        return expression != null ? expression : Expression.byHumor(personality.humor());
    }

    /**
     * The same settings as they apply under the server's cap: how it sounds bounded by the cap, at the same revision, so
     * that nothing waiting to be said is dropped for it. Never saved.
     */
    public SocialProfile capped(Expression cap) {
        Expression bounded = effectiveExpression().capped(cap);
        return bounded == expression ? this : new SocialProfile(companionId, personality, ambientEnabled, publicConversation, revision, bounded);
    }

    /** Whether it sounds playful under the server's cap. */
    public boolean playful() { return effectiveExpression() == Expression.PLAYFUL; }

    /** How long between bits of small talk: more sociable players talk more often. */
    public long ambientIntervalMillis() { return 180_000L - personality.sociability() * 1_200L; }
}
