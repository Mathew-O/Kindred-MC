package dev.kindred.fabric;

import dev.kindred.core.tactics.Ballistics;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/**
 * Where an ender pearl thrown from here lands, followed tick by tick through the real blocks as vanilla 1.21.1 flies it
 * (E-39f): it moves, is slowed by drag (0.99, 0.8 in water) and pulled down by 0.03; the first block in its way stops
 * it, and vanilla puts the thrower where the pearl was when it struck, from where the player falls to the ground.
 * Throws are chosen so that a throw a degree off (vanilla's spread) lands on the same ground.
 */
final class PearlFlight {
    private static final Ballistics.Projectile PEARL = Ballistics.Projectile.PEARL;
    private static final int FLIGHT_TICKS = 200;
    /** Farthest a landing may leave the player to fall: five health for the pearl is enough. */
    static final int MAX_FALL = 3;

    /** A throw that lands: its yaw and pitch, where the pearl strikes, and the ground the player then stands on. */
    record Throw(float yaw, float pitch, Vec3d strike, BlockPos rest) { }

    private PearlFlight() { }

    /** Where a pearl from {@code start} at {@code yaw} and {@code pitch} strikes; empty if it falls away or flies off. */
    static Optional<Vec3d> strike(ServerWorld world, Vec3d start, float yaw, float pitch) {
        float r = MathHelper.RADIANS_PER_DEGREE;
        Vec3d v = new Vec3d(-MathHelper.sin(yaw * r) * MathHelper.cos(pitch * r), -MathHelper.sin(pitch * r),
                MathHelper.cos(yaw * r) * MathHelper.cos(pitch * r)).normalize().multiply(PEARL.speed());
        Vec3d pos = start;
        for (int tick = 0; tick < FLIGHT_TICKS; tick++) {
            Vec3d next = pos.add(v);
            if (!world.isChunkLoaded(BlockPos.ofFloored(next))) return Optional.empty();
            var hit = world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                    net.minecraft.block.ShapeContext.absent()));
            if (hit.getType() == HitResult.Type.BLOCK) return Optional.of(pos);
            boolean wet = !world.getFluidState(BlockPos.ofFloored(pos)).isEmpty() && world.getFluidState(BlockPos.ofFloored(pos)).isIn(net.minecraft.registry.tag.FluidTags.WATER);
            v = v.multiply(wet ? 0.8 : PEARL.drag()).add(0, -PEARL.gravity(), 0);
            pos = next;
            if (pos.y < world.getBottomY()) return Optional.empty();
        }
        return Optional.empty();
    }

    /** The ground a player put at {@code at} comes to rest on, falling at most {@link #MAX_FALL}; empty over a drop. */
    static Optional<BlockPos> rest(ServerWorld world, Vec3d at) {
        BlockPos feet = BlockPos.ofFloored(at);
        for (int fall = 0; fall <= MAX_FALL + 1; fall++) {
            BlockPos here = feet.down(fall);
            var below = world.getBlockState(here.down());
            boolean room = world.getBlockState(here).getCollisionShape(world, here).isEmpty()
                    && world.getBlockState(here.up()).getCollisionShape(world, here.up()).isEmpty();
            if (!room) continue;
            var shape = below.getCollisionShape(world, here.down());
            if (!shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.9) return fall <= MAX_FALL ? Optional.of(here) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Where a pearl is thrown from by a player standing at {@code feet}: a little below the eyes. */
    static Vec3d start(ServerPlayerEntity player, Vec3d feet) {
        return feet.add(0, player.getStandingEyeHeight() - 0.1, 0);
    }

    /**
     * A throw from {@code from} (feet) that brings the player to rest within {@code tolerance} blocks of {@code target}
     * (feet) and at its height: the low arc or the high, nudged a little, whichever lands there with throws a degree
     * off it landing there too; empty when none does.
     */
    static Optional<Throw> toward(ServerPlayerEntity player, Vec3d from, BlockPos target, double tolerance) {
        var world = player.getServerWorld();
        Vec3d start = start(player, from);
        Vec3d aim = new Vec3d(target.getX() + 0.5, target.getY() + 0.2, target.getZ() + 0.5);
        double dx = aim.x - start.x, dz = aim.z - start.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        Throw best = null;
        int bestSteady = -1;
        double bestOff = Double.MAX_VALUE;
        for (boolean high : new boolean[] {false, true}) {
            var shot = Ballistics.aim(Math.hypot(dx, dz), aim.y - start.y, PEARL, high);
            if (shot.isEmpty()) continue;
            for (float nudge : new float[] {0, -0.75f, 0.75f, -1.5f, 1.5f}) {
                float pitch = shot.get().pitch() + nudge;
                var landing = lands(world, start, yaw, pitch, target, tolerance);
                if (landing.isEmpty()) continue;
                // As steady as it is exact: how many throws a degree off land on the same ground.
                int steady = 0;
                for (float[] off : new float[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (lands(world, start, yaw + off[0], pitch + off[1], target, tolerance).isPresent()) steady++;
                }
                double off = Math.hypot(landing.get().rest().getX() - target.getX(), landing.get().rest().getZ() - target.getZ());
                if (steady > bestSteady || steady == bestSteady && off < bestOff) {
                    best = landing.get();
                    bestSteady = steady;
                    bestOff = off;
                }
            }
        }
        // A throw that lands only when exact is no throw to make: two of the four throws a degree off must land too.
        return bestSteady >= 2 ? Optional.of(best) : Optional.empty();
    }

    private static Optional<Throw> lands(ServerWorld world, Vec3d start, float yaw, float pitch, BlockPos target, double tolerance) {
        var hit = strike(world, start, yaw, pitch);
        if (hit.isEmpty()) return Optional.empty();
        var rest = rest(world, hit.get());
        if (rest.isEmpty() || rest.get().getY() != target.getY()
                || Math.hypot(rest.get().getX() - target.getX(), rest.get().getZ() - target.getZ()) > tolerance) return Optional.empty();
        return Optional.of(new Throw(yaw, pitch, hit.get(), rest.get()));
    }
}
