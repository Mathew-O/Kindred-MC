package dev.kindred.core.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: who on the team gets what made from a find such as diamonds or iron: the piece that does
 * the team the most good goes first, to whoever needs it most, with the team's first diamond pickaxe ahead of
 * everything. Nobody gets a piece no better than what they have, and what is too little to use is kept.
 *
 * Technical: A team's finds shared out by what each player lacks (E-40a), as players on one run do: "you take
 * the chestplate, I've got one; you need the pickaxe for the obsidian". Pure: the adapter says who is on the
 * team, what each wears and holds of the kit that matters, and what the work each is on; this says who gets
 * which piece made from a find, such as diamonds or iron.
 *
 * <ul>
 *   <li>Essential first: the team's first diamond pickaxe (obsidian for the portal needs one) goes to whoever is
 *       mining, before any armour.</li>
 *   <li>Then the piece that does the team most good for what it costs: the most protection, or damage, gained for each
 *       ingot or gem, weighted by the work the player is on (the one going to the Nether values its armour more, the
 *       one mining its pickaxe).</li>
 *   <li>Between equals, the player worst protected; then the lowest id, so every player reckons it alike.</li>
 *   <li>Nobody is given a piece no better than the one it has, and what is too little for any piece is kept.</li>
 *   <li>Gold is made into armour only (a gold tool is worse than stone), and one piece of it goes first to whoever is
 *       going to the Nether wearing none, so that piglins leave it alone: in place of an iron helmet, which is no
 *       better protection.</li>
 *   <li>Netherite only makes a diamond piece better, an ingot each.</li>
 * </ul>
 * Items are only ever made from what the team has found and passed by hand: nothing here makes or moves an item.
 */
public final class ShareOut {
    private ShareOut() { }

    /** Kit that a find can be made into, with what one piece costs. */
    public enum Slot {
        HELMET(5), CHESTPLATE(8), LEGGINGS(7), BOOTS(4), SWORD(2), PICKAXE(3), AXE(3);

        private final int cost;

        Slot(int cost) { this.cost = cost; }

        /** Ingots or gems for one piece. */
        public int cost() { return cost; }

        public boolean armour() { return ordinal() <= BOOTS.ordinal(); }
    }

    /** What a piece is made of, worst to best. */
    public enum Material { NONE, LEATHER, GOLD, CHAIN, IRON, DIAMOND, NETHERITE }

    /** The work a player is on, which weighs what a piece is worth to it. */
    public enum Work { GATHERING, NETHER, STRONGHOLD, BUILDING }

    /** One player on the team: its id, its name, the kit it has, and its work. */
    public record Member(UUID id, String name, Map<Slot, Material> kit, Work work) {
        public Member {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(work);
            var copy = new EnumMap<Slot, Material>(Slot.class);
            for (Slot slot : Slot.values()) copy.put(slot, kit == null ? Material.NONE : kit.getOrDefault(slot, Material.NONE));
            kit = Map.copyOf(copy);
        }

        Material has(Slot slot) { return kit.get(slot); }

        /** Armour points it wears. */
        int protection() {
            int points = 0;
            for (Slot slot : Slot.values()) if (slot.armour()) points += armour(slot, has(slot));
            return points;
        }

        /** The same player with one piece of kit changed. */
        Member with(Slot slot, Material material) {
            var next = new EnumMap<Slot, Material>(kit);
            next.put(slot, material);
            return new Member(id, name, next, work);
        }
    }

    /** One piece made from the find, for one player. */
    public record Grant(UUID to, String name, Slot slot, Material material, int cost) { }

    /**
     * Who gets what made of {@code count} of {@code material}, in the order to make it. Pieces no one would be better
     * for are not made; what is left over is kept.
     */
    public static List<Grant> share(List<Member> team, Material material, int count) {
        Objects.requireNonNull(material);
        if (count < 0) throw new IllegalArgumentException("A count of a find is never below nothing");
        var members = new ArrayList<>(team);
        var grants = new ArrayList<Grant>();
        int left = count;
        while (true) {
            Grant best = null;
            double bestValue = 0;
            int bestIndex = -1;
            boolean pickaxeInTeam = material == Material.DIAMOND
                    && members.stream().anyMatch(m -> m.has(Slot.PICKAXE).compareTo(Material.DIAMOND) >= 0);
            for (int i = 0; i < members.size(); i++) {
                var member = members.get(i);
                for (Slot slot : Slot.values()) {
                    int cost = cost(slot, material);
                    if (cost > left) continue;
                    double value = value(member, slot, material, pickaxeInTeam);
                    if (!(value > 0)) continue;
                    var grant = new Grant(member.id(), member.name(), slot, material, cost);
                    if (best == null || value > bestValue + 1e-9
                            || Math.abs(value - bestValue) <= 1e-9 && better(member, members.get(bestIndex))) {
                        best = grant;
                        bestValue = value;
                        bestIndex = i;
                    }
                }
            }
            if (best == null) return List.copyOf(grants);
            grants.add(best);
            left -= best.cost();
            members.set(bestIndex, members.get(bestIndex).with(best.slot(), material));
        }
    }

    /** What a piece is worth to a player for each ingot or gem of it; nothing when it is not to be made for it. */
    private static double value(Member member, Slot slot, Material material, boolean pickaxeInTeam) {
        Material has = member.has(slot);
        if (!makeable(slot, material)) return 0;
        if (material == Material.NETHERITE && has != Material.DIAMOND) return 0;
        // A gold piece against piglins, for the one going to the Nether in none: where it costs no protection.
        boolean piglins = material == Material.GOLD && slot.armour() && member.work() == Work.NETHER && !wearsGold(member)
                && armour(slot, Material.GOLD) >= armour(slot, has);
        if (has.compareTo(material) >= 0 && !piglins) return 0;
        double value = gain(slot, has, material) * weight(member.work(), slot) / cost(slot, material);
        if (piglins) value += 500;
        // The team's first diamond pickaxe, for its obsidian, before anything else; for whoever mines.
        if (slot == Slot.PICKAXE && material == Material.DIAMOND && !pickaxeInTeam) {
            value += 1000 + (member.work() == Work.GATHERING ? 100 : 0);
        }
        return value;
    }

    /** Whether a piece can be made of it: tools and weapons of iron, diamond or netherite, armour of any but chain. */
    static boolean makeable(Slot slot, Material material) {
        return switch (material) {
            case NONE, CHAIN -> false;
            case LEATHER, GOLD -> slot.armour();
            case IRON, DIAMOND, NETHERITE -> true;
        };
    }

    /** What one piece costs of it: a netherite ingot upgrades a diamond piece; any other is the piece's own count. */
    static int cost(Slot slot, Material material) {
        return material == Material.NETHERITE ? 1 : slot.cost();
    }

    private static boolean wearsGold(Member member) {
        for (Slot slot : Slot.values()) if (slot.armour() && member.has(slot) == Material.GOLD) return true;
        return false;
    }

    /** Of two the find would do as much for, the one worse protected; then the lower id. */
    private static boolean better(Member a, Member b) {
        return Comparator.comparingInt(Member::protection).thenComparing(Member::id).compare(a, b) < 0;
    }

    /** What a piece gains it: armour points, or for a weapon or tool, the step up in tier. */
    public static double gain(Slot slot, Material from, Material to) {
        if (slot.armour()) return armour(slot, to) - armour(slot, from) + toughness(to) - toughness(from);
        return switch (slot) {
            case SWORD -> swordDamage(to) - swordDamage(from);
            // A pickaxe's worth is what it opens up and how fast it digs, a step a tier above the stone one every player
            // has; an axe only cuts wood faster, and is worth little more than its stone one.
            case PICKAXE -> toolTier(to) - toolTier(from);
            default -> 0.3 * (toolTier(to) - toolTier(from));
        };
    }

    /** A tool's tier, counted from the stone one every player makes first. */
    private static int toolTier(Material material) {
        return Math.max(0, material.ordinal() - Material.CHAIN.ordinal());
    }

    private static double weight(Work work, Slot slot) {
        return switch (work) {
            // Blazes, fire and the fortress: armour and the sword.
            case NETHER -> slot.armour() ? 1.5 : slot == Slot.SWORD ? 1.3 : 0.8;
            // Ore below: the pickaxe most.
            case GATHERING -> slot == Slot.PICKAXE ? 1.5 : 1.0;
            // Out across the land and into a stronghold's halls: armour and the sword a little more.
            case STRONGHOLD -> slot.armour() || slot == Slot.SWORD ? 1.2 : 0.9;
            case BUILDING -> slot == Slot.AXE || slot == Slot.PICKAXE ? 1.2 : 0.9;
        };
    }

    /** Vanilla 1.21.1's armour points for a piece. */
    static int armour(Slot slot, Material material) {
        int[] points = switch (material) {
            case NONE -> new int[] {0, 0, 0, 0};
            case LEATHER -> new int[] {1, 3, 2, 1};
            case GOLD -> new int[] {2, 5, 3, 1};
            case CHAIN -> new int[] {2, 5, 4, 1};
            case IRON -> new int[] {2, 6, 5, 2};
            case DIAMOND, NETHERITE -> new int[] {3, 8, 6, 3};
        };
        return points[slot.ordinal()];
    }

    private static double toughness(Material material) {
        return material == Material.DIAMOND ? 2 : material == Material.NETHERITE ? 3 : 0;
    }

    private static double swordDamage(Material material) {
        return switch (material) {
            case NONE, LEATHER, CHAIN -> 1;
            case GOLD -> 4;
            case IRON -> 6;
            case DIAMOND -> 7;
            case NETHERITE -> 8;
        };
    }
}
