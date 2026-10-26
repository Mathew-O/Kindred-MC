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
 * A minecart on its rails pushed by walking into it, as a player pushes one (E-45e): a cart is not solid to a player,
 * and a player inside its bounds shoves it away from where the player stands. Walked into from behind at a walk, never
 * running or jumping (a body running through a cart pushed it back out again as it passed its middle), until the cart
 * rolls on of itself, as a cart on level rails does a long way loaded and a short way empty. It ends once the cart rolls,
 * the player clear behind it; or when the cart is gone or its time is up. Given a block to {@code clear}, it is a nudge:
 * it ends once the cart has come to rest clear of that block (a cart stopped half in a cell's front, nudged on into the
 * cell against the bed).
 */
final class NativePushSkill implements SkillBody {
    /** How fast the cart must roll, blocks a tick, for the push to count: an empty cart this fast picks up a mob it touches. */
    static final double ROLLING = 0.12;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID cart;
    private final net.minecraft.util.math.BlockPos clear;
    private final int maxTicks;
    private int ticks;

    NativePushSkill(NativePlayerBody body, Supplier<ActionLease> lease, UUID cart, int seconds, net.minecraft.util.math.BlockPos clear) {
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("A push lasts 1..30 seconds");
        this.body = body;
        this.lease = lease;
        this.cart = cart;
        this.clear = clear;
        this.maxTicks = seconds * 20;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        var player = body.entity();
        if (!(player.getServerWorld().getEntity(cart) instanceof AbstractMinecartEntity minecart) || minecart.isRemoved()) {
            body.stopInputs();
            return Step.blocked("No cart to push", true);
        }
        Vec3d at = minecart.getPos(), from = player.getPos(), velocity = minecart.getVelocity();
        double gap = Math.hypot(at.x - from.x, at.z - from.z), speed = velocity.horizontalLength();
        if (clear != null) {
            if (speed < 0.02 && !minecart.getBoundingBox().intersects(new net.minecraft.util.math.Box(clear))) {
                body.stopInputs();
                return Step.completed(Map.of("ended", "clear"));
            }
        } else if (speed >= ROLLING && gap > 1.0) {
            body.stopInputs();
            return Step.completed(Map.of("ended", "rolling", "speed", String.format(Locale.ROOT, "%.2f", speed)));
        }
        if (++ticks > maxTicks) {
            body.stopInputs();
            return Step.blocked("The cart would not roll", true);
        }
        if (gap > 4) {
            body.stopInputs();
            return Step.blocked("Too far from the cart to push it", true);
        }
        // Rolling away already: let it go, rather than walk into its back again.
        if (speed >= ROLLING) {
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
