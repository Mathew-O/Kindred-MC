package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.util.math.BlockPos;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * In plain terms: a long walk made on its own horse, as a person does it: over to the horse, on, ridden toward where it
 * is going, off near there, and the last few blocks on foot. If the horse will not go on (a wall, water, a cliff), it
 * gets off and walks the rest; the walk ends where the walk alone would have ended.
 *
 * Technical: A {@code native_goto} given {@code horse} (M-01a): its own tamed, saddled horse in sight, chosen by
 * {@code PlayerRuntime} for its own far walks. Steps through its own skills: a {@link NativeGoToSkill} to the horse,
 * {@link NativeRideSkill} mount, go (straight toward the target, the horse stepping up a block by itself) and off, then
 * the original {@link NativeGoToSkill}, whose outcome is the trip's. A failed or refused ride step falls back to getting
 * off and walking. Riding steps never checkpoint; a resumed trip walks. Nothing in its inventory changes.
 */
final class NativeTripSkill implements SkillBody {
    /** A walk at least this far, horizontally, is ridden. */
    static final double RIDE_FROM = 48;
    /** Its horse at most this far, and in sight, to be fetched for the trip. */
    static final double HORSE_NEAR = 24;
    /** Off this far from the target, the rest walked. */
    static final double OFF_AT = 6;
    private static final int RIDE_SECONDS = 50;
    /** Walks up to a horse that moved off before it got on, before it walks instead. */
    private static final int APPROACHES = 3;

    enum Phase { TO_HORSE, MOUNT, RIDE, OFF, WALK }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID horse;
    private final int slot;
    private final BlockPos target;
    private final SkillBody walk;
    private Phase phase;
    private SkillBody step;
    private boolean freshStep = true;
    private String note = "";
    private double ridden;
    private int approaches;

    NativeTripSkill(NativePlayerBody body, Supplier<ActionLease> lease, UUID horse, int slot, BlockPos target, SkillBody walk) {
        this.body = body;
        this.lease = lease;
        this.horse = horse;
        this.slot = slot;
        this.target = target.toImmutable();
        this.walk = walk;
        this.phase = body.entity().getVehicle() instanceof AbstractHorseEntity ? Phase.RIDE : Phase.TO_HORSE;
    }

    Phase phase() { return phase; }

    /** Why it walked rather than rode, or the empty string. */
    String note() { return note; }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (checkpoint.isPresent() && phase != Phase.WALK) fallBack("resumed after a pause");
        if (phase == Phase.WALK) {
            var s = walk.tick(args, phase == Phase.WALK && freshStep ? Optional.empty() : checkpoint, fresh || freshStep);
            freshStep = false;
            if (s.status() == Status.COMPLETED && ridden > 0) {
                var out = new java.util.HashMap<>(s.progress());
                out.put("ridden", String.valueOf((int) ridden));
                return Step.completed(out);
            }
            return s;
        }
        var player = body.entity();
        if (phase != Phase.OFF && !(player.getServerWorld().getEntity(horse) instanceof AbstractHorseEntity mount && usable(mount, player))) {
            fallBack("its horse is gone or not fit to ride");
            return tick(args, Optional.empty(), true);
        }
        if (step == null) step = stepFor(phase);
        if (step == null) return tick(args, Optional.empty(), true);
        var start = player.getPos();
        var s = step.tick(Map.of(), Optional.empty(), freshStep);
        freshStep = false;
        if (phase == Phase.RIDE) ridden += Math.hypot(player.getX() - start.x, player.getZ() - start.z);
        if (s.status() == Status.RUNNING) return Step.running(Map.of("trip", phase.name().toLowerCase(java.util.Locale.ROOT)), false);
        step = null;
        freshStep = true;
        if (s.status() == Status.BLOCKED) {
            if (phase == Phase.OFF) return s;
            // A horse that stepped off while it walked up is walked up to again, a few times, as a person follows one.
            if (phase == Phase.MOUNT && ++approaches < APPROACHES) {
                phase = Phase.TO_HORSE;
                return Step.running(Map.of("trip", "to_horse"), false);
            }
            fallBack(s.reason());
            return Step.running(Map.of("trip", "on foot"), false);
        }
        next();
        return Step.running(Map.of("trip", phase.name().toLowerCase(java.util.Locale.ROOT)), false);
    }

    /** The step for the phase it is in, or null when that phase has nothing to do (and the phase has moved on). */
    private SkillBody stepFor(Phase p) {
        var player = body.entity();
        var mount = player.getServerWorld().getEntity(horse);
        switch (p) {
            case TO_HORSE -> {
                if (player.distanceTo(mount) <= 2.5) { next(); return null; }
                return new NativeGoToSkill(body, lease, mount.getBlockPos(), 2, false, NativeNavigator.DEFAULT_MAX_NODES, false, NativeNavigator.MAX_SAFE_DROP);
            }
            case MOUNT -> {
                if (player.getVehicle() == mount) { next(); return null; }
                if (player.distanceTo(mount) > 2.5) { phase = Phase.TO_HORSE; return stepFor(Phase.TO_HORSE); }
                return new NativeRideSkill(body, lease, NativeRideSkill.Op.MOUNT, horse, slot, 0, 0, 2, 1);
            }
            case RIDE -> {
                if (player.getVehicle() != mount) { fallBack("not on its horse"); return null; }
                return new NativeRideSkill(body, lease, NativeRideSkill.Op.GO, horse, -1, target.getX() + 0.5, target.getZ() + 0.5, OFF_AT, RIDE_SECONDS);
            }
            case OFF -> {
                if (!(player.getVehicle() instanceof AbstractHorseEntity)) { next(); return null; }
                return new NativeRideSkill(body, lease, NativeRideSkill.Op.OFF, horse, -1, 0, 0, 2, 1);
            }
            default -> { return null; }
        }
    }

    private void next() {
        phase = switch (phase) {
            case TO_HORSE -> Phase.MOUNT;
            case MOUNT -> Phase.RIDE;
            case RIDE -> Phase.OFF;
            case OFF, WALK -> Phase.WALK;
        };
    }

    private boolean usable(AbstractHorseEntity mount, net.minecraft.server.network.ServerPlayerEntity player) {
        if (player.getVehicle() == mount) return true;
        return mount.isAlive() && mount.isTame() && mount.isSaddled() && player.getUuid().equals(mount.getOwnerUuid())
                && !mount.hasPassengers() && !mount.isLeashed() && player.distanceTo(mount) <= HORSE_NEAR + 4;
    }

    /** Off the horse if on it, then the walk. */
    private void fallBack(String why) {
        if (step != null) step.halt();
        note = why == null ? "" : why;
        KindredMod.LOGGER.info("{} trip on foot from {}: {}", body.entity().getName().getString(), phase, note);
        step = null;
        freshStep = true;
        phase = body.entity().getVehicle() instanceof AbstractHorseEntity ? Phase.OFF : Phase.WALK;
    }

    /** What the step it is on may do, and nothing else. */
    boolean permits(NativePlayerBody.Intent intent, net.minecraft.util.math.BlockPos at) {
        var current = phase == Phase.WALK ? walk : step;
        return current != null && NativePlayerSkills.permits(current, intent, at);
    }

    @Override
    public void halt() {
        if (step != null) step.halt();
        walk.halt();
        body.stopInputs();
    }

    @Override
    public boolean settled() { return (step == null || step.settled()) && walk.settled(); }
}
