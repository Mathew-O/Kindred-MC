package dev.kindred.core.team;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: turns a find shared out among the team into jobs for the player who found it: make each
 * piece and hand it to whoever gets it. The materials are set aside so the same find is never promised twice.
 *
 * Technical: A find shared out as the team's work (E-40a, E-11): the giver makes each piece {@link ShareOut}
 * grants from what it found, and hands over those for another player. One task for each piece, its own
 * included, so that what it tells its team it will make is all made; each held by the giver, its material
 * reserved from what the giver holds, so the same find is never promised twice and nothing else is made of it
 * meanwhile. Pure: nothing here makes or moves an item.
 */
public final class HandOvers {
    private HandOvers() { }

    /** The skill a hand-over task names: make the piece, and for another player take it over and toss it across. */
    public static final String SKILL = "native_hand_over";

    /** The ledger's key for what a player holds of an item: its own stock, which its promises are reserved from. */
    public static String held(UUID player, String item) {
        return "held:" + Objects.requireNonNull(player) + ":" + Objects.requireNonNull(item);
    }

    /** The item a material is counted in: "minecraft:diamond", "minecraft:iron_ingot". */
    public static String materialItem(ShareOut.Material material) {
        return switch (material) {
            case DIAMOND -> "minecraft:diamond";
            case IRON -> "minecraft:iron_ingot";
            case GOLD -> "minecraft:gold_ingot";
            case NETHERITE -> "minecraft:netherite_ingot";
            case LEATHER -> "minecraft:leather";
            case CHAIN, NONE -> throw new IllegalArgumentException("Nothing is made of " + material);
        };
    }

    /** The item a piece is: "minecraft:diamond_boots", "minecraft:golden_helmet", "minecraft:iron_sword". */
    public static String pieceItem(ShareOut.Slot slot, ShareOut.Material material) {
        String prefix = switch (material) {
            case DIAMOND -> "diamond";
            case IRON -> "iron";
            case GOLD -> "golden";
            case NETHERITE -> "netherite";
            case LEATHER -> "leather";
            case CHAIN, NONE -> throw new IllegalArgumentException("Nothing is made of " + material);
        };
        if (material == ShareOut.Material.LEATHER && !slot.armour()) throw new IllegalArgumentException("No leather " + slot);
        return "minecraft:" + prefix + "_" + slot.name().toLowerCase(Locale.ROOT);
    }

    /** One piece to hand over, as a task's arguments carry it. */
    public record Piece(String piece, String material, int cost, UUID to, UUID giver) {
        public Piece {
            Objects.requireNonNull(piece);
            Objects.requireNonNull(material);
            Objects.requireNonNull(to);
            Objects.requireNonNull(giver);
            if (cost <= 0) throw new IllegalArgumentException("A piece costs something");
        }

        /** Read back from a hand-over task, or null for any other task. */
        public static Piece of(SharedTask task) {
            if (!SKILL.equals(task.skill())) return null;
            var a = task.arguments();
            return new Piece(a.get("piece"), a.get("material"), Integer.parseInt(a.get("cost")), UUID.fromString(a.get("to")),
                    UUID.fromString(a.get("giver")));
        }
    }

    /**
     * The share-out of {@code count} of {@code material} that {@code giver} found, as a project: named for the find,
     * described by what the giver tells its team of it ({@link TeamTalk#find}), with a task for each piece granted, in the
     * order the share-out makes them.
     */
    public static SharedProject project(ProjectId id, UUID giver, ShareOut.Material material, int count, List<ShareOut.Grant> grants) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(giver);
        var project = SharedProject.create(id, "Share out " + count + " " + TeamTalk.of(material, count), TeamTalk.find(giver, material, count, grants));
        int n = 0;
        for (var grant : grants) {
            var args = new LinkedHashMap<String, String>();
            args.put("piece", pieceItem(grant.slot(), grant.material()));
            args.put("material", materialItem(grant.material()));
            args.put("cost", String.valueOf(grant.cost()));
            args.put("to", grant.to().toString());
            args.put("giver", giver.toString());
            args.put("reserved_item", held(giver, materialItem(grant.material())));
            args.put("reserved_count", String.valueOf(grant.cost()));
            String title = TeamTalk.piece(grant.slot(), grant.material()) + " for " + grant.name();
            project = project.withTask(SharedTask.open(TaskId.of(id, (n++) + ":" + title), id, title, SKILL, Map.copyOf(args), Set.of()));
        }
        return project;
    }

    /** Whether a hand-over task is the giver's to do: only the player that found it makes and hands over its pieces. */
    public static boolean givenBy(SharedTask task, UUID player) {
        var piece = Piece.of(task);
        return piece != null && piece.giver().equals(player);
    }
}
