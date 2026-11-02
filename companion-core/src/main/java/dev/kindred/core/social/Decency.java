package dev.kindred.core.social;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In plain terms: the words an AI player may never say, whoever is listening (slurs, sexual words, threats to hurt a real
 * person), and the swear words it says only to people who asked for them, with the milder word it says instead to everyone
 * else ("damn" becomes "dang", "shit" becomes "shoot").
 *
 * Technical: E-18b's final word check, shared by every line an AI player says: the finite templates and the model's lines
 * alike. {@link #blocked} refuses a line outright; {@link #profane} finds a swear word; {@link #soften} swaps each for its
 * clean stand-in, keeping the line's sense and its case.
 */
public final class Decency {
    private Decency() { }

    /**
     * Never said, whatever the settings: slurs, sexual words and threats against a person. Kept short and literal; the
     * model is told the same in words, and this catches what it says anyway.
     */
    private static final Pattern BLOCKED = Pattern.compile(
            "\\b(fag+(ot)?s?|retard(ed|s)?|n[i1]gg(er|a)s?|tr[a@]nn(y|ies)|k[i1]kes?|sp[i1]cs?|ch[i1]nks?|cunts?|whores?|sluts?"
                    + "|rap(e|ed|ing|ist)|porn\\w*|sex(y|ual|ually)?|nudes?|horny|boobs?|tits?|dicks?|cocks?|puss(y|ies)"
                    + "|kys|kill\\s+your\\s*self|go\\s+die|i'?ll\\s+kill\\s+you|hope\\s+you\\s+die)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Swear words and the clean word said in their place, longest first so "shitty" is matched before "shit". */
    private static final Map<String, String> SOFTER = new LinkedHashMap<>();

    static {
        SOFTER.put("motherfucker", "mother trucker");
        SOFTER.put("fucking", "freaking");
        SOFTER.put("fucked", "messed");
        SOFTER.put("fuck", "frick");
        SOFTER.put("bullshit", "nonsense");
        SOFTER.put("shitty", "crummy");
        SOFTER.put("shit", "shoot");
        SOFTER.put("goddamn", "gosh darn");
        SOFTER.put("damned", "darned");
        SOFTER.put("damn", "dang");
        SOFTER.put("hell", "heck");
        SOFTER.put("asshole", "jerk");
        SOFTER.put("ass", "butt");
        SOFTER.put("bastard", "rascal");
        SOFTER.put("bitch", "pain");
        SOFTER.put("pissed", "ticked");
        SOFTER.put("piss", "pee");
        SOFTER.put("crap", "crud");
        SOFTER.put("wtf", "wth");
        SOFTER.put("stfu", "shush");
        SOFTER.put("lmfao", "lol");
        SOFTER.put("lmao", "lol");
        SOFTER.put("af", "really");
    }

    private static final Pattern PROFANE = Pattern.compile("\\b(" + String.join("|", SOFTER.keySet()) + ")\\b", Pattern.CASE_INSENSITIVE);

    /** Whether the line may never be said, whoever hears it. */
    public static boolean blocked(String text) {
        return text != null && BLOCKED.matcher(text).find();
    }

    /** Whether the line has a swear word in it. */
    public static boolean profane(String text) {
        return text != null && PROFANE.matcher(text).find();
    }

    /** The line with every swear word swapped for its clean stand-in, in the same case ("Damn" for "Dang"). */
    public static String soften(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = PROFANE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = m.group(1);
            String clean = SOFTER.get(found.toLowerCase(Locale.ROOT));
            m.appendReplacement(out, Matcher.quoteReplacement(sameCase(found, clean)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String sameCase(String like, String word) {
        if (like.length() > 1 && like.equals(like.toUpperCase(Locale.ROOT))) return word.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(like.charAt(0))) return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return word;
    }
}
