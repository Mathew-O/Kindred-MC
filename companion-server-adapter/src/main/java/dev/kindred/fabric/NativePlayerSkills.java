package dev.kindred.fabric;

import dev.kindred.core.ActionLease;
import dev.kindred.core.AuthorizedMovementCadence;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.MannerismState;
import dev.kindred.core.Personality;
import dev.kindred.core.SkillBody;
import dev.kindred.core.SkillDescriptor;
import dev.kindred.core.SprintStride;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Short, explicitly requested native tasks. No replay, inventory mirror or automatic renewal. */
final class NativePlayerSkills {
    record NativeMovementHabits(MannerismState mannerisms, Personality personality, LongSupplier nowMillis) { }
    static SkillDescriptor descriptor(String id) {
        return switch (id) {
            case "native_wait" -> new SkillDescriptor(id, Set.of("seconds"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_eat" -> new SkillDescriptor(id, Set.of("slot"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_walk" -> new SkillDescriptor(id, Set.of("direction", "blocks"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(2), 0);
            case "native_traverse" -> new SkillDescriptor(id, Set.of("direction", "blocks"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(2), 0);
            case "native_retreat" -> new SkillDescriptor(id, Set.of("direction", "blocks"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(2), 0);
            case "native_equip" -> new SkillDescriptor(id, Set.of("slot"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_mine" -> new SkillDescriptor(id, Set.of("x", "y", "z", "slot"), Set.of(), Set.of("break"), Set.of(dev.kindred.core.EditPolicy.Kind.BREAK),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_gather" -> new SkillDescriptor(id, Set.of("count", "slot"), Set.of(), Set.of("break", "pickup"),
                    Set.of(dev.kindred.core.EditPolicy.Kind.BREAK), Duration.ofMinutes(3), Duration.ofSeconds(15), 2);
            case "native_craft" -> new SkillDescriptor(id, Set.of("recipe"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_place" -> new SkillDescriptor(id, Set.of("x", "y", "z", "slot"), Set.of("side"),
                    Set.of("place"), Set.of(dev.kindred.core.EditPolicy.Kind.PLACE),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_smelt" -> new SkillDescriptor(id, Set.of(), Set.of("x", "y", "z", "action", "fuel_slot", "input_slot", "count"),
                    Set.of("smelt"), Set.of(dev.kindred.core.EditPolicy.Kind.PLACE),
                    Duration.ofSeconds(10), Duration.ofSeconds(5), 0);
            case "native_swap" -> new SkillDescriptor(id, Set.of("source", "target"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_attack" -> new SkillDescriptor(id, Set.of("target"), Set.of("slot"), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(2), 0);
            case "native_block" -> new SkillDescriptor(id, Set.of(), Set.of("ticks"), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(2), 0);
            case "native_till" -> new SkillDescriptor(id, Set.of("x", "y", "z", "slot"), Set.of(),
                    Set.of("till"), Set.of(dev.kindred.core.EditPolicy.Kind.PLACE),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_plant" -> new SkillDescriptor(id, Set.of("x", "y", "z", "slot"), Set.of("item"),
                    Set.of("plant"), Set.of(dev.kindred.core.EditPolicy.Kind.PLACE),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_harvest" -> new SkillDescriptor(id, Set.of("x", "y", "z"), Set.of("slot", "replant"),
                    Set.of("harvest", "pickup"), Set.of(dev.kindred.core.EditPolicy.Kind.BREAK),
                    Duration.ofSeconds(10), Duration.ofSeconds(6), 0);
            case "native_sleep" -> new SkillDescriptor(id, Set.of("x", "y", "z"), Set.of(),
                    Set.of("sleep"), Set.of(),
                    Duration.ofSeconds(10), Duration.ofSeconds(5), 0);
            case "native_ignite_portal" -> new SkillDescriptor(id, Set.of("x", "y", "z", "slot"), Set.of(),
                    Set.of("ignite_portal"), Set.of(dev.kindred.core.EditPolicy.Kind.PLACE),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_organize_chest" -> new SkillDescriptor(id, Set.of("x", "y", "z"), Set.of("max_moves"),
                    Set.of("container"), Set.of(dev.kindred.core.EditPolicy.Kind.CONTAINER),
                    Duration.ofSeconds(10), Duration.ofSeconds(5), 0);
            case "native_toss" -> new SkillDescriptor(id, Set.of("slot", "count", "target"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_goto" -> new SkillDescriptor(id, Set.of("x", "y", "z"), Set.of("range", "partial", "nodes", "sprint", "drop"), Set.of("move"), Set.of(),
                    Duration.ofSeconds(60), Duration.ofSeconds(10), 0);
            case "native_break" -> new SkillDescriptor(id, Set.of("x", "y", "z"), Set.of("collect", "guarded"), Set.of("break", "pickup"),
                    Set.of(dev.kindred.core.EditPolicy.Kind.BREAK), Duration.ofSeconds(30), Duration.ofSeconds(30), 0);
            case "native_discard" -> new SkillDescriptor(id, Set.of("slot", "count"), Set.of(), Set.of(), Set.of(),
                    Duration.ofSeconds(5), Duration.ofSeconds(5), 0);
            case "native_fight" -> new SkillDescriptor(id, Set.of("target"), Set.of("slot"), Set.of("move"), Set.of(),
                    Duration.ofSeconds(25), Duration.ofSeconds(10), 0);
            case "native_bucket" -> new SkillDescriptor(id, Set.of("x", "y", "z", "mode"), Set.of(), Set.of("bucket"),
                    Set.of(dev.kindred.core.EditPolicy.Kind.PLACE), Duration.ofSeconds(8), Duration.ofSeconds(8), 0);
            case "native_cast" -> new SkillDescriptor(id, Set.of("x", "y", "z"), Set.of(), Set.of("bucket"),
                    Set.of(dev.kindred.core.EditPolicy.Kind.PLACE), Duration.ofSeconds(8), Duration.ofSeconds(8), 0);
            default -> throw new IllegalArgumentException("Unsupported native skill");
        };
    }

    static SkillBody body(String id, Map<String, String> arguments, NativePlayerBody body, Supplier<ActionLease> lease) {
        return body(id, arguments, body, lease, null);
    }

    static SkillBody body(String id, Map<String, String> arguments, NativePlayerBody body, Supplier<ActionLease> lease,
                          NativeMovementHabits habits) {
        if (id.equals("native_goto")) {
            BlockPos target = new BlockPos(Integer.parseInt(arguments.get("x")), Integer.parseInt(arguments.get("y")), Integer.parseInt(arguments.get("z")));
            return new NativeGoToSkill(body, lease, target, Double.parseDouble(arguments.getOrDefault("range", "1.5")),
                    Boolean.parseBoolean(arguments.getOrDefault("partial", "false")),
                    Integer.parseInt(arguments.getOrDefault("nodes", String.valueOf(NativeNavigator.DEFAULT_MAX_NODES))),
                    Boolean.parseBoolean(arguments.getOrDefault("sprint", "false")),
                    Integer.parseInt(arguments.getOrDefault("drop", String.valueOf(NativeNavigator.MAX_SAFE_DROP))));
        }
        if (id.equals("native_walk")) return new Walk(body, lease, arguments.get("direction"), Integer.parseInt(arguments.get("blocks")));
        if (id.equals("native_traverse")) {
            return new Traverse(body, lease, habits, arguments.get("direction"), Integer.parseInt(arguments.get("blocks")));
        }
        if (id.equals("native_retreat")) return new Retreat(body, lease, arguments.get("direction"), Integer.parseInt(arguments.get("blocks")));
        if (id.equals("native_swap")) {
            int source = Integer.parseInt(arguments.get("source"));
            int target = Integer.parseInt(arguments.get("target"));
            if (source >= 0 && source < 36 && target >= 0 && target < 36) {
                return new Swap(body, lease, source, target);
            }
            throw new IllegalArgumentException("Native swap needs valid slots 0..35");
        }
        if (id.equals("native_attack")) {
            UUID targetId = UUID.fromString(arguments.get("target"));
            Integer slot = arguments.containsKey("slot") ? Integer.parseInt(arguments.get("slot")) : null;
            return new Attack(body, lease, targetId, slot);
        }
        if (id.equals("native_block")) {
            int ticks = Integer.parseInt(arguments.getOrDefault("ticks", "20"));
            if (ticks < 1 || ticks > 100) throw new IllegalArgumentException("Native block ticks must be 1..100");
            return new Block(body, lease, ticks);
        }
        int value = Integer.parseInt(arguments.get(id.equals("native_wait") ? "seconds" : "slot"));
        if (id.equals("native_wait") && value >= 1 && value <= 4) return new MovementSkills.Wait(value);
        if (id.equals("native_eat") && value >= 0 && value <= 8) return new Eat(body, lease, value);
        if (id.equals("native_equip") && value >= 0 && value < 36) return new Equip(body, lease, value);
        throw new IllegalArgumentException("Native wait needs 1..4 seconds; eat needs hotbar slot 0..8");
    }

    static boolean permits(SkillBody task, NativePlayerBody.Intent intent) {
        return permits(task, intent, null);
    }
    static boolean permits(SkillBody task, NativePlayerBody.Intent intent, BlockPos target) {
        if (task instanceof NativeRecordedAction recorded) return recorded.permits(intent, target);
        return (task instanceof Eat food && food.permits(intent))
                || (task instanceof Walk walk && intent == NativePlayerBody.Intent.MOVE && walk.safe())
                || (task instanceof Traverse trav && intent == NativePlayerBody.Intent.MOVE && trav.safe())
                || (task instanceof NativeGoToSkill && intent == NativePlayerBody.Intent.MOVE)
                || (task instanceof Retreat ret && intent == NativePlayerBody.Intent.MOVE && ret.safe())
                || (task instanceof Equip equip && intent == NativePlayerBody.Intent.EQUIP && equip.permits())
                || (task instanceof Swap swap && intent == NativePlayerBody.Intent.SWAP)
                || (task instanceof Attack attack && (intent == NativePlayerBody.Intent.ATTACK || intent == NativePlayerBody.Intent.SELECT_SLOT))
                || (task instanceof Block block && intent == NativePlayerBody.Intent.BLOCK);
    }

    static boolean uncertain(SkillBody task) {
        return (task instanceof Equip equip && equip.uncertain)
                || (task instanceof NativeRecordedAction recorded && recorded.uncertain());
    }

    private static final class Equip implements SkillBody {
        final NativePlayerBody body;
        final Supplier<ActionLease> lease;
        final int slot;
        final net.minecraft.entity.EquipmentSlot target;
        final ItemStack requested;
        boolean attempted, uncertain;
        Equip(NativePlayerBody body, Supplier<ActionLease> lease, int slot) {
            this.body = body; this.lease = lease; this.slot = slot;
            target = body.equipmentDestination(slot).orElseThrow(() -> new IllegalArgumentException("Owned armor/shield needs an eligible empty equipment slot and an empty cursor"));
            requested = body.entity().getInventory().getStack(slot).copy();
        }
        boolean permits() {
            var source = body.entity().getInventory().getStack(slot);
            return !attempted && source.getCount() == requested.getCount() && ItemStack.areItemsAndComponentsEqual(source, requested)
                    && body.equipmentDestination(slot).filter(target::equals).isPresent();
        }
        @Override public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent() || !permits()) return Step.blocked("Equipment source/destination changed; no transfer attempted", true);
            var inventory = body.entity().getInventory();
            ItemStack[] before = new ItemStack[inventory.size()];
            for (int i = 0; i < before.length; i++) before[i] = inventory.getStack(i).copy();
            boolean moved;
            try { moved = body.equipOwned(lease.get(), slot); }
            catch (RuntimeException failure) { attempted = uncertain = true; return Step.blocked("Native equipment outcome uncertain; reconcile", true); }
            attempted = true;
            if (!moved) return Step.blocked("Equipment authority denied; no transfer attempted", true);
            int destination = target == net.minecraft.entity.EquipmentSlot.OFFHAND ? 40 : 36 + target.getEntitySlotId();
            for (int i = 0; i < before.length; i++) {
                var expected = i == slot ? ItemStack.EMPTY : i == destination ? requested : before[i];
                var actual = inventory.getStack(i);
                if (actual.getCount() != expected.getCount() || !ItemStack.areItemsAndComponentsEqual(expected, actual)) {
                    uncertain = true;
                    return Step.blocked("Native equipment conservation could not be verified; reconcile", true);
                }
            }
            return Step.completed(Map.of("equipped", target.getName(), "sourceSlot", String.valueOf(slot), "conserved", "true"));
        }
        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return true; }
    }

    /** Deliberately short flat traversal; larger navigation is a separate qualification. */
    private static final class Walk implements SkillBody {
        final NativePlayerBody body;
        final Supplier<ActionLease> lease;
        final Vec3d start, goal;
        Walk(NativePlayerBody body, Supplier<ActionLease> lease, String direction, int blocks) {
            this.body = body; this.lease = lease;
            if (blocks < 1 || blocks > 3) throw new IllegalArgumentException("Native walk is limited to 1..3 blocks");
            Vec3d heading = switch (direction) {
                case "north" -> new Vec3d(0, 0, -1);
                case "south" -> new Vec3d(0, 0, 1);
                case "east" -> new Vec3d(1, 0, 0);
                case "west" -> new Vec3d(-1, 0, 0);
                default -> throw new IllegalArgumentException("Walk direction must be north, south, east or west");
            };
            start = body.entity().getPos();
            goal = start.add(heading.multiply(blocks));
            if (!safe()) throw new IllegalArgumentException("Walk needs a clear, supported, loaded flat corridor");
        }
        boolean safe() {
            var player = body.entity();
            var world = player.getServerWorld();
            Vec3d current = player.getPos();
            if (!player.isOnGround() || Math.abs(current.y - start.y) > 0.1
                    || current.squaredDistanceTo(start) > 16 || current.squaredDistanceTo(goal) > 16) return false;
            Vec3d delta = goal.subtract(current);
            int samples = Math.max(1, (int) Math.ceil(delta.length() * 5));
            if (samples > 20) return false;
            for (int i = 0; i <= samples; i++) {
                Vec3d point = current.add(delta.multiply((double) i / samples));
                // Check the whole body footprint before collision queries; never force a neighboring chunk.
                for (double dx : new double[]{-0.3, 0.3}) for (double dz : new double[]{-0.3, 0.3}) {
                    BlockPos feet = BlockPos.ofFloored(point.x + dx, point.y, point.z + dz);
                    BlockPos ground = BlockPos.ofFloored(point.x + dx, point.y - 0.05, point.z + dz);
                    if (!world.isChunkLoaded(feet) || !world.isInBuildLimit(feet.up()) || !world.getWorldBorder().contains(feet)) return false;
                    var support = world.getBlockState(ground);
                    if (!support.isSideSolidFullSquare(world, ground, Direction.UP)
                            || support.isOf(Blocks.MAGMA_BLOCK) || support.isOf(Blocks.CACTUS)
                            || !world.getFluidState(feet).isEmpty()) return false;
                }
                var box = player.getBoundingBox().offset(point.subtract(current));
                if (!world.isSpaceEmpty(player, box)) return false;
                var feet = world.getBlockState(BlockPos.ofFloored(point));
                if (feet.isOf(Blocks.FIRE) || feet.isOf(Blocks.SOUL_FIRE) || feet.isOf(Blocks.SWEET_BERRY_BUSH)
                        || feet.isOf(Blocks.POWDER_SNOW)) return false;
            }
            return true;
        }
        @Override public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent()) return Step.blocked("Short walk cannot replay a saved route", true);
            if (!safe()) return Step.blocked("Walk corridor became obstructed, unsupported or unavailable", true);
            Vec3d delta = goal.subtract(body.entity().getPos());
            double distance = delta.horizontalLength();
            if (distance <= 0.12) {
                body.stopInputs();
                return Step.completed(Map.of("arrived", "true", "remainingMillimeters", String.valueOf(Math.round(distance * 1000))));
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            if (!body.move(lease.get(), distance < 0.75 ? 0.35f : 1f, 0, false, false, yaw)) return Step.blocked("Movement authority denied", true);
            return Step.running(Map.of("remainingMillimeters", String.valueOf(Math.round(distance * 1000))), true);
        }
        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return true; }
    }

    /** Bounded 3D terrain traversal supporting 1-block step-up jump and 1..3 block safe step-down drop. */
    private static final class Traverse implements SkillBody {
        final NativePlayerBody body;
        final Supplier<ActionLease> lease;
        final NativeMovementHabits habits;
        final SprintStride stride = new SprintStride();
        final int hopPhase;
        final Vec3d start, heading, goal;
        final int blocks;
        final double[] stepSurfaces;

        Traverse(NativePlayerBody body, Supplier<ActionLease> lease, NativeMovementHabits habits, String direction, int blocks) {
            this.body = body;
            this.lease = lease;
            this.habits = habits;
            this.hopPhase = Math.floorMod(direction.hashCode() * 31 + blocks, 4);
            if (blocks < 1 || blocks > 3) throw new IllegalArgumentException("Native traverse is limited to 1..3 blocks");
            this.blocks = blocks;
            this.heading = switch (direction) {
                case "north" -> new Vec3d(0, 0, -1);
                case "south" -> new Vec3d(0, 0, 1);
                case "east" -> new Vec3d(1, 0, 0);
                case "west" -> new Vec3d(-1, 0, 0);
                default -> throw new IllegalArgumentException("Traverse direction must be north, south, east or west");
            };
            this.start = body.entity().getPos();
            this.stepSurfaces = new double[blocks];
            if (!planProfile()) throw new IllegalArgumentException("Traverse corridor is impassable, unsupported, hazardous or unloaded");
            this.goal = new Vec3d(start.x + heading.x * blocks, stepSurfaces[blocks - 1], start.z + heading.z * blocks);
            if (!safe()) throw new IllegalArgumentException("Traverse needs a clear, supported, loaded corridor");
        }

        private boolean planProfile() {
            var player = body.entity();
            var world = player.getServerWorld();
            BlockPos startGround = BlockPos.ofFloored(start.x, start.y - 0.05, start.z);
            if (!world.isChunkLoaded(startGround) || !world.isInBuildLimit(startGround) || !world.getWorldBorder().contains(startGround)) return false;
            var startSupport = world.getBlockState(startGround);
            boolean supportedStart = startSupport.isSideSolidFullSquare(world, startGround, Direction.UP)
                    && !startSupport.isOf(Blocks.MAGMA_BLOCK) && !startSupport.isOf(Blocks.CACTUS)
                    && world.getFluidState(startGround.up()).isEmpty();
            if (!supportedStart) return false;
            if (!player.isOnGround() && Math.abs(player.getVelocity().y) > 0.08) return false;

            double currentY = Math.round(start.y);

            double prevY = currentY;
            double fromX = start.x;
            double fromZ = start.z;
            for (int step = 1; step <= blocks; step++) {
                double targetX = start.x + heading.x * step;
                double targetZ = start.z + heading.z * step;
                int bx = (int) Math.floor(targetX);
                int bz = (int) Math.floor(targetZ);
                int fx = (int) Math.floor(fromX);
                int fz = (int) Math.floor(fromZ);

                Double validSurface = null;
                for (int deltaY : new int[]{1, 0, -1, -2, -3}) {
                    double candY = prevY + deltaY;
                    if (isStepPassable(world, fx, fz, bx, bz, prevY, candY, deltaY)) {
                        validSurface = candY;
                        break;
                    }
                }
                if (validSurface == null) return false;
                stepSurfaces[step - 1] = validSurface;
                prevY = validSurface;
                fromX = targetX;
                fromZ = targetZ;
            }
            return true;
        }

        private static boolean isStepPassable(ServerWorld world, int fromX, int fromZ, int bx, int bz, double fromY, double toY, int deltaY) {
            int minY = (int) Math.round(Math.min(fromY, toY)) - 1;
            int maxY = (int) Math.round(Math.max(fromY, toY)) + 2;
            for (int y = minY; y <= maxY; y++) {
                BlockPos p = new BlockPos(bx, y, bz);
                if (!world.isChunkLoaded(p) || !world.isInBuildLimit(p) || !world.getWorldBorder().contains(p)) return false;
            }

            // Target landing surface support check (block directly under feet at toY)
            BlockPos supportPos = new BlockPos(bx, (int) Math.round(toY) - 1, bz);
            var support = world.getBlockState(supportPos);
            if (!support.isSideSolidFullSquare(world, supportPos, Direction.UP)
                    || support.isOf(Blocks.MAGMA_BLOCK) || support.isOf(Blocks.CACTUS)
                    || !world.getFluidState(supportPos.up()).isEmpty()) {
                return false;
            }

            // Target landing clearance (feet and head)
            BlockPos feetPos = new BlockPos(bx, (int) Math.round(toY), bz);
            BlockPos headPos = new BlockPos(bx, (int) Math.round(toY) + 1, bz);
            if (isHazardOrSolid(world, feetPos) || isHazardOrSolid(world, headPos)) return false;

            if (deltaY == 1) {
                // Step up +1:
                // Check jump headroom above departure position (feet at fromY, so fromY + 2 must be clear to jump)
                BlockPos jumpHeadroom = new BlockPos(fromX, (int) Math.round(fromY) + 2, fromZ);
                if (isHazardOrSolid(world, jumpHeadroom)) return false;
            } else if (deltaY < 0) {
                // Step down 1..3 blocks:
                // Fall column from fromY down to toY at (bx, bz) must be clear
                for (int y = (int) Math.round(toY); y <= (int) Math.round(fromY) + 1; y++) {
                    BlockPos fallPos = new BlockPos(bx, y, bz);
                    if (isHazardOrSolid(world, fallPos)) return false;
                }
            }
            return true;
        }

        private static boolean isHazardOrSolid(ServerWorld world, BlockPos pos) {
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.SWEET_BERRY_BUSH)
                    || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.LAVA) || state.isOf(Blocks.MAGMA_BLOCK)
                    || state.isOf(Blocks.CACTUS)) return true;
            if (!world.getFluidState(pos).isEmpty()) return true;
            return !state.getCollisionShape(world, pos).isEmpty();
        }

        boolean safe() {
            var player = body.entity();
            var world = player.getServerWorld();
            Vec3d current = player.getPos();
            if (current.squaredDistanceTo(start) > 25 || current.squaredDistanceTo(goal) > 25) return false;

            BlockPos currentPos = player.getBlockPos();
            if (!world.isChunkLoaded(currentPos) || !world.isInBuildLimit(currentPos) || !world.getWorldBorder().contains(currentPos)) return false;

            // Re-verify that all planned step supports and clearance are intact
            for (int step = 1; step <= blocks; step++) {
                double targetX = start.x + heading.x * step;
                double targetZ = start.z + heading.z * step;
                int bx = (int) Math.floor(targetX);
                int bz = (int) Math.floor(targetZ);
                double stepY = stepSurfaces[step - 1];

                BlockPos support = new BlockPos(bx, (int) Math.round(stepY) - 1, bz);
                if (!world.isChunkLoaded(support) || !world.isInBuildLimit(support.up(2))) return false;
                if (!world.getBlockState(support).isSideSolidFullSquare(world, support, Direction.UP)) return false;
                if (isHazardOrSolid(world, support.up()) || isHazardOrSolid(world, support.up(2))) return false;
            }
            return true;
        }

        @Override
        public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent()) return Step.blocked("Short traverse cannot replay a saved route", true);
            if (!safe()) return Step.blocked("Traverse corridor became obstructed, unsupported or unavailable", true);

            var player = body.entity();
            Vec3d current = player.getPos();
            double dx = goal.x - current.x;
            double dz = goal.z - current.z;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            double verticalDelta = goal.y - current.y;

            boolean descending = goal.y < start.y - 0.25;
            double horizontalTolerance = descending ? 0.45 : 0.20;
            double verticalTolerance = descending ? 0.55 : 0.35;

            if (horizontalDist <= horizontalTolerance && Math.abs(verticalDelta) <= verticalTolerance && player.isOnGround()) {
                body.stopInputs();
                return Step.completed(Map.of("arrived", "true", "remainingMillimeters", String.valueOf(Math.round(horizontalDist * 1000))));
            }

            // Correct toward the destination after a jump's momentum carries us past it.
            // A fixed corridor heading otherwise drives farther away forever.
            // On a drop, the full player footprint must leave the upper ledge
            // before braking; horizontal proximity alone can leave us standing on it.
            boolean leavingDrop = descending && verticalDelta < -verticalTolerance;
            float yaw = leavingDrop
                    ? (float) Math.toDegrees(Math.atan2(-heading.x, heading.z))
                    : (float) Math.toDegrees(Math.atan2(-dx, dz));

            // Determine which step in the corridor we are targeting
            double traveled = (current.x - start.x) * heading.x + (current.z - start.z) * heading.z;
            int nextStepIndex = Math.min(blocks - 1, Math.max(0, (int) Math.floor(traveled + 0.35)));
            double nextTargetY = stepSurfaces[nextStepIndex];

            // Check if jumping is needed for a step-up (planned profile is authoritative; probe is a fallback)
            boolean needJump = nextTargetY > current.y + 0.45;
            Vec3d probe = current.add(heading.multiply(0.6));
            BlockPos probeFeet = BlockPos.ofFloored(probe.x, current.y + 0.1, probe.z);
            var probeState = player.getServerWorld().getBlockState(probeFeet);
            boolean blockAhead = probeState.isSideSolidFullSquare(player.getServerWorld(), probeFeet, Direction.UP);

            if (!needJump && (blockAhead || player.horizontalCollision) && nextTargetY > current.y + 0.1) {
                needJump = true;
            }
            if (current.y >= nextTargetY - 0.05 && player.isOnGround()) {
                needJump = false;
            }

            float speed = horizontalDist < 0.75 ? 0.35f : 1.0f;
            // Arriving horizontally while airborne is not completion. Release forward
            // input and let native gravity land the player inside the arrival tolerance.
            if (horizontalDist <= horizontalTolerance && !leavingDrop) speed = 0;
            if (descending) speed = Math.min(speed, horizontalDist < 0.55 ? 0.30f : 0.55f);
            boolean striding = horizontalDist >= 0.35;
            boolean sprint = false;
            boolean jump = needJump;
            if (habits != null) {
                var context = traverseMovementContext(player, heading, true);
                MannerismState.Gait gait = traverseGait(habits.mannerisms(), habits.personality(),
                        habits.nowMillis().getAsLong(), context, striding);
                sprint = AuthorizedMovementCadence.sprint(gait, striding);
                jump = AuthorizedMovementCadence.requestJump(stride, gait, striding, player.isOnGround(), player.age + hopPhase, needJump);
            }
            // Habit hops overshoot a nearby destination and can bounce around it forever; arrive on foot.
            if (horizontalDist < 1.2) jump = needJump;
            if (!body.move(lease.get(), speed, 0, jump, sprint, yaw)) {
                return Step.blocked("Movement authority denied", true);
            }
            return Step.running(Map.of("remainingMillimeters", String.valueOf(Math.round(horizontalDist * 1000)),
                    "verticalMillimeters", String.valueOf(Math.round(Math.abs(verticalDelta) * 1000))), true);
        }

        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return true; }
    }

    static MannerismState.MovementContext traverseMovementContext(ServerPlayerEntity player,
                                                                    Vec3d heading, boolean leased) {
        ServerWorld world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        Vec3d direction = heading.lengthSquared() > 1e-6 ? heading.normalize() : Vec3d.ZERO;
        BlockPos ahead = direction.equals(Vec3d.ZERO) ? feet : BlockPos.ofFloored(player.getPos().add(direction));
        boolean lowClearance = !traversePassable(world, feet.up(2)) || !traversePassable(world, ahead.up(2))
                || !traversePassable(world, ahead.up(1));
        boolean narrow = false;
        if (!direction.equals(Vec3d.ZERO)) {
            BlockPos left = BlockPos.ofFloored(player.getPos().add(-direction.z, 0, direction.x));
            BlockPos right = BlockPos.ofFloored(player.getPos().add(direction.z, 0, -direction.x));
            narrow = !traversePassable(world, left.up()) && !traversePassable(world, right.up());
        }
        Vec3d landing = player.getPos().add(direction.multiply(1.5));
        boolean hazardousLanding = !direction.equals(Vec3d.ZERO) && !traverseSafeLanding(world, landing);
        return new MannerismState.MovementContext(leased, player.isOnGround(), lowClearance, narrow, hazardousLanding, false);
    }

    static MannerismState.Gait traverseGait(MannerismState mannerisms, Personality personality, long now,
                                            MannerismState.MovementContext context, boolean striding) {
        if (!striding) return MannerismState.Gait.WALK;
        MannerismState.Gait gait = mannerisms.gait(personality, now, context);
        // Bounded paid traverse must stay walk-paced; habit sprint-jump hops only when terrain does not need a jump.
        return gait == MannerismState.Gait.SPRINT ? MannerismState.Gait.WALK : gait;
    }

    private static boolean traversePassable(ServerWorld world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean traverseSafeLanding(ServerWorld world, Vec3d near) {
        for (int dy : new int[]{0, -1, 1}) {
            BlockPos feet = BlockPos.ofFloored(near.x, near.y + dy, near.z);
            BlockPos ground = feet.down();
            if (!world.isChunkLoaded(feet) || !traversePassable(world, feet) || !traversePassable(world, feet.up())) continue;
            var support = world.getBlockState(ground);
            if (support.isSideSolidFullSquare(world, ground, Direction.UP)
                    && !support.isOf(Blocks.MAGMA_BLOCK) && !support.isOf(Blocks.CACTUS)
                    && world.getFluidState(feet).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static final class Eat implements SkillBody {
        final NativePlayerBody body;
        final Supplier<ActionLease> lease;
        final int slot;
        ItemStack before;
        long usesBefore;
        Eat(NativePlayerBody body, Supplier<ActionLease> lease, int slot) {
            this.body = body; this.lease = lease; this.slot = slot;
        }
        @Override public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            var player = body.entity();
            var stack = player.getInventory().getStack(slot);
            if (before == null) {
                if (checkpoint.isPresent()) return Step.blocked("Food use cannot be replayed from a checkpoint", true);
                if (!stack.contains(DataComponentTypes.FOOD) || stack.isOf(Items.CHORUS_FRUIT) || !player.canConsume(false)) {
                    return Step.blocked("Selected owned slot is not eligible food, or hunger is full", true);
                }
                before = stack.copy(); // Comparison only; never inserted into an inventory.
                usesBefore = body.foodUses();
                if (!body.selectHotbar(lease.get(), slot) || !body.eat(lease.get()) || !player.isUsingItem()) {
                    return Step.blocked("Native food use was denied", true);
                }
            } else if (!player.isUsingItem()) {
                if (body.foodUses() == usesBefore + 1 && stack.getCount() == before.getCount() - 1
                        && (stack.isEmpty() || ItemStack.areItemsAndComponentsEqual(before, stack))) {
                    return Step.completed(Map.of("consumed", "1", "slot", String.valueOf(slot)));
                }
                return Step.blocked("Food outcome changed or was interrupted; no automatic retry", true);
            } else if (player.getInventory().selectedSlot != slot || stack.getCount() != before.getCount()
                    || !ItemStack.areItemsAndComponentsEqual(before, stack)) {
                return Step.blocked("Food changed during use; reconcile before another request", true);
            }
            return Step.running(Map.of("useTicks", String.valueOf(player.getItemUseTime())), false);
        }
        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return !body.entity().isUsingItem(); }
        boolean permits(NativePlayerBody.Intent intent) {
            if (before == null || body.foodUses() != usesBefore) return false;
            var player = body.entity();
            var stack = player.getInventory().getStack(slot);
            return stack.getCount() == before.getCount() && ItemStack.areItemsAndComponentsEqual(before, stack)
                    && (intent == NativePlayerBody.Intent.SELECT_SLOT
                    || (intent == NativePlayerBody.Intent.EAT && player.getInventory().selectedSlot == slot));
        }
    }

    /**
     * Tactical retreat/evasion: moves rapidly away from hazards or threat vectors toward a safe destination.
     * Unlike ordinary walk, departure ground is allowed to be hazardous (which necessitates the retreat),
     * but the goal and landing surface must be safe, solid, loaded, non-hazardous support.
     */
    private static final class Retreat implements SkillBody {
        final NativePlayerBody body;
        final Supplier<ActionLease> lease;
        final Vec3d start, goal, heading;
        final int blocks;

        Retreat(NativePlayerBody body, Supplier<ActionLease> lease, String direction, int blocks) {
            this.body = body;
            this.lease = lease;
            this.blocks = blocks;
            if (blocks < 1 || blocks > 3) throw new IllegalArgumentException("Native retreat is limited to 1..3 blocks");
            heading = switch (direction) {
                case "north" -> new Vec3d(0, 0, -1);
                case "south" -> new Vec3d(0, 0, 1);
                case "east" -> new Vec3d(1, 0, 0);
                case "west" -> new Vec3d(-1, 0, 0);
                default -> throw new IllegalArgumentException("Retreat direction must be north, south, east or west");
            };
            start = body.entity().getPos();
            goal = start.add(heading.multiply(blocks));
            if (!safeGoal()) throw new IllegalArgumentException("Retreat needs a clear, supported, loaded non-hazardous goal");
        }

        boolean safeGoal() {
            var player = body.entity();
            var world = player.getServerWorld();
            BlockPos goalFeet = BlockPos.ofFloored(goal.x, goal.y, goal.z);
            BlockPos goalGround = BlockPos.ofFloored(goal.x, goal.y - 0.05, goal.z);
            if (!world.isChunkLoaded(goalFeet) || !world.isInBuildLimit(goalFeet.up()) || !world.getWorldBorder().contains(goalFeet)) return false;
            var support = world.getBlockState(goalGround);
            if (!support.isSideSolidFullSquare(world, goalGround, Direction.UP)
                    || support.isOf(Blocks.MAGMA_BLOCK) || support.isOf(Blocks.CACTUS)
                    || !world.getFluidState(goalFeet).isEmpty()) {
                return false;
            }
            var feetState = world.getBlockState(goalFeet);
            if (feetState.isOf(Blocks.FIRE) || feetState.isOf(Blocks.SOUL_FIRE) || feetState.isOf(Blocks.SWEET_BERRY_BUSH)
                    || feetState.isOf(Blocks.POWDER_SNOW) || feetState.isOf(Blocks.LAVA)) {
                return false;
            }
            var box = player.getBoundingBox().offset(goal.subtract(player.getPos()));
            return world.isSpaceEmpty(player, box);
        }

        boolean safe() {
            var player = body.entity();
            Vec3d current = player.getPos();
            if (current.squaredDistanceTo(start) > 16 || current.squaredDistanceTo(goal) > 16) return false;
            return safeGoal();
        }

        @Override
        public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent()) return Step.blocked("Retreat cannot replay a saved route", true);
            if (!safe()) return Step.blocked("Retreat destination became obstructed, unsupported or unavailable", true);
            Vec3d delta = goal.subtract(body.entity().getPos());
            double distance = delta.horizontalLength();
            if (distance <= 0.15) {
                body.stopInputs();
                return Step.completed(Map.of("arrived", "true", "remainingMillimeters", String.valueOf(Math.round(distance * 1000))));
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            // Move with sprint to clear hazard rapidly
            if (!body.move(lease.get(), distance < 0.5 ? 0.4f : 1.0f, 0, false, true, yaw)) {
                return Step.blocked("Movement authority denied", true);
            }
            return Step.running(Map.of("remainingMillimeters", String.valueOf(Math.round(distance * 1000))), true);
        }

        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return true; }
    }

    static final class Swap implements SkillBody {
        private final NativePlayerBody body;
        private final Supplier<ActionLease> lease;
        private final int source, target;

        Swap(NativePlayerBody body, Supplier<ActionLease> lease, int source, int target) {
            this.body = body;
            this.lease = lease;
            this.source = source;
            this.target = target;
        }

        @Override
        public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent()) return Step.blocked("Native inventory swap cannot replay a saved step", true);
            if (source == target) return Step.blocked("Self-swap is a no-effect request", true);
            if (body.swapSlots(lease.get(), source, target)) {
                return Step.completed(Map.of("swapped", source + "<->" + target));
            }
            return Step.blocked("Native inventory swap denied", true);
        }

        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return true; }
    }

    static final class Attack implements SkillBody {
        private final NativePlayerBody body;
        private final Supplier<ActionLease> lease;
        private final UUID targetId;
        private final Integer slot;

        Attack(NativePlayerBody body, Supplier<ActionLease> lease, UUID targetId, Integer slot) {
            this.body = body;
            this.lease = lease;
            this.targetId = targetId;
            this.slot = slot;
        }

        @Override
        public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent()) return Step.blocked("Attack cannot replay a saved state", true);
            var player = body.entity();
            var target = player.getServerWorld().getEntity(targetId);
            if (!(target instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
                return Step.blocked("Target entity not found or dead", true);
            }
            if (player.squaredDistanceTo(living) > 16.0) {
                return Step.blocked("Target entity out of reach (> 4.0 blocks)", true);
            }
            if (!player.canSee(living)) {
                return Step.blocked("Target entity not in line of sight", true);
            }
            float cooldown = player.getAttackCooldownProgress(0.5f);
            if (cooldown < 0.9f) {
                return Step.running(Map.of("cooldown", String.valueOf(cooldown)), false);
            }
            float targetHealthBefore = living.getHealth();
            if (body.attack(lease.get(), targetId, slot != null ? slot : -1)) {
                float damageDealt = targetHealthBefore - living.getHealth();
                return Step.completed(Map.of(
                        "hit", "true",
                        "target", targetId.toString(),
                        "damageDealt", String.valueOf(damageDealt)));
            }
            return Step.blocked("Attack execution denied by body authority", true);
        }

        @Override public void halt() { body.stopInputs(); }
        @Override public boolean settled() { return true; }
    }

    static final class Block implements SkillBody {
        private final NativePlayerBody body;
        private final Supplier<ActionLease> lease;
        private final int durationTicks;
        private int elapsedTicks = 0;
        private boolean started = false;

        Block(NativePlayerBody body, Supplier<ActionLease> lease, int durationTicks) {
            this.body = body;
            this.lease = lease;
            this.durationTicks = durationTicks;
        }

        @Override
        public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
            if (checkpoint.isPresent()) return Step.blocked("Shield block cannot replay a saved step", true);
            if (!started) {
                if (!body.block(lease.get(), true)) {
                    return Step.blocked("Shield block cannot be raised (no shield equipped or busy)", true);
                }
                started = true;
            }
            elapsedTicks++;
            if (elapsedTicks < durationTicks) {
                return Step.running(Map.of("blocking", "true", "remainingTicks", String.valueOf(durationTicks - elapsedTicks)), true);
            }
            body.block(lease.get(), false);
            return Step.completed(Map.of("blockedTicks", String.valueOf(elapsedTicks)));
        }

        @Override
        public void halt() {
            body.block(lease.get(), false);
            body.stopInputs();
        }

        @Override
        public boolean settled() {
            return !body.entity().isUsingItem();
        }
    }
}
