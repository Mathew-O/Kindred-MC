package dev.kindred.core.social;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In plain terms: the few plain things a person may say to an AI player about themselves that it notes as claims ("I'm
 * off to the Nether", "I need iron", "I've got spare wheat"), taking one back ("forget that", "forget what I said about
 * the Nether", "that's wrong"), and asking what it knows of someone ("what does Sol need?", "what is Sol doing?", "where
 * is Sol?"). Anything else is ordinary talk and leaves no note.
 *
 * Technical: E-50's local reading of chat, without a model: fixed patterns over the message (its addressing removed),
 * lower-cased, trailing punctuation dropped. A claim is first-person only, so it is always about whoever said it. A going
 * somewhere names the dimension when it is the Overworld, the Nether or the End, so a sighting there can confirm it.
 */
public final class Claims {
    private Claims() { }

    /** A claim a person made of themselves: on what topic, in what words, and the dimension it says they are going to, if any. */
    public record Claim(PeerLedger.Topic topic, String text, String dimension) { }

    /** A claim taken back: how, and the word it is about (null for the last one). */
    public record Taken(PeerLedger.Correction how, String about) { }

    /** What a question asks of someone. */
    public enum Ask { NEEDS, DOING, WHERE }

    /** A question about someone, by name. */
    public record Question(Ask ask, String name) { }

    private static final Pattern GOING_TO = Pattern.compile(
            "(?:i'?m|i am|im)\\s+(?:off|going|heading|headed|on my way|going back)\\s+(?:to|into|back to|over to)\\s+(.{2,60})");
    private static final Pattern GOING_ING = Pattern.compile("(?:i'?m|i am|im)\\s+(?:off|going|heading)\\s+([a-z]{3,20}ing)(?:\\s+.{0,40})?");
    private static final Pattern NEED = Pattern.compile("i\\s+(?:need|could use|could do with)\\s+(?:some\\s+|a few\\s+|more\\s+|an?\\s+)?(.{2,60})");
    private static final Pattern SPARE = Pattern.compile("i(?:'ve|\\s+have)?(?:\\s+got)?\\s+(?:some\\s+)?(?:spare|extra)\\s+(.{2,60})"
            + "|i\\s+(?:can|could)\\s+spare\\s+(?:some\\s+)?(.{2,60})");
    private static final Pattern FORGET = Pattern.compile("(?:please\\s+)?(?:forget (?:that|it|what i (?:said|told you)(?: about (?:the\\s+)?(.{2,40}))?)|never ?mind)");
    private static final Pattern WRONG = Pattern.compile("(?:that'?s|that is|thats)\\s+(?:wrong|not true|not right)|not true");
    private static final Pattern NEEDS_Q = Pattern.compile("what (?:does|do) ([a-z0-9_]{2,16}) need");
    private static final Pattern DOING_Q = Pattern.compile("what(?:'s| is) ([a-z0-9_]{2,16}) (?:doing|up to)");
    private static final Pattern WHERE_Q = Pattern.compile("(?:where(?:'s| is) ([a-z0-9_]{2,16})|have you seen ([a-z0-9_]{2,16}))");

    /** The message as matched: lower case, one line, apostrophes plain, trailing punctuation dropped. */
    static String plain(String message) {
        if (message == null) return "";
        return message.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip().replaceAll("[!?.,]+$", "").strip();
    }

    /** A claim a person makes of themselves in {@code message}, if it is one. */
    public static Optional<Claim> claim(String message) {
        String m = plain(message);
        Matcher going = GOING_TO.matcher(m);
        if (going.matches()) {
            String where = going.group(1).strip();
            return Optional.of(new Claim(PeerLedger.Topic.DOING, "going to " + where, dimension(where)));
        }
        Matcher doing = GOING_ING.matcher(m);
        if (doing.matches()) return Optional.of(new Claim(PeerLedger.Topic.DOING, "going " + doing.group(1), null));
        Matcher need = NEED.matcher(m);
        if (need.matches()) return Optional.of(new Claim(PeerLedger.Topic.NEEDS, need.group(1).strip(), null));
        Matcher spare = SPARE.matcher(m);
        if (spare.matches()) return Optional.of(new Claim(PeerLedger.Topic.SPARE, (spare.group(1) != null ? spare.group(1) : spare.group(2)).strip(), null));
        return Optional.empty();
    }

    /** A claim taken back in {@code message}, if it is one. */
    public static Optional<Taken> taken(String message) {
        String m = plain(message);
        Matcher forget = FORGET.matcher(m);
        if (forget.matches()) return Optional.of(new Taken(PeerLedger.Correction.FORGOTTEN, forget.group(1) == null ? null : forget.group(1).strip()));
        if (WRONG.matcher(m).matches()) return Optional.of(new Taken(PeerLedger.Correction.WRONG, null));
        return Optional.empty();
    }

    /** A question about someone in {@code message}, if it is one. */
    public static Optional<Question> question(String message) {
        String m = plain(message);
        Matcher needs = NEEDS_Q.matcher(m);
        if (needs.matches()) return Optional.of(new Question(Ask.NEEDS, needs.group(1)));
        Matcher doing = DOING_Q.matcher(m);
        if (doing.matches()) return Optional.of(new Question(Ask.DOING, doing.group(1)));
        Matcher where = WHERE_Q.matcher(m);
        if (where.matches()) return Optional.of(new Question(Ask.WHERE, where.group(1) != null ? where.group(1) : where.group(2)));
        return Optional.empty();
    }

    /** The dimension a place in words is in, when it names one. */
    static String dimension(String where) {
        String w = " " + where + " ";
        if (w.contains(" nether ")) return "minecraft:the_nether";
        if (w.contains(" end ") && !w.contains(" end of ")) return "minecraft:the_end";
        if (w.contains(" overworld ") || w.contains(" surface ")) return "minecraft:overworld";
        return null;
    }
}
