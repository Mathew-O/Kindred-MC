package dev.kindred.core.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In plain terms: one thing a person may ask an AI player in chat, with what it is about: a goal ("go for the Nether"), a
 * way of playing ("play it safe"), working together or alone, a race or a duel and who is in it, joining or leaving a
 * match, a build, a ranch or a field where the speaker stands, a skin, coming over, waiting, stopping, a toss, fewer or more
 * lines. Each is done exactly as its command does it, by whoever asked, so nobody can ask for more than they could do by
 * command; what stays a command (making players, the operator's switches, limits, anything with coordinates) is answered
 * with the command to use.
 *
 * Technical: E-52's ask vocabulary, pure. {@link #of} is the single check every ask passes, whether the local grammar read
 * it or a model returned it (as E-20b's asks are checked): a known kind, its argument one of the kind's own, names well
 * formed and at most eight. {@link #who()} says who may make it: a controller of the player, anyone (a match's proposal,
 * which still needs everyone's consent), or the speaker for themselves.
 */
public record Intent(Kind kind, String arg, List<String> names) {
    /** The kinds of ask. */
    public enum Kind {
        GOAL, MODE, TEAM, RACE, DUEL, JOIN, LEAVE, BUILD, RANCH, FARM, SKIN, COME, WAIT, STOP, TOSS, QUIET, TALK, OPERATOR, PLACE
    }

    /** Who may make an ask. */
    public enum Who { CONTROLLER, ANYONE, SELF }

    /** The goals by their spoken names. */
    public static final List<String> GOALS = List.of("nether", "end", "dragon", "none");
    /** The ways of playing by their spoken names. */
    public static final List<String> MODES = List.of("relaxed", "survival", "speedrun");
    public static final List<String> TEAMS = List.of("together", "solo");
    /** What may be built by chat: the plans with no coordinates in their name. */
    public static final List<String> BUILDS = List.of("hut", "cabin", "house", "manor");
    public static final List<String> RANCHES = List.of("cow", "sheep", "pig", "chicken");
    /** The operator's own switches and what stays a command, by the command that does them. */
    public static final List<String> OPERATOR = List.of("edits", "dragonfight", "bases", "create", "remove", "limit");
    /** "me", for the speaker, or a player's name. */
    private static final Pattern NAME = Pattern.compile("me|[a-z0-9_]{2,16}");
    private static final Pattern ITEM = Pattern.compile("(minecraft:)?[a-z0-9_]{2,40}");
    private static final Pattern SKIN_KEY = Pattern.compile("[a-z0-9_]{1,24}");
    public static final int MAX_NAMES = 8;

    public Intent {
        Objects.requireNonNull(kind);
        arg = arg == null ? "" : arg;
        names = List.copyOf(names == null ? List.of() : names);
    }

    /** Who may make this ask. */
    public Who who() {
        return switch (kind) {
            case RACE, DUEL -> Who.ANYONE;
            case JOIN, LEAVE, QUIET, TALK, OPERATOR, PLACE -> Who.SELF;
            default -> Who.CONTROLLER;
        };
    }

    /**
     * The ask, checked: a known kind, its argument one of the kind's own (lower case), and its names well formed and at
     * most {@value #MAX_NAMES}; empty for anything else, which is then no ask at all.
     */
    public static Optional<Intent> of(String kind, String arg, List<String> names) {
        if (kind == null) return Optional.empty();
        Kind k;
        try {
            k = Kind.valueOf(kind.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        return of(k, arg, names);
    }

    /** As {@link #of(String, String, List)}, for a kind already known. */
    public static Optional<Intent> of(Kind kind, String arg, List<String> names) {
        if (kind == null) return Optional.empty();
        String a = arg == null ? "" : arg.strip().toLowerCase(Locale.ROOT);
        var cleanNames = new ArrayList<String>();
        if (names != null) {
            for (String name : names) {
                String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
                if (!NAME.matcher(n).matches()) return Optional.empty();
                if (!cleanNames.contains(n)) cleanNames.add(n);
            }
        }
        if (cleanNames.size() > MAX_NAMES) return Optional.empty();
        boolean ok = switch (kind) {
            case GOAL -> GOALS.contains(a);
            case MODE -> MODES.contains(a);
            case TEAM -> TEAMS.contains(a);
            case BUILD -> BUILDS.contains(a);
            case RANCH -> RANCHES.contains(a);
            case SKIN -> SKIN_KEY.matcher(a).matches();
            case TOSS -> ITEM.matcher(a).matches();
            case OPERATOR -> OPERATOR.contains(a);
            case RACE, DUEL -> !cleanNames.isEmpty() && a.isEmpty();
            case PLACE -> Set.of("build", "ranch", "farm").contains(a);
            default -> a.isEmpty();
        };
        if (!ok) return Optional.empty();
        if (kind != Kind.RACE && kind != Kind.DUEL && !cleanNames.isEmpty()) return Optional.empty();
        return Optional.of(new Intent(kind, a, cleanNames));
    }
}
