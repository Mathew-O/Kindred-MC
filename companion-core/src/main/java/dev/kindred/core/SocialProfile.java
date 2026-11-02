package dev.kindred.core;

import dev.kindred.core.social.ChatStyle;
import dev.kindred.core.social.Expression;

import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: a player's chat settings, set by its owner: its personality, whether it makes small talk on
 * its own, whether it chats in public, how it sounds (plain, dry, playful or rough) and how it types (properly, or like a
 * player in a hurry). Chatting never gives anyone control of the player.
 *
 * Technical: Owner-controlled social preferences. Public conversation never grants body authority. The expression preset
 * (E-18a) is null until one is set: such a player sounds by its personality. Its chat style (E-18b) likewise: null types
 * as its personality has it.
 */
public record SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled,
                            boolean publicConversation, long revision, Expression expression, ChatStyle chatStyle) {
    public SocialProfile {
        Objects.requireNonNull(companionId);
        Objects.requireNonNull(personality);
        if (revision < 0) throw new IllegalArgumentException("Negative profile revision");
    }

    /** The settings from before presets: none set, so it sounds by its humour. */
    public SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled, boolean publicConversation, long revision) {
        this(companionId, personality, ambientEnabled, publicConversation, revision, null, null);
    }

    /** The settings from before chat styles (E-18a): a preset or none, typing as its personality has it. */
    public SocialProfile(UUID companionId, Personality personality, boolean ambientEnabled, boolean publicConversation, long revision,
                         Expression expression) {
        this(companionId, personality, ambientEnabled, publicConversation, revision, expression, null);
    }

    /** The starting chat settings. */
    public static SocialProfile defaults(UUID id) {
        return new SocialProfile(id, Personality.defaults(), true, false, 0);
    }

    /** The same settings with small talk on or off. */
    public SocialProfile withAmbient(boolean enabled) {
        return new SocialProfile(companionId, personality, enabled, publicConversation, Math.incrementExact(revision), expression, chatStyle);
    }

    /** The same settings with public chat on or off. */
    public SocialProfile withPublic(boolean enabled) {
        return new SocialProfile(companionId, personality, ambientEnabled, enabled, Math.incrementExact(revision), expression, chatStyle);
    }

    /** The same settings with one personality trait changed. */
    public SocialProfile withTrait(Personality.Trait trait, int value) {
        return new SocialProfile(companionId, personality.withTrait(trait, value), ambientEnabled,
                publicConversation, Math.incrementExact(revision), expression, chatStyle);
    }

    /** The same settings with a whole personality, as a starter's preset gives a player when it is made (C13). */
    public SocialProfile withPersonality(Personality next) {
        Objects.requireNonNull(next);
        var kept = new Personality(next.initiative(), next.caution(), next.curiosity(), next.sociability(), next.humor(),
                next.combativeness(), next.helpfulness(), next.farming(), next.mining(), Math.incrementExact(personality.revision()));
        return new SocialProfile(companionId, kept, ambientEnabled, publicConversation, Math.incrementExact(revision), expression, chatStyle);
    }

    /** The same settings with an expression preset (E-18a), set by its controller; null goes back to sounding by its humour. */
    public SocialProfile withExpression(Expression next) {
        return new SocialProfile(companionId, personality, ambientEnabled, publicConversation, Math.incrementExact(revision), next, chatStyle);
    }

    /** The same settings with a chat style (E-18b), set by its controller; null goes back to typing as its personality has it. */
    public SocialProfile withChatStyle(ChatStyle next) {
        return new SocialProfile(companionId, personality, ambientEnabled, publicConversation, Math.incrementExact(revision), expression, next);
    }

    /** How it sounds: its preset, or, with none set, by its personality (E-18b: rough if combative as well as funny). */
    public Expression effectiveExpression() {
        return expression != null ? expression : Expression.byPersonality(personality);
    }

    /** How it types: its chat style, or, with none set, as its personality has it. */
    public ChatStyle effectiveChatStyle() {
        return chatStyle != null ? chatStyle : ChatStyle.forPlayer(personality);
    }

    /** Whether it flirts (with other AI players, and with a person who said yes to it), by its personality. */
    public boolean flirty() { return Expression.flirty(personality); }

    /**
     * The same settings as they apply under the server's cap: how it sounds bounded by the cap, at the same revision, so
     * that nothing waiting to be said is dropped for it. Never saved.
     */
    public SocialProfile capped(Expression cap) {
        Expression bounded = effectiveExpression().capped(cap);
        return bounded == expression ? this : new SocialProfile(companionId, personality, ambientEnabled, publicConversation, revision, bounded, chatStyle);
    }

    /** Whether it sounds playful (or rough, which jokes as much) under the server's cap. */
    public boolean playful() { return effectiveExpression().ordinal() >= Expression.PLAYFUL.ordinal(); }

    /** How long between bits of small talk: more sociable players talk more often. */
    public long ambientIntervalMillis() { return 180_000L - personality.sociability() * 1_200L; }
}
