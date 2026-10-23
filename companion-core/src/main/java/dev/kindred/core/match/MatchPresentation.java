package dev.kindred.core.match;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a match shows the players in it (M-00b), worked out apart from how it is sent: a title for each second of the
 * countdown, one for the start and one for each player's result, and a bar with the match and its clock. Everything a
 * vanilla client shows unaided (titles, a boss bar, sounds), so a player needs no mod to follow a match.
 */
public final class MatchPresentation {
    private MatchPresentation() { }

    /** The sound that goes with a title. */
    public enum Cue { COUNT, START, WIN, LOSE, DRAW }

    public record Title(String title, String subtitle, Cue cue) { }

    /** The bar's text and how full it is: the time left of the match's time, or full before it starts. */
    public record Bar(String text, float progress) { }

    /**
     * The countdown's title at {@code tick}: the seconds left, rounded up. It is the same title for a whole second, so
     * whoever shows it shows each one once, as it changes.
     */
    public static Optional<Title> countdown(MatchRegistry.Match match, long tick) {
        if (match.state() != MatchRegistry.State.COUNTDOWN) return Optional.empty();
        long left = match.countdownEnds() - tick;
        if (left <= 0) return Optional.empty();
        return Optional.of(new Title(String.valueOf(seconds(left)), "Get ready", Cue.COUNT));
    }

    /** The title as the fight begins. */
    public static Title start() { return new Title("Fight!", "", Cue.START); }

    /** The result as {@code viewer} sees it: won, lost, or neither, with how it ended. */
    public static Title result(MatchRegistry.Match match, UUID viewer, Function<UUID, String> names) {
        String how = match.result() == null ? "" : match.result();
        if (match.winner() == null) {
            // No winner: time ran out, an operator stopped it, or it was called off before the start.
            if (how.startsWith("Time ran out")) return new Title("Draw", "Time ran out", Cue.DRAW);
            if (how.equals("Stopped")) return new Title("Stopped", "No winner", Cue.DRAW);
            if (how.startsWith("Every runner")) return new Title("Race over", how, Cue.DRAW);
            return new Title("Called off", how, Cue.DRAW);
        }
        if (match.winner().equals(viewer)) return new Title("Victory", subtitle(match, names), Cue.WIN);
        if (match.participant(viewer)) return new Title("Defeat", subtitle(match, names), Cue.LOSE);
        return new Title(names.apply(match.winner()) + " wins", how, Cue.WIN);
    }

    /** The bar: the players, then the countdown or the time left. */
    public static Bar bar(MatchRegistry.Match match, long tick, Function<UUID, String> names) { return bar(match, tick, names, 1f); }

    /**
     * The bar, with {@code progress} for a race: how far its leader is along the splits. A match with a time shows the
     * time left, emptying; a race shows the time since the start, filling as its leader reaches each split.
     */
    public static Bar bar(MatchRegistry.Match match, long tick, Function<UUID, String> names, float progress) {
        String who = match.invited().stream().map(names).collect(Collectors.joining(" vs "));
        String mode = match.mode().label();
        return switch (match.state()) {
            case LOBBY -> new Bar(mode + ": " + who + " - waiting for players", 1f);
            case COUNTDOWN -> new Bar(mode + ": " + who + " - starting in " + seconds(match.countdownEnds() - tick), 1f);
            case RUNNING -> {
                if (match.mode().ticks == 0) yield new Bar(mode + ": " + who + " - " + clock(Math.max(0, tick - match.countdownEnds())),
                        Math.clamp(progress, 0f, 1f));
                long left = Math.max(0, match.endsAt() - tick);
                yield new Bar(mode + ": " + who + " - " + clock(left), Math.clamp(left / (float) match.mode().ticks, 0f, 1f));
            }
            case ENDED -> new Bar(mode + ": " + who + " - over", 0f);
        };
    }

    /** A split reached, as every runner in the race is told it: who, where, and the time. */
    public static String split(SpeedrunSplits.Reached reached, Function<UUID, String> names) {
        return names.apply(reached.runner()) + ": " + reached.split().label() + " at " + SpeedrunSplits.time(reached.ticks());
    }

    /** Minutes and seconds, the seconds rounded up so the clock reads 0:00 only at the end. */
    static String clock(long ticks) {
        long seconds = seconds(ticks);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static long seconds(long ticks) { return Math.max(0, (ticks + 19) / 20); }

    private static String subtitle(MatchRegistry.Match match, Function<UUID, String> names) {
        String winner = names.apply(match.winner());
        return switch (match.result() == null ? "" : match.result()) {
            case "finished" -> winner + " freed the End first";
            case "fell" -> winner + " won: " + names.apply(MatchRegistry.other(match, match.winner())) + " fell";
            case "forfeited" -> winner + " won by forfeit";
            case "left the game" -> winner + " won: the other left the game";
            default -> winner + " won";
        };
    }
}
