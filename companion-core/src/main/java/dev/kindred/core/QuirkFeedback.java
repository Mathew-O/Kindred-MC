package dev.kindred.core;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a companion says and does when someone comments on one of its habits. The decision is a
 * deterministic function of its own traits, never of how insistent the speaker is. Only an owner
 * command edits the saved setting; chat can earn at most a bounded temporary accommodation.
 */
public final class QuirkFeedback {
    public static final int POLICY_VERSION = 1;
    public static final double OWNER_THRESHOLD = 55;
    public static final double PLAYER_THRESHOLD = 70;
    public static final long OWNER_ACCOMMODATION_MILLIS = 30 * 60_000L;
    public static final long PLAYER_ACCOMMODATION_MILLIS = 5 * 60_000L;
    /** A speaker earns at most one accommodation per window; repeating inside it changes nothing. */
    public static final long SPEAKER_WINDOW_MILLIS = 30 * 60_000L;

    public enum Speaker { OWNER, PLAYER, COMPANION, MODEL }

    /** NONE means the companion answered but nothing about its behavior changes. */
    public enum Decision { KEEP, ACCOMMODATE, NONE }

    public record Response(Mannerisms.Quirk quirk, Decision decision, long accommodationMillis, String reply) {
        public Response {
            Objects.requireNonNull(quirk);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(reply);
            if ((decision == Decision.ACCOMMODATE) != (accommodationMillis > 0)) {
                throw new IllegalArgumentException("Only an accommodation carries a duration");
            }
        }
    }

    private static final Pattern CUE = Pattern.compile(
            "\\b(hunger|hungry|food|wast\\w*|optimal|efficien\\w*|annoying|stop|quit|why|please|don'?t|knock it off|"
            + "calm down|settle|cut it out|weird|creepy|too much|so much|all the time|everywhere|constantly)\\b");
    private static final Pattern SPRINTJUMP = Pattern.compile("\\b(sprint[- ]?jump\\w*|jump\\w*|hop\\w*|bunny[- ]?hop\\w*|bouncing around)\\b");
    private static final Pattern LIVELY = Pattern.compile("\\b(fidget\\w*|bounc\\w*|wiggl\\w*|restless|hyper|sit still|hold still|stop moving|twitch\\w*)\\b");
    private static final Pattern CURIOUSGLANCE = Pattern.compile("\\b(star(e|es|ing)( at me)?|looking at me|watching me|gawk\\w*|eyeball\\w*)\\b");
    private static final Pattern STEADY = Pattern.compile("\\b(boring|loosen up|lighten up|so stiff|too stiff|so serious|too serious|robotic)\\b");

    private QuirkFeedback() { }

    /** Detects a remark about a habit in an already name-addressed message; anything else is not feedback. */
    public static Optional<Mannerisms.Quirk> detect(String addressedMessage) {
        if (addressedMessage == null || addressedMessage.isBlank() || addressedMessage.length() > 256) return Optional.empty();
        String message = addressedMessage.strip().toLowerCase(Locale.ROOT);
        boolean cue = CUE.matcher(message).find();
        if (LIVELY.matcher(message).find() && (cue || message.contains("still"))) return Optional.of(Mannerisms.Quirk.LIVELY);
        if (CURIOUSGLANCE.matcher(message).find()) return Optional.of(Mannerisms.Quirk.CURIOUSGLANCE);
        if (STEADY.matcher(message).find()) return Optional.of(Mannerisms.Quirk.STEADY);
        if (SPRINTJUMP.matcher(message).find() && (cue || message.contains("jumping") || message.contains("hopping"))) {
            return Optional.of(Mannerisms.Quirk.SPRINTJUMP);
        }
        return Optional.empty();
    }

    /** 0..100; higher means more willing to go along with a suggestion about its own habits. */
    public static double accommodation(Personality personality) {
        Objects.requireNonNull(personality, "personality");
        return 0.5 * personality.helpfulness() + 0.3 * personality.caution() + 0.2 * (100 - personality.initiative());
    }

    public static Response respond(Mannerisms.Quirk quirk, Personality personality, Speaker speaker, boolean currentlyActive) {
        Objects.requireNonNull(quirk, "quirk");
        Objects.requireNonNull(personality, "personality");
        Objects.requireNonNull(speaker, "speaker");
        boolean witty = personality.humor() >= 50;
        if (!currentlyActive) {
            return new Response(quirk, Decision.NONE, 0, witty
                    ? "I'm not even doing that right now. You're thinking of a different " + noun(quirk) + "."
                    : "I'm not doing that at the moment.");
        }
        Decision decision = switch (speaker) {
            case COMPANION, MODEL -> Decision.NONE;
            case OWNER -> accommodation(personality) >= OWNER_THRESHOLD ? Decision.ACCOMMODATE : Decision.KEEP;
            case PLAYER -> accommodation(personality) >= PLAYER_THRESHOLD ? Decision.ACCOMMODATE : Decision.KEEP;
        };
        long duration = switch (decision) {
            case ACCOMMODATE -> speaker == Speaker.OWNER ? OWNER_ACCOMMODATION_MILLIS : PLAYER_ACCOMMODATION_MILLIS;
            default -> 0;
        };
        return new Response(quirk, decision, duration, wording(quirk, decision, speaker, witty));
    }

    private static String wording(Mannerisms.Quirk quirk, Decision decision, Speaker speaker, boolean witty) {
        String control = "/companion quirk " + quirk.commandName() + " off";
        return switch (decision) {
            case ACCOMMODATE -> witty
                    ? switch (quirk) {
                        case SPRINTJUMP -> "Fine, fine. Walking. For a bit. No promises after lunch.";
                        case LIVELY -> "Okay, okay. Statue mode. Briefly.";
                        case CURIOUSGLANCE -> "Noted. I'll keep my eyes to myself for a while.";
                        case STEADY -> "A little bounce, then. Don't get used to it.";
                    }
                    : switch (quirk) {
                        case SPRINTJUMP -> "Fair point. I'll walk for a while.";
                        case LIVELY -> "All right, I'll settle down for a bit.";
                        case CURIOUSGLANCE -> "Sorry. I'll stop staring for a while.";
                        case STEADY -> "All right, I can loosen up for a bit.";
                    };
            case KEEP -> {
                String base = witty
                        ? switch (quirk) {
                            case SPRINTJUMP -> "Hunger's a small price for style.";
                            case LIVELY -> "Standing still is for armor stands.";
                            case CURIOUSGLANCE -> "Can't help it. People are interesting.";
                            case STEADY -> "I like a steady pace. Builds character.";
                        }
                        : switch (quirk) {
                            case SPRINTJUMP -> "I know. I like it anyway.";
                            case LIVELY -> "That's just how I wait.";
                            case CURIOUSGLANCE -> "I was only looking. I'll get back to it.";
                            case STEADY -> "I prefer to keep it even.";
                        };
                yield speaker == Speaker.OWNER
                        ? base + " If it really bothers you, " + control + " and I'll change it."
                        : base;
            }
            case NONE -> witty ? "Says the one with the same habits." : "I'll take that under advisement.";
        };
    }

    private static String noun(Mannerisms.Quirk quirk) {
        return switch (quirk) {
            case SPRINTJUMP -> "jumper";
            case LIVELY -> "fidgeter";
            case CURIOUSGLANCE -> "starer";
            case STEADY -> "stick-in-the-mud";
        };
    }
}
