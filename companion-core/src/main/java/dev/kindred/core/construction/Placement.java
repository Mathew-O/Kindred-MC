package dev.kindred.core.construction;

import dev.kindred.core.construction.Pos.Face;

import java.util.List;
import java.util.Set;

/**
 * How vanilla 1.21.1 decides the state a placed block takes (E-08a), so a player can place it in the state wanted: a
 * stair's facing is the way the player looks and its half the face and height clicked; a slab's half likewise; a log's
 * axis the clicked face's; a furnace, chest or pumpkin faces back at the player; a wall torch or a ladder hangs on the
 * face clicked. Blocks whose placement is not modelled yet are reported, never guessed at. Pure.
 */
public final class Placement {
    /** The kinds of placement modelled. */
    public enum Rule {
        /** Any face; nothing about the state depends on it. */
        FULL,
        /** Like {@link #FULL}, but only on the top of the block below: sand, gravel and concrete powder fall. */
        GRAVITY,
        /** On the top of the block below: a torch, a lantern, a carpet, a pressure plate. */
        ON_TOP,
        /** On the face of the block behind it, facing out: a wall torch, a ladder. */
        WALL,
        /** Its axis is the clicked face's: logs, pillars, basalt, hay. */
        AXIS,
        /** Its half by the face and height clicked: a slab. */
        SLAB,
        /** Its facing the player's, its half by the face and height clicked: stairs. */
        STAIRS,
        /** Facing back at the player: a furnace, a chest, a carved pumpkin. */
        FACING_PLAYER,
        /** Facing the way the player looks: a fence gate. */
        FACING_AWAY,
        /**
         * A door's lower half: on the top of the block below, facing the way the player looks, the upper half coming
         * with it into the cell above, which must be clear (M-01b).
         */
        DOOR,
        /** A bed's foot: on the top of the block below, facing the way the player looks, its head coming with it in the cell ahead. */
        BED,
        /** Placed with another block, never on its own: a door's upper half, a bed's head. */
        COMPANION,
        /** Not modelled yet: trapdoors, signs, buttons, rails, redstone, plants and the rest. */
        UNSUPPORTED
    }

    private static final Set<String> GRAVITY = Set.of("minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
            "minecraft:suspicious_sand", "minecraft:suspicious_gravel");
    private static final Set<String> ON_TOP = Set.of("minecraft:torch", "minecraft:soul_torch", "minecraft:redstone_torch",
            "minecraft:snow");
    private static final Set<String> WALL = Set.of("minecraft:wall_torch", "minecraft:soul_wall_torch", "minecraft:redstone_wall_torch",
            "minecraft:ladder");
    private static final Set<String> FACING_PLAYER = Set.of("minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace",
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest", "minecraft:carved_pumpkin",
            "minecraft:jack_o_lantern", "minecraft:loom", "minecraft:stonecutter", "minecraft:lectern", "minecraft:beehive",
            "minecraft:bee_nest");

    private Placement() { }

    public static Rule rule(BlockSpec spec) {
        String id = spec.block();
        if (WALL.contains(id)) return Rule.WALL;
        if (ON_TOP.contains(id) || id.endsWith("_carpet") || id.endsWith("_pressure_plate")
                || id.equals("minecraft:lantern") && !"true".equals(spec.property("hanging"))
                || id.equals("minecraft:soul_lantern") && !"true".equals(spec.property("hanging"))) return Rule.ON_TOP;
        if (GRAVITY.contains(id) || id.endsWith("_concrete_powder")) return Rule.GRAVITY;
        if (id.endsWith("_stairs") && spec.property("half") != null) return Rule.STAIRS;
        if (id.endsWith("_slab")) return "double".equals(spec.property("type")) ? Rule.UNSUPPORTED : Rule.SLAB;
        if (id.endsWith("_glazed_terracotta") || FACING_PLAYER.contains(id) && !"left".equals(spec.property("type"))
                && !"right".equals(spec.property("type"))) return spec.property("facing") == null ? Rule.FULL : Rule.FACING_PLAYER;
        if (id.endsWith("_fence_gate")) return Rule.FACING_AWAY;
        if (id.endsWith("_door") && !id.endsWith("_trapdoor") && spec.property("facing") != null) {
            return "upper".equals(spec.property("half")) ? Rule.COMPANION : Rule.DOOR;
        }
        if (id.endsWith("_bed") && spec.property("facing") != null) return "head".equals(spec.property("part")) ? Rule.COMPANION : Rule.BED;
        if (spec.property("axis") != null) return Rule.AXIS;
        if (UNSUPPORTED_SUFFIXES.stream().anyMatch(id::endsWith) || UNSUPPORTED.contains(id)) return Rule.UNSUPPORTED;
        // A facing, a half or a hinge we have no rule for: not guessed at.
        for (String p : List.of("facing", "half", "hinge", "face", "attachment", "part", "hanging", "rotation", "type")) {
            if (spec.property(p) != null) return Rule.UNSUPPORTED;
        }
        return Rule.FULL;
    }

    private static final Set<String> UNSUPPORTED_SUFFIXES = Set.of("_door", "_bed", "_sign", "_banner", "_button", "_sapling",
            "_flower", "_tulip", "_rail", "_head", "_skull", "_candle", "_coral", "_coral_fan", "_mushroom", "_wall_hanging_sign",
            "_hanging_sign", "_trapdoor");
    private static final Set<String> UNSUPPORTED = Set.of("minecraft:rail", "minecraft:redstone_wire", "minecraft:lever",
            "minecraft:vine", "minecraft:water", "minecraft:lava", "minecraft:fire", "minecraft:tripwire", "minecraft:tripwire_hook",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern", "minecraft:dandelion",
            "minecraft:poppy", "minecraft:sugar_cane", "minecraft:cactus", "minecraft:kelp", "minecraft:seagrass", "minecraft:bamboo",
            "minecraft:scaffolding", "minecraft:piston", "minecraft:sticky_piston", "minecraft:observer", "minecraft:dispenser",
            "minecraft:dropper", "minecraft:hopper", "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
            "minecraft:bell", "minecraft:end_portal_frame", "minecraft:nether_portal", "minecraft:end_portal");

    /**
     * The faces of the block a placed block is set against that give {@code spec}: the face is on the support's side
     * toward the target (UP when the support is below). Empty for a block placed on no face.
     */
    public static List<Face> faces(BlockSpec spec) {
        return switch (rule(spec)) {
            case FULL, FACING_PLAYER, FACING_AWAY -> List.of(Face.UP, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST, Face.DOWN);
            case GRAVITY, ON_TOP, DOOR, BED -> List.of(Face.UP);
            case WALL -> {
                Face facing = face(spec.property("facing"));
                yield facing == null || !facing.horizontal() ? List.of() : List.of(facing);
            }
            case AXIS -> switch (String.valueOf(spec.property("axis"))) {
                case "y" -> List.of(Face.UP, Face.DOWN);
                case "x" -> List.of(Face.EAST, Face.WEST);
                case "z" -> List.of(Face.NORTH, Face.SOUTH);
                default -> List.of();
            };
            case SLAB -> "top".equals(spec.property("type")) ? List.of(Face.DOWN, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)
                    : List.of(Face.UP, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST);
            case STAIRS -> "top".equals(spec.property("half")) ? List.of(Face.DOWN, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)
                    : List.of(Face.UP, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST);
            case UNSUPPORTED, COMPANION -> List.of();
        };
    }

    /**
     * The cell the block placed as {@code spec} at {@code at} also fills: a door's upper half above it, a bed's head
     * ahead of its foot. Empty for a block of one cell.
     */
    public static java.util.Optional<Pos> partner(BlockSpec spec, Pos at) {
        return switch (rule(spec)) {
            case DOOR -> java.util.Optional.of(at.up());
            case BED -> java.util.Optional.ofNullable(face(spec.property("facing"))).map(at::offset);
            default -> java.util.Optional.empty();
        };
    }

    /** The state of what {@link #partner} fills, as vanilla sets it: the same door's upper half, the same bed's head. */
    public static BlockSpec partnerState(BlockSpec spec) {
        return switch (rule(spec)) {
            case DOOR -> spec.with("half", "upper");
            case BED -> spec.with("part", "head");
            default -> spec;
        };
    }

    /**
     * Where on a side face to click, as a height within the target block (0 bottom, 1 top), for {@code spec}: the
     * upper half for a top slab or stair, the lower for a bottom one, the middle otherwise. Vanilla reads the upper
     * half as above half a block.
     */
    public static double hitHeight(BlockSpec spec) {
        var rule = rule(spec);
        if (rule == Rule.SLAB) return "top".equals(spec.property("type")) ? 0.75 : 0.25;
        if (rule == Rule.STAIRS) return "top".equals(spec.property("half")) ? 0.75 : 0.25;
        return 0.5;
    }

    /** The way the player must face, horizontally, to place {@code spec}; null when it does not matter. */
    public static Face playerFacing(BlockSpec spec) {
        Face facing = face(spec.property("facing"));
        return switch (rule(spec)) {
            case STAIRS, FACING_AWAY, DOOR, BED -> facing;
            case FACING_PLAYER -> facing == null ? null : facing.opposite();
            default -> null;
        };
    }

    /**
     * The horizontal way a player looking along {@code yaw} faces, as vanilla reckons it (yaw 0 south, 90 west, 180
     * north, -90 east).
     */
    public static Face facingOf(float yaw) {
        int i = (int) Math.floor(yaw / 90.0 + 0.5) & 3;
        return switch (i) { case 0 -> Face.SOUTH; case 1 -> Face.WEST; case 2 -> Face.NORTH; default -> Face.EAST; };
    }

    /** The item a block in {@code spec} is placed from: a wall torch from a torch, and so on. */
    public static String item(BlockSpec spec) {
        return switch (spec.block()) {
            case "minecraft:wall_torch" -> "minecraft:torch";
            case "minecraft:soul_wall_torch" -> "minecraft:soul_torch";
            case "minecraft:redstone_wall_torch" -> "minecraft:redstone_torch";
            default -> spec.block();
        };
    }

    static Face face(String name) {
        if (name == null) return null;
        for (Face f : Face.values()) if (f.id().equals(name)) return f;
        return null;
    }
}
