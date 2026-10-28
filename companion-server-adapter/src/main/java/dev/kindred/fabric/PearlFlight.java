package dev.kindred.fabric;

import dev.kindred.core.tactics.Ballistics;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Optional;

/**
 * Where an ender pearl thrown from here lands, followed tick by tick through the real blocks as vanilla 1.21.1 flies it
 * (E-39f): it moves, is slowed by drag (0.99, 0.8 in water) and pulled down by 0.03; the first block in its way stops
 * it, and vanilla puts the thrower where the pearl was when it struck, from where the player falls to the ground.
 * Throws are chosen so that nearly every throw vanilla's spread makes of them lands on the same ground.
 */
final class PearlFlight {
    private static final Ballistics.Projectile PEARL = Ballistics.Projectile.PEARL;
    private static final int FLIGHT_TICKS = 200;
    /** Farthest a landing may leave the player to fall: five health for the pearl is enough. */
    static final int MAX_FALL = 3;

    /**
     * A throw that lands: its yaw and pitch, where the pearl strikes, the ground the player then stands on, and how many
     * of {@link #SAMPLES} throws as vanilla's spread makes them land there too.
     */
    record Throw(float yaw, float pitch, Vec3d strike, BlockPos rest, int steady) { }

    private PearlFlight() { }

    /** Where a pearl from {@code start} at {@code yaw} and {@code pitch} strikes; empty if it falls away or flies off. */
    static Optional<Vec3d> strike(ServerWorld world, Vec3d start, float yaw, float pitch) {
        return fly(world, start, direction(yaw, pitch).multiply(PEARL.speed()), List.of()).filter(f -> !f.onEntity()).map(Flight::at);
    }

    /** Where a pearl's flight ended: the block it struck, or one of the boxes it was kept from. */
    private record Flight(Vec3d at, boolean onEntity) { }

    /** The unit direction vanilla throws along for a yaw and pitch. */
    private static Vec3d direction(float yaw, float pitch) {
        float r = MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.sin(yaw * r) * MathHelper.cos(pitch * r), -MathHelper.sin(pitch * r),
                MathHelper.cos(yaw * r) * MathHelper.cos(pitch * r)).normalize();
    }

    /** A pearl followed from {@code start} at {@code v}: the first block or {@code avoid} box in its way stops it. */
    private static Optional<Flight> fly(ServerWorld world, Vec3d start, Vec3d v, List<Box> avoid) {
        Vec3d pos = start;
        for (int tick = 0; tick < FLIGHT_TICKS; tick++) {
            Vec3d next = pos.add(v);
            if (!world.isChunkLoaded(BlockPos.ofFloored(next))) return Optional.empty();
            var hit = world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                    net.minecraft.block.ShapeContext.absent()));
            Vec3d end = hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : next;
            for (Box box : avoid) if (box.contains(pos) || box.raycast(pos, end).isPresent()) return Optional.of(new Flight(pos, true));
            if (hit.getType() == HitResult.Type.BLOCK) return Optional.of(new Flight(pos, false));
            boolean wet = world.getFluidState(BlockPos.ofFloored(pos)).isIn(net.minecraft.registry.tag.FluidTags.WATER);
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
            if (here.getY() <= world.getBottomY()) break;
            var below = world.getBlockState(here.down());
            boolean room = world.getBlockState(here).getCollisionShape(world, here).isEmpty()
                    && world.getBlockState(here.up()).getCollisionShape(world, here.up()).isEmpty();
            if (!room) continue;
            var shape = below.getCollisionShape(world, here.down());
            if (!shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.9) return fall <= MAX_FALL ? Optional.of(here) : Optional.empty();
        }
        return Optional.empty();
    }

    /** How far a player put at {@code at} drops before a block under it stops it; empty over the void or a longer drop. */
    private static java.util.OptionalDouble drop(ServerWorld world, Vec3d at, int limit) {
        BlockPos feet = BlockPos.ofFloored(at);
        for (int d = 0; d <= limit; d++) {
            BlockPos below = feet.down(d + 1);
            if (below.getY() < world.getBottomY()) break;
            var shape = world.getBlockState(below).getCollisionShape(world, below);
            if (!shape.isEmpty()) return java.util.OptionalDouble.of(Math.max(0, at.y - (below.getY() + shape.getMax(Direction.Axis.Y))));
        }
        return java.util.OptionalDouble.empty();
    }

    /** Where a pearl is thrown from by a player standing at {@code feet}: a little below the eyes. */
    static Vec3d start(ServerPlayerEntity player, Vec3d feet) {
        return feet.add(0, player.getStandingEyeHeight() - 0.1, 0);
    }

    /**
     * Vanilla's spread (`ProjectileEntity.calculateVelocity`): each part of the throw's unit direction is moved by a
     * triangular amount of at most this before it is scaled to the pearl's speed, so the speed varies a little too.
     */
    private static final double SPREAD = 0.0172275;
    /** Throws as vanilla's spread makes them, drawn once from a fixed seed so that the choice is the same each time. */
    static final int SAMPLES = 16;
    private static final double[][] NOISE = noise();
    /** Of {@link #SAMPLES} throws so spread, how many must land on the same ground for a throw to be made. */
    static final int STEADY = 14;
    /** Health a landing that misses must leave, at the least: five for the pearl and the fall's own are taken. */
    private static final float SAFE_HEALTH = 4;
    private static final float[] NUDGES = {0, -0.4f, 0.4f, -0.8f, 0.8f, -1.2f, 1.2f, -1.6f, 1.6f};

    private static double[][] noise() {
        var random = new java.util.Random(0x5EA4L);
        double[][] out = new double[SAMPLES][3];
        for (double[] n : out) for (int i = 0; i < 3; i++) n[i] = SPREAD * (random.nextDouble() - random.nextDouble());
        return out;
    }

    /**
     * A throw from {@code from} (feet) that brings the player to rest within {@code tolerance} blocks of {@code target}
     * (feet) and at its height, with the best chance of doing so: each throw tried (the high arc and the low, nudged a
     * little) is flown again under vanilla's spread, and only one that lands {@link #STEADY} times in {@link #SAMPLES}
     * is made; of those, the likeliest, then the nearest the spot. A throw is never made when any of its spread, the
     * samples or the spread's eight extremes, could harm the player: strike an End crystal on the way (vanilla blows a
     * crystal up at any hit, a pearl's too, beside the player it brings), or put it where the drop would leave it under
     * {@link #SAFE_HEALTH}, or over the void.
     * Empty when no throw is that sure.
     */
    static Optional<Throw> toward(ServerPlayerEntity player, Vec3d from, BlockPos target, double tolerance) {
        var world = player.getServerWorld();
        Vec3d start = start(player, from);
        Vec3d aim = new Vec3d(target.getX() + 0.5, target.getY() + 0.2, target.getZ() + 0.5);
        double dx = aim.x - start.x, dz = aim.z - start.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Vanilla widens an entity's box by 0.3 for a projectile's hit (ProjectileUtil.getEntityCollision).
        List<Box> crystals = world.getEntitiesByClass(EndCrystalEntity.class, new Box(start, aim).expand(48), e -> true)
                .stream().map(e -> e.getBoundingBox().expand(0.3)).toList();
        float health = player.getHealth();
        Throw best = null;
        int bestHits = 0;
        double bestOff = Double.MAX_VALUE;
        for (boolean high : new boolean[] {true, false}) {
            var shot = Ballistics.aim(Math.hypot(dx, dz), aim.y - start.y, PEARL, high);
            if (shot.isEmpty()) continue;
            for (float nudge : NUDGES) {
                float pitch = shot.get().pitch() + nudge;
                Vec3d dir = direction(yaw, pitch);
                var landing = lands(world, start, dir.multiply(PEARL.speed()), crystals, target, tolerance);
                if (landing.isEmpty()) continue;
                int hits = steadiness(world, start, dir, crystals, target, tolerance, Math.max(STEADY, bestHits), health);
                double off = Math.hypot(landing.get().getX() - target.getX(), landing.get().getZ() - target.getZ());
                if (hits < STEADY || best != null && (hits < bestHits || hits == bestHits && off >= bestOff)) continue;
                best = new Throw(yaw, pitch, strikeOf(world, start, dir, crystals), landing.get(), hits);
                bestHits = hits;
                bestOff = off;
                if (hits == SAMPLES && off < 1) return Optional.of(best); // As sure and as near as a throw gets.
            }
        }
        return Optional.ofNullable(best);
    }

    private static Vec3d strikeOf(ServerWorld world, Vec3d start, Vec3d dir, List<Box> avoid) {
        return fly(world, start, dir.multiply(PEARL.speed()), avoid).map(Flight::at).orElse(start);
    }

    /**
     * How many of the {@link #SAMPLES} spread throws about {@code dir} land; fewer than {@code floor} as soon as that
     * is certain, and none when any of them could harm a player at {@code health}.
     */
    private static int steadiness(ServerWorld world, Vec3d start, Vec3d dir, List<Box> crystals, BlockPos target, double tolerance,
                                  int floor, float health) {
        int hits = 0, misses = 0;
        for (double[] n : NOISE) {
            Vec3d v = dir.add(n[0], n[1], n[2]).multiply(PEARL.speed());
            var flight = fly(world, start, v, crystals);
            if (flight.isEmpty()) {
                // Flew off without striking: the pearl is lost and the player stays put.
            } else if (flight.get().onEntity()) {
                return 0;
            } else if (landsOn(world, flight.get().at(), target, tolerance).isPresent()) {
                hits++;
                continue;
            } else {
                // A miss: harmless only if the drop under where it puts the player is one it lives through. Wedged in
                // a cage's bars is such a miss (a pearl gets it down again); put over the void or a long drop is not.
                var drop = drop(world, flight.get().at(), 64);
                if (drop.isEmpty() || health - 5 - Math.max(0, Math.ceil(drop.getAsDouble() - 3)) < SAFE_HEALTH) return 0;
            }
            if (++misses > SAMPLES - floor) return hits;
        }
        // Samples cannot show a rare harm (a cage's bars once let a pearl through onto its crystal that none of sixteen
        // did), so the spread's eight extremes are flown too, for harm alone: where the throw can go is bounded by them.
        for (int corner = 0; corner < 8; corner++) {
            Vec3d v = dir.add((corner & 1) == 0 ? -SPREAD : SPREAD, (corner & 2) == 0 ? -SPREAD : SPREAD, (corner & 4) == 0 ? -SPREAD : SPREAD)
                    .multiply(PEARL.speed());
            var flight = fly(world, start, v, crystals);
            if (flight.isEmpty() || landsOn(world, flight.get().at(), target, tolerance).isPresent()) continue;
            if (flight.get().onEntity()) return 0;
            var drop = drop(world, flight.get().at(), 64);
            if (drop.isEmpty() || health - 5 - Math.max(0, Math.ceil(drop.getAsDouble() - 3)) < SAFE_HEALTH) return 0;
        }
        return hits;
    }

    /** The ground a pearl at {@code v} brings the player to, when it is the target's; never through a crystal. */
    private static Optional<BlockPos> lands(ServerWorld world, Vec3d start, Vec3d v, List<Box> crystals, BlockPos target, double tolerance) {
        var flight = fly(world, start, v, crystals);
        if (flight.isEmpty() || flight.get().onEntity()) return Optional.empty();
        return landsOn(world, flight.get().at(), target, tolerance);
    }

    private static Optional<BlockPos> landsOn(ServerWorld world, Vec3d strike, BlockPos target, double tolerance) {
        var rest = rest(world, strike);
        if (rest.isEmpty() || rest.get().getY() != target.getY()
                || Math.hypot(rest.get().getX() - target.getX(), rest.get().getZ() - target.getZ()) > tolerance) return Optional.empty();
        return rest;
    }
}
