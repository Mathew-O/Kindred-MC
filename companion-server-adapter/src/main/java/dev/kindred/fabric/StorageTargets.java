package dev.kindred.fabric;

import dev.kindred.core.WorkSetup;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Container identity on the server thread. A storage assignment records block identity and an observed
 * version; before any transfer the live block is compared again, so a replaced, broken or changed container
 * is a blocker rather than a trusted coordinate.
 */
final class StorageTargets {
    enum Verdict { OK, UNLOADED, NOT_A_CONTAINER, CHANGED }

    record Observation(String blockId, long version, boolean container) { }

    private StorageTargets() { }

    /** Observe the block at a position; the version changes whenever the block state changes. */
    static Observation observe(World world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) return null;
        BlockState state = world.getBlockState(pos);
        BlockEntity entity = world.getBlockEntity(pos);
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        long version = state.toString().hashCode() & Integer.MAX_VALUE;
        return new Observation(id, version, entity instanceof Inventory);
    }

    static Verdict validate(World world, WorkSetup.StorageTarget target) {
        BlockPos pos = new BlockPos(target.position().x(), target.position().y(), target.position().z());
        Observation now = observe(world, pos);
        if (now == null) return Verdict.UNLOADED;
        if (!now.container()) return Verdict.NOT_A_CONTAINER;
        if (!now.blockId().equals(target.blockId()) || now.version() != target.observedVersion()) return Verdict.CHANGED;
        return Verdict.OK;
    }

    static String explain(Verdict verdict) {
        return switch (verdict) {
            case OK -> "assigned storage verified";
            case UNLOADED -> "assigned storage is in an unloaded chunk";
            case NOT_A_CONTAINER -> "assigned storage is no longer a container; reassign it";
            case CHANGED -> "assigned storage block changed since it was assigned; reassign it";
        };
    }
}
