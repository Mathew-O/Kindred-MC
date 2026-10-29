package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: decides whether an AI player may break, place or open a block, till soil, sow seed or use a
 * furnace. Inside a work area an administrator gave it, that area's rules apply; anywhere else, the server's
 * rules for players roaming freely.
 *
 * Technical: One edit decision for native actions. A player with an operator-assigned work area keeps that
 * area's bounded rules; a player without one works under the server's standing roaming policy.
 */
final class NativeEdits {
    private NativeEdits() { }

    /** Whether the player may break, place or use a container at this block. */
    static EditPolicy.Decision decide(ServerPlayerEntity player, WorkSetup setup, EditPolicy.Kind kind, BlockPos pos,
                                      String blockId, EditPolicy.ServerPolicy policy) {
        String dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        if (setup != null && setup.area() != null) {
            return EditPolicy.evaluate(setup, kind, dimension, new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ()), blockId, policy);
        }
        String denial = switch (kind) {
            case BREAK -> RoamingEdits.breakDenial(player, pos, policy);
            case PLACE -> RoamingEdits.placeDenial(player, pos, policy);
            case CONTAINER -> RoamingEdits.storageDenial(player, setup, pos, blockId, policy);
        };
        return denial == null ? new EditPolicy.Decision(EditPolicy.Verdict.ALLOWED, "Roaming survival edit under server policy")
                : new EditPolicy.Decision(EditPolicy.Verdict.OUTSIDE_AREA, denial);
    }

    /**
     * Soil tilled, or a seed sown, by a player without an assigned area, in a field of its own (M-06a): the server's
     * roaming policy for that block.
     */
    static EditPolicy.Decision farm(ServerPlayerEntity player, BlockPos pos, EditPolicy.ServerPolicy policy) {
        String denial = RoamingEdits.workstationDenial(player, pos, policy);
        return denial == null ? new EditPolicy.Decision(EditPolicy.Verdict.ALLOWED, "Roaming survival edit under server policy")
                : new EditPolicy.Decision(EditPolicy.Verdict.OUTSIDE_AREA, denial);
    }

    /** Using a workstation (loading or emptying a furnace) the player can reach and edit. */
    static EditPolicy.Decision useWorkstation(ServerPlayerEntity player, WorkSetup setup, BlockPos pos, String blockId,
                                              EditPolicy.ServerPolicy policy) {
        if (setup != null && setup.area() != null) return decide(player, setup, EditPolicy.Kind.PLACE, pos, blockId, policy);
        String denial = RoamingEdits.workstationDenial(player, pos, policy);
        return denial == null ? new EditPolicy.Decision(EditPolicy.Verdict.ALLOWED, "Roaming workstation use under server policy")
                : new EditPolicy.Decision(EditPolicy.Verdict.OUTSIDE_AREA, denial);
    }
}
