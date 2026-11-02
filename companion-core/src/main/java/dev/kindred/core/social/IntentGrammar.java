package dev.kindred.core.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In plain terms: how an AI player understands the plain ways of asking it something without any model: "go for the
 * Nether", "play it safe", "work together", "race me", "duel Sol", "I'm in", "count me out", "build a cabin here", "make a
 * cow ranch here", "plant a field here", "wear the knight skin", "come here", "wait", "stop", "toss me some bread", "keep
 * it down", "you can talk more". A race or a duel with nobody named is asked back ("Race whom?"); the answer is read once.
 * The operator's switches and anything with coordinates in it are recognised so that the player can say which command
 * does them.
 *
 * Technical: E-52's local grammar: fixed patterns over the message (its addressing removed), lower case, one line, trailing
 * punctuation dropped (as {@link Claims} reads it). Every reading is passed through {@link Intent#of}, the same check a
 * model's ask passes. Pure.
 */
public final class IntentGrammar {
    private IntentGrammar() { }

    /** What a message was read as: an ask, or an ask that needs one more word ({@code question} asks it). */
    public sealed interface Reading permits Ask, Unclear { }

    public record Ask(Intent intent) implements Reading { }

    public record Unclear(Intent.Kind kind, String question) implements Reading { }

    private static final String NAMES = "((?:me|[a-z0-9_]{2,16})(?:(?:\\s*,\\s*|\\s+and\\s+)(?:me|[a-z0-9_]{2,16})){0,7})";

    private static final Pattern GOAL_NETHER = Pattern.compile("(?:go for|get|make|build|light|work on|aim for|head for)\\s+(?:a\\s+|the\\s+)?nether(?:\\s+portal)?");
    private static final Pattern GOAL_END = Pattern.compile("(?:go for|find|get to|go to|head for|aim for)\\s+the\\s+end(?:\\s+portal)?");
    private static final Pattern GOAL_DRAGON = Pattern.compile("(?:go for|beat|kill|fight|slay|take on)\\s+the\\s+(?:ender\\s+)?dragon");
    private static final Pattern GOAL_NONE = Pattern.compile("(?:drop|stop|forget|give up|leave)\\s+(?:your\\s+|the\\s+)?goal|no (?:more )?goal");
    private static final Pattern MODE_SAFE = Pattern.compile("play it safe|play safe|be careful|play (?:it )?normally|survive");
    private static final Pattern MODE_RELAX = Pattern.compile("relax|take it easy|take a break|chill(?: out)?");
    private static final Pattern MODE_FAST = Pattern.compile("speedrun(?: it)?|go fast|hurry(?: up)?|speed it up");
    private static final Pattern TEAM_TOGETHER = Pattern.compile("(?:work|play|stick) together|team up|join (?:up with )?the team");
    private static final Pattern TEAM_SOLO = Pattern.compile("(?:work|play|go) (?:alone|solo|on your own)|do your own thing");
    private static final Pattern RACE = Pattern.compile("(?:let'?s\\s+)?(?:have a\\s+)?(?:speedrun\\s+)?race(?:\\s+(?:with|against))?(?:\\s+" + NAMES + ")?");
    private static final Pattern DUEL = Pattern.compile("(?:let'?s\\s+)?(?:duel|fight|spar)(?:\\s+(?:with|against))?(?:\\s+" + NAMES + ")?");
    private static final Pattern JOIN = Pattern.compile("i'?m in(?: too)?|count me in|i'?ll join|i'?ll play|i'?m game");
    private static final Pattern LEAVE = Pattern.compile("count me out|i'?m out|i quit|i'?m leaving the match|i give up");
    private static final Pattern BUILD = Pattern.compile("build (?:a |an |me a )?(hut|cabin|house|manor)(?: here)?(.*)");
    private static final Pattern RANCH = Pattern.compile("(?:make|build|start) (?:a |an )?(cow|sheep|pig|chicken) (?:ranch|pen)(?: here)?(.*)"
            + "|ranch (?:the )?(cow|sheep|pig|chicken)s?(?: here)?(.*)");
    private static final Pattern FARM = Pattern.compile("(?:make|build|start|plant) (?:a |an )?(?:farm|field|wheat field)(?: here)?(.*)");
    private static final Pattern SKIN = Pattern.compile("(?:wear|put on|change into|switch to) (?:the |your )?([a-z0-9_]{1,24}) skin");
    private static final Pattern COME = Pattern.compile("come (?:here|over|to me|over here)|over here");
    private static final Pattern WAIT = Pattern.compile("wait(?: here| a (?:moment|minute|second|sec))?|hold on|hang on");
    private static final Pattern STOP = Pattern.compile("stop(?: what you'?re doing| everything)?|halt|freeze|stand still");
    private static final Pattern TOSS = Pattern.compile("(?:toss|throw|give|pass) me (?:some |a |an |your )?(?:spare )?([a-z_ ]{2,40})");
    private static final Pattern QUIET = Pattern.compile("keep it down|be quiet|quiet(?: please)?|shh+|less chatter|talk less");
    private static final Pattern TALK = Pattern.compile("you can talk(?: more)?|talk more|chat more|more chatter");
    private static final Pattern OP_EDITS = Pattern.compile("(?:turn|switch) (?:on|off) (?:world |ai )?edits|(?:allow|enable|disable) (?:world |ai )?edits");
    private static final Pattern OP_DRAGON = Pattern.compile("(?:allow|enable|turn on|switch on) the dragon fight");
    private static final Pattern OP_BASES = Pattern.compile("(?:allow|enable|turn on|switch on) (?:the )?bases");
    private static final Pattern OP_CREATE = Pattern.compile("(?:make|create|spawn) (?:a |another )?(?:new )?(?:ai )?player(?: called [a-z0-9_]{2,16})?");
    private static final Pattern OP_REMOVE = Pattern.compile("(?:remove|delete) (?:yourself|that player|the player)");
    private static final Pattern OP_LIMIT = Pattern.compile("(?:set|change) (?:your |the )?(?:limit|cap|hours)(?: .*)?");
    private static final Pattern COORDINATES = Pattern.compile("-?\\d+\\s*[,/ ]\\s*-?\\d+\\s*[,/ ]\\s*-?\\d+");

    /** What {@code message} asks, if it is one of the plain forms. */
    public static Optional<Reading> read(String message) {
        String m = Claims.plain(message);
        if (m.isEmpty()) return Optional.empty();
        Matcher x;
        if (GOAL_NONE.matcher(m).matches()) return ask(Intent.Kind.GOAL, "none");
        if (GOAL_DRAGON.matcher(m).matches()) return ask(Intent.Kind.GOAL, "dragon");
        if (GOAL_END.matcher(m).matches()) return ask(Intent.Kind.GOAL, "end");
        if (GOAL_NETHER.matcher(m).matches()) return ask(Intent.Kind.GOAL, "nether");
        if (MODE_FAST.matcher(m).matches()) return ask(Intent.Kind.MODE, "speedrun");
        if (MODE_RELAX.matcher(m).matches()) return ask(Intent.Kind.MODE, "relaxed");
        if (MODE_SAFE.matcher(m).matches()) return ask(Intent.Kind.MODE, "survival");
        if (TEAM_TOGETHER.matcher(m).matches()) return ask(Intent.Kind.TEAM, "together");
        if (TEAM_SOLO.matcher(m).matches()) return ask(Intent.Kind.TEAM, "solo");
        if ((x = RACE.matcher(m)).matches()) return match(Intent.Kind.RACE, x.group(1), "Race whom?");
        if ((x = DUEL.matcher(m)).matches()) return match(Intent.Kind.DUEL, x.group(1), "Duel whom?");
        if (JOIN.matcher(m).matches()) return ask(Intent.Kind.JOIN, "");
        if (LEAVE.matcher(m).matches()) return ask(Intent.Kind.LEAVE, "");
        if ((x = BUILD.matcher(m)).matches()) return placed(Intent.Kind.BUILD, x.group(1), x.group(2), "build");
        if ((x = RANCH.matcher(m)).matches()) {
            return x.group(1) != null ? placed(Intent.Kind.RANCH, x.group(1), x.group(2), "ranch") : placed(Intent.Kind.RANCH, x.group(3), x.group(4), "ranch");
        }
        if ((x = FARM.matcher(m)).matches()) return placed(Intent.Kind.FARM, "", x.group(1), "farm");
        if ((x = SKIN.matcher(m)).matches()) return ask(Intent.Kind.SKIN, x.group(1));
        if (COME.matcher(m).matches()) return ask(Intent.Kind.COME, "");
        if (WAIT.matcher(m).matches()) return ask(Intent.Kind.WAIT, "");
        if (STOP.matcher(m).matches()) return ask(Intent.Kind.STOP, "");
        if ((x = TOSS.matcher(m)).matches()) return ask(Intent.Kind.TOSS, x.group(1).strip().replace(' ', '_'));
        if (QUIET.matcher(m).matches()) return ask(Intent.Kind.QUIET, "");
        if (TALK.matcher(m).matches()) return ask(Intent.Kind.TALK, "");
        if (OP_EDITS.matcher(m).matches()) return ask(Intent.Kind.OPERATOR, "edits");
        if (OP_DRAGON.matcher(m).matches()) return ask(Intent.Kind.OPERATOR, "dragonfight");
        if (OP_BASES.matcher(m).matches()) return ask(Intent.Kind.OPERATOR, "bases");
        if (OP_CREATE.matcher(m).matches()) return ask(Intent.Kind.OPERATOR, "create");
        if (OP_REMOVE.matcher(m).matches()) return ask(Intent.Kind.OPERATOR, "remove");
        if (OP_LIMIT.matcher(m).matches()) return ask(Intent.Kind.OPERATOR, "limit");
        return Optional.empty();
    }

    /**
     * The answer to a question asked back ("Race whom?"): the names in it, read once; empty when it names nobody, and the
     * ask is then dropped.
     */
    public static Optional<Intent> answer(Intent.Kind kind, String message) {
        String m = Claims.plain(message).replaceFirst("^(?:with|against|just)\\s+", "");
        Matcher names = Pattern.compile(NAMES).matcher(m);
        if (!names.matches()) return Optional.empty();
        return Intent.of(kind, "", split(names.group(1)));
    }

    private static Optional<Reading> ask(Intent.Kind kind, String arg) {
        return Intent.of(kind, arg, List.of()).map(Ask::new);
    }

    private static Optional<Reading> match(Intent.Kind kind, String names, String question) {
        if (names == null || names.isBlank()) return Optional.of(new Unclear(kind, question));
        return Intent.of(kind, "", split(names)).map(Ask::new);
    }

    /** A build, a ranch or a field where the speaker stands; one given with coordinates stays a command. */
    private static Optional<Reading> placed(Intent.Kind kind, String what, String rest, String command) {
        if (rest != null && COORDINATES.matcher(rest).find()) return Intent.of(Intent.Kind.PLACE, command, List.of()).map(Ask::new);
        if (rest != null && !rest.isBlank()) return Optional.empty();
        return Intent.of(kind, what, List.of()).map(Ask::new);
    }

    private static List<String> split(String names) {
        var out = new ArrayList<String>();
        for (String part : names.split("\\s*,\\s*|\\s+and\\s+")) if (!part.isBlank()) out.add(part.strip());
        return out;
    }
}
