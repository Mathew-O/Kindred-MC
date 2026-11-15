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
