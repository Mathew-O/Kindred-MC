package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player pushing a minecart along its rails by walking into it from behind, as a human
 * does, until it rolls on by itself. It can also nudge a stopped cart just far enough to clear a spot.
 *
 * Technical: A minecart on its rails pushed by walking into it, as a player pushes one (E-45e): a cart is not
 * solid to a player, and a player inside its bounds shoves it away from where the player stands. Walked into
 * from behind at a walk, never running or jumping (a body running through a cart pushed it back out again as
 * it passed its middle), until the cart rolls on of itself, as a cart on level rails does a long way loaded
 * and a short way empty. It ends once the cart rolls, the player clear behind it; or when the cart is gone or
 * its time is up. Given a block to {@code clear}, it is a nudge: it ends once the cart has come to rest clear
 * of that block (a cart stopped half in a cell's front, nudged on into the cell against the bed).
 */
final class NativePushSkill implements SkillBody {
    /** How fast the cart must roll, blocks a tick, for the push to count: an empty cart this fast picks up a mob it touches. */
    static final double ROLLING = 0.12;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID cart;
    private final net.minecraft.util.math.BlockPos clear;
    private final int maxTicks;
    /**
     * Set rolling and let roll (F-22c98c): walked into until the cart moves at {@link #SET_ROLLING}, then stepped back out of
     * its bounds and never walked into again, until it has taken up what it meets. A cart takes up what lies within a fifth
     * of a block after its move only while it moves faster than a tenth; one moving more than a fifth a tick can step from
     * beyond that fifth into touching, which stops it dead, untaken. Pushed at a walk until it rolls on, about 0.25 a tick,
     * a bring's cart met the librarian's held boat 0.207 short and stopped dead against it, three pushes alike (CI on #429,
     * run 44936497582). Each tick a body stands in a cart's bounds shoves it on whatever the body's pace: at half a walk,
     * or stood still once it moved, a push still gave it 0.18 then 0.22 a tick; stepped back out of it, no more than a fifth.
     */
    private final boolean gentle;
    private boolean started;
    private int ticks;

    /** How fast a gentle push's cart moves, blocks a tick, before the body steps back out of it. */
    static final double SET_ROLLING = 0.06;

    /** Apart enough that a body no longer stands in a cart's bounds: a body's half-width and a cart's, and a little. */
    private static final double CLEAR = 0.85;

    /**
     * A push of the given cart for at most the given seconds; with a block to clear, a nudge until the cart
     * has stopped off that block.
     */
    NativePushSkill(NativePlayerBody body, Supplier<ActionLease> lease, UUID cart, int seconds, net.minecraft.util.math.BlockPos clear) {
        this(body, lease, cart, seconds, clear, false);
    }

    /**
     * As above; {@code gentle}, the cart set rolling and let roll (see {@link #gentle}), until it has taken up what it meets
     * or the time is up. Not with a block to clear.
     */
    NativePushSkill(NativePlayerBody body, Supplier<ActionLease> lease, UUID cart, int seconds, net.minecraft.util.math.BlockPos clear, boolean gentle) {
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("A push lasts 1..30 seconds");
        if (gentle && clear != null) throw new IllegalArgumentException("A nudge is not a gentle push");
        this.gentle = gentle;
        this.body = body;
        this.lease = lease;
        this.cart = cart;
        this.clear = clear;
        this.maxTicks = seconds * 20;
    }

    /** Moves the push on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        var player = body.entity();
        if (!(player.getServerWorld().getEntity(cart) instanceof AbstractMinecartEntity minecart) || minecart.isRemoved()) {
            body.stopInputs();
            return Step.blocked("No cart to push", true);
        }
        Vec3d at = minecart.getPos(), from = player.getPos(), velocity = minecart.getVelocity();
        double gap = Math.hypot(at.x - from.x, at.z - from.z), speed = velocity.horizontalLength();
        // Rolling on is rolling away from the player: one rolling back down its line toward it, as a cart does that ran
        // out of way short of its cell, is met and pushed on, not taken for one rolling on. Taken so, each push of a
        // bring's loaded cart that had rolled back ended at once, at the speed it rolled back at, and the bring gave up
        // after eight with the librarian short of its cell (the wandered bring fixture in CI on 5b130a6).
        boolean rollingAway = speed >= ROLLING && velocity.x * (at.x - from.x) + velocity.z * (at.z - from.z) > 0;
        if (gentle) {
            if (minecart.hasPassengers()) {
                body.stopInputs();
                return Step.completed(Map.of("ended", "taken up"));
            }
            if (++ticks > maxTicks) {
                body.stopInputs();
                return Step.blocked("The cart would not roll", true);
            }
            if (gap > 4) {
                body.stopInputs();
                return Step.blocked("Too far from the cart to push it", true);
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-(at.x - from.x), at.z - from.z));
            // Set rolling, then let roll: walked into until the cart moves, then stepped back out of it, never walked into
            // again. Each tick a body stands in a cart's bounds shoves it on, about 0.05 to 0.09 a tick whatever its pace.
            if (started || speed >= SET_ROLLING) {
                started = true;
                if (gap < CLEAR) {
                    if (!body.walk(lease.get(), -1f, 0f, false, false, yaw, 20f)) {
                        body.stopInputs();
                        return Step.blocked("Walking authority denied", true);
                    }
                    return Step.running(Map.of("gap", String.format(Locale.ROOT, "%.1f", gap), "speed", String.format(Locale.ROOT, "%.2f", speed),
                            "gentle", "true", "back", "true"), false);
                }
                body.stopInputs();
                return Step.running(Map.of("gap", String.format(Locale.ROOT, "%.1f", gap), "speed", String.format(Locale.ROOT, "%.2f", speed),
                        "gentle", "true"), false);
            }
            if (!body.walk(lease.get(), 1f, 0f, false, false, yaw, 20f)) {
                body.stopInputs();
                return Step.blocked("Walking authority denied", true);
            }
            return Step.running(Map.of("gap", String.format(Locale.ROOT, "%.1f", gap), "speed", String.format(Locale.ROOT, "%.2f", speed),
                    "gentle", "true"), false);
        }
        if (clear != null) {
            if (speed < 0.02 && !minecart.getBoundingBox().intersects(new net.minecraft.util.math.Box(clear))) {
                body.stopInputs();
                return Step.completed(Map.of("ended", "clear"));
            }
        } else if (rollingAway && gap > 1.0) {
            body.stopInputs();
            return Step.completed(Map.of("ended", "rolling", "speed", String.format(Locale.ROOT, "%.2f", speed)));
        }
        if (++ticks > maxTicks) {
            body.stopInputs();
            return Step.blocked("The cart would not roll", true);
        }
        // Coming back toward it: waited for where it stands, and met as it comes, not walked away from as too far.
        if (speed >= ROLLING && !rollingAway && gap > 1.0) {
            body.stopInputs();
            return Step.running(Map.of("coming back", String.format(Locale.ROOT, "%.2f", speed)), false);
        }
        if (gap > 4) {
            body.stopInputs();
            return Step.blocked("Too far from the cart to push it", true);
        }
        // Rolling away already: let it go, rather than walk into its back again.
        if (rollingAway) {
            body.stopInputs();
            return Step.running(Map.of("speed", String.format(Locale.ROOT, "%.2f", speed)), false);
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(at.x - from.x), at.z - from.z));
        if (!body.walk(lease.get(), 1f, 0f, false, false, yaw, 20f)) {
            body.stopInputs();
            return Step.blocked("Walking authority denied", true);
        }
        return Step.running(Map.of("gap", String.format(Locale.ROOT, "%.1f", gap)), false);
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }
}
