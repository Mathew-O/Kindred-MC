package dev.kindred.core.wire;

import dev.kindred.core.social.ListenerPreferences;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: what happened to one message: refused (and why), or who got it: the AI players who now know it as
 * told, the people to whom it is to be said in words, the people who would have heard it but have had enough lines
 * for now, and those in the audience held back because they may not know one of its facts.
 *
 * Technical: The Wire's answer. {@code told} goes to each AI receiver's knowledge; {@code phrase} to the voice, which
 * words it for that person within the social lane; nothing is phrased for an AI receiver.
 */
public record Delivery(Message message, Optional<Refusal> refused, List<Told> told, List<Phrase> phrase, List<UUID> quiet,
                       List<UUID> withheld) {
    /** Why a message did not go out at all. */
    public enum Refusal {
        /** An AI player's words, which never re-enter (the echo rule). */
        ECHO,
        /** The world's mode does not use this channel. */
        MODE,
        /** The sender is not a known player. */
        UNKNOWN_SENDER,
        /** The sender is not in the audience it sends to. */
        NOT_MEMBER,
        /** Only the server sends to the whole server, and only its registered announcements. */
        NOT_ANNOUNCEMENT,
        /** A fact narrower than the channel's audience (a side's position on public). */
        NARROWER_FACT,
        /** The same message on the same scope within its dedupe window. */
        DUPLICATE,
        /** The channel's lines for this minute are spent. */
        BUDGET
    }

    /** What an AI receiver now knows, and how. */
    public record Told(UUID receiver, Message message, HowKnown howKnown) {
        public Told { Objects.requireNonNull(receiver); Objects.requireNonNull(message); Objects.requireNonNull(howKnown); }
    }

    /** A message to be put into words for one person, with their settings. */
    public record Phrase(UUID person, String name, Message message, ListenerPreferences preferences) {
        public Phrase { Objects.requireNonNull(person); Objects.requireNonNull(message); }
    }

    public Delivery {
        Objects.requireNonNull(message);
        refused = refused == null ? Optional.empty() : refused;
        told = List.copyOf(told);
        phrase = List.copyOf(phrase);
        quiet = List.copyOf(quiet);
        withheld = List.copyOf(withheld);
    }

    static Delivery refused(Message message, Refusal why) {
        return new Delivery(message, Optional.of(why), List.of(), List.of(), List.of(), List.of());
    }

    public boolean sent() { return refused.isEmpty(); }

    /** Everyone who received it, AI players and people (spoken to or quiet). */
    public List<UUID> receivers() {
        var out = new java.util.ArrayList<UUID>();
        told.forEach(t -> out.add(t.receiver()));
        phrase.forEach(p -> out.add(p.person()));
        out.addAll(quiet);
        return List.copyOf(out);
    }
}
