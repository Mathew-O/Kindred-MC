package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Standing server policy for an AI player working out in the world (decision D4, recorded by an operator):
 * ordinary survival breaking and placing anywhere the player itself may edit, never containers or other
 * block entities except an empty furnace it can pick back up, and never a break that would release lava.
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
        String common = common(player, pos, policy);
        if (common != null) return common;
        var world = player.getServerWorld();
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(world, pos).isEmpty()) return "Nothing solid to break";
        if (state.getHardness(world, pos) < 0) return "Unbreakable block";
        if (state.hasBlockEntity() && !emptyFurnace(player, pos, state)) return "Containers and block entities are not broken autonomously";
        if (state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL_FRAME)) return "Protected structure block";
        BlockPos support = BlockPos.ofFloored(player.getX(), player.getY() - 0.2, player.getZ());
        if (pos.equals(support)) return "Would remove the block the player stands on";
        boolean guarded = waterGuarded && (state.isOf(Blocks.OBSIDIAN) && waterGuards(player, pos)
                || state.isOf(Blocks.COBBLESTONE) && waterBeside(player, pos));
        for (Direction direction : Direction.values()) {
            BlockPos near = pos.offset(direction);
            if (!guarded && world.isChunkLoaded(near) && world.getFluidState(near).isIn(FluidTags.LAVA)) return "Breaking it would release lava";
        }
        return null;
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

    static String placeDenial(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy) {
        String common = common(player, pos, policy);
        if (common != null) return common;
        var state = player.getServerWorld().getBlockState(pos);
        if (!state.isReplaceable()) return "The destination is occupied";
        return null;
    }

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

    private static boolean emptyFurnace(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof AbstractFurnaceBlock)) return false;
        return player.getServerWorld().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace && furnace.isEmpty();
    }
}
