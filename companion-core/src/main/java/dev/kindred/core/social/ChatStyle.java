package dev.kindred.core.social;

import dev.kindred.core.Personality;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In plain terms: how a player types in chat, like the people it plays with: some type properly, some in lower case and
 * shorthand ("ur" for "your", "rn", "idk"), some leave out every full stop and comma and throw in a "lol" or "ngl". Its
 * personality picks one; whoever may command it can choose another. It changes how a line looks, never what it says: names,
 * numbers, coordinates and item names come through as they were.
 *
 * Technical: E-18b. Four presets ordered from proper to chaotic, applied last to every line an AI player says (after the
 * words are chosen and checked). Deterministic: the same line from the same player always types the same way, so fixtures
 * can predict it. A player with none set takes one from its personality: how loose its humour, sociability and lack of
 * caution make it ({@link #forPlayer}).
 */
public enum ChatStyle {
    /** Capitals, full stops and whole words. */
    PROPER(false, Punctuation.FULL, 0, 0),
    /** Lower case, no full stop at the end, the common shorthand ("u", "ur", "rn", "idk"). */
    CASUAL(true, Punctuation.LIGHT, 1, 0),
    /** Lower case, no punctuation at all, more shorthand, and now and then its own word ("ngl", "fr", "lol"). */
    GAMER(true, Punctuation.NONE, 2, 1),
    /** As a gamer, with its word more often and a stretched word when it is pleased ("niceee"). */
    CHAOTIC(true, Punctuation.NONE, 2, 2);

    /** How much punctuation survives. */
    public enum Punctuation { FULL, LIGHT, NONE }

    private final boolean lowercase;
    private final Punctuation punctuation;
    private final int shorthand;
    private final int slang;

    ChatStyle(boolean lowercase, Punctuation punctuation, int shorthand, int slang) {
        this.lowercase = lowercase;
        this.punctuation = punctuation;
        this.shorthand = shorthand;
        this.slang = slang;
    }

    public boolean lowercase() { return lowercase; }
    public Punctuation punctuation() { return punctuation; }
    public int shorthand() { return shorthand; }
    public int slang() { return slang; }

    /** The style named {@code proper}, {@code casual}, {@code gamer} or {@code chaotic}, any case; empty for anything else. */
    public static Optional<ChatStyle> parse(String name) {
        if (name == null) return Optional.empty();
        for (ChatStyle style : values()) if (style.name().equalsIgnoreCase(name.strip())) return Optional.of(style);
        return Optional.empty();
    }

    /** Its name as a command takes it. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /**
     * The style a player with none set types in: the looser its humour and sociability and the less its caution, the more
     * it types like a player in a hurry. Kindred types casually, Sol like a gamer, Echo properly; an even personality
     * types properly.
     */
    public static ChatStyle forPlayer(Personality personality) {
        if (personality == null) return PROPER;
        int looseness = (personality.humor() + personality.sociability() + (100 - personality.caution())) / 3;
        if (looseness >= 75) return CHAOTIC;
        if (looseness >= 62) return GAMER;
        if (looseness >= 55) return CASUAL;
        return PROPER;
    }

    /** Its own word, said now and then by a gamer or a chaotic typist: one of a few, the same for a player always. */
    public static String signature(UUID player) {
        List<String> words = List.of("ngl", "fr", "lol", "xd", "lmao", "bruh");
        return words.get(Math.floorMod(player == null ? 0 : player.hashCode(), words.size()));
    }

    /** Whole words and phrases, and what a player in a hurry types instead; in order, phrases before their words. */
    private static final String[][] SHORT_ONE = {
            {"to be honest", "tbh"}, {"i don't know", "idk"}, {"i do not know", "idk"}, {"right now", "rn"},
            {"be right back", "brb"}, {"on my way", "omw"}, {"oh my god", "omg"}, {"good game", "gg"},
            {"want to", "wanna"}, {"kind of", "kinda"}, {"let me", "lemme"},
            {"you are", "ur"}, {"you're", "ur"}, {"your", "ur"}, {"you", "u"}, {"okay", "ok"}, {"thanks", "thx"},
            {"thank you", "ty"}, {"please", "pls"}, {"because", "cuz"}, {"probably", "prob"}, {"people", "ppl"},
            {"though", "tho"}, {"i'm", "im"}, {"i am", "im"}, {"don't", "dont"}, {"can't", "cant"}, {"won't", "wont"},
            {"it's", "its"}, {"that's", "thats"}, {"what's", "whats"}, {"there's", "theres"}, {"let's", "lets"},
            {"i've", "ive"}, {"isn't", "isnt"}, {"didn't", "didnt"}, {"doesn't", "doesnt"}};
    private static final String[][] SHORT_TWO = {
            {"something", "smth"}, {"are", "r"}, {"with", "w"}, {"really", "rly"}, {"seriously", "srsly"},
            {"tonight", "tn"}, {"later", "l8r"}, {"before", "b4"}, {"great", "gr8"}};

    private static final Pattern NAMES_AND_NUMBERS = Pattern.compile("-?\\d+(?:[.,]\\d+)*|[a-z_]+:[a-z_/]+");

    /**
     * The line as this style types it. {@code keep} are words left exactly as they are (the names of the people and
     * players in it); {@code salt} varies which lines get its own word, the same salt giving the same line.
     */
    public String apply(String text, UUID speaker, List<String> keep, long salt) {
        if (text == null || text.isBlank() || this == PROPER) return text;
        String out = text;
        // Shorthand, on whole words only.
        out = replaceAll(out, SHORT_ONE);
        if (shorthand >= 2) out = replaceAll(out, SHORT_TWO);
        // Punctuation: a light hand drops the full stop at the end and apostrophes; none drops all of it but what keeps
        // numbers, coordinates, commands and item names whole (a minus before a digit, a slash, an underscore, a colon in an id).
        out = switch (punctuation) {
            case FULL -> out;
            case LIGHT -> out.replace("'", "").replaceAll("\\.(\\s*)$", "$1");
            case NONE -> strip(out);
        };
        if (lowercase) out = lower(out, keep);
        // A pleased last word stretched, and its own word now and then.
        if (slang >= 2 && Math.floorMod(salt, 7) == 1) out = stretch(out);
        if (slang >= 1 && Math.floorMod(salt, slang >= 2 ? 3 : 5) == 0 && !out.isBlank()) {
            out = out.stripTrailing() + " " + signature(speaker);
        }
        return out.replaceAll(" {2,}", " ").strip();
    }

    private static String replaceAll(String text, String[][] table) {
        String out = text;
        for (String[] pair : table) {
            Pattern p = Pattern.compile("(?<![\\w'])" + Pattern.quote(pair[0]) + "(?![\\w'])", Pattern.CASE_INSENSITIVE);
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(pair[1]));
        }
        return out;
    }

    /** Every punctuation mark gone but those inside numbers, ids, commands and names with underscores. */
    private static String strip(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digitNext = i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
            boolean digitBefore = i > 0 && Character.isDigit(text.charAt(i - 1));
            boolean wordBefore = i > 0 && Character.isLetterOrDigit(text.charAt(i - 1));
            boolean wordNext = i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i + 1));
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || c == '_' || c == '/') out.append(c);
            else if ((c == '\'' || c == '\u2019') && wordBefore && wordNext) continue; // "Sol's" to "Sols", not "Sol s".
            else if (c == '-' && digitNext) out.append(c); // A negative coordinate.
            else if ((c == '.' || c == ',') && digitBefore && digitNext) out.append(c); // 1.5, 1,000
            else if (c == ':' && wordBefore && wordNext) out.append(c); // minecraft:iron_ingot
            else if (c == '-' && wordBefore && wordNext) out.append(' ');
            else if (c == '?' && i == text.length() - 1) out.append(""); // A question still reads as one without its mark.
            else out.append(' ');
        }
        return out.toString();
    }

    /** Lower case, but for the words to keep (names) and ids. */
    private static String lower(String text, List<String> keep) {
        String lowered = text.toLowerCase(Locale.ROOT);
        if (keep == null || keep.isEmpty()) return lowered;
        String out = lowered;
        for (String name : keep) {
            if (name == null || name.isBlank()) continue;
            String key = name.toLowerCase(Locale.ROOT);
            // Players type names as they read them in chat; lower-case names are kept as typed.
            out = Pattern.compile("(?<![\\w])" + Pattern.quote(key) + "(?![\\w])").matcher(out).replaceAll(Matcher.quoteReplacement(name));
        }
        return out;
    }

    /** A pleased last word stretched: "nice" to "niceee", "yes" to "yesss", "lets go" to "lets gooo". */
    private static String stretch(String text) {
        Matcher m = Pattern.compile("\\b(nice|yes|lets go|finally|done)\\s*$", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return text;
        String word = m.group(1);
        char last = word.charAt(word.length() - 1);
        return text.substring(0, m.start(1)) + word + String.valueOf(last).repeat(2);
    }

    /** Numbers, coordinates and ids in a line, for a check that styling never changed them. */
    public static List<String> facts(String text) {
        var out = new java.util.ArrayList<String>();
        Matcher m = NAMES_AND_NUMBERS.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }
}
