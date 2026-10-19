package dev.kindred.fabric;

import dev.kindred.core.Checkpoint;
import dev.kindred.core.SkillBody;
import dev.kindred.core.SkillDescriptor;
import dev.kindred.core.WorkSetup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * First-release movement skills: go to a marker inside the allocated area, return home, and wait. Arrival is a
 * fresh position within one block; a blocked path, an unloaded destination or no progress produce typed reasons.
 * Navigation never digs, places scaffolding or changes dimension.
 */
final class MovementSkills {
    static final SkillDescriptor MOVE_TO = new SkillDescriptor("move_to", Set.of("x", "y", "z"), Set.of(), Set.of("navigate"), Set.of(),
            Duration.ofMinutes(2), Duration.ofSeconds(10), 2);
    static final SkillDescriptor RETURN_HOME = new SkillDescriptor("return_home", Set.of(), Set.of(), Set.of("navigate"), Set.of(),
            Duration.ofMinutes(2), Duration.ofSeconds(10), 2);
    static final SkillDescriptor WAIT = new SkillDescriptor("wait", Set.of("seconds"), Set.of(), Set.of(), Set.of(),
            Duration.ofSeconds(61), Duration.ofSeconds(61), 0);
    static final int MIN_WAIT_SECONDS = 1;
    static final int MAX_WAIT_SECONDS = 60;

    private MovementSkills() { }

    static Optional<SkillDescriptor> descriptor(String id) {
        return switch (id) {
            case "move_to" -> Optional.of(MOVE_TO);
            case "return_home" -> Optional.of(RETURN_HOME);
            case "wait" -> Optional.of(WAIT);
            case "gather_logs" -> Optional.of(ChoreSkills.GATHER);
            case "deposit" -> Optional.of(ChoreSkills.DEPOSIT);
            default -> Optional.empty();
        };
    }

    /** Rebuild a body for a saved checkpoint after restart; the destination is re-derived from arguments/setup, never from stale state. */
    static Optional<SkillBody> body(String skillId, Map<String, String> arguments, CompanionEntity entity, WorkSetup setup,
                                    java.util.function.Supplier<dev.kindred.core.EditPolicy.ServerPolicy> edits, ChoreSkills.Gate gate) {
        return switch (skillId) {
            case "move_to" -> Optional.of(new MoveTo(entity, setup, new BlockPos(Integer.parseInt(arguments.get("x")),
                    Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z"))), false));
            case "return_home" -> Optional.of(new MoveTo(entity, setup, setup.home() == null ? null
                    : new BlockPos(setup.home().position().x(), setup.home().position().y(), setup.home().position().z()), true));
            case "wait" -> Optional.of(new Wait(Integer.parseInt(arguments.get("seconds"))));
            case "gather_logs" -> Optional.of(new ChoreSkills.Gather(entity, setup, edits, gate));
            case "deposit" -> Optional.of(new ChoreSkills.Deposit(entity, setup, edits, gate));
            default -> Optional.empty();
        };
    }

    static final class MoveTo implements SkillBody {
        private final CompanionEntity body;
        private final WorkSetup setup;
        private final BlockPos target;
        private final boolean home;
        private int routeAttemptAge = Integer.MIN_VALUE;

        MoveTo(CompanionEntity body, WorkSetup setup, BlockPos target, boolean home) {
            this.body = body;
            this.setup = setup;
            this.target = target;
            this.home = home;
        }

        @Override
        public Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart) {
            if (target == null) return Step.blocked(home ? "no home marker is set" : "no destination", true);
            if (setup.area() == null) return Step.blocked("no work area is assigned", true);
            var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
            if (!body.getWorld().getRegistryKey().getValue().toString().equals(setup.dimension())) {
                return Step.blocked("the body is not in its own dimension", true);
            }
            if (!setup.area().contains(point)) return Step.blocked("destination is outside the allocated area", true);
            if (!body.getWorld().isChunkLoaded(target)) return Step.blocked("destination is unloaded", true);
            Vec3d stand = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (!FollowBehavior.safeDestination(body, stand)) return Step.blocked("destination is not safe standing ground", true);
            double distance = body.getPos().multiply(1, 0, 1).distanceTo(stand.multiply(1, 0, 1));
            Map<String, String> progress = Map.of("target", target.toShortString(), "distance", String.valueOf((int) Math.ceil(distance)));
            if (distance <= 1.0 && Math.abs(body.getY() - stand.y) < 1.5) {
                body.haltControls();
                return Step.completed(Map.of("target", target.toShortString(), "arrived", "true"));
            }
            if (freshStart || (body.getNavigation().isIdle() && body.age - routeAttemptAge >= 10)) {
                routeAttemptAge = body.age;
                if (!body.getNavigation().startMovingTo(stand.x, stand.y, stand.z, 1.0)) {
                    return Step.blocked("no path to the destination", false);
                }
            }
            return Step.running(progress, true);
        }

        @Override public void halt() { body.haltControls(); }
        @Override public boolean settled() { return body.getNavigation().isIdle() && !body.getMoveControl().isMoving(); }
    }

    /** Counts only ticks actually driven under the lease: paused, stopped and unloaded time is not waiting. */
    static final class Wait implements SkillBody {
        private final int seconds;
        private int elapsedTicks;
        private boolean started;

        Wait(int seconds) { this.seconds = seconds; }

        @Override
        public Step tick(Map<String, String> arguments, Optional<Checkpoint> resumeFrom, boolean freshStart) {
            if (seconds < MIN_WAIT_SECONDS || seconds > MAX_WAIT_SECONDS) return Step.blocked("wait must be 1..60 seconds", true);
            if (!started) {
                started = true;
                elapsedTicks = resumeFrom.map(c -> Integer.parseInt(c.progress().getOrDefault("elapsedTicks", "0"))).orElse(0);
            }
            elapsedTicks++;
            Map<String, String> progress = Map.of("elapsedTicks", String.valueOf(elapsedTicks), "requestedTicks", String.valueOf(seconds * 20));
            return elapsedTicks >= seconds * 20 ? Step.completed(progress) : Step.running(progress, true);
        }

        @Override public void halt() { }
        @Override public boolean settled() { return true; }
    }
}
