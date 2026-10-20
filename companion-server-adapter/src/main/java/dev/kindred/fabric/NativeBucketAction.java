package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
 * Recorded bucket use: fill an empty bucket from a water or lava source, or pour a water bucket into a
 * position. Vanilla bucket use decides the outcome from where the player looks; this action only aims.
 */
final class NativeBucketAction extends NativeRecordedAction {
    enum Mode { FILL, POUR }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;
    private final Mode mode;
    private final Item held;
    private final int sourceSlot;
    private final int hotbarSlot;
    private final Vec3d aim;
    private final String claimKey;
    private ActionLease issuedLease;
    private ClaimRegistry.Grant claim;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private boolean halted, unknown, used;
    private int settleTicks;
    private int filledBefore, pouredBefore;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeBucketAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                       ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos target, Mode mode) {
        this.body = body; this.lease = lease; this.policy = policy; this.claims = claims; this.writer = writer;
        this.dispatch = dispatch; this.target = target.toImmutable(); this.mode = mode;
        var player = body.entity();
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) throw new IllegalArgumentException("Bucket target is unloaded");
        String denial = RoamingEdits.workstationDenial(player, target, policy.get());
        if (denial != null) throw new IllegalArgumentException(denial);
        var fluid = world.getFluidState(target);
        if (mode == Mode.FILL) {
            if (!fluid.isStill()) throw new IllegalArgumentException("Only a fluid source can fill a bucket");
            held = Items.BUCKET;
            aim = Vec3d.ofCenter(target);
            if (!rayLands(target, null)) throw new IllegalArgumentException("The fluid source is not in reach or sight");
        } else {
            if (!world.getBlockState(target).isReplaceable() && !fluid.isIn(FluidTags.WATER)) throw new IllegalArgumentException("Pour target is occupied");
            held = Items.WATER_BUCKET;
            aim = pourAim().orElseThrow(() -> new IllegalArgumentException("No reachable face to pour water onto that position"));
        }
        sourceSlot = slotOf(held);
        if (sourceSlot < 0) throw new IllegalArgumentException("No owned " + (mode == Mode.FILL ? "empty bucket" : "water bucket"));
        hotbarSlot = sourceSlot < 9 ? sourceSlot : freeHotbarSlot();
        claimKey = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + target.toShortString();
    }

    private int slotOf(Item item) {
        var inventory = body.entity().getInventory();
        for (int slot = 0; slot < 36; slot++) if (inventory.getStack(slot).isOf(item)) return slot;
        return -1;
    }

    private int freeHotbarSlot() {
        var inventory = body.entity().getInventory();
        for (int slot = 0; slot < 9; slot++) if (inventory.getStack(slot).isEmpty()) return slot;
        return 8;
    }

    /** Aim point on a solid neighbour's face whose clicked side places water exactly at the target. */
    private Optional<Vec3d> pourAim() {
        var world = body.entity().getServerWorld();
        for (Direction toward : List.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN)) {
            BlockPos support = target.offset(toward.getOpposite());
            if (!world.isChunkLoaded(support) || world.getBlockState(support).getCollisionShape(world, support).isEmpty()) continue;
            Vec3d face = Vec3d.ofCenter(support).add(toward.getOffsetX() * 0.5, toward.getOffsetY() * 0.5, toward.getOffsetZ() * 0.5);
            Vec3d inside = face.subtract(toward.getOffsetX() * 0.02, toward.getOffsetY() * 0.02, toward.getOffsetZ() * 0.02);
            if (rayLands(support, toward, inside)) return Optional.of(inside);
        }
        return Optional.empty();
    }

    private boolean rayLands(BlockPos expected, Direction side) {
        return rayLands(expected, side, aim);
    }

    private boolean rayLands(BlockPos expected, Direction side, Vec3d point) {
        var player = body.entity();
        Vec3d eye = player.getEyePos();
        if (eye.distanceTo(point) > player.getBlockInteractionRange() - 0.05) return false;
        Vec3d end = eye.add(point.subtract(eye).normalize().multiply(player.getBlockInteractionRange()));
        var fluidHandling = mode == Mode.FILL ? RaycastContext.FluidHandling.SOURCE_ONLY : RaycastContext.FluidHandling.NONE;
        BlockHitResult hit = player.getServerWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, fluidHandling, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(expected) && (side == null || hit.getSide() == side);
    }

    private boolean outcome() {
        var world = body.entity().getServerWorld();
        var inventory = body.entity().getInventory();
        if (mode == Mode.FILL) {
            int filled = ItemMoves.count(inventory, Items.WATER_BUCKET) + ItemMoves.count(inventory, Items.LAVA_BUCKET);
            return !world.getFluidState(target).isStill() && filled > filledBefore;
        }
        return world.getFluidState(target).isIn(FluidTags.WATER) && ItemMoves.count(inventory, Items.WATER_BUCKET) < pouredBefore;
    }

    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "used", String.valueOf(used)), false); }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("Recorded bucket use never replays", true);
        if (admission == null) {
            issuedLease = lease.get();
            claim = claims.acquire(issuedLease.taskId(), issuedLease.sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 10_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this position", true);
            before = InventoryEvidence.capture(body);
            var inventory = body.entity().getInventory();
            filledBefore = ItemMoves.count(inventory, Items.WATER_BUCKET) + ItemMoves.count(inventory, Items.LAVA_BUCKET);
            pouredBefore = ItemMoves.count(inventory, Items.WATER_BUCKET);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_bucket",
                    mode + " at " + target.toShortString()), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable bucket acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Bucket close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        if (admission.phase() == ActionAdmission.Phase.READY) {
            boolean ready = claim != null && claims.holdsAll(claim, System.currentTimeMillis())
                    && (mode == Mode.FILL ? rayLands(target, null) : pourAim().isPresent())
                    && RoamingEdits.workstationDenial(body.entity(), target, policy.get()) == null;
            if (!ready) {
                result = Step.blocked("Bucket target changed before use; nothing happened", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            boolean moved = sourceSlot == hotbarSlot || body.swapSlots(issuedLease, sourceSlot, hotbarSlot);
            used = moved && body.useItemToward(issuedLease, hotbarSlot, mode == Mode.FILL ? aim : pourAim().orElse(aim), target);
            if (!used) settle("Bucket use was denied", false);
            return waiting();
        }
        if (admission.phase() == ActionAdmission.Phase.ISSUED) {
            // Vanilla applies the bucket immediately; give fluid updates a couple of ticks to settle.
            if (outcome() || ++settleTicks > 3) settle(outcome() ? "Bucket used" : "The bucket use had no effect", outcome());
        }
        return waiting();
    }

    private void settle(String reason, boolean completed) {
        body.stopInputs();
        var after = InventoryEvidence.capture(body);
        if (completed) {
            admission.verify(after.description() + "; " + mode + " at " + target.toShortString(), "Vanilla bucket use");
            result = Step.completed(Map.of("bucket", mode.name().toLowerCase(java.util.Locale.ROOT), "target", target.toShortString()));
        } else if (before.sameContents(body)) {
            admission.noEffect(before.description() + "; bucket unchanged", reason);
            result = Step.blocked(reason, true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; the inventory changed without the expected bucket outcome");
            result = Step.blocked("Bucket outcome requires reconciliation", true);
        }
    }

    @Override boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || used) return false;
        return intent == NativePlayerBody.Intent.SWAP || intent == NativePlayerBody.Intent.BUCKET && target.equals(requested);
    }
    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    @Override CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) { if (claim != null) claims.release(claim); return flush; }
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) settle("Authority ended", outcome());
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        if (claim != null) claims.release(claim);
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
