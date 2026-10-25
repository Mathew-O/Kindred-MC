package dev.kindred.core.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a player does when something it stored is gone (E-43), by what it saw and the mode it plays, as the owner set
 * it out (2026-10-25): in co-op it asks its team before reckoning anything lost; in a match others play against it, it
 * reckons its storage raided and may move what is left; alone, it reckons the items lost. In every mode it never
 * accuses anyone it did not see take them, never retaliates (rapport never justifies revenge), and names whoever it saw
 * take them only to its own team, or with no team, to those it plays for. Speech is of what it counted and saw, nothing
 * more. Pure: the adapter says what a look found, who was seen at the container, and what is left there worth.
 */
public final class Missing {
    private Missing() { }

    /** How a player plays where the container is. */
    public enum Mode {
        /** Together with teammates (co-op): there is a team to ask. */
        TEAM,
        /** Nobody to ask. */
        ALONE,
        /** In a match others play against it (Manhunt, Factions): its storage is somewhere others may raid. */
        MATCH
    }

    /** Who it says it to. */
    public enum Audience {
        /** Its own team only. */
        TEAM,
        /** Those it plays for: its controllers, with no team to tell. */
        OWN,
        /** Nobody. */
        NOBODY
    }

    /** Someone the player saw at the container while it was away: their name, and whether they are on its team. */
    public record Seen(String name, boolean teammate) {
        public Seen {
            Objects.requireNonNull(name);
        }
    }

    /**
     * What it does: what it says and to whom; whether it asks its team and waits for a word before reckoning the items
     * lost; and whether it moves what is left somewhere safer.
     */
    public record Response(String say, Audience to, boolean ask, boolean moveRest) {
        static final Response NOTHING = new Response(null, Audience.NOBODY, false, false);
    }

    /** Minutes' worth left in a raided container that it is worth moving somewhere safer. */
    static final double MOVE_WORTH = 5;

    /**
     * What a player does about a look that found things gone: {@code seen} is who it saw at the container while away,
     * if anyone; {@code worthLeft} what is still in it is worth to it, in minutes.
     */
    public static Response respond(Mode mode, Storage.Look look, Optional<Seen> seen, double worthLeft) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(look);
        if (!look.isShort()) return Response.NOTHING;
        String what = items(look.gone());
        String where = where(look);
        if (seen.isPresent()) {
            Seen who = seen.get();
            if (who.teammate()) {
                // A teammate using what the team stores: noted to the team, so every count agrees; nothing to answer for.
                return new Response(who.name() + " took " + what + " from " + where + ".", Audience.TEAM, false, false);
            }
            boolean move = mode == Mode.MATCH && worthLeft >= MOVE_WORTH;
            return new Response("I saw " + who.name() + " take " + what + " from " + where + "." + (move ? " I'm moving the rest." : ""),
                    mode == Mode.ALONE ? Audience.OWN : Audience.TEAM, false, move);
        }
        return switch (mode) {
            case TEAM -> new Response("Did anyone take " + what + " from " + where + "?", Audience.TEAM, true, false);
            case MATCH -> {
                boolean move = worthLeft >= MOVE_WORTH;
                yield new Response("Someone has been in " + where + ": " + what + " gone." + (move ? " I'm moving the rest." : ""),
                        Audience.TEAM, false, move);
            }
            case ALONE -> new Response(capital(what) + (count(look.gone()) == 1 ? " is" : " are") + " gone from " + where + ".",
                    Audience.OWN, false, worthLeft >= MOVE_WORTH);
        };
    }

    /**
     * Asked in co-op and nobody said they took them: the items are reckoned lost, said to the team as a count, with no
     * one blamed.
     */
    public static Response unanswered(Storage.Look look) {
        if (!look.isShort()) return Response.NOTHING;
        String what = items(look.gone());
        return new Response("Nobody took them, so " + what + (count(look.gone()) == 1 ? " is" : " are") + " gone from " + where(look) + ".",
                Audience.TEAM, false, false);
    }

    private static String where(Storage.Look look) {
        String place = look.place().words();
        return look.whose() == Storage.Whose.TEAM ? place.replaceFirst("^the ", "our ") : place.replaceFirst("^the ", "my ");
    }

    private static int count(Map<String, Integer> items) {
        int total = 0;
        for (int n : items.values()) total += n;
        return total;
    }

    /** What players count without a plural: "3 bread", "1 coal". */
    private static final java.util.Set<String> MASS = java.util.Set.of("bread", "coal", "charcoal", "flint", "string", "leather",
            "gunpowder", "redstone", "cobblestone", "stone", "dirt", "sand", "gravel", "wheat", "sugar", "obsidian", "netherrack",
            "glass", "glowstone dust", "rotten flesh", "lapis lazuli", "cooked beef", "beef");

    /** "5 iron ingots, 3 bread and a diamond". */
    static String items(Map<String, Integer> items) {
        var parts = new ArrayList<String>();
        items.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1).replace('_', ' ');
            int n = entry.getValue();
            if (MASS.contains(name)) parts.add(n + " " + name);
            else parts.add(n == 1 ? (startsWithVowel(name) ? "an " : "a ") + name : n + " " + (name.endsWith("s") ? name : name + "s"));
        });
        return list(parts);
    }

    private static boolean startsWithVowel(String name) {
        return !name.isEmpty() && "aeiou".indexOf(name.charAt(0)) >= 0;
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String list(List<String> items) {
        if (items.size() == 1) return items.getFirst();
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
