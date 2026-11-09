package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.util.math.Vec3d;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player on a horse, as a human does it: getting on with an empty hand (an untamed horse throws
 * it off, a little tamer each time, until it bonds with it), riding toward a spot by facing it and pressing forward,
 * and getting off.
 *
 * Technical: Three steps of riding (M-01a), each a player's own input: {@code mount} clicks the horse with the empty
 * hand in {@code slot} and ends seated, or blocked when the click is refused; {@code go} faces {@code x}, {@code z} and
 * holds forward, the horse moving from the rider's input on the server ({@code BoatAuthorityMixin}), ending within
 * {@code range}, when thrown off, when going nowhere or when its seconds are up; {@code off} gets off. Nothing in its
 * inventory changes.
 */
final class NativeRideSkill implements SkillBody {
    /** Ticks going nowhere on course before a ride counts as stuck. */
    private static final int STUCK_TICKS = 40;

    enum Op { MOUNT, GO, OFF }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final Op op;
    private final UUID horse;
    private final int slot;
    private final double x, z, range;
    private final int maxTicks;
    private int ticks, still, clicked = -1;
    private Vec3d last;

    NativeRideSkill(NativePlayerBody body, Supplier<ActionLease> lease, Op op, UUID horse, int slot, double x, double z, double range, int seconds) {
        if (op == Op.MOUNT && (horse == null || slot < 0 || slot > 8)) throw new IllegalArgumentException("Mounting needs a horse and an empty hotbar slot");
        if (op == Op.GO && (!Double.isFinite(x) || !Double.isFinite(z) || !(range >= 1 && range <= 64) || seconds < 1 || seconds > 120)) {
            throw new IllegalArgumentException("Invalid ride target");
        }
        this.body = body;
        this.lease = lease;
        this.op = op;
        this.horse = horse;
        this.slot = slot;
        this.x = x;
        this.z = z;
        this.range = range;
        this.maxTicks = seconds * 20;
    }

    boolean moves() { return op == Op.GO; }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (checkpoint.isPresent()) return Step.blocked("A ride cannot replay a saved step", true);
        ticks++;
        var player = body.entity();
        return switch (op) {
            case MOUNT -> {
                var entity = player.getServerWorld().getEntity(horse);
                if (!(entity instanceof AbstractHorseEntity mount) || !mount.isAlive()) yield Step.blocked("No such horse in sight", true);
                if (player.getVehicle() == mount) yield Step.completed(Map.of("horse", horse.toString(), "tame", String.valueOf(mount.isTame())));
                if (clicked >= 0) yield Step.blocked("The horse did not take it on", true);
                if (!body.mount(lease.get(), slot, mount)) yield Step.blocked("The horse is out of reach or the click was refused", true);
                clicked = ticks;
                yield Step.running(Map.of("horse", "mounting"), false);
            }
            case OFF -> {
                if (!(player.getVehicle() instanceof AbstractHorseEntity)) yield Step.completed(Map.of("off", "true"));
                if (clicked >= 0) yield Step.blocked("Could not get off the horse", true);
                if (!body.dismount(lease.get())) yield Step.blocked("Getting off was refused", true);
                clicked = ticks;
                yield Step.running(Map.of("horse", "getting off"), false);
            }
            case GO -> {
                if (!(player.getVehicle() instanceof AbstractHorseEntity mount)) {
                    body.stopInputs();
                    yield Step.blocked("Not on a horse: thrown off, or never on", true);
                }
                Vec3d at = mount.getPos();
                double dx = x - at.x, dz = z - at.z;
                if (Math.hypot(dx, dz) <= range) {
                    body.stopInputs();
                    yield Step.completed(Map.of("arrived", mount.getBlockPos().toShortString()));
                }
                if (ticks > maxTicks) {
                    body.stopInputs();
                    yield Step.blocked("Out of time riding toward " + (int) x + ", " + (int) z, true);
                }
                if (last != null && at.squaredDistanceTo(last) < 0.0004) still++;
                else still = 0;
                last = at;
                if (still > STUCK_TICKS) {
                    body.stopInputs();
                    yield Step.blocked("The horse is going nowhere", true);
                }
                float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                if (!body.move(lease.get(), 1, 0, false, false, yaw)) {
                    body.stopInputs();
                    yield Step.blocked("Riding was refused", true);
                }
                yield Step.running(Map.of("riding", mount.getBlockPos().toShortString()), false);
            }
        };
    }

    @Override public void halt() { body.stopInputs(); }
    @Override public boolean settled() { return true; }
}
