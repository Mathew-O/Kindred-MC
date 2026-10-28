package dev.kindred.fabric;

import dev.kindred.core.FollowSpacing;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.MannerismState;
import dev.kindred.core.Personality;
import dev.kindred.core.SprintStride;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Set;

/**
 * In plain terms: how an older-style AI body follows someone: staying close at one side, stepping out of their
 * way, and saying it is stuck if it gets no nearer for ten seconds.
 *
 * Technical: Ephemeral follow state, ticked only on the server thread under the current body lease.
 */
final class FollowBehavior {
    private FollowSpacing.Motion motion = FollowSpacing.Motion.HOLD;
    private final int preferredSide;
    private Vec3d ownerAnchor;
    private double heading;
    private Vec3d lastProgress;
    private Vec3d lastTarget;
    private int lastProgressAge;
    private int nextRouteAge;
    private int failedRoutes;
    private boolean blocked;
    private final SprintStride stride = new SprintStride();
    private final FocusBehavior focus = new FocusBehavior();

    FollowBehavior(CompanionEntity body, ServerPlayerEntity owner) {
        preferredSide = (body.getUuid().hashCode() & 1) == 0 ? 1 : -1;
        ownerAnchor = owner.getPos();
        heading = Math.toRadians(owner.getYaw());
        lastProgress = body.getPos();
        lastProgressAge = body.age;
        nextRouteAge = body.age;
    }

    boolean blocked() { return blocked; }

    /** True while settled beside the owner; the only follow phase where a social glance may own the gaze. */
    boolean waiting() { return motion == FollowSpacing.Motion.HOLD; }

    /** Moves the body one tick nearer, or lets it wait. */
    void tick(CompanionEntity body, ServerPlayerEntity owner) {
        tick(body, owner, MannerismState.Gait.WALK, null, Personality.defaults(), Set.of());
    }

    /**
     * @param gait   already bounded by {@link MannerismState}; applied only to motion this lease is performing.
     * @param glance a nearby person the attention policy admitted; focus may hold them, then drift back.
     */
    void tick(CompanionEntity body, ServerPlayerEntity owner, MannerismState.Gait gait, Entity glance,
              Personality personality, Set<Mannerisms.Quirk> quirks) {
        // Looking around must not make the companion orbit a stationary owner.
        Vec3d travel = owner.getPos().subtract(ownerAnchor);
        if (travel.horizontalLengthSquared() >= 0.25) {
            heading = Math.atan2(-travel.x, travel.z);
            ownerAnchor = owner.getPos();
        }
        FollowSpacing.Motion previous = motion;
        motion = FollowSpacing.next(motion, body.squaredDistanceTo(owner));
        if (motion == FollowSpacing.Motion.HOLD) {
            body.haltControls();
            lastTarget = null;
            resetProgress(body);
            focus.tick(body, owner, false, glance, personality, quirks);
            return;
        }
        if (previous != motion) {
            body.haltControls();
            lastTarget = null;
            resetProgress(body);
            nextRouteAge = body.age;
        }
        if (body.getPos().squaredDistanceTo(lastProgress) >= 0.25) resetProgress(body);
        if (body.age - lastProgressAge >= 200) {
            body.haltControls();
            blocked = true;
            return;
        }
        applyGait(body, gait, stride, striding(body, owner));
        focus.tick(body, owner, true, glance, personality, quirks);
        if (body.age < nextRouteAge) return;
        nextRouteAge = body.age + 10;

        if (lastTarget != null && !safeDestination(body, lastTarget)) {
            body.haltControls();
            lastTarget = null;
        }
        Vec3d preferred = destinationNear(body, owner, preferredSide);
        // Keep a useful route while the target shifts slightly; do not reset native navigation every tick.
        if (lastTarget != null && !body.getNavigation().isIdle()
                && lastTarget.squaredDistanceTo(preferred) < 1) return;

        for (int side : new int[]{preferredSide, 0, -preferredSide}) {
            Vec3d target = findStandingPosition(body, destinationNear(body, owner, side));
            if (target == null) continue;
            // A yielding route must move away from the owner, never through their current body.
            if (motion == FollowSpacing.Motion.YIELD && !clearsOwner(body.getPos(), target, owner.getPos())) continue;
            if (body.getNavigation().startMovingTo(target.x, target.y, target.z, gait == MannerismState.Gait.WALK ? 1.0 : 1.12)) {
                lastTarget = target;
                return;
            }
        }
        body.haltControls();
        lastTarget = null;
        if (++failedRoutes > 2) blocked = true;
    }

    private void resetProgress(CompanionEntity body) {
        lastProgress = body.getPos();
        lastProgressAge = body.age;
        failedRoutes = 0;
    }

    /** True only while closing a real gap. The last few blocks, a yield, and a shuffle are walked. */
    private boolean striding(CompanionEntity body, ServerPlayerEntity owner) {
        return motion == FollowSpacing.Motion.APPROACH
                && body.squaredDistanceTo(owner) > 6 * 6
                && body.getVelocity().horizontalLengthSquared() > 0.003;
    }

    /** One hop per stride. A jump is requested on a single tick, never on every grounded tick. */
    static void applyGait(CompanionEntity body, MannerismState.Gait gait, SprintStride stride, boolean striding) {
        boolean sprint = striding && gait != MannerismState.Gait.WALK;
        body.setSprinting(sprint);
        if (stride.tick(sprint && gait == MannerismState.Gait.SPRINT_JUMP, body.isOnGround(), sprint, body.age)) {
            body.getJumpControl().setActive();
        }
    }

    /**
     * Trusted facts for {@link MannerismState#gait}. A low ceiling, a narrow gap beside the route or an unsafe
     * predicted landing all forbid a jump; the policy then falls back to sprinting or walking.
     */
    static MannerismState.MovementContext movementContext(CompanionEntity body, boolean leased) {
        World world = body.getWorld();
        BlockPos feet = body.getBlockPos();
        Vec3d velocity = body.getVelocity().multiply(1, 0, 1);
        boolean moving = velocity.horizontalLengthSquared() > 1e-4;
        Vec3d direction = moving ? velocity.normalize() : Vec3d.ZERO;
        BlockPos ahead = moving ? BlockPos.ofFloored(body.getPos().add(direction)) : feet;
        boolean lowClearance = !passable(world, feet.up(2)) || !passable(world, ahead.up(2)) || !passable(world, ahead.up(1));
        boolean narrow = false;
        if (moving) {
            BlockPos left = BlockPos.ofFloored(body.getPos().add(-direction.z, 1, direction.x));
            BlockPos right = BlockPos.ofFloored(body.getPos().add(direction.z, 1, -direction.x));
            narrow = !passable(world, left) && !passable(world, right);
        }
        // Roughly where a sprint-jump lands: about a block and a half along the current motion.
        Vec3d landing = body.getPos().add(direction.multiply(1.5));
        boolean hazardousLanding = moving && !safeLanding(body, landing);
        return new MannerismState.MovementContext(leased, body.isOnGround(), lowClearance, narrow, hazardousLanding, false);
    }

    private static boolean passable(World world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean safeLanding(CompanionEntity body, Vec3d near) {
        for (int dy : new int[]{0, -1, 1}) {
            if (safeDestination(body, new Vec3d(near.x, Math.floor(near.y) + dy, near.z))) return true;
        }
        return false;
    }

    private Vec3d destinationNear(CompanionEntity body, ServerPlayerEntity owner, int side) {
        Vec3d away = body.getPos().subtract(owner.getPos()).multiply(1, 0, 1);
        if (motion == FollowSpacing.Motion.YIELD && away.horizontalLengthSquared() > 0.0001) {
            away = away.normalize();
            return owner.getPos().add(away.x * 2.25 + away.z * side * 1.25,
                    0, away.z * 2.25 - away.x * side * 1.25);
        }
        var offset = FollowSpacing.offset(heading, side);
        return owner.getPos().add(offset.x(), 0, offset.z());
    }

    /** Do not choose a retreat on the other side of the person we are making room for. */
    static boolean clearsOwner(Vec3d from, Vec3d to, Vec3d owner) {
        Vec3d away = from.subtract(owner);
        Vec3d movement = to.subtract(from);
        return away.x * movement.x + away.z * movement.z >= 0;
    }

    private static Vec3d findStandingPosition(CompanionEntity body, Vec3d near) {
        for (int dy : new int[]{0, 1, -1}) {
            Vec3d target = new Vec3d(near.x, Math.floor(near.y) + dy, near.z);
            if (safeDestination(body, target)) return target;
        }
        return null;
    }

    /** Bounded landing checks only; vanilla navigation still owns traversal and obstacle avoidance. */
    static boolean safeDestination(CompanionEntity body, Vec3d target) {
        World world = body.getWorld();
        Box box = body.getBoundingBox().offset(target.subtract(body.getPos()));
        if (!world.getWorldBorder().contains(box)) return false;
        // Check every touched chunk before collision/block reads, including a bounding box straddling a border.
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY - 1, box.minZ),
                BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (!world.isChunkLoaded(pos)) return false;
        }
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY - 1, box.minZ),
                BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty() || hazardous(state)) return false;
        }
        BlockPos ground = BlockPos.ofFloored(target).down();
        return world.getBlockState(ground).isSolidBlock(world, ground) && world.isSpaceEmpty(body, box);
    }

    private static boolean hazardous(BlockState state) {
        return state.isIn(BlockTags.FIRE) || state.isIn(BlockTags.CAMPFIRES)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.WITHER_ROSE);
    }
}
