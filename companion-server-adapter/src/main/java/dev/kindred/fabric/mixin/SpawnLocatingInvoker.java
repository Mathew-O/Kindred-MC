package dev.kindred.fabric.mixin;

import net.minecraft.server.network.SpawnLocating;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * In plain terms: a small hook that lets the mod ask Minecraft whether a column of ground is somewhere a new player may
 * be put, by the same rule a human joining the world is put by (dry ground, never water).
 *
 * Technical: invokes vanilla's {@code SpawnLocating.findOverworldSpawn}, the per-column judge of
 * {@code ServerPlayerEntity.getWorldSpawnPos} (M-06i4a): down from the motion-blocking height, refused at the first
 * fluid, the spot above the first full-square top. The caller asks only for columns in already loaded chunks.
 */
@Mixin(SpawnLocating.class)
public interface SpawnLocatingInvoker {
    @Invoker("findOverworldSpawn")
    static BlockPos kindred$findOverworldSpawn(ServerWorld world, int x, int z) { throw new AssertionError(); }
}
