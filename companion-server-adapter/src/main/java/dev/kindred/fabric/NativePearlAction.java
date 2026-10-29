package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
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
 * In plain terms: an AI player throwing one of its own ender pearls and watching it land, as a human does to
 * get up onto a tall pillar or back down. The game moves the player to where the pearl hit and takes some
 * health for it; exactly one pearl is used, and it reports where the player landed and how close that is to
 * where it aimed.
 *
 * Technical: Throws one owned ender pearl along a chosen yaw and pitch and follows it until it lands (E-39f),
 * as a player does to get up onto a pillar's cage or down from it: vanilla moves the player to where the
 * pearl struck and takes five health for it. Exactly one pearl is spent under the durable ledger; the step
 * reports where the player landed, and how far that is from where the throw was meant to land.
 */
final class NativePearlAction extends NativeRecordedAction {
    /** Ticks to follow a pearl: a long throw down from a pillar lands within about sixty. */
    private static final int WATCH_TICKS = 160;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final float yaw, pitch;
    private final Vec3d meant;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Vec3d origin;
    private UUID pearl;
    private int watched = -1;
    private Step result;
    private boolean halted, unknown, thrown;
    private CompletableFuture<ActionRecord> flush;

    /** A pearl throw from a hotbar slot in the given direction, aimed to land at the given spot. */
    NativePearlAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                      int slot, float yaw, float pitch, Vec3d meant) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.yaw = yaw;
        this.pitch = pitch;
        this.meant = meant;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A pearl is thrown from an owned hotbar slot 0..8");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || Math.abs(pitch) > 90) throw new IllegalArgumentException("A throw needs a finite yaw and a pitch within 90");
    }

    /** Moves the throw on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded throw cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (!player.getInventory().getStack(slot).isOf(Items.ENDER_PEARL)) return Step.blocked("The hotbar slot holds no ender pearl", true);
            // A pearl thrown within the last second is still cooling down: wait it out rather than throw nothing.
            if (player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL)) return Step.running(Map.of("cooldown", "true"), false);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_pearl",
                    String.format(Locale.ROOT, "slot=%d; yaw=%.2f; pitch=%.2f", slot, yaw, pitch)), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable throw acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Throw close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
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
                if (!body.throwHeld(lease.get(), slot, yaw, pitch)) return settle("Throw authority denied");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Throw interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            thrown = true;
            watched = 0;
            var spawned = player.getServerWorld().getEntitiesByClass(EnderPearlEntity.class, new Box(player.getBlockPos()).expand(3),
                    e -> e.age <= 1 && e.getOwner() == player);
            if (spawned.isEmpty()) return settle("No pearl left the hand");
            pearl = spawned.getFirst().getUuid();
            return waiting();
        }
        var entity = player.getServerWorld().getEntity(pearl);
        if (++watched >= WATCH_TICKS || !(entity instanceof EnderPearlEntity) || !entity.isAlive()) return settle(null);
        return waiting();
    }

    private Step settle(String reason) {
        var player = body.entity();
        var after = InventoryEvidence.capture(body);
        int spent = count(before, Items.ENDER_PEARL) - count(after, Items.ENDER_PEARL);
        if (spent == 0 && conserved(after, 0) && pearl == null) {
            result = Step.blocked(reason != null ? reason : "The pearl was not thrown", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent != 1 || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Throw conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        Vec3d at = player.getPos();
        double moved = origin == null ? 0 : at.distanceTo(origin);
        var progress = new HashMap<String, String>(Map.of("toX", fmt(at.x), "toY", fmt(at.y), "toZ", fmt(at.z),
                "moved", fmt(moved), "teleported", String.valueOf(moved > 1.0)));
        if (meant != null) progress.put("off", fmt(Math.hypot(at.x - meant.x, at.z - meant.z)));
        result = Step.completed(Map.copyOf(progress));
        admission.verify(after.description() + "; " + result.progress(), "Native pearl throw verified");
        return waiting();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int count(InventoryEvidence evidence, Item item) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    /** At most {@code pearls} pearls gone and nothing else lost; pickups on the way, and the armour's wear, are fine. */
    private boolean conserved(InventoryEvidence after, int pearls) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == Items.ENDER_PEARL ? pearls : 0)) return false;
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
            int spent = count(before, Items.ENDER_PEARL) - count(after, Items.ENDER_PEARL);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native pearl throw verified");
            } else if (spent == 0 && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Throw cancelled before the pearl left the hand");
            } else if (spent == 1 && conserved(after, 1)) {
                admission.verify(after.description() + "; thrown, landing not watched", "Native pearl throw verified");
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
