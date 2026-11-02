package dev.kindred.core.gift;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In plain terms: when an AI player has something to spare and someone near has said they need it, it offers some: never
 * what it needs itself (its tools, armour and the food it keeps), never what it has promised to a teammate or still has to
 * gather for its goal, and at most half of what it could spare, so that a gift never leaves it short. A gift is offered,
 * never pushed; the person says yes.
 *
 * Technical: E-21a, pure. {@link #spare} reads the player's own record of its unpromised finds (E-50's {@code PeerUpdate}
 * spare list, which already leaves out what the team's share-out promised and what the goal's follow-ups still want) and
 * takes away what is never given; {@link #offerFor} matches a person's stated need to it. The adapter offers through E-53's
 * attention budget and hands over through the toss method on a yes; E-22a verifies the receipt.
 */
public final class GiftPolicy {
    private GiftPolicy() { }

    /** At most this many of anything in one gift. */
    public static final int MOST = 16;

    /** Something it could give: the item's words ("iron ingot") and how many. */
    public record Spare(String item, int count) {
        public Spare {
            Objects.requireNonNull(item);
            if (count < 0) throw new IllegalArgumentException("Negative spare");
        }

        /** The item's id as the toss method takes it: "iron_ingot". */
        public String id() { return item.strip().toLowerCase(Locale.ROOT).replace(' ', '_'); }
    }

    /** A gift to offer: what, how many, and the need it answers. */
    public record Offer(String item, int count, String need) { }

    private static final Pattern COUNTED = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s*$");

    /** Never given, whatever is spare: a player's tools, weapons, armour and the things a run depends on. */
    private static final Pattern NEVER = Pattern.compile(
            ".*(pickaxe|axe|shovel|hoe|sword|bow|crossbow|shield|helmet|chestplate|leggings|boots|bucket|flint and steel|ender pearl|eye of ender|totem|elytra|bed).*");

    /** The things in its own spare list ("4 iron ingot", "2 diamond") that it may give, half of each, at most {@value #MOST}. */
    public static List<Spare> spare(List<String> unpromised) {
        var out = new ArrayList<Spare>();
        if (unpromised == null) return out;
        for (String line : unpromised) {
            if (line == null) continue;
            Matcher m = COUNTED.matcher(line.toLowerCase(Locale.ROOT));
            if (!m.matches()) continue;
            int count = Integer.parseInt(m.group(1));
            String item = m.group(2);
            if (NEVER.matcher(item).matches()) continue;
            int give = Math.min(MOST, count / 2);
            if (give > 0) out.add(new Spare(item, give));
        }
        return out;
    }

    /**
     * What to offer for a need someone said they have ("iron", "some torches", "food"): the spare thing whose words the need
     * names, or names it ("iron" for "iron ingot", "torches" for "torch"); empty when nothing spare answers it.
     */
    public static Optional<Offer> offerFor(String need, List<Spare> spare) {
        if (need == null || need.isBlank() || spare == null) return Optional.empty();
        String wanted = singular(need.toLowerCase(Locale.ROOT).replaceAll("^(some|a few|a|an|more)\\s+", "").strip());
        for (Spare s : spare) {
            String item = singular(s.item());
            if (item.equals(wanted) || item.contains(wanted) || wanted.contains(item)) {
                return Optional.of(new Offer(s.id(), s.count(), need.strip()));
            }
        }
        return Optional.empty();
    }

    private static String singular(String words) {
        String w = words.strip();
        if (w.endsWith("ches") || w.endsWith("shes")) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }
}
