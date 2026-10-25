package dev.kindred.core.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a player tells its team of a find (E-40a, F-18): what it found, counted from what it holds, and who gets which
 * piece of it, from {@link ShareOut}. Only what it holds and the share-out say; nothing promised that is not there.
 */
public final class TeamTalk {
    private TeamTalk() { }

    /**
     * "I've got 11 diamonds: diamond boots and a diamond helmet for Wren, and a diamond sword for me." With nothing to
     * make of it for anyone, it says so.
     */
    public static String find(UUID self, ShareOut.Material material, int count, List<ShareOut.Grant> grants) {
        String got = "I've got " + count + " " + of(material, count);
        if (grants.isEmpty()) return got + "; nobody needs anything made of " + (count == 1 ? "it" : "them") + " yet.";
        var byPlayer = new LinkedHashMap<String, List<String>>();
        for (var grant : grants) {
            String who = grant.to().equals(self) ? "me" : grant.name();
            byPlayer.computeIfAbsent(who, k -> new ArrayList<>()).add(piece(grant.slot(), grant.material()));
        }
        var parts = new ArrayList<String>();
        for (Map.Entry<String, List<String>> e : byPlayer.entrySet()) parts.add(list(e.getValue()) + " for " + e.getKey());
        return got + ": " + list(parts) + ".";
    }

    /**
     * What a player tells its team when it puts something into, or takes something out of, the team's storage (E-43),
     * from the counts before and after: "I put 12 iron ingots in our chest at 12, 64, -30.", "I took a bucket from our
     * chest at 12, 64, -30." So every teammate's count of what the team has stored stays true.
     */
    public static String stored(boolean put, Storage.Place place, Map<String, Integer> items) {
        String where = place.words().replaceFirst("^the ", "our ");
        return "I " + (put ? "put " : "took ") + Missing.items(items) + (put ? " in " : " from ") + where + ".";
    }

    /** "11 diamonds", "an iron ingot", "4 gold ingots". */
    static String of(ShareOut.Material material, int count) {
        String one = switch (material) {
            case DIAMOND -> "diamond";
            case IRON -> "iron ingot";
            case GOLD -> "gold ingot";
            case NETHERITE -> "netherite ingot";
            case LEATHER -> "leather";
            case CHAIN, NONE -> "piece of chain";
        };
        return material == ShareOut.Material.LEATHER || count == 1 ? one : one + "s";
    }

    /** "a diamond helmet", "diamond boots", "a netherite chestplate". */
    static String piece(ShareOut.Slot slot, ShareOut.Material material) {
        String name = material.name().toLowerCase(Locale.ROOT) + " " + switch (slot) {
            case HELMET -> "helmet";
            case CHESTPLATE -> "chestplate";
            case LEGGINGS -> "leggings";
            case BOOTS -> "boots";
            case SWORD -> "sword";
            case PICKAXE -> "pickaxe";
            case AXE -> "axe";
        };
        return slot == ShareOut.Slot.LEGGINGS || slot == ShareOut.Slot.BOOTS ? name : (material == ShareOut.Material.IRON ? "an " : "a ") + name;
    }

    /** "a, b and c". */
    private static String list(List<String> items) {
        if (items.size() == 1) return items.getFirst();
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
