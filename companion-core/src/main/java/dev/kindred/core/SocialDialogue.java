package dev.kindred.core;

import java.util.Locale;
import java.util.Optional;

/**
 * In plain terms: a small, fixed set of friendly replies for when a player is spoken to by name. No AI model
 * is involved, and nothing said can make the player do anything.
 *
 * Technical: Deliberately finite small talk, without model access or a command execution path.
 */
public final class SocialDialogue {
    private SocialDialogue() { }

    /** The rest of a chat message, if it starts by addressing this player by name. */
    public static Optional<String> addressedMessage(String name, String raw) {
        if (raw.length() > 256) return Optional.empty();
        String message = raw.strip();
        if (message.length() <= name.length() || !message.regionMatches(true, 0, name, 0, name.length())) return Optional.empty();
        char delimiter = message.charAt(name.length());
        if (delimiter != ',' && delimiter != ':') return Optional.empty();
        String remainder = message.substring(name.length() + 1).strip();
        return remainder.isEmpty() ? Optional.empty() : Optional.of(remainder);
    }

    /** The player's reply, picked from what it is doing and its personality. */
    public static String reply(String raw, SocialSnapshot snapshot, SocialProfile profile) {
        if (raw.isBlank() || raw.length() > 256) return "Keep it to a short message, please.";
        String message = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[!?.,]+$", "");
        if (message.matches("(hi|hello|hey)( there)?")) return profile.personality().humor() >= 65
                ? "Hey! Always room for another square head around here." : "Hello! Nice to have some company.";
        if (message.equals("thanks") || message.equals("thank you")) return "You're welcome.";
        if (message.equals("bye") || message.equals("goodbye")) return "See you around.";
        if (message.equals("how are you") || message.equals("are you okay")) return snapshot.hurt()
                ? "I've just taken some damage." : "I'm here and ready to talk.";
        if (message.equals("what are you doing") || message.equals("status")) return snapshot.active()
                ? "I'm watching what's nearby." : "I'm staying put. Talking won't start me moving.";
        if (message.equals("weather") || message.equals("how's the weather")) return snapshot.raining()
                ? "It's raining where I'm standing." : "I'm not feeling rain where I'm standing.";
        if (message.equals("any danger") || message.equals("what do you see")) return snapshot.visibleThreat() != null
                ? "I can see a " + snapshot.visibleThreat() + " nearby." : "I don't see a hostile creature nearby right now.";
        if (message.matches("(follow|stop|stay|resume|come|go|build|gather|mine)( .*)?")) {
            return "Authorized players can use /aiplayer for controls. Conversation doesn't change my tasks.";
        }
        if (message.matches(".*\\b(duel|spar|fight)\\b.*")) return "Combat skills are not installed yet.";
        return "I can chat about what's nearby. Try hello, how are you, what are you doing, weather, or what do you see.";
    }
}
