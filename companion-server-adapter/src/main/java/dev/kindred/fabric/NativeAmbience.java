package dev.kindred.fabric;

import dev.kindred.core.hearing.HeardSound;
import dev.kindred.core.hearing.Hearing;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.WorldChunk;

import java.util.function.Consumer;

/**
 * In plain terms: the lava and running water a person hears. Their client makes those sounds itself, from the blocks
 * around it, so the server never sends them; this makes them for an AI player's body by the same rule, and no more:
 * lava pops only with air above it, water sounds only where it runs, at the client's own rate and reach. Lava sealed
 * behind stone is as silent here as it is to a person.
 *
 * Technical: E-71f. Vanilla's client loop ({@code ClientWorld.doRandomBlockDisplayTicks}) picks 667 blocks a tick
 * within 16 and 667 within 32 of the player, each axis {@code centre + nextInt(r) - nextInt(r)}
 * ({@code randomBlockDisplayTick}), and calls the block's fluid's {@code randomDisplayTick}: {@code LavaFluid}'s, with
 * the block above air and not an opaque full cube, pops at 1 in 100 and murmurs at 1 in 200; {@code WaterFluid}'s, on
 * water flowing and not falling, at 1 in 64. Here the same picks, radii and chances, on the server thread, from the
 * body's own random (never the world's, so nothing else in the world changes), each sound a {@link HeardSound}. A pick
 * more than 16 blocks off is let go before anything is read (those sounds carry 16 blocks), and only chunks already
 * loaded are read, so it reads less than the client's rule, never more, and loads nothing.
 */
final class NativeAmbience {
    static final int PICKS = 667, NEAR_RADIUS = 16, FAR_RADIUS = 32, CARRY = 16;

    private final Random random = Random.createLocal(); // Unchecked (one thread only: the server's), so the 8,000 draws a tick stay cheap.
    private final BlockPos.Mutable at = new BlockPos.Mutable();
    private final BlockPos.Mutable above = new BlockPos.Mutable();
    private final WorldChunk[] chunks = new WorldChunk[9];
    private long nanos, ticks;

    /** One tick of listening around {@code player}: each sound by the client's rule handed to {@code heard}. */
    void listen(ServerPlayerEntity player, long tick, Consumer<HeardSound> heard) {
        long start = System.nanoTime();
        var world = player.getServerWorld();
        int cx = player.getBlockX(), cy = player.getBlockY(), cz = player.getBlockZ();
        int baseX = (cx >> 4) - 1, baseZ = (cz >> 4) - 1;
        for (int i = 0; i < 9; i++) chunks[i] = world.getChunkManager().getWorldChunk(baseX + i % 3, baseZ + i / 3);
        float yaw = player.getYaw();
        double ex = player.getX(), ey = player.getEyeY(), ez = player.getZ();
        for (int radius : new int[] {NEAR_RADIUS, FAR_RADIUS}) {
            for (int n = 0; n < PICKS; n++) {
                int dx = random.nextInt(radius) - random.nextInt(radius);
                int dy = random.nextInt(radius) - random.nextInt(radius);
                int dz = random.nextInt(radius) - random.nextInt(radius);
                if (dx * dx + dy * dy + dz * dz > CARRY * CARRY) continue;
                int x = cx + dx, y = cy + dy, z = cz + dz;
                int ci = (x >> 4) - baseX, cj = (z >> 4) - baseZ;
                if (ci < 0 || ci > 2 || cj < 0 || cj > 2) continue;
                WorldChunk chunk = chunks[ci + cj * 3];
                if (chunk == null || world.isOutOfHeightLimit(y)) continue;
                at.set(x, y, z);
                FluidState fluid = chunk.getFluidState(x, y, z);
                if (fluid.isEmpty()) continue;
                HeardSound.Kind kind = null;
                if (fluid.isIn(FluidTags.LAVA)) {
                    above.set(x, y + 1, z);
                    BlockState over = world.isOutOfHeightLimit(y + 1) ? null : chunk.getBlockState(above);
                    if (over != null && over.isAir() && !over.isOpaqueFullCube(world, above)) {
                        if (random.nextInt(100) == 0) kind = HeardSound.Kind.LAVA;
                        if (random.nextInt(200) == 0) kind = HeardSound.Kind.LAVA;
                    }
                } else if (fluid.isIn(FluidTags.WATER)) {
                    if (!fluid.isStill() && !fluid.get(FlowableFluid.FALLING) && random.nextInt(64) == 0) kind = HeardSound.Kind.WATER;
                }
                if (kind != null) heard.accept(Hearing.heard(kind, x + 0.5 - ex, y + 0.5 - ey, z + 0.5 - ez, yaw, tick));
            }
        }
        nanos += System.nanoTime() - start;
        ticks++;
    }

    /** The mean time a tick's listening took, in microseconds, for the cost check. */
    double meanMicros() { return ticks == 0 ? 0 : nanos / 1000.0 / ticks; }
}
