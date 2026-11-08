package dev.kindred.model;

import dev.kindred.core.chronicle.Chronicle;

import java.util.List;

/**
 * In plain terms: what the model is told when it writes one page of the Chronicle: one short sentence for each
 * numbered event of this player's day (or this faction's week, or the world's month), from what is listed and nothing
 * else, never a count, an owner or a price the list does not give. The sentences are put in the order things happened.
 *
 * Technical: F-33b's prompt. The instructions are the same on every request (cached); the request lists the tier
 * below as numbered lines ({@code [id] kind: text}). The reply is structured: one sentence a line with its number,
 * which {@link #page} puts in the lines' order, so the model never orders events; core's {@link Chronicle#check} then
 * decides whether it is kept.
 */
public final class ChroniclePrompt {
    private ChroniclePrompt() { }

    public static final String SYSTEM = """
            You keep the Chronicle of a Minecraft survival world played by people and AI players. You write one page: \
            one player's game day, one faction's or settlement's week, or the world's month, from the numbered lines \
            you are given and nothing else.

            Write short sentences in plain past tense, as a chronicler would, each giving the numbers of the lines it \
            tells. Tell like lines together in one sentence ("Wren mined coal four times"), and every death, capture, \
            sale or promise in a sentence of its own. At most twelve sentences.
            - Use only what the line says. Add no event, place, reason or feeling it does not give.
            - Give a count, an owner or a price only exactly as the line gives it.
            - Something a line marks as said or claimed is told as said, never as fact.""";

    public static final String SENTENCES = "At most twelve sentences, each with the numbers of the lines it tells.";
    public static final String LINE = "The numbers of the lines this sentence tells, each as given.";
    public static final String SAY = "One short sentence, plain past tense, about those lines only.";

    /**
     * A page from the model's sentences: put in the order of each one's first line number (the Record's ids, so the
     * order things happened), joined, every number cited. The order is the lines', never the model's. A sentence with
     * no number is dropped.
     */
    public static Strategist.Page page(List<long[]> numbersAndIndexes, List<String> sentences, long in, long cached, long out, long millis) {
        var order = new java.util.ArrayList<Integer>();
        for (int i = 0; i < sentences.size(); i++) if (numbersAndIndexes.get(i).length > 0) order.add(i);
        order.sort(java.util.Comparator.comparingLong(i -> java.util.Arrays.stream(numbersAndIndexes.get(i)).min().orElse(Long.MAX_VALUE)));
        var text = new StringBuilder();
        var cites = new java.util.ArrayList<Long>();
        for (int i : order) {
            String said = sentences.get(i) == null ? "" : sentences.get(i).strip();
            if (said.isEmpty()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(said.endsWith(".") || said.endsWith("!") || said.endsWith("?") ? said : said + ".");
            for (long n : numbersAndIndexes.get(i)) if (!cites.contains(n)) cites.add(n);
        }
        if (text.isEmpty()) return Strategist.Page.failed("no sentences", in, cached, out, millis);
        return new Strategist.Page(java.util.Optional.of(text.toString()), cites, in, cached, out, millis, java.util.Optional.empty());
    }

    /** The request for one page: what it covers, then the tier below as numbered lines. */
    public static String request(Chronicle.Tier tier, String scopeName, long from, List<String> lines) {
        var out = new StringBuilder();
        String what = switch (tier) {
            case PLAYER_DAY -> "the game day " + (from / Chronicle.DAY + 1) + " of " + scopeName;
            case FACTION_WEEK -> "the week from day " + (from / Chronicle.DAY + 1) + " of the faction " + scopeName;
            case SETTLEMENT_WEEK -> "the week from day " + (from / Chronicle.DAY + 1) + " of the settlement " + scopeName;
            case WORLD_MONTH -> "the month from day " + (from / Chronicle.DAY + 1) + " of the world";
        };
        out.append("Write the page for ").append(what).append(". Its lines, in the order they happened:\n");
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    /** An event as a numbered line. */
    public static String line(Chronicle.Event e) { return "[" + e.id() + "] " + e.kind() + ": " + e.text(); }

    /**
     * A summary of the tier below as a numbered line: numbered by its first citation (so the lines keep the order things
     * happened), its text after; a sentence citing it cites that number, which is one of the events it rests on.
     */
    public static String line(Chronicle.Summary s) {
        return "[" + (s.cites().isEmpty() ? 0 : s.cites().getFirst()) + "] " + s.tier().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ": " + s.text();
    }
}
