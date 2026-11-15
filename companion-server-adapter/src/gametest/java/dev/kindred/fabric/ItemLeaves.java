package dev.kindred.fabric;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Where an item goes when it leaves the world in a bring fixture's ground (F-22c84's diagnostic, report only): in
 * single-test bring batches an item the player broke was never taken back (a dirt, a rail, the cart), with no line
 * saying why. Each one leaving logs its stack, how it left, its age and pickup delay, whether its chunk ticks entities,
 * the player acting if any, and the frames that removed it. A pickup, a merge, a despawn, an unload and a fixture's
 * restore each show in the frames. Nothing changes here.
 */
public final class ItemLeaves {
    private ItemLeaves() { }

    /** A lectern in a live bring test's ground replaced by {@code now}: by whom, from where. */
    public static void lecternGone(net.minecraft.world.World world, BlockPos pos, net.minecraft.block.BlockState now) {
        if (world.isClient()) return;
        String test = FixtureBounds.liveTestAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (test == null || !test.startsWith("nativebringgametests.")) return;
        var acting = FixtureBounds.actingName();
        KindredMod.LOGGER.warn("Lectern gone {}: at {}, now {}, acting {}; from {}", test, pos.toShortString(), now, acting == null ? "nobody" : acting, frames());
    }

    /** Chunks under a live bring test's ground loading or unloading (a placed block lost with its chunk would show here). */
    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> chunkEvent("unloads", chunk));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> chunkEvent("loads", chunk));
    }

    private static void chunkEvent(String what, net.minecraft.world.chunk.WorldChunk chunk) {
        var pos = chunk.getPos();
        String test = null;
        for (int dx = 0; dx < 16 && test == null; dx += 4) for (int dz = 0; dz < 16 && test == null; dz += 4) {
            test = FixtureBounds.liveTestAt(pos.getStartX() + dx + 0.5, -58.5, pos.getStartZ() + dz + 0.5);
        }
        if (test == null || !test.startsWith("nativebringgametests.")) return;
        KindredMod.LOGGER.info("Chunk {} under {}: {}", what, test, pos);
    }

    /** The frames that did it, deep enough to show the step that called a shared helper such as {@code breakAt}. */
    private static String frames() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().endsWith("ItemLeaves") && !f.getMethodName().contains("kindred$")
                        && !f.getClassName().startsWith("net.minecraft.world.chunk.") && !f.getClassName().startsWith("net.minecraft.block."))
                .limit(12)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" < ")));
    }

    public static void leaving(ItemEntity item, Entity.RemovalReason reason) {
        if (!(item.getWorld() instanceof ServerWorld world)) return;
        String test = FixtureBounds.liveTestAt(item.getX(), item.getY(), item.getZ());
        if (test == null || !test.startsWith("nativebringgametests.")) return;
        var access = (dev.kindred.fabric.audit.ItemEntityAccess) item;
        BlockPos at = item.getBlockPos();
        var acting = FixtureBounds.actingName();
        String from = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("net.minecraft.entity.Entity") && !f.getClassName().endsWith("ItemLeaves")
                        && !f.getMethodName().contains("kindred$"))
                .limit(6)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" < ")));
        KindredMod.LOGGER.info("Item leaves {}: {} at {}, {}; age {}, pickup delay {}, its chunk {}, acting {}; from {}", test, item.getStack(),
                at.toShortString(), reason, access.kindred$age(), access.kindred$pickupDelay(),
                world.shouldTickEntity(at) ? "ticking entities" : world.isChunkLoaded(at) ? "loaded, entities not ticking" : "not loaded",
                acting == null ? "nobody" : acting, from);
    }
}
