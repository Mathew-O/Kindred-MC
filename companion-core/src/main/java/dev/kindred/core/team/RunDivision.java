package dev.kindred.core.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A run divided between the players on it (E-40a), as players share one out: while one goes to the Nether for blaze
 * rods and pearls, another gathers iron and diamonds, food, wool for beds and arrows, and once there are eyes enough,
 * one goes to find the stronghold; at the end they all meet there for the End. Pure: the adapter says who is on the
 * team, what each has of the kit that decides it, and where the run stands; this says what each is to do, and why.
 *
 * <ul>
 *   <li>Before the portal is lit, everyone gathers: the one with the best pickaxe digs for the portal's obsidian.</li>
 *   <li>With the portal lit and rods or pearls still short, the best protected goes to the Nether. A third goes too,
 *       for pearls from piglins, if it has gold to trade; otherwise it gathers.</li>
 *   <li>With eyes enough, the one with the most of them finds the stronghold; the rest gather for the End: beds,
 *       arrows, blocks.</li>
 *   <li>With the stronghold found and the team ready, all meet there.</li>
 *   <li>Between equals, the lower id, so every player reckons it alike. A lone player does each part itself, in
 *       turn.</li>
 * </ul>
 */
public final class RunDivision {
    private RunDivision() { }

    /** Blaze rods and ender pearls enough for twelve eyes, as a run sets out to have. */
    public static final int RODS = 6, PEARLS = 12, EYES = 12;

    /** Where the run stands, for the whole team. */
    public record Run(boolean portalLit, int blazeRods, int enderPearls, int eyes, boolean strongholdFound, boolean readyForEnd) {
        public Run {
            if (blazeRods < 0 || enderPearls < 0 || eyes < 0) throw new IllegalArgumentException("No count below nothing");
        }
    }

    /** One player as the division sees it: its armour points, its pickaxe's tier, the gold it holds and its eyes. */
    public record Player(UUID id, String name, int protection, ShareOut.Material pickaxe, int gold, int eyes) {
        public Player {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(pickaxe);
        }
    }

    /** What a player is to do, and why, in words it can say. */
    public record Part(ShareOut.Work work, String why) { }

    /** Each player's part, in the team's order. */
    public static Map<UUID, Part> divide(List<Player> team, Run run) {
        var parts = new LinkedHashMap<UUID, Part>();
        if (team.isEmpty()) return parts;
        var byId = new ArrayList<>(team);
        byId.sort(Comparator.comparing(Player::id));
        if (run.strongholdFound() && run.readyForEnd()) {
            for (Player p : byId) parts.put(p.id(), new Part(ShareOut.Work.STRONGHOLD, "To the stronghold, to meet for the End"));
            return parts;
        }
        if (!run.portalLit()) {
            Player digger = best(byId, Comparator.comparing(Player::pickaxe));
            for (Player p : byId) {
                parts.put(p.id(), p == digger ? new Part(ShareOut.Work.GATHERING, "Obsidian for the portal, with the best pickaxe")
                        : new Part(ShareOut.Work.GATHERING, "Iron, food and wood for the run"));
            }
            return parts;
        }
        boolean eyesEnough = run.eyes() >= EYES;
        if (!eyesEnough && (run.blazeRods() < RODS || run.enderPearls() < PEARLS)) {
            Player nether = best(byId, Comparator.comparingInt(Player::protection));
            parts.put(nether.id(), new Part(ShareOut.Work.NETHER, run.blazeRods() < RODS
                    ? "Blaze rods from a fortress, the best protected" : "Pearls, the best protected"));
            for (Player p : byId) {
                if (parts.containsKey(p.id())) continue;
                // A second to the Nether, for pearls from piglins, only with gold to trade and pearls short.
                boolean barter = byId.size() >= 3 && p.gold() >= 8 && run.enderPearls() < PEARLS
                        && parts.values().stream().filter(part -> part.work() == ShareOut.Work.NETHER).count() < 2;
                parts.put(p.id(), barter ? new Part(ShareOut.Work.NETHER, "Pearls from piglins, with gold to trade")
                        : new Part(ShareOut.Work.GATHERING, "Food, wool for beds, and arrows for the End"));
            }
            return parts;
        }
        Player finder = best(byId, Comparator.comparingInt(Player::eyes));
        for (Player p : byId) {
            parts.put(p.id(), p == finder ? new Part(ShareOut.Work.STRONGHOLD, run.strongholdFound()
                    ? "At the stronghold, the others coming" : "The stronghold, with the most eyes")
                    : new Part(ShareOut.Work.GATHERING, "Beds, arrows and blocks for the End"));
        }
        return parts;
    }

    /** The first of the team by most of {@code order}, the lowest id between equals. */
    private static Player best(List<Player> byId, Comparator<Player> order) {
        Player best = byId.getFirst();
        for (Player p : byId) if (order.compare(p, best) > 0) best = p;
        return best;
    }
}
