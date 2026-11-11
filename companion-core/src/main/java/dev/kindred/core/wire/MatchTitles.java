package dev.kindred.core.wire;

import dev.kindred.core.match.MatchPresentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: the big titles a match shows a player ("Fight!", "Run!", "Victory", "Draw: Time ran out", a build
 * battle's theme), told to an AI player as the server's word, to it alone, since it has no screen to see them on. The
 * countdown's seconds are not told: the match's start is.
 *
 * Technical: F-34c's presenter path. A title {@link MatchPresentation#start}, {@link MatchPresentation#result} or a
 * mode's own shows becomes a {@link #KIND} message from the server on {@link Channel#PRIVATE} to that player, every
 * fact its own ({@link Visibility#SELF}): {@code title}, {@code subtitle} when there is one, {@code cue} (start, win,
 * lose, draw). A person is never sent one: the title is on their screen.
 */
public final class MatchTitles {
    public static final String KIND = "match.title";

    private MatchTitles() { }

    /** The title shown to {@code player} as a message to it from the server; empty for a countdown's second. */
    public static Optional<Message> of(UUID player, MatchPresentation.Title title) {
        if (title == null || title.cue() == MatchPresentation.Cue.COUNT || title.title().isBlank()) return Optional.empty();
        var facts = new LinkedHashMap<String, Fact>();
        facts.put("title", Fact.of(title.title(), Visibility.SELF));
        if (title.subtitle() != null && !title.subtitle().isBlank()) facts.put("subtitle", Fact.of(title.subtitle(), Visibility.SELF));
        facts.put("cue", Fact.of(title.cue().name().toLowerCase(java.util.Locale.ROOT), Visibility.SELF));
        return Optional.of(Message.of(KIND, null, Scope.toPlayer(player), facts, List.of()));
    }
}
