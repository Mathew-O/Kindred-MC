package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * In plain terms: the server's standing rules for an AI player working out in the world with no assigned
 * area: it may break and place blocks the normal survival way wherever it itself is allowed to, but it does
 * not break chests and the like, protected blocks or anything that would let lava out, and it opens only
 * storage that is its own, a structure's unopened loot, or an opponent's storage during a match.
 *
 * Technical: Standing server policy for an AI player working out in the world (decision D4, recorded by an
 * operator): ordinary survival breaking and placing anywhere the player itself may edit, never containers or
 * other block entities except an empty furnace it can pick back up, and never a break that would release
 * lava.
 */
final class RoamingEdits {
    private RoamingEdits() { }

    /** Null when this edit is allowed; otherwise why not. */
    static String breakDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy) {
        return breakDenial(player, pos, policy, false);
    }

    /**
     * {@code waterGuarded} permits mining obsidian beside lava when water will refill the gap, which is how
     * obsidian is made: water above it (still or flowing), or a still source beside it, pours into the hole,
     * and any lava it touches hardens instead of spreading. It also permits mining cobblestone with water beside
     * it, as in a cobblestone generator: lava flowing into the gap touches that water and hardens at once.
     */
    static String breakDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy, boolean waterGuarded) {
        return breakDenial(player, pos, policy, waterGuarded, false);
    }

    /**
     * As {@link #breakDenial(ServerPlayerEntity, BlockPos, EditPolicy.ServerPolicy, boolean)}; {@code underfoot} lets the
     * player mine the block it stands on where the block under that is solid ground to land on a step down, as a player
     * gets down a pillar it built (E-39c), and nowhere else.
     */
    static String breakDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy, boolean waterGuarded, boolean underfoot) {
        return breakDenial(player, pos, policy, waterGuarded, underfoot, false);
    }

    /**
     * As {@link #breakDenial(ServerPlayerEntity, BlockPos, EditPolicy.ServerPolicy, boolean, boolean)}; {@code overLava}
     * lets the player open a block whose only lava is directly under it, as a lava search does to see the lava and fill
     * its bucket there: lava never rises, so none is released into the gap (F-25c4ac). Lava on any other side still
     * refuses the break.
     */
    static String breakDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy, boolean waterGuarded, boolean underfoot,
                              boolean overLava) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        var world = player.getServerWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty()) return "Nothing solid to break";
        if (state.getHardness(world, pos) < 0) return "Unbreakable block";
        if (state.hasBlockEntity() && !emptyFurnace(player, pos, state) && !emptyLectern(state)) return "Containers and block entities are not broken autonomously";
        if (state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL_FRAME)) return "Protected structure block";
        if (state.isIn(BlockTags.GUARDED_BY_PIGLINS) && piglinsAbout(player)) return "A piglin nearby would turn on the player for it";
        BlockPos support = BlockPos.ofFloored(player.getX(), player.getY() - 0.2, player.getZ());
        if (pos.equals(support) && !(underfoot && landing(world, pos.down()))) return "Would remove the block the player stands on";
        boolean guarded = waterGuarded && (state.isOf(Blocks.OBSIDIAN) && waterGuards(player, pos)
                || state.isOf(Blocks.COBBLESTONE) && waterBeside(player, pos));
        for (Direction direction : Direction.values()) {
            BlockPos near = pos.offset(direction);
            if (overLava && direction == Direction.DOWN) continue;
            if (!guarded && world.isChunkLoaded(near) && world.getFluidState(near).isIn(FluidTags.LAVA)) return "Breaking it would release lava";
        }
        return null;
    }

    /**
     * A piglin about that breaking gold would turn on the player (E-07e). Vanilla angers every piglin within sixteen
     * blocks of a player who breaks a block piglins guard (gold ore of any kind, gold blocks, gilded blackstone), seen
     * or not, and gold worn does not calm a piglin angered that way.
     */
    static boolean piglinsAbout(ServerPlayerEntity player) {
        return !player.getServerWorld().getEntitiesByClass(net.minecraft.entity.mob.PiglinEntity.class,
                player.getBoundingBox().expand(16.0), net.minecraft.entity.LivingEntity::isAlive).isEmpty();
    }

    /**
     * Gold ore a player could mine with no piglin near enough to be angered: none within sixteen blocks of where it could
     * stand to mine it, its reach (4.5) beyond the ore.
     */
    static boolean clearOfPiglins(net.minecraft.server.world.ServerWorld world, BlockPos ore) {
        return world.getEntitiesByClass(net.minecraft.entity.mob.PiglinEntity.class,
                new net.minecraft.util.math.Box(ore).expand(16.0 + 5.0), net.minecraft.entity.LivingEntity::isAlive).isEmpty();
    }

    /** Solid ground a step down to land on: a full top face, nothing burning or flowing about it. */
    private static boolean landing(net.minecraft.server.world.ServerWorld world, BlockPos below) {
        BlockState state = world.getBlockState(below);
        if (!state.isSideSolidFullSquare(world, below, Direction.UP) || state.isOf(Blocks.MAGMA_BLOCK)) return false;
        for (Direction direction : Direction.values()) {
            if (world.isChunkLoaded(below.offset(direction)) && world.getFluidState(below.offset(direction)).isIn(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private static boolean waterGuards(ServerPlayerEntity player, BlockPos pos) {
        var world = player.getServerWorld();
        if (world.getFluidState(pos.up()).isIn(FluidTags.WATER)) return true;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            var fluid = world.getFluidState(pos.offset(direction));
            if (fluid.isIn(FluidTags.WATER) && fluid.isStill()) return true;
        }
        return false;
    }

    private static boolean waterBeside(ServerPlayerEntity player, BlockPos pos) {
        var world = player.getServerWorld();
        for (Direction direction : Direction.Type.HORIZONTAL) if (world.getFluidState(pos.offset(direction)).isIn(FluidTags.WATER)) return true;
        return false;
    }

    /** Why the player may not place a block here, or nothing when it may. */
    static String placeDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        var state = player.getServerWorld().getBlockState(pos);
        if (!state.isReplaceable()) return "The destination is occupied";
        return null;
    }

    /**
     * A roaming player uses one container outside its own crafting and smelting steps: the storage an operator assigned
     * it (E-08c), while that block is still the one assigned.
     */
    static String storageDenial(ServerPlayerEntity player, WorkSetup setup, BlockPos pos, String blockId, EditPolicy.ServerPolicy policy) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        var storage = setup == null ? null : setup.storage();
        if (storage == null || !storage.position().equals(new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()))
                || !setup.dimension().equals(player.getServerWorld().getRegistryKey().getValue().toString())) {
            return "Roaming players only use their assigned storage and their own crafting and smelting steps";
        }
        if (blockId != null && !blockId.equals(storage.blockId())) return "The assigned container's block identity changed; reassign storage";
        return null;
    }

    /**
     * A store of its own (E-43): the storage its administrator assigned it, or a chest of a base it built, while it is
     * still one; never anybody else's.
     */
    static String ownStoreDenial(ServerPlayerEntity player, BlockPos pos, boolean own, EditPolicy.ServerPolicy policy) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        return own ? null : "Only a store of its own: its assigned storage, or a chest of a base it built";
    }

    /**
     * A structure's container, looted by a player out in the world (E-42): one nobody has opened since the world made it,
     * which still holds its loot table. Storage anyone has opened, a player's own or another's, never has one, and is
     * never looted; a cart's position is where it stands.
     */
    static String lootDenial(ServerPlayerEntity player, BlockPos pos, boolean unrolled, EditPolicy.ServerPolicy policy) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        return unrolled ? null : "Only a structure's container nobody has opened is looted; this one has been opened";
    }

    /**
     * An opponent's storage, raided in a match (E-44): only while the player is in a running match whose other side the
     * container's owner is on, a chest it saw them use; never a teammate's, never outside a match, never in co-op.
     */
    static String raidDenial(ServerPlayerEntity player, BlockPos pos, boolean opponents, EditPolicy.ServerPolicy policy) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        return opponents ? null : "Only an opponent's storage, in a running match both joined, is raided";
    }

    /** Why the player may not use a workstation here, or nothing when it may. */
    static String workstationDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy) {
        return common(player, pos, policy);
    }

    private static String common(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy) {
        if (policy == null || !policy.autonomousEditsEnabled()) return "AI world edits are disabled on this server";
        if (!policy.protectionCompatibilityKnown()) return "Protection-plugin compatibility is unverified; edits are blocked";
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(pos) || !world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) return "Outside the loaded world";
        if (!world.canPlayerModifyAt(player, pos)) return "Protected area (spawn protection)";
        return null;
    }

    /** A lectern with no book on it: breaking it loses nothing, as a player rerolling a librarian breaks it (E-45c). */
    private static boolean emptyLectern(BlockState state) {
        return state.isOf(Blocks.LECTERN) && !state.get(net.minecraft.block.LecternBlock.HAS_BOOK);
    }

    private static boolean emptyFurnace(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof AbstractFurnaceBlock)) return false;
        return player.getServerWorld().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace && furnace.isEmpty();
    }
}
