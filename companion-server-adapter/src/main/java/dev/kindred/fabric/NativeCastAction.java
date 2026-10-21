package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidFillable;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recorded casting of one obsidian block in place, as speedrunners build a portal without a diamond pickaxe: water
 * from the player's own bucket is poured into the cell above the target, lava from its own bucket is poured into the
 * target, where vanilla hardens it at once because water touches it, and the water is taken back. The lava never
 * stands uncovered, so it cannot spread. Outcome: obsidian at the target, one lava bucket emptied, the water bucket
 * refilled.
 */
final class NativeCastAction extends NativeRecordedAction {
    private enum Phase { WATER, LAVA, RECOVER, DONE }
    private static final int SETTLE_TICKS = 6;
    /** Offsets from a centre, centre first: the next block down can hide a face's centre from a low eye. */
    private static final double[] SPREAD = {0, 0.4, -0.4};

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target, water;
    private final List<String> claimKeys;
    private ActionLease issuedLease;
    private ClaimRegistry.Grant claim;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private Phase phase = Phase.WATER;
    private boolean halted, unknown, used;
    private String failure;
    private int waits;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeCastAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                     ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos target) {
        this.body = body; this.lease = lease; this.policy = policy; this.claims = claims; this.writer = writer; this.dispatch = dispatch;
        this.target = target.toImmutable();
        this.water = target.up().toImmutable();
        var player = body.entity();
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target) || !world.isChunkLoaded(water)) throw new IllegalArgumentException("Cast target is unloaded");
        for (BlockPos pos : List.of(target, water)) {
            String denial = RoamingEdits.placeDenial(player, pos, policy.get());
            if (denial != null) throw new IllegalArgumentException(denial);
        }
        if (!world.getBlockState(target).isAir() || !world.getBlockState(water).isAir()) {
            throw new IllegalArgumentException("Casting needs open air at the target and above it");
        }
        if (ItemMoves.count(player.getInventory(), Items.LAVA_BUCKET) == 0 || ItemMoves.count(player.getInventory(), Items.WATER_BUCKET) == 0) {
            throw new IllegalArgumentException("Casting needs an owned lava bucket and water bucket");
        }
        if (aimAt(water).isEmpty() || aimAt(target).isEmpty()) throw new IllegalArgumentException("No reachable face to pour against");
        String dimension = world.getRegistryKey().getValue().toString();
        claimKeys = List.of("block:" + worldId + ":" + dimension + ":" + target.toShortString(),
                "block:" + worldId + ":" + dimension + ":" + water.toShortString());
    }

    /** Aim point on a solid neighbour's face that puts a poured fluid exactly at {@code cell}. */
    private Optional<Vec3d> aimAt(BlockPos cell) {
        var world = body.entity().getServerWorld();
        for (Direction toward : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN)) {
            BlockPos support = cell.offset(toward.getOpposite());
            if (support.equals(water) && cell.equals(target)) continue; // The water cell is not a face to pour against.
            if (!world.isChunkLoaded(support) || world.getBlockState(support).getCollisionShape(world, support).isEmpty()) continue;
            // A water bucket clicked on leaves, a slab or a fence waterlogs it instead of pouring beside it (sky-island run 5).
            if (cell.equals(water) && world.getBlockState(support).getBlock() instanceof FluidFillable) continue;
            Vec3d face = Vec3d.ofCenter(support).add(toward.getOffsetX() * 0.5, toward.getOffsetY() * 0.5, toward.getOffsetZ() * 0.5);
            Vec3d inside = face.subtract(toward.getOffsetX() * 0.02, toward.getOffsetY() * 0.02, toward.getOffsetZ() * 0.02);
            for (double u : SPREAD) for (double v : SPREAD) {
                Vec3d point = inside.add(switch (toward.getAxis()) {
                    case X -> new Vec3d(0, v, u);
                    case Y -> new Vec3d(u, 0, v);
                    case Z -> new Vec3d(u, v, 0);
                });
                if (rayLands(support, toward, point, RaycastContext.FluidHandling.NONE)) return Optional.of(point);
            }
        }
        return Optional.empty();
    }

    /** Aim point inside the poured water source that a bucket reaches without passing through the cast block. */
    private Optional<Vec3d> sourceAim() {
        for (double dx : SPREAD) for (double dy : SPREAD) for (double dz : SPREAD) {
            Vec3d point = Vec3d.ofCenter(water).add(dx, dy, dz);
            if (rayLands(water, null, point, RaycastContext.FluidHandling.SOURCE_ONLY)) return Optional.of(point);
        }
        return Optional.empty();
    }

    private boolean rayLands(BlockPos expected, Direction side, Vec3d point, RaycastContext.FluidHandling fluids) {
        var player = body.entity();
        Vec3d eye = player.getEyePos();
        if (eye.distanceTo(point) > player.getBlockInteractionRange() - 0.05) return false;
        Vec3d end = eye.add(point.subtract(eye).normalize().multiply(player.getBlockInteractionRange()));
        BlockHitResult hit = player.getServerWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, fluids, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(expected) && (side == null || hit.getSide() == side);
    }

    private int slotOf(Item item) {
        var inventory = body.entity().getInventory();
        for (int slot = 0; slot < 36; slot++) if (inventory.getStack(slot).isOf(item)) return slot;
        return -1;
    }

    /** Puts an owned {@code item} in the hotbar and uses it toward {@code aim}. */
    private boolean use(Item item, Vec3d aim, BlockPos at) {
        int source = slotOf(item);
        if (source < 0) return false;
        int hotbar = source;
        if (source >= 9) {
            var inventory = body.entity().getInventory();
            hotbar = 8;
            for (int slot = 0; slot < 9; slot++) if (inventory.getStack(slot).isEmpty()) { hotbar = slot; break; }
            if (!body.swapSlots(issuedLease, source, hotbar)) return false;
        }
        return body.useItemToward(issuedLease, hotbar, aim, at);
    }

    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "cast", phase.name()), false); }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded cast never replays", true);
        var world = body.entity().getServerWorld();
        if (admission == null) {
            issuedLease = lease.get();
            claim = claims.acquire(issuedLease.taskId(), issuedLease.sessionEpoch(), claimKeys, System.currentTimeMillis(), 10_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds these positions", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_cast",
                    "cast obsidian at " + target.toShortString()), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable cast acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Cast close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        if (admission.phase() == ActionAdmission.Phase.READY) {
            boolean ready = claims.holdsAll(claim, System.currentTimeMillis()) && world.getBlockState(target).isAir()
                    && world.getBlockState(water).isAir() && RoamingEdits.placeDenial(body.entity(), target, policy.get()) == null;
            Optional<Vec3d> aim = aimAt(water);
            if (!ready || aim.isEmpty()) {
                result = Step.blocked("The cast site changed before casting; nothing happened", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            used = use(Items.WATER_BUCKET, aim.get(), water);
            if (!used) settle("Pouring the water was denied", false);
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.ISSUED) return waiting();
        switch (phase) {
            case WATER -> {
                // Lava goes in only once water stands over the target, so it hardens the moment it lands.
                if (!world.getFluidState(water).isIn(FluidTags.WATER) || !world.getFluidState(water).isStill()) {
                    if (++waits > SETTLE_TICKS) settle("The water did not stay above the target", false);
                    return waiting();
                }
                Optional<Vec3d> aim = aimAt(target);
                if (aim.isEmpty() || !use(Items.LAVA_BUCKET, aim.get(), target)) {
                    takeWaterBack("Pouring the lava was denied"); // Nothing cast: leave the place as it was found.
                    return waiting();
                }
                phase = Phase.LAVA;
                waits = 0;
            }
            case LAVA -> {
                if (!world.getBlockState(target).isOf(Blocks.OBSIDIAN)) {
                    if (++waits <= SETTLE_TICKS) return waiting();
                    // Still holding the lava: the pour never happened, so take the water back and leave no trace.
                    if (ItemMoves.count(body.entity().getInventory(), Items.LAVA_BUCKET) == count(Items.LAVA_BUCKET)) {
                        takeWaterBack("The lava could not be poured");
                    } else {
                        settle("The lava did not harden; the target holds " + world.getBlockState(target), false);
                    }
                    return waiting();
                }
                takeWaterBack(null);
            }
            case RECOVER -> {
                if (world.getFluidState(water).isStill() && world.getFluidState(water).isIn(FluidTags.WATER) && ++waits <= SETTLE_TICKS) return waiting();
                settle(failure == null ? "Obsidian cast" : failure, failure == null);
            }
            default -> { }
        }
        return waiting();
    }

    /** Fill a bucket from the water poured over the target; {@code failure} is why the cast is being abandoned, if it is. */
    private void takeWaterBack(String failure) {
        this.failure = failure;
        Optional<Vec3d> aim = sourceAim();
        if (aim.isEmpty() || !use(Items.BUCKET, aim.get(), water)) {
            settle(failure == null ? "Taking the water back was denied" : failure + "; the water could not be taken back", false);
            return;
        }
        phase = Phase.RECOVER;
        waits = 0;
    }

    /** Exactly one lava bucket became an empty bucket, and the water bucket came back. */
    private boolean conserved(InventoryEvidence after) {
        var now = body.entity().getInventory();
        int lava = ItemMoves.count(now, Items.LAVA_BUCKET), empty = ItemMoves.count(now, Items.BUCKET), filled = ItemMoves.count(now, Items.WATER_BUCKET);
        return lava == count(Items.LAVA_BUCKET) - 1 && empty == count(Items.BUCKET) + 1 && filled == count(Items.WATER_BUCKET);
    }

    private int count(Item item) {
        int total = 0;
        for (var stack : before.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    private void settle(String reason, boolean cast) {
        body.stopInputs();
        phase = Phase.DONE;
        var world = body.entity().getServerWorld();
        var after = InventoryEvidence.capture(body);
        boolean hardened = world.getBlockState(target).isOf(Blocks.OBSIDIAN);
        if (cast && hardened && conserved(after)) {
            admission.verify(after.description() + "; obsidian at " + target.toShortString(), "Lava hardened in place under poured water");
            result = Step.completed(Map.of("obsidian", target.toShortString()));
        } else if (before.sameContents(body) && !hardened && !world.getFluidState(target).isIn(FluidTags.LAVA)) {
            // Both buckets as they were and no lava out: at most water still draining from the target.
            admission.noEffect(before.description() + "; buckets as before, nothing cast", reason);
            result = Step.blocked(reason + "; nothing was cast", true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; buckets or blocks changed without a complete cast");
            result = Step.blocked("Cast outcome requires reconciliation: " + reason, true);
        }
    }

    @Override boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || result != null) return false;
        return intent == NativePlayerBody.Intent.SWAP
                || intent == NativePlayerBody.Intent.BUCKET && (target.equals(requested) || water.equals(requested));
    }
    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    @Override CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) { if (claim != null) claims.release(claim); return flush; }
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) settle("Authority ended", false);
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        if (claim != null) claims.release(claim);
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
