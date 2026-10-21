package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Throws one owned eye of ender and watches it fly, as a player does to find a stronghold: the eye rises and drifts
 * twelve blocks along the way to the stronghold, then drops or shatters. What is reported is only what the player saw:
 * where it threw from and the heading of the eye's flight. Exactly one eye is spent under the durable ledger; whether
 * it drops to be picked up again is vanilla's chance. An eye that finds no stronghold to fly to is kept, and the step
 * says so.
 */
final class NativeThrowEyeAction extends NativeRecordedAction {
    /** Ticks to watch: an eye flies for eighty, then drops or breaks. */
    private static final int WATCH_TICKS = 90;
    /** Blocks the eye must be seen to travel before its heading counts. */
    private static final double MIN_FLIGHT = 3.0;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final float yaw;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Vec3d origin;
    private UUID eye;
    private Vec3d lastSeen;
    private int watched = -1;
    private Step result;
    private boolean halted, unknown, thrown;
    private CompletableFuture<ActionRecord> flush;

    NativeThrowEyeAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer,
                         Consumer<Runnable> dispatch, int slot, float yaw) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.yaw = yaw;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A throw needs an owned hotbar slot 0..8");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded throw cannot replay after cancellation/restart", true);
        if (admission == null) {
            var stack = body.entity().getInventory().getStack(slot);
            if (!stack.isOf(Items.ENDER_EYE)) return Step.blocked("The hotbar slot holds no eye of ender", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_throw_eye",
                    "slot=" + slot + "; yaw=" + Math.round(yaw)), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable throw acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Throw close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        var player = body.entity();
        if (!thrown) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!before.matches(body) || !player.isAlive()) {
                result = Step.blocked("Owned inventory changed before the throw; nothing thrown", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            origin = player.getPos();
            try {
                if (!body.throwHeld(lease.get(), slot, yaw)) return settle(null, "Throw authority denied");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Throw interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            thrown = true;
            watched = 0;
            // The eye appears at the player's middle the moment it is thrown.
            var world = player.getServerWorld();
            var spawned = world.getEntitiesByClass(EyeOfEnderEntity.class, new Box(player.getBlockPos()).expand(3), e -> e.age <= 1);
            if (spawned.isEmpty()) return settle(null, "No stronghold for the eye to fly to; it was kept");
            eye = spawned.getFirst().getUuid();
            lastSeen = spawned.getFirst().getPos();
            return waiting();
        }
        var entity = player.getServerWorld().getEntity(eye);
        if (entity instanceof EyeOfEnderEntity flying && flying.isAlive()) lastSeen = flying.getPos();
        if (++watched >= WATCH_TICKS || !(entity instanceof EyeOfEnderEntity) || !entity.isAlive()) return settle(lastSeen, null);
        return waiting();
    }

    /** Ends the step with the flight seen (or none), checking that at most the one eye was spent. */
    private Step settle(Vec3d end, String reason) {
        var after = InventoryEvidence.capture(body);
        int spent = spent(after);
        if (spent == 0 && conserved(after, 0)) {
            result = Step.blocked(reason != null ? reason : "The eye was not thrown", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent != 1 || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Throw conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        double dx = end == null ? 0 : end.x - origin.x, dz = end == null ? 0 : end.z - origin.z;
        if (Math.hypot(dx, dz) < MIN_FLIGHT) {
            result = Step.completed(Map.of("fromX", fmt(origin.x), "fromZ", fmt(origin.z), "heading", "unseen"));
        } else {
            result = Step.completed(Map.of("fromX", fmt(origin.x), "fromZ", fmt(origin.z), "dx", fmt(dx), "dz", fmt(dz),
                    "toX", fmt(end.x), "toY", fmt(end.y), "toZ", fmt(end.z)));
        }
        admission.verify(after.description() + "; " + result.progress(), "Native eye throw verified");
        return waiting();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private int spent(InventoryEvidence after) {
        return count(before, Items.ENDER_EYE) - count(after, Items.ENDER_EYE);
    }

    private static int count(InventoryEvidence evidence, Item item) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    /** At most {@code eyes} eyes gone and nothing else lost; pickups on the way are fine. */
    private boolean conserved(InventoryEvidence after, int eyes) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == Items.ENDER_EYE ? eyes : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || thrown) return false;
        return intent == NativePlayerBody.Intent.THROW || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            var after = InventoryEvidence.capture(body);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native eye throw verified");
            } else if (before != null && spent(after) == 0 && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Throw cancelled before the eye left the hand");
            } else if (before != null && spent(after) == 1 && conserved(after, 1)) {
                // Thrown, but the flight was not watched to its end: the eye is spent, the heading unknown.
                admission.verify(after.description() + "; thrown, flight not watched", "Native eye throw verified");
            } else {
                unknown = true;
                admission.uncertain("Throw cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
