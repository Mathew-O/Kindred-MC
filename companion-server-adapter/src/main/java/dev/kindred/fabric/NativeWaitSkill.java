package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.Gaze;
import dev.kindred.core.SkillBody;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A native wait in which the player looks at what matters (E-33a): the timer of {@link MovementSkills.Wait}, with the
 * head eased each tick toward where {@link Gaze} says, at a threat in sight, at whoever speaks to it, back at a player
 * close by looking at it, at what it waits on, or about it. What it sees is read every few ticks: monsters within
 * sixteen blocks, players within twelve, each only in plain sight. Looking takes the wait's own lease, so a stop ends
 * it with the wait; a look the gate refuses is passed over and the wait goes on. It never looks into an enderman's
 * eyes, which would anger it, nor at one at all; and asleep in its bed it does not look about.
 */
final class NativeWaitSkill implements SkillBody {
    static final double MONSTERS = 16, PEOPLE = 12;
    /** Ticks between reads of what is in view; the head eases every tick toward the last. */
    static final int READ_EVERY = 5;
    /** How long after speaking to it a player is looked at, in ticks. */
    static final int SPOKEN = 100;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final int seconds;
    private final Gaze gaze;
    private final Supplier<UUID> speaker;
    private final Gaze.Point work;
    private int elapsedTicks;
    private boolean started, read;
    private Gaze.Seen threat, spoken;
    private List<Gaze.Seen> people = List.of();
    /** Endermen about, read with the rest; their eyes are kept clear of at their place each tick. */
    private List<EndermanEntity> endermen = List.of();

    NativeWaitSkill(NativePlayerBody body, Supplier<ActionLease> lease, int seconds, Gaze gaze, Supplier<UUID> speaker, Gaze.Point work) {
        this.body = body;
        this.lease = lease;
        this.seconds = seconds;
        this.gaze = gaze;
        this.speaker = speaker;
        this.work = work;
    }

    @Override
    public Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart) {
        if (seconds < 1 || seconds > 4) return Step.blocked("wait must be 1..4 seconds", true);
        if (!started) {
            started = true;
            elapsedTicks = resumeFrom.map(c -> Integer.parseInt(c.progress().getOrDefault("elapsedTicks", "0"))).orElse(0);
        }
        look();
        elapsedTicks++;
        Map<String, String> progress = Map.of("elapsedTicks", String.valueOf(elapsedTicks), "requestedTicks", String.valueOf(seconds * 20));
        return elapsedTicks >= seconds * 20 ? Step.completed(progress) : Step.running(progress, true);
    }

    private void look() {
        ServerPlayerEntity player = body.entity();
        if (player == null || !player.isAlive() || player.isSleeping()) return;
        long tick = player.getServerWorld().getTime();
        if (!read || elapsedTicks % READ_EVERY == 0) read(player);
        Vec3d eye = player.getEyePos();
        var look = gaze.choose(new Gaze.View(tick, eye.x, eye.y, eye.z, player.getHeadYaw(), threat, spoken, people, work));
        float[] next = Gaze.step(player.getHeadYaw(), player.getPitch(), look);
        if (staresAtAnEnderman(player, endermen, next[0], next[1])) return;
        body.glance(lease.get(), next[0], next[1]);
    }

    /** What is in plain view: the nearest monster that would come for it, whoever spoke to it, and the players about. */
    private void read(ServerPlayerEntity player) {
        read = true;
        var world = player.getServerWorld();
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : world.getOtherEntities(player, new Box(player.getBlockPos()).expand(MONSTERS), e -> e instanceof Monster && e.isAlive())) {
            if (e instanceof EndermanEntity) continue;
            // A neutral mob (a zombified piglin) only when it is after this player.
            if (e instanceof Angerable && !(e instanceof MobEntity mob && mob.getTarget() == player)) continue;
            double d = e.squaredDistanceTo(player);
            if (d < best && d <= MONSTERS * MONSTERS && player.canSee(e)) {
                best = d;
                nearest = e;
            }
        }
        threat = nearest == null ? null : seen(nearest, false);
        UUID who = speaker == null ? null : speaker.get();
        spoken = null;
        var seen = new ArrayList<Gaze.Seen>();
        for (PlayerEntity other : world.getPlayers()) {
            if (other == player || !other.isAlive() || other.isSpectator() || other.isInvisible()
                    || other.squaredDistanceTo(player) > PEOPLE * PEOPLE || !player.canSee(other)) continue;
            var s = seen(other, looksAt(other, player));
            if (other.getUuid().equals(who)) spoken = s;
            if (seen.size() < 8) seen.add(s);
        }
        people = List.copyOf(seen);
        endermen = world.getEntitiesByClass(EndermanEntity.class, new Box(player.getBlockPos()).expand(64), Entity::isAlive);
    }

    private static Gaze.Seen seen(Entity e, boolean looksAtMe) {
        return new Gaze.Seen(e.getUuid(), e.getX(), e.getEyeY(), e.getZ(), looksAtMe);
    }

    /** Whether {@code other} faces {@code player}'s head, within about twelve degrees. */
    static boolean looksAt(PlayerEntity other, ServerPlayerEntity player) {
        Vec3d toward = player.getEyePos().subtract(other.getEyePos());
        double length = toward.length();
        return length > 1e-6 && other.getRotationVec(1f).dotProduct(toward.multiply(1 / length)) > 0.978;
    }

    /**
     * Whether looking along {@code yaw} and {@code pitch} would be staring at an enderman in sight: vanilla's own test
     * ({@code EndermanEntity.isPlayerStaring}), with twice its margin.
     */
    static boolean staresAtAnEnderman(ServerPlayerEntity player, List<EndermanEntity> endermen, float yaw, float pitch) {
        if (endermen.isEmpty()) return false;
        // The view along a yaw and pitch, as Entity.getRotationVector reckons it.
        double p = Math.toRadians(pitch), y = Math.toRadians(-yaw);
        Vec3d view = new Vec3d(Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        for (EndermanEntity enderman : endermen) {
            if (!enderman.isAlive() || enderman.getWorld() != player.getWorld()) continue;
            Vec3d toward = new Vec3d(enderman.getX() - player.getX(), enderman.getEyeY() - player.getEyeY(), enderman.getZ() - player.getZ());
            double d = toward.length();
            if (d < 1e-6) continue;
            if (view.dotProduct(toward.multiply(1 / d)) > 1.0 - 0.05 / d && player.canSee(enderman)) return true;
        }
        return false;
    }

    @Override public void halt() { }
    @Override public boolean settled() { return true; }
}
