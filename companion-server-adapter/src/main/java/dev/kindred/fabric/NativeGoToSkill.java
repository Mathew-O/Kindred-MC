package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Walks to a block position along a bounded A* route using only ordinary movement inputs (forward, yaw, jump).
 * It never breaks or places blocks. A route that cannot reach the goal may still be followed toward the
 * closest explored point when {@code partial} is requested, which is how exploration makes headway.
 */
final class NativeGoToSkill implements SkillBody {
    private static final int MAX_REPLANS = 4;
    private static final int STUCK_TICKS = 40;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final BlockPos target;
    private final double range;
    private final boolean partial;
    private final int maxNodes;
    private final boolean sprint;
    private List<BlockPos> path;
    private boolean pathComplete;
    private int index;
    private int replans;
    private int ticksSinceProgress;
    private double bestDistance = Double.MAX_VALUE;
    private int ticks;

    /** With {@code sprint}, straight stretches are run at sprint speed, as a player gets away from a creeper. */
    NativeGoToSkill(NativePlayerBody body, Supplier<ActionLease> lease, BlockPos target, double range, boolean partial, int maxNodes,
                    boolean sprint) {
        this.sprint = sprint;
        this.body = body;
        this.lease = lease;
        this.target = target.toImmutable();
        if (!(range >= 0 && range <= 64)) throw new IllegalArgumentException("Goto range must be 0..64 blocks");
        this.range = range;
        this.partial = partial;
        this.maxNodes = Math.clamp(maxNodes, 200, 20_000);
        var player = body.entity();
        if (player.getPos().distanceTo(Vec3d.ofBottomCenter(target)) > 160) throw new IllegalArgumentException("Goto target is too far for one route");
    }

    boolean goalReached(BlockPos feet) {
        return Math.sqrt(feet.getSquaredDistance(target)) <= range + 0.01;
    }

    private boolean plan() {
        var player = body.entity();
        var navigator = new NativeNavigator(player.getServerWorld());
        BlockPos start = BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
        var found = navigator.find(start, target, this::goalReached, maxNodes, 96);
        if (found.nodes().size() < 2 && !goalReached(start) || !found.complete() && !partial) {
            KindredMod.LOGGER.debug("No route from {} toward {} (expanded {}, standing {})", start.toShortString(),
                    target.toShortString(), found.expanded(), navigator.standable(start));
            path = null;
            return false;
        }
        path = found.nodes();
        pathComplete = found.complete();
        index = path.size() > 1 ? 1 : 0;
        ticksSinceProgress = 0;
        bestDistance = Double.MAX_VALUE;
        return true;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        var player = body.entity();
        ticks++;
        BlockPos feet = BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
        if (goalReached(feet) && (player.isOnGround() || player.isTouchingWater())) {
            body.stopInputs();
            return Step.completed(Map.of("arrived", feet.toShortString()));
        }
        if (path == null || fresh) {
            if (!plan()) {
                body.stopInputs();
                return Step.blocked("No walkable route toward " + target.toShortString(), true);
            }
        }
        Vec3d pos = player.getPos();
        // Advance past waypoints already reached, including when momentum carries the body beyond one.
        while (index < path.size() - 1 && reached(pos, path.get(index))) index++;
        if (index < path.size() - 1 && reached(pos, path.get(index + 1))) index++;
        BlockPos next = path.get(index);
        if (index == path.size() - 1 && reached(pos, next)) {
            body.stopInputs();
            if (pathComplete) return Step.completed(Map.of("arrived", feet.toShortString()));
            // The partial route ends here; another plan from this vantage may see farther.
            if (!replan()) return Step.completed(Map.of("closest", feet.toShortString(), "partial", "true"));
            next = path.get(index);
        }
        double horizontal = Math.hypot(next.getX() + 0.5 - pos.x, next.getZ() + 0.5 - pos.z);
        double distance = horizontal + Math.abs(next.getY() - pos.y);
        if (distance < bestDistance - 0.05) {
            bestDistance = distance;
            ticksSinceProgress = 0;
        } else if (++ticksSinceProgress > STUCK_TICKS || horizontal > 4 || pos.y < next.getY() - 2.5) {
            if (!replan()) {
                body.stopInputs();
                return Step.blocked("Route to " + target.toShortString() + " is blocked", true);
            }
            next = path.get(index);
        }
        var navigator = new NativeNavigator(player.getServerWorld());
        if (navigator.hazard(next) || navigator.hazard(next.up())) {
            if (!replan()) {
                body.stopInputs();
                return Step.blocked("Route crosses a new hazard", true);
            }
            next = path.get(index);
        }
        if (!steer(next, pos)) {
            body.stopInputs();
            return Step.blocked("Movement authority denied", true);
        }
        return Step.running(Map.of("remaining", String.valueOf(Math.round(Math.sqrt(feet.getSquaredDistance(target)))),
                "waypoint", String.valueOf(index)), true);
    }

    private boolean replan() {
        if (++replans > MAX_REPLANS) return false;
        return plan();
    }

    private static boolean reached(Vec3d pos, BlockPos node) {
        double dx = node.getX() + 0.5 - pos.x;
        double dz = node.getZ() + 0.5 - pos.z;
        return dx * dx + dz * dz < 0.16 && Math.abs(pos.y - node.getY()) < 0.9;
    }

    private boolean steer(BlockPos next, Vec3d pos) {
        var player = body.entity();
        double dx = next.getX() + 0.5 - pos.x;
        double dz = next.getZ() + 0.5 - pos.z;
        double horizontal = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        boolean inWater = player.isTouchingWater();
        boolean stepUp = next.getY() > Math.floor(pos.y + 0.01) && horizontal < 1.6;
        boolean jump = (player.isOnGround() && (stepUp || player.horizontalCollision))
                || (inWater && (next.getY() >= Math.floor(pos.y) || player.isSubmergedInWater()));
        float forward = horizontal < 0.35 ? 0.3f : 1.0f;
        boolean run = sprint && forward == 1.0f && !stepUp && !inWater;
        return body.move(lease.get(), forward, 0, jump, run, yaw);
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }
}
