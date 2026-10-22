package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import dev.kindred.core.tactics.FallClutch;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Breaks a long fall with an owned water bucket (E-07d), as players do: when the block it will land on comes within
 * reach, water is poured on it, the fall ends in the water (vanilla feels the water before the ground, so the fall
 * does no harm), and the water is scooped back into the bucket. The server's edit policy covers the water's block; one
 * bucket of water is spent and taken back, and a pour that could not be scooped up leaves an empty bucket and the water
 * where it was poured, both verified.
 */
final class NativeClutchAction extends NativeRecordedAction {
    /** Ticks to wait after landing for the water to be scooped; the fall itself is not timed. */
    private static final int SCOOP_TICKS = 40;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private BlockPos water;
    private float fallen;
    private int landedTicks;
    private boolean halted, unknown, scooped;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeClutchAction(NativePlayerBody body, Supplier<ActionLease> lease, Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                       ActionAdmission.Writer writer, Consumer<Runnable> dispatch, int slot) {
        this.body = body;
        this.lease = lease;
        this.setup = setup;
        this.policy = policy;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Water is poured from an owned hotbar slot 0..8");
    }

    /**
     * The block a player falling from where it is now lands on: the highest top under the four corners of its feet,
     * the middle's column first where they are level; with how far above it the feet are. Empty over nothing.
     */
    static Optional<Map.Entry<BlockPos, Double>> landing(net.minecraft.server.network.ServerPlayerEntity player) {
        var world = player.getServerWorld();
        double half = player.getWidth() / 2 - 0.01;
        BlockPos best = null;
        double bestTop = Double.NEGATIVE_INFINITY;
        double[][] columns = {{0, 0}, {-half, -half}, {-half, half}, {half, -half}, {half, half}};
        for (double[] c : columns) {
            int x = net.minecraft.util.math.MathHelper.floor(player.getX() + c[0]), z = net.minecraft.util.math.MathHelper.floor(player.getZ() + c[1]);
            for (int y = net.minecraft.util.math.MathHelper.floor(player.getY()); y > Math.max(world.getBottomY(), player.getBlockY() - 80); y--) {
                BlockPos at = new BlockPos(x, y, z);
                var shape = world.getBlockState(at).getCollisionShape(world, at);
                if (shape.isEmpty()) continue;
                double top = y + shape.getMax(Direction.Axis.Y);
                if (top > player.getY() + 0.01) continue; // Beside the player, not under it.
                if (top > bestTop + 1e-6) { bestTop = top; best = at; }
                break;
            }
        }
        return best == null ? Optional.empty() : Optional.of(Map.entry(best, player.getY() - bestTop));
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded clutch cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (!player.getInventory().getStack(slot).isOf(Items.WATER_BUCKET)) return Step.blocked("The hotbar slot holds no water bucket", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_clutch", "slot=" + slot),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable clutch acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Clutch close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        fallen = Math.max(fallen, player.fallDistance);
        boolean down = player.isOnGround() || player.isTouchingWater() || player.isInLava() || !player.isAlive();
        if (water == null) {
            if (down) {
                // Landed (or in water) before the water was needed: nothing poured, nothing spent.
                if (admission.phase() == ActionAdmission.Phase.READY || admission.phase() == ActionAdmission.Phase.ISSUED) return settle();
                return waiting();
            }
            if (admission.phase() != ActionAdmission.Phase.READY && admission.phase() != ActionAdmission.Phase.ISSUED) return waiting();
            var landing = landing(player);
            if (landing.isEmpty() || !FallClutch.pourNow(landing.get().getValue())) return waiting();
            BlockPos ground = landing.get().getKey();
            BlockPos at = ground.up();
            var world = player.getServerWorld();
            if (!world.getBlockState(at).isReplaceable() || world.getDimension().ultrawarm()) return settle();
            var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.PLACE, at, "minecraft:water", policy.get());
            if (!decision.allowed()) return settle();
            if (admission.phase() == ActionAdmission.Phase.READY && admission.take().isEmpty()) return waiting();
            // The middle of the top face it lands on, a hair inside it, as a player aims straight down at it.
            Vec3d face = new Vec3d(ground.getX() + 0.5, player.getY() - landing.get().getValue() - 0.02, ground.getZ() + 0.5);
            try {
                if (!body.useItemToward(lease.get(), slot, face, at)) return settle();
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Clutch interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            if (world.getFluidState(at).isIn(FluidTags.WATER)) water = at;
            else return settle();
            return waiting();
        }
        // Poured: once down in it, the water is scooped back up.
        if (!down) return waiting();
        var world = player.getServerWorld();
        if (++landedTicks > SCOOP_TICKS || !world.getFluidState(water).isStill()) return settle();
        if (landedTicks < 3 || !player.getInventory().getStack(slot).isOf(Items.BUCKET)) return waiting();
        try {
            body.useItemToward(lease.get(), slot, Vec3d.ofCenter(water).add(0, 0.3, 0), water);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Scoop interrupted with an unknown outcome; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        scooped = !world.getFluidState(water).isStill() && player.getInventory().getStack(slot).isOf(Items.WATER_BUCKET);
        return scooped ? settle() : waiting();
    }

    private Step settle() {
        var player = body.entity();
        var after = InventoryEvidence.capture(body);
        int waterBefore = count(before, Items.WATER_BUCKET), waterAfter = count(after, Items.WATER_BUCKET);
        int emptyBefore = count(before, Items.BUCKET), emptyAfter = count(after, Items.BUCKET);
        var progress = new HashMap<String, String>(Map.of("fallen", String.format(java.util.Locale.ROOT, "%.1f", fallen),
                "poured", String.valueOf(water != null), "scooped", String.valueOf(scooped), "health", String.format(java.util.Locale.ROOT, "%.1f", player.getHealth())));
        if (water == null) {
            if (waterAfter == waterBefore && emptyAfter == emptyBefore && conserved(after)) {
                result = Step.completed(Map.copyOf(progress));
                // Nothing was done in the world: an admission never taken is closed, one taken records no effect.
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.noEffect(before.description() + "; nothing poured", "No water was poured");
                else flush = admission.close();
                return waiting();
            }
        } else if (scooped ? waterAfter == waterBefore && emptyAfter == emptyBefore : waterAfter == waterBefore - 1 && emptyAfter == emptyBefore + 1) {
            if (conserved(after)) {
                if (!scooped) progress.put("waterLeftAt", water.toShortString());
                result = Step.completed(Map.copyOf(progress));
                admission.verify(after.description() + "; " + progress, scooped ? "Native clutch: poured and scooped" : "Native clutch: poured, water left");
                return waiting();
            }
        }
        unknown = true;
        result = Step.blocked("Clutch conservation check failed; reconcile", true);
        admission.uncertain(result.reason());
        return waiting();
    }

    private static int count(InventoryEvidence evidence, Item item) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    /** Nothing but the bucket's water lost; pickups on the way, and the armour's wear, are fine. */
    private boolean conserved(InventoryEvidence after) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == Items.WATER_BUCKET ? 1 : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || result != null) return false;
        return intent == NativePlayerBody.Intent.BUCKET || intent == NativePlayerBody.Intent.SELECT_SLOT;
    }

    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }

    private Step waiting() {
        return Step.running(Map.of("durableAction", phase()), false);
    }

    @Override
    CompletableFuture<ActionRecord> close() {
        halted = true;
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) {
            var after = InventoryEvidence.capture(body);
            int waterBefore = count(before, Items.WATER_BUCKET), waterAfter = count(after, Items.WATER_BUCKET);
            if (water == null && waterAfter == waterBefore && conserved(after)) {
                admission.noEffect(before.description() + "; nothing poured", "Clutch cancelled before pouring");
            } else if (water != null && waterAfter == waterBefore - 1 && conserved(after)) {
                admission.verify(after.description() + "; water left at " + water.toShortString(), "Native clutch: poured, water left");
            } else if (water != null && waterAfter == waterBefore && conserved(after)) {
                admission.verify(after.description() + "; poured and scooped", "Native clutch: poured and scooped");
            } else {
                unknown = true;
                admission.uncertain("Clutch cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
