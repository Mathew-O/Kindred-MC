package dev.kindred.core.construction;

import dev.kindred.core.Capabilities;
import dev.kindred.core.VanillaIds;

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
        /**
         * Facing the block it was set against, which it feeds (M-01d): a hopper clicked onto a chest's side points into
         * the chest, onto the top of a furnace points down into it.
         */
        HOPPER,
        /** Not modelled yet: trapdoors, signs, buttons, rails, redstone, plants and the rest. */
        UNSUPPORTED
    }

    private static final Set<String> GRAVITY = Set.of(VanillaIds.SAND, VanillaIds.RED_SAND, VanillaIds.GRAVEL,
            VanillaIds.SUSPICIOUS_SAND, VanillaIds.SUSPICIOUS_GRAVEL);
    private static final Set<String> ON_TOP = Set.of(VanillaIds.TORCH, VanillaIds.SOUL_TORCH, VanillaIds.REDSTONE_TORCH,
            VanillaIds.SNOW);
    private static final Set<String> WALL = Set.of(VanillaIds.WALL_TORCH, VanillaIds.SOUL_WALL_TORCH, VanillaIds.REDSTONE_WALL_TORCH,
            VanillaIds.LADDER);
    private static final Set<String> FACING_PLAYER = Set.of(VanillaIds.FURNACE, VanillaIds.SMOKER, VanillaIds.BLAST_FURNACE,
            VanillaIds.CHEST, VanillaIds.TRAPPED_CHEST, VanillaIds.ENDER_CHEST, VanillaIds.CARVED_PUMPKIN,
            VanillaIds.JACK_O_LANTERN, VanillaIds.LOOM, VanillaIds.STONECUTTER, VanillaIds.LECTERN, VanillaIds.BEEHIVE,
            VanillaIds.BEE_NEST);

    private Placement() { }

    public static Rule rule(BlockSpec spec) {
        String id = spec.block();
        if (WALL.contains(id)) return Rule.WALL;
        Capabilities facts = Capabilities.current();
        if (ON_TOP.contains(id) || facts.isAny(id, VanillaIds.WOOL_CARPETS, VanillaIds.PRESSURE_PLATES)
                || id.equals(VanillaIds.MOSS_CARPET)
                || id.equals(VanillaIds.LANTERN) && !"true".equals(spec.property("hanging"))
                || id.equals(VanillaIds.SOUL_LANTERN) && !"true".equals(spec.property("hanging"))) return Rule.ON_TOP;
        if (GRAVITY.contains(id) || facts.is(VanillaIds.CONCRETE_POWDERS, id)) return Rule.GRAVITY;
        if (facts.is(VanillaIds.STAIRS, id) && spec.property("half") != null) return Rule.STAIRS;
        if (facts.is(VanillaIds.SLABS, id)) return "double".equals(spec.property("type")) ? Rule.UNSUPPORTED : Rule.SLAB;
        if (facts.is(VanillaIds.GLAZED_TERRACOTTAS, id) || FACING_PLAYER.contains(id) && !"left".equals(spec.property("type"))
                && !"right".equals(spec.property("type"))) return spec.property("facing") == null ? Rule.FULL : Rule.FACING_PLAYER;
        if (facts.is(VanillaIds.FENCE_GATES, id)) return Rule.FACING_AWAY;
        if (facts.is(VanillaIds.DOORS, id) && spec.property("facing") != null) {
            return "upper".equals(spec.property("half")) ? Rule.COMPANION : Rule.DOOR;
        }
        if (facts.is(VanillaIds.BEDS, id) && spec.property("facing") != null) return "head".equals(spec.property("part")) ? Rule.COMPANION : Rule.BED;
        if (id.equals(VanillaIds.HOPPER)) {
            Face facing = face(spec.property("facing") == null ? "down" : spec.property("facing"));
            return facing == null || facing == Face.UP ? Rule.UNSUPPORTED : Rule.HOPPER;
        }
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
    private static final Set<String> UNSUPPORTED = Set.of(VanillaIds.RAIL, VanillaIds.REDSTONE_WIRE, VanillaIds.LEVER,
            VanillaIds.VINE, VanillaIds.WATER, VanillaIds.LAVA, VanillaIds.FIRE, VanillaIds.TRIPWIRE, VanillaIds.TRIPWIRE_HOOK,
            VanillaIds.SHORT_GRASS, VanillaIds.TALL_GRASS, VanillaIds.FERN, VanillaIds.LARGE_FERN, VanillaIds.DANDELION,
            VanillaIds.POPPY, VanillaIds.SUGAR_CANE, VanillaIds.CACTUS, VanillaIds.KELP, VanillaIds.SEAGRASS, VanillaIds.BAMBOO,
            VanillaIds.SCAFFOLDING, VanillaIds.PISTON, VanillaIds.STICKY_PISTON, VanillaIds.OBSERVER, VanillaIds.DISPENSER,
            VanillaIds.DROPPER, VanillaIds.ANVIL, VanillaIds.CHIPPED_ANVIL, VanillaIds.DAMAGED_ANVIL,
            VanillaIds.BELL, VanillaIds.END_PORTAL_FRAME, VanillaIds.NETHER_PORTAL, VanillaIds.END_PORTAL);

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
            // Set against the block it faces: the face of that block toward it.
            case HOPPER -> List.of(face(spec.property("facing") == null ? "down" : spec.property("facing")).opposite());
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
            case VanillaIds.WALL_TORCH -> VanillaIds.TORCH;
            case VanillaIds.SOUL_WALL_TORCH -> VanillaIds.SOUL_TORCH;
            case VanillaIds.REDSTONE_WALL_TORCH -> VanillaIds.REDSTONE_TORCH;
            default -> spec.block();
        };
    }

    static Face face(String name) {
        if (name == null) return null;
        for (Face f : Face.values()) if (f.id().equals(name)) return f;
        return null;
    }
}
