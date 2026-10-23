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
    /**
     * Waypoints ahead checked every tick, re-plans allowed for what changed ahead, and ticks between them. A block put
     * down in the way is seen coming and the route bends round it at a run (F-10g); before, the player walked into it
     * and stood two seconds against it before re-planning.
     */
    private static final int WATCH_AHEAD = 10, MAX_REROUTES = 40, REROUTE_GAP = 3;
    /** Half the body's width (0.3) and a little room, for straight runs past corners. */
    private static final double MARGIN = 0.42;
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
    /** Arrived only near the middle of the target block, as a player lines up on a spot to build from (E-08b). */
    private boolean centre;
    private List<BlockPos> path;
    private boolean pathComplete;
    private int index;
    /** A sprint-jump is in the air; it is held to its landing, as a player commits to one. */
    private boolean leapt;
    private int replans, reroutes, lastReroute = -REROUTE_GAP;
    /** Something ahead has changed and the route is to be planned again on landing. */
    private boolean rerouteDue;
    private int ticksSinceProgress;
    private double bestDistance = Double.MAX_VALUE;
    private int ticks;
    /** Mob kinds it means to fight, when it keeps clear of the rest; null when it minds no mobs. */
    private final java.util.Set<String> engage;
    /** Where each mob it kept clear of stood when the route was planned. */
    private final java.util.Map<java.util.UUID, Vec3d> planned = new java.util.HashMap<>();
    /** How far the route looks for mobs, how often it looks again, and how far one moves before the route is redone. */
    private static final double MOB_WATCH = 40, MOB_MOVED = 3;
    private static final int MOB_CHECK = 10;

    /** With {@code sprint}, straight stretches are run at sprint speed, as a player gets away from a creeper. */
    NativeGoToSkill(NativePlayerBody body, Supplier<ActionLease> lease, BlockPos target, double range, boolean partial, int maxNodes,
                    boolean sprint, int maxDrop) {
        this(body, lease, target, range, partial, maxNodes, sprint, maxDrop, null);
    }

    /**
     * With {@code engage} not null, the route keeps out of the notice of hostile mobs about (F-10g), all but the
     * kinds named there, which the player means to fight: a wither skeleton passed at more than its sixteen blocks
     * where a way round allows, rather than walked up to. The route is planned again as they move.
     */
    NativeGoToSkill(NativePlayerBody body, Supplier<ActionLease> lease, BlockPos target, double range, boolean partial, int maxNodes,
                    boolean sprint, int maxDrop, java.util.Set<String> engage) {
        this.engage = engage == null ? null : java.util.Set.copyOf(engage);
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

    /** Arrive only near the middle of the target block. */
    NativeGoToSkill centred() {
        centre = true;
        return this;
    }

    /** The hotbar slot held on the way, as a player walks with food out for animals to follow (M-01a); -1 for none. */
    private int hold = -1;
    /** Done after this many blocks along the route, to look back at what follows; 0 for the whole way. */
    private int leg;
    private Vec3d start;

    NativeGoToSkill holding(int slot) {
        if (slot < -1 || slot > 8) throw new IllegalArgumentException("A held hotbar slot is 0..8");
        hold = slot;
        return this;
    }

    NativeGoToSkill leg(int blocks) {
        if (blocks < 0 || blocks > 64) throw new IllegalArgumentException("A leg is 0..64 blocks");
        leg = blocks;
        return this;
    }

    boolean holds() { return hold >= 0; }

    private boolean plan() {
        var player = body.entity();
        var navigator = new NativeNavigator(player.getServerWorld(), maxDrop);
        if (engage != null) navigator.avoiding(dangers());
        BlockPos start = BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
        var found = navigator.find(start, target, this::goalReached, maxNodes, 96);
        // A body over an edge searches from the block below its feet; when that is the goal, the route is the step off.
        boolean below = found.complete() && found.nodes().size() == 1 && goalReached(found.nodes().getFirst());
        if (found.nodes().size() < 2 && !goalReached(start) && !below || !found.complete() && !partial) {
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
        if (hold >= 0 && player.getInventory().selectedSlot != hold && !body.selectHotbar(lease.get(), hold)) {
            body.stopInputs();
            return Step.blocked("Could not take hotbar slot " + hold + " in hand", true);
        }
        if (goalReached(feet) && (!centre || reached(player.getPos(), target)) && (player.isOnGround() || player.isTouchingWater())) {
            body.stopInputs();
            return Step.completed(Map.of("arrived", feet.toShortString()));
        }
        if (start == null) start = player.getPos();
        if (leg > 0 && player.isOnGround() && Math.hypot(player.getX() - start.x, player.getZ() - start.z) >= leg) {
            body.stopInputs();
            return Step.completed(Map.of("leg", feet.toShortString()));
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
        while (index < path.size() - 1 && (reached(pos, path.get(index)) || passed(pos, index))) index++;
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
        // Ahead of it, the way has changed (a block put down in it, a hole opened, lava come in), or the mobs it keeps
        // clear of have moved: a new route from here while still on the move, as a player steers round what it sees
        // coming. Mid-jump, on landing.
        if (rerouteDue || blockedAhead(navigator) || engage != null && ticks % MOB_CHECK == 0 && mobsMoved()) {
            rerouteDue = true;
            boolean footing = player.isOnGround() || player.isTouchingWater();
            if (footing && ticks - lastReroute >= REROUTE_GAP && reroutes < MAX_REROUTES) {
                reroutes++;
                lastReroute = ticks;
                rerouteDue = false;
                if (!plan() && !replan()) {
                    body.stopInputs();
                    return Step.blocked("Route to " + target.toShortString() + " is blocked ahead", true);
                }
                next = path.get(index);
            }
        }
        if (!steer(next, pos)) {
            body.stopInputs();
            return Step.blocked("Movement authority denied", true);
        }
        return Step.running(Map.of("remaining", String.valueOf(Math.round(Math.sqrt(feet.getSquaredDistance(target)))),
                "waypoint", String.valueOf(index)), true);
    }

    /**
     * The hostile mobs about that it does not mean to fight, as dangers to keep clear of: how far each notices a player,
     * from vanilla's own ranges (MobSenses), and whether it can see this one now. A piglin counts as hostile while the
     * player wears no gold.
     */
    private List<NativeNavigator.Danger> dangers() {
        var player = body.entity();
        planned.clear();
        var dangers = new java.util.ArrayList<NativeNavigator.Danger>();
        boolean gold = false;
        for (var armor : player.getArmorItems()) gold |= armor.getItem() instanceof net.minecraft.item.ArmorItem piece
                && piece.getMaterial().matchesKey(net.minecraft.item.ArmorMaterials.GOLD.getKey().orElseThrow());
        for (var mob : player.getServerWorld().getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class,
                player.getBoundingBox().expand(MOB_WATCH, 16, MOB_WATCH), m -> m.isAlive() && m instanceof net.minecraft.entity.mob.Monster)) {
            String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
            if (engage.contains(id)) continue;
            var profile = dev.kindred.core.tactics.MobSenses.of(id, true);
            if (id.equals("piglin") && !gold) {
                profile = new dev.kindred.core.tactics.MobSenses.Profile(profile.range(), profile.sight(),
                        dev.kindred.core.tactics.MobSenses.Temper.HOSTILE, profile.ranged(), profile.outpaces());
            }
            if (profile.temper() != dev.kindred.core.tactics.MobSenses.Temper.HOSTILE) continue;
            dangers.add(new NativeNavigator.Danger(mob.getPos().add(0, 0.5, 0), profile, mob.canSee(player)));
            planned.put(mob.getUuid(), mob.getPos());
        }
        return dangers;
    }

    /** A mob it keeps clear of has moved a few blocks since the route was planned, or a new one has come within range. */
    private boolean mobsMoved() {
        var player = body.entity();
        var world = player.getServerWorld();
        for (var entry : planned.entrySet()) {
            var mob = world.getEntity(entry.getKey());
            if (mob == null || !mob.isAlive() || mob.getPos().squaredDistanceTo(entry.getValue()) > MOB_MOVED * MOB_MOVED) return true;
        }
        return world.getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, player.getBoundingBox().expand(24, 8, 24),
                m -> m.isAlive() && m instanceof net.minecraft.entity.mob.Monster && !planned.containsKey(m.getUuid())
                        && !engage.contains(net.minecraft.registry.Registries.ENTITY_TYPE.getId(m.getType()).getPath())).size() > 0;
    }

    /** One of the next few waypoints can no longer be stood on, or has turned hazardous, since the route was planned. */
    private boolean blockedAhead(NativeNavigator navigator) {
        for (int k = index; k <= Math.min(path.size() - 1, index + WATCH_AHEAD); k++) {
            BlockPos node = path.get(k);
            if (!navigator.standable(node) && !navigator.swimmable(node) || navigator.hazard(node) || navigator.hazard(node.up())) return true;
        }
        return false;
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

    /**
     * In the air, the body is already beyond waypoint {@code k} on its way to the next, on the same level and near the
     * line between them: passed, though mid-jump above it, where {@link #reached} does not count it. Without this a waypoint flown
     * over stayed the one to make for, and the body swung back toward it in the air (F-10g's hallway, 45 degrees in a tick).
     */
    private boolean passed(Vec3d pos, int k) {
        BlockPos node = path.get(k), following = path.get(k + 1);
        if (body.entity().isOnGround() || node.getY() != following.getY() || !onLevel(pos, node)) return false;
        double ax = node.getX() + 0.5, az = node.getZ() + 0.5;
        double bx = following.getX() + 0.5 - ax, bz = following.getZ() + 0.5 - az;
        double px = pos.x - ax, pz = pos.z - az;
        double length = Math.hypot(bx, bz);
        double along = (px * bx + pz * bz) / length, across = Math.abs(px * bz - pz * bx) / length;
        return along > 0 && along < length && across < 0.8;
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
        // With no long straight run in sight, a point a little way along the route rather than the next block's centre,
        // as a player steers along a way rather than from block to block: no sharp swing to a waypoint a lane over and
        // barely ahead (F-10g's hallway snapped 45 degrees in a tick past a block put in its way).
        Vec3d aimAt = centre(aim);
        if (level && flat(pos, aim) < PURSUIT) {
            for (double ahead : new double[] {PURSUIT, PURSUIT - 1}) {
                Vec3d carrot = alongRoute(pos, ahead);
                if (carrot != null && straight(navigator, pos, carrot, false)) {
                    aimAt = carrot;
                    break;
                }
            }
        }
        double dx = aimAt.x - pos.x;
        double dz = aimAt.z - pos.z;
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

    /** How far along the route the steering looks when no long straight run is in sight. */
    private static final double PURSUIT = 2.5;

    /**
     * The point {@code ahead} blocks along the route from {@code pos}, through the next waypoint and on, keeping to
     * the next waypoint's level; null when the route changes level or ends first.
     */
    private Vec3d alongRoute(Vec3d pos, double ahead) {
        int y = path.get(index).getY();
        Vec3d b = centre(path.get(index));
        // Where the body is along the route: its foot on the segment from the waypoint before to the next one.
        Vec3d a = index > 0 && path.get(index - 1).getY() == y ? centre(path.get(index - 1)) : new Vec3d(pos.x, y, pos.z);
        Vec3d ab = b.subtract(a);
        double lengthSquared = ab.x * ab.x + ab.z * ab.z;
        double t = lengthSquared < 1e-6 ? 1 : Math.clamp(((pos.x - a.x) * ab.x + (pos.z - a.z) * ab.z) / lengthSquared, 0, 1);
        Vec3d from = a.add(ab.multiply(t));
        double left = ahead;
        for (int k = index; k < path.size(); k++) {
            BlockPos node = path.get(k);
            if (node.getY() != y) return null;
            Vec3d to = centre(node);
            double length = Math.hypot(to.x - from.x, to.z - from.z);
            if (length >= left) return from.add(to.subtract(from).multiply(left / length));
            left -= length;
            from = to;
        }
        return null;
    }

    private static Vec3d centre(BlockPos node) {
        return new Vec3d(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
    }

    private static boolean straight(NativeNavigator navigator, Vec3d pos, Vec3d to, boolean headRoom) {
        return straight(navigator, pos, to.x, to.z, (int) Math.floor(to.y + 0.01), headRoom);
    }

    /**
     * The body can go straight from {@code pos} to the centre of {@code to} on the same level: standable ground with
     * nothing in the way all along the line, for the body's whole width, and with {@code headRoom} a third block free
     * overhead for a jump.
     */
    private static boolean straight(NativeNavigator navigator, Vec3d pos, BlockPos to, boolean headRoom) {
        return straight(navigator, pos, to.getX() + 0.5, to.getZ() + 0.5, to.getY(), headRoom);
    }

    private static boolean straight(NativeNavigator navigator, Vec3d pos, double tx, double tz, int y, boolean headRoom) {
        double length = Math.hypot(tx - pos.x, tz - pos.z);
        int steps = Math.max(1, (int) Math.ceil(length / 0.25));
        for (int i = 0; i <= steps; i++) {
            double x = pos.x + (tx - pos.x) * i / steps, z = pos.z + (tz - pos.z) * i / steps;
            // Half the body's width and a margin: an eased turn lags its aim, so a line that only just clears a corner
            // leaves the body clipping it (F-10g's hallway: one block put down, the body caught on its corner).
            for (double ox = -MARGIN; ox <= MARGIN + 0.01; ox += 2 * MARGIN) for (double oz = -MARGIN; oz <= MARGIN + 0.01; oz += 2 * MARGIN) {
                BlockPos cell = BlockPos.ofFloored(x + ox, y, z + oz);
                if (!navigator.standable(cell) || navigator.hazard(cell) || navigator.portal(cell)) return false;
                if (headRoom && !navigator.passable(cell.up(2))) return false;
            }
        }
        return true;
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }
}
