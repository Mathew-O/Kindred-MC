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
 * <p>
 * It moves as a player does. Across level, clear ground it heads straight for the furthest of the next few
 * waypoints it can reach in a line, rather than zigzagging from block centre to block centre. It turns smoothly and
 * slows for sharp turns. It sprints on open runs when its food allows, sprint-jumps along a long clear stretch with
 * head room, and eases off near the goal. Tunnels, steps, water and edges are followed waypoint by waypoint, as before.
 */
final class NativeGoToSkill implements SkillBody {
    private static final int MAX_REPLANS = 4;
    private static final int STUCK_TICKS = 40;
    /** How many waypoints ahead a straight run may reach. */
    private static final int LOOK_AHEAD = 6;
    /** Straight, clear blocks ahead for a sprint-jump (one carries three to four blocks). */
    private static final double JUMP_RUN = 5;
    /** Food levels for a sprint (vanilla's own floor) and for sprint-jumping, which costs twice the hunger. */
    private static final int SPRINT_FOOD = 7, JUMP_FOOD = 10;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final BlockPos target;
    private final double range;
    private final boolean partial;
    private final int maxNodes;
    private final boolean sprint;
    private final int maxDrop;
    private List<BlockPos> path;
    private boolean pathComplete;
    private int index;
    /** A sprint-jump is in the air; it is held to its landing, as a player commits to one. */
    private boolean leapt;
    private int replans;
    private int ticksSinceProgress;
    private double bestDistance = Double.MAX_VALUE;
    private int ticks;

    /** With {@code sprint}, straight stretches are run at sprint speed, as a player gets away from a creeper. */
    NativeGoToSkill(NativePlayerBody body, Supplier<ActionLease> lease, BlockPos target, double range, boolean partial, int maxNodes,
                    boolean sprint, int maxDrop) {
        this.sprint = sprint;
        this.maxDrop = maxDrop;
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
        var navigator = new NativeNavigator(player.getServerWorld(), maxDrop);
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
        // With corners rounded, the nearest of the next few waypoints on this level is where the route has got to.
        int nearest = index;
        double nearestDistance = flat(pos, path.get(index));
        for (int k = index + 1; k <= Math.min(path.size() - 1, index + LOOK_AHEAD); k++) {
            if (path.get(k).getY() != path.get(index).getY()) break;
            double d = flat(pos, path.get(k));
            if (d < nearestDistance - 0.01) { nearest = k; nearestDistance = d; }
        }
        if (nearest != index && onLevel(pos, path.get(nearest))) index = nearest;
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

    private static double flat(Vec3d pos, BlockPos node) {
        return Math.hypot(node.getX() + 0.5 - pos.x, node.getZ() + 0.5 - pos.z);
    }

    /** Standing on the waypoint's level, or above it in the air mid-jump (a jump rises about 1.25 blocks). */
    private boolean onLevel(Vec3d pos, BlockPos node) {
        var player = body.entity();
        double above = pos.y - node.getY();
        return above > -0.6 && above < (player.isOnGround() || player.isTouchingWater() ? 0.6 : 1.4);
    }

    /** The route stays on this waypoint's level for the next few, so a sprint will not overrun a step or a drop. */
    private boolean levelAhead() {
        if (index + 2 > path.size() - 1) return false;
        int y = path.get(index).getY();
        for (int k = index + 1; k <= index + 2; k++) if (path.get(k).getY() != y) return false;
        return true;
    }

    private static boolean reached(Vec3d pos, BlockPos node) {
        double dx = node.getX() + 0.5 - pos.x;
        double dz = node.getZ() + 0.5 - pos.z;
        return dx * dx + dz * dz < 0.16 && Math.abs(pos.y - node.getY()) < 0.9;
    }

    private boolean steer(BlockPos next, Vec3d pos) {
        var player = body.entity();
        var navigator = new NativeNavigator(player.getServerWorld());
        boolean inWater = player.isTouchingWater();
        boolean grounded = player.isOnGround();
        if (grounded || inWater) leapt = false;
        // The furthest of the next few waypoints reachable in a straight line over level, clear ground.
        BlockPos aim = next;
        boolean level = !inWater && onLevel(pos, next);
        if (level && straight(navigator, pos, next, false)) {
            for (int k = index + 1; k <= Math.min(path.size() - 1, index + LOOK_AHEAD); k++) {
                BlockPos candidate = path.get(k);
                if (candidate.getY() != next.getY() || !straight(navigator, pos, candidate, false)) break;
                aim = candidate;
            }
        }
        double dx = aim.getX() + 0.5 - pos.x;
        double dz = aim.getZ() + 0.5 - pos.z;
        double horizontal = Math.hypot(dx, dz);
        float yaw = horizontal < 1e-3 ? player.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float turn = body.turnLeft(yaw);
        double toNext = flat(pos, next);
        boolean stepUp = next.getY() > Math.floor(pos.y + 0.01) && toNext < 1.6;
        int food = player.getHungerManager().getFoodLevel();
        double toGoal = Math.hypot(target.getX() + 0.5 - pos.x, target.getZ() + 0.5 - pos.z);
        boolean nearGoal = toGoal < range + 2.5;
        boolean levelAhead = level && levelAhead();
        // A jump once taken is held to its landing. On the ground: slow for a sharp turn, and for the last half block
        // to a waypoint where the route changes level or ends, as a player does.
        float forward = leapt ? 1.0f : turn > 70 ? 0.25f : turn > 40 ? 0.6f
                : aim.equals(next) && toNext < 0.35 && !levelAhead ? 0.3f : 1.0f;
        // A sprint along level, clear ground with food to spare, easing off near the goal unless told to run.
        boolean sprinting = leapt || forward == 1.0f && !stepUp && !inWater && turn < 30 && food >= SPRINT_FOOD
                && (sprint || levelAhead && !nearGoal && straight(navigator, pos, next, false));
        // A sprint-jump: lined up, a long clear run ahead with head room all the way, and food for its extra hunger.
        boolean sprintJump = sprinting && grounded && !leapt && turn < 12 && horizontal >= JUMP_RUN && food >= JUMP_FOOD
                && toGoal > range + JUMP_RUN && straight(navigator, pos, aim, true);
        if (sprintJump) leapt = true;
        boolean jump = (grounded && (stepUp || player.horizontalCollision || sprintJump))
                || (inWater && (next.getY() >= Math.floor(pos.y) || player.isSubmergedInWater()));
        // Eyes a few blocks down the way, a little higher for a step up.
        float pitch = stepUp ? -5f : Math.clamp((float) Math.toDegrees(Math.atan2(player.getStandingEyeHeight() - 0.8, Math.max(2.5, Math.min(horizontal, 6)))), 0f, 25f);
        return body.walk(lease.get(), forward, 0, jump, sprinting, yaw, pitch);
    }

    /**
     * The body can go straight from {@code pos} to the centre of {@code to} on the same level: standable ground with
     * nothing in the way all along the line, for the body's whole width, and with {@code headRoom} a third block free
     * overhead for a jump.
     */
    private static boolean straight(NativeNavigator navigator, Vec3d pos, BlockPos to, boolean headRoom) {
        double tx = to.getX() + 0.5, tz = to.getZ() + 0.5;
        double length = Math.hypot(tx - pos.x, tz - pos.z);
        int y = to.getY();
        int steps = Math.max(1, (int) Math.ceil(length / 0.25));
        for (int i = 0; i <= steps; i++) {
            double x = pos.x + (tx - pos.x) * i / steps, z = pos.z + (tz - pos.z) * i / steps;
            for (double ox = -0.3; ox <= 0.31; ox += 0.6) for (double oz = -0.3; oz <= 0.31; oz += 0.6) {
                BlockPos cell = BlockPos.ofFloored(x + ox, y, z + oz);
                if (!navigator.standable(cell) || navigator.hazard(cell)) return false;
                if (headRoom && !navigator.passable(cell.up(2))) return false;
            }
        }
        return true;
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }
}
