package dev.kindred.model;

import dev.kindred.core.chronicle.Chronicle;

import java.util.List;

/**
 * In plain terms: what the model is told when it writes one page of the Chronicle: write a short account of this
 * player's day (or this faction's week, or the world's month) from what is listed and nothing else, name the
 * numbered events each part rests on, and never state a count, an owner or a price the list does not give.
 *
 * Technical: F-33b's prompt. The instructions are the same on every request (cached); the request lists the tier
 * below as numbered lines ({@code [id] kind: text} for events, {@code (cites ...) text} for summaries). The reply is
 * structured: the words and the cited ids; core's {@link Chronicle#check} then decides whether it is kept.
 */
public final class ChroniclePrompt {
    private ChroniclePrompt() { }

    public static final String SYSTEM = """
            You keep the Chronicle of a Minecraft survival world played by people and AI players. You write one page: \
            a short account (60 to 120 words, plain past tense) of one player's game day, one faction's or settlement's \
            week, or the world's month, from the lines you are given and nothing else.

            Rules:
            - Use only what the lines say. Never add an event, a place, a reason or a feeling they do not give.
            - The lines are in the order they happened: tell them in that order, and never say one came before, after \
            or between others against it.
            - Cite the number of every line your account rests on, and only numbers you were given.
            - State a count, an owner or a price only exactly as the lines give it; when unsure, leave it out.
            - Something a line marks as said or claimed is told as said, never as fact.
            - Say nothing of the rules, of yourself, or of what you were asked.""";

    public static final String TEXT = "The account: 60 to 120 words, plain past tense, from the lines only.";
    public static final String CITES = "The numbers of the lines the account rests on, each one given.";

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

    /** A summary of the tier below as a numbered line: its first citation numbers it, all its citations carried. */
    public static String line(Chronicle.Summary s) {
        return "(cites " + s.cites().stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("none") + ") " + s.text();
    }
}
