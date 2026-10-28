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
 *   <li>Before the portal is lit, everyone gathers: the one with the best pickaxe digs for the portal's obsidian and
 *       builds the team's one portal, and keeps it once begun; the rest get iron, food and wood for the run.</li>
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

    /**
     * One player as the division sees it: its armour points, its pickaxe's tier, the gold it holds, its eyes, and
     * whether it has a portal of its own under way.
     */
    public record Player(UUID id, String name, int protection, ShareOut.Material pickaxe, int gold, int eyes, boolean portalBegun) {
        public Player {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(pickaxe);
        }

        public Player(UUID id, String name, int protection, ShareOut.Material pickaxe, int gold, int eyes) {
            this(id, name, protection, pickaxe, gold, eyes, false);
        }
    }

    /** The part itself, which steers what the player does. */
    public enum Role {
        /** Dig the obsidian and build the team's portal. */
        PORTAL,
        /** Iron, food and wood for the run while another builds the portal. */
        PROVISIONS,
        /** To the Nether for blaze rods, or for pearls once the rods are in. */
        NETHER,
        /** To the Nether for pearls from piglins, with gold to trade. */
        BARTER,
        /** Beds, arrows and blocks for the End. */
        END_KIT,
        /** Find the stronghold with the eyes, or wait there for the rest once found. */
        STRONGHOLD,
        /** To the stronghold, to meet for the End. */
        MEET
    }

    /** What a player is to do, as work and as its part, why, and what it says of it to the team as it takes it up. */
    public record Part(ShareOut.Work work, Role role, String why, String says) { }

    /** Each player's part, in the team's order. */
    public static Map<UUID, Part> divide(List<Player> team, Run run) {
        var parts = new LinkedHashMap<UUID, Part>();
        if (team.isEmpty()) return parts;
        var byId = new ArrayList<>(team);
        byId.sort(Comparator.comparing(Player::id));
        if (run.strongholdFound() && run.readyForEnd()) {
            for (Player p : byId) parts.put(p.id(), new Part(ShareOut.Work.STRONGHOLD, Role.MEET, "To the stronghold, to meet for the End",
                    "Heading to the stronghold for the End."));
            return parts;
        }
        if (!run.portalLit()) {
            // A portal under way stays its builder's, whatever pickaxe another comes to have meanwhile.
            Player digger = byId.stream().filter(Player::portalBegun).findFirst().orElseGet(() -> best(byId, Comparator.comparing(Player::pickaxe)));
            for (Player p : byId) {
                parts.put(p.id(), p == digger ? (p.portalBegun()
                        ? new Part(ShareOut.Work.GATHERING, Role.PORTAL, "The portal it has begun", "I'm building the portal.")
                        : new Part(ShareOut.Work.GATHERING, Role.PORTAL, "Obsidian for the portal, with the best pickaxe",
                                "I've got the best pickaxe; I'll dig the obsidian for the portal."))
                        : new Part(ShareOut.Work.GATHERING, Role.PROVISIONS, "Iron, food and wood for the run", "I'll get iron, food and wood."));
            }
            return parts;
        }
        boolean eyesEnough = run.eyes() >= EYES;
        if (!eyesEnough && (run.blazeRods() < RODS || run.enderPearls() < PEARLS)) {
            Player nether = best(byId, Comparator.comparingInt(Player::protection));
            parts.put(nether.id(), run.blazeRods() < RODS
                    ? new Part(ShareOut.Work.NETHER, Role.NETHER, "Blaze rods from a fortress, the best protected", "I'll go to the Nether for blaze rods.")
                    : new Part(ShareOut.Work.NETHER, Role.NETHER, "Pearls, the best protected", "I'll go to the Nether for pearls."));
            for (Player p : byId) {
                if (parts.containsKey(p.id())) continue;
                // A second to the Nether, for pearls from piglins, only with gold to trade and pearls short.
                boolean barter = byId.size() >= 3 && p.gold() >= 8 && run.enderPearls() < PEARLS
                        && parts.values().stream().filter(part -> part.work() == ShareOut.Work.NETHER).count() < 2;
                parts.put(p.id(), barter ? new Part(ShareOut.Work.NETHER, Role.BARTER, "Pearls from piglins, with gold to trade",
                        "I've got gold; I'll trade with the piglins for pearls.")
                        : new Part(ShareOut.Work.GATHERING, Role.END_KIT, "Food, wool for beds, and arrows for the End", "I'll get food, wool for beds, and arrows."));
            }
            return parts;
        }
        Player finder = best(byId, Comparator.comparingInt(Player::eyes));
        for (Player p : byId) {
            parts.put(p.id(), p == finder ? (run.strongholdFound()
                    ? new Part(ShareOut.Work.STRONGHOLD, Role.STRONGHOLD, "At the stronghold, the others coming", "I'm at the stronghold. Come when you're ready.")
                    : new Part(ShareOut.Work.STRONGHOLD, Role.STRONGHOLD, "The stronghold, with the most eyes", "I've got the most eyes; I'll find the stronghold."))
                    : new Part(ShareOut.Work.GATHERING, Role.END_KIT, "Beds, arrows and blocks for the End", "I'll get beds, arrows and blocks for the End."));
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
