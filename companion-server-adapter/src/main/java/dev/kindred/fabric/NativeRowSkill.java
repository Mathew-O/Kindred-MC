package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Rows the boat the player sits in toward a point, with the paddle inputs a player's client sets: forward, and left
 * or right to turn, easing off the turn as the bow comes round. It ends on arriving, on reaching a shore (land under
 * the bow, or the boat aground and going nowhere), or when its time is up, and says which. It never leaves the boat.
 * Set off {@code fromLand}, a boat on the ground is rowed over it to the water first (slowly, as on land), and only a
 * shore met once afloat ends it.
 */
final class NativeRowSkill implements SkillBody {
    /** Degrees off course within which it rows straight on, and beyond which it turns without rowing. */
    private static final float ON_COURSE = 6f, TURN_ONLY = 60f;
    /** Ticks with the boat going nowhere while rowing on course before it counts as aground. */
    private static final int AGROUND_TICKS = 30;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final double x, z, range;
    private final int maxTicks;
    private final boolean fromLand;
    private int ticks, stillTicks;
    private boolean afloat;
    private Vec3d last;

    NativeRowSkill(NativePlayerBody body, Supplier<ActionLease> lease, double x, double z, double range, int seconds) {
        this(body, lease, x, z, range, seconds, false);
    }

    NativeRowSkill(NativePlayerBody body, Supplier<ActionLease> lease, double x, double z, double range, int seconds, boolean fromLand) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !(range >= 0.5 && range <= 64)) throw new IllegalArgumentException("Invalid row target");
        if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("A row lasts 1..120 seconds");
        this.body = body;
        this.lease = lease;
        this.x = x;
        this.z = z;
        this.range = range;
        this.maxTicks = seconds * 20;
        this.fromLand = fromLand;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        var boat = body.boat();
        if (boat.isEmpty()) {
            body.stopInputs();
            return Step.blocked("Not in a boat", true);
        }
        var player = body.entity();
        var at = boat.get().getPos();
        double dx = x - at.x, dz = z - at.z, distance = Math.hypot(dx, dz);
        if (distance <= range) return end("arrived", at);
        if (++ticks > maxTicks) return end("far", at);
        float heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float off = MathHelper.wrapDegrees(heading - boat.get().getYaw());
        // Land under the bow, at the water's level: a shore.
        double yaw = Math.toRadians(boat.get().getYaw());
        var world = player.getServerWorld();
        boolean landAhead = false;
        for (double up : new double[] {-0.2, 0.2}) {
            BlockPos bow = BlockPos.ofFloored(at.x - Math.sin(yaw) * 1.6, at.y + up, at.z + Math.cos(yaw) * 1.6);
            landAhead |= Math.abs(off) < 30 && world.isChunkLoaded(bow) && !world.getFluidState(bow).isIn(FluidTags.WATER)
                    && !world.getBlockState(bow).getCollisionShape(world, bow).isEmpty();
        }
        afloat |= boat.get().isTouchingWater();
        if (landAhead && (!fromLand || afloat)) return end("shore", at);
        if (last != null && Math.abs(off) < ON_COURSE * 2 && at.squaredDistanceTo(last) < 0.03 * 0.03) {
            if (++stillTicks > AGROUND_TICKS) return end("shore", at);
        } else stillTicks = 0;
        last = at;
        boolean left = off < -ON_COURSE, right = off > ON_COURSE;
        if (!body.row(lease.get(), Math.abs(off) < TURN_ONLY, left, right)) {
            body.stopInputs();
            return Step.blocked("Rowing authority denied", true);
        }
        return Step.running(Map.of("off", String.format(Locale.ROOT, "%.0f", distance)), false);
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }

    private Step end(String why, Vec3d at) {
        body.row(lease.get(), false, false, false);
        body.stopInputs();
        return Step.completed(Map.of("ended", why, "x", String.format(Locale.ROOT, "%.1f", at.x), "z", String.format(Locale.ROOT, "%.1f", at.z)));
    }
}
