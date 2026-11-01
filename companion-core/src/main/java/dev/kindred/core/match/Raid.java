package dev.kindred.core.match;

import dev.kindred.core.team.Missing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * In plain terms: whether a player raids a chest it saw an opponent use, in a match it is playing: an
 * optional AI adviser picks raid, leave or watch from what the player knows, and the player's own rule
 * decides when there is no adviser or no usable answer. It tells only its own team what it took.
 *
 * Technical: Raiding an opponent's storage in a match (E-44), as the owner set it out (2026-10-25): a player
 * that finds a chest belonging to another player, in a match whose sides are opponents, asks the model
 * whether to take that hostile action and take its valuables. A chest is an opponent's only by what the
 * player saw: it saw them use it. The model answers from three choices; with none set up, or no usable
 * answer, the player's own rule answers on the same facts. What it tells afterwards it tells its own team
 * only. Whether the two are opponents at all is the match's to say ({@link MatchRegistry#opponents}); nothing
 * here makes anyone one. Pure: the adapter says what the player saw and knows.
 */
public final class Raid {
    private Raid() { }

    /** What a player may do about an opponent's chest. */
    public enum Choice {
        /** Raid it now: open it and take what is worth taking. */
        RAID,
        /** Leave it alone. */
        LEAVE,
        /** Leave it for now and think again when it next comes by. */
        WATCH
    }

    /**
     * What a player knows when it finds an opponent's chest: its own name, the match and its side in it; whose the
     * chest is and their side, and how long ago it saw them use it; how far it is; how far the nearest opponent in
     * sight is, if any is; its health of twenty; what of note it holds; and what its run wants.
     */
    public record Facts(String player, MatchRegistry.Mode mode, String side, String owner, String ownerSide, int seenSecondsAgo,
                        int distance, OptionalInt nearestOpponent, float health, List<String> holds, List<String> wants) {
        public Facts {
            Objects.requireNonNull(player);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(side);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(ownerSide);
            Objects.requireNonNull(nearestOpponent);
            holds = List.copyOf(holds);
            wants = List.copyOf(wants);
        }
    }

    /** An opponent in sight closer than this would see it at the chest, or reach it there. */
    static final int SEEN_WITHIN = 24;
    /** Below this health it picks no fight. */
    static final float HURT = 8;
    /** Further than this, the chest is too far out of its way for now. */
    static final int FAR = 48;
    /** A manhunt's runner, whose run comes first, takes only what is this close to its way. */
    static final int RUNNER_NEAR = 16;

    /** The model's instructions for the question, the same every time. */
    public static final String SYSTEM = """
            You decide one thing for an autonomous Minecraft player in a match it joined, whose other side are its \
            opponents: a Manhunt's runners against its hunters, or the two players of a duel. It has found a chest it \
            saw one of its opponents use, and asks whether to raid it: open it and take what in it is worth taking. \
            That is a hostile act against another player, which the match's rules allow.

            Weigh what the player knows. A runner wants to reach the dragon and stay ahead of its hunters, and a raid \
            costs it time. A hunter wants to catch and stop the runners, and taking their supplies slows them. An \
            opponent in sight may catch it at the chest or come for it. A hurt player should not pick a fight.

            Answer RAID to raid it now, LEAVE to leave it alone, or WATCH to leave it for now and think again when it \
            next comes by, with one sentence why. Answer only from what the question says: never suppose what is in \
            the chest, which the player has not seen.""";

    /** The answers the model may give. */
    public static List<String> choices() {
        return List.of(Choice.RAID.name(), Choice.LEAVE.name(), Choice.WATCH.name());
    }

    /** One of the answers, whatever its case, or none. */
    public static Optional<Choice> parse(String answer) {
        if (answer == null) return Optional.empty();
        try {
            return Optional.of(Choice.valueOf(answer.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** The question, from what the player knows and nothing else. */
    public static String question(Facts f) {
        var text = new StringBuilder();
        text.append("You are ").append(f.player()).append(", ").append(article(f.side())).append(' ').append(f.side())
                .append(" in a ").append(f.mode().label()).append(" match. ");
        text.append("You saw ").append(f.owner()).append(", ").append(article(f.ownerSide())).append(' ').append(f.ownerSide())
                .append(" and your opponent, use a chest ").append(ago(f.seenSecondsAgo())).append("; it is ")
                .append(f.distance()).append(" blocks from you. ");
        if (f.nearestOpponent().isPresent()) {
            text.append("An opponent is in sight ").append(f.nearestOpponent().getAsInt()).append(" blocks away. ");
        } else {
            text.append("No opponent is in sight. ");
        }
        text.append("Your health is ").append(Math.round(f.health())).append(" of 20. ");
        text.append("You hold ").append(f.holds().isEmpty() ? "nothing of note" : String.join(", ", f.holds())).append(". ");
        if (!f.wants().isEmpty()) text.append("Your run still wants ").append(String.join(", ", f.wants())).append(". ");
        text.append("What is in the chest you have not seen. Raid it now, leave it, or watch it?");
        return text.toString();
    }

    /** The player's own answer, with no model or no usable answer from one: on the same facts the model was given. */
    public static Choice local(Facts f) {
        if (f.health() < HURT) return Choice.LEAVE;
        if (f.nearestOpponent().isPresent() && f.nearestOpponent().getAsInt() < SEEN_WITHIN) return Choice.WATCH;
        if (f.mode() == MatchRegistry.Mode.MANHUNT && f.side().equals("runner")) {
            return f.distance() <= RUNNER_NEAR ? Choice.RAID : Choice.LEAVE;
        }
        return f.distance() > FAR ? Choice.WATCH : Choice.RAID;
    }

    /** Why its own rule answered as it did, in a sentence. */
    public static String why(Choice choice, Facts f) {
        if (f.health() < HURT) return "Too hurt to risk a fight at it.";
        if (f.nearestOpponent().isPresent() && f.nearestOpponent().getAsInt() < SEEN_WITHIN) return "An opponent is in sight.";
        if (f.mode() == MatchRegistry.Mode.MANHUNT && f.side().equals("runner")) {
            return choice == Choice.RAID ? "It is on the way and nobody is watching it." : "The run comes first.";
        }
        return choice == Choice.WATCH ? "Too far out of the way for now." : "Its opponent's supplies, and nobody watching them.";
    }

    /** What it tells its own team of a raid: "I raided Steve's chest: 4 diamonds and 10 gold ingots." */
    public static String told(String owner, Map<String, Integer> taken) {
        Objects.requireNonNull(owner);
        if (taken.isEmpty()) return "I raided " + owner + "'s chest: nothing in it worth taking.";
        return "I raided " + owner + "'s chest: " + Missing.items(taken) + ".";
    }

    private static String ago(int seconds) {
        if (seconds < 60) return seconds + " seconds ago";
        int minutes = seconds / 60;
        return minutes == 1 ? "a minute ago" : minutes + " minutes ago";
    }

    private static String article(String word) {
        return !word.isEmpty() && "aeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }
}
