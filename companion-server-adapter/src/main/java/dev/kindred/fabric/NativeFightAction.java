package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recorded fight with one mob: chase it with ordinary movement and strike with vanilla attacks whenever reach and
 * the attack cooldown allow, until it dies, gets away or the bounded time runs out. One continuous action, as a
 * player fights, rather than a walk and a single swing per task while a skeleton keeps shooting. The only effects
 * a fight may have on owned items are weapon and armor wear (or the weapon breaking) and drops picked up on the way.
 * The chase never steps into lava, fire or a drop deeper than the navigator allows; when that leaves no way to the
 * target, the player backs away from the edge before giving up. A creeper is fought hit and run, backing off while
 * the cooldown recovers, and any creeper that starts to hiss nearby is run from.
 */
final class NativeFightAction extends NativeRecordedAction {
    static final int MAX_TICKS = 300;
    private static final double STRIKE_RANGE = 2.8;
    /** A hissing creeper this close is run from; it stops its fuse once the player is beyond seven blocks. */
    private static final double CREEPER_FLEE = 7.5;
    private static final int UNSAFE_TICKS = 40;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final UUID targetId;
    private final int slot;
    private ActionLease issuedLease;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private boolean halted, unknown, fighting;
    private int ticks, unseen, hits, unsafe;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeFightAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                      UUID targetId, int slot) {
        this.body = body; this.lease = lease; this.writer = writer; this.dispatch = dispatch;
        this.targetId = targetId; this.slot = slot;
        if (!(body.entity().getServerWorld().getEntity(targetId) instanceof LivingEntity target) || !target.isAlive()) {
            throw new IllegalArgumentException("Nothing to fight");
        }
    }

    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "hits", String.valueOf(hits)), false); }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded fight never replays", true);
        if (admission == null) {
            issuedLease = lease.get();
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_fight",
                    "target=" + targetId + "; slot=" + slot), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable fight acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Fight close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        var player = body.entity();
        var entity = player.getServerWorld().getEntity(targetId);
        LivingEntity target = entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
        if (admission.phase() == ActionAdmission.Phase.READY) {
            if (target == null) {
                result = Step.blocked("The target is gone; nothing to fight", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            fighting = true;
            if (slot >= 0 && slot < 9) body.selectHotbar(issuedLease, slot);
        }
        if (admission.phase() != ActionAdmission.Phase.ISSUED || !fighting) return waiting();
        if (target == null) { settle("The target was defeated or is gone", true); return waiting(); }
        if (++ticks > MAX_TICKS || player.squaredDistanceTo(target) > 24 * 24) { settle("The fight broke off", false); return waiting(); }
        boolean visible = player.canSee(target);
        unseen = visible ? 0 : unseen + 1;
        if (unseen > 60) { settle("Lost sight of the target", false); return waiting(); }

        double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double distance = player.distanceTo(target);
        boolean ready = player.getAttackCooldownProgress(0.5f) >= 0.9f;
        var navigator = new NativeNavigator(player.getServerWorld());
        CreeperEntity hissing = hissingCreeper(player);
        boolean moved;
        if (hissing != null) {
            moved = flee(navigator, hissing);
        } else if (target instanceof CreeperEntity && !ready && distance < 4.0) {
            // Hit and run: out of its reach while the swing recovers, so it never gets close enough to light.
            moved = flee(navigator, target);
        } else if (distance > STRIKE_RANGE || !visible) {
            if (navigator.safeHeading(player, yaw)) {
                unsafe = 0;
                boolean climb = player.isOnGround() && (player.horizontalCollision || target.getY() > player.getY() + 0.6 && Math.hypot(dx, dz) < 1.5);
                moved = body.move(issuedLease, 1.0f, 0, climb || player.isTouchingWater(), false, yaw);
            } else {
                // Cannot get at it from here (a ledge, lava between): step back from the edge, where a hit or an
                // arrow could knock the player over, until giving up on this fight.
                moved = flee(navigator, target);
                if (++unsafe > UNSAFE_TICKS) { settle("The way to the target is unsafe", false); return waiting(); }
            }
        } else {
            moved = body.move(issuedLease, 0, 0, false, false, yaw);
            if (moved && ready && body.attack(issuedLease, targetId, slot)) hits++;
        }
        if (!moved) settle("Movement authority denied", false);
        return Step.running(Map.of("hits", String.valueOf(hits), "distance", String.format("%.1f", distance)), true);
    }

    /** The nearest creeper within flee range whose fuse is burning. */
    private static CreeperEntity hissingCreeper(net.minecraft.server.network.ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(CreeperEntity.class, player.getBoundingBox().expand(CREEPER_FLEE),
                        creeper -> creeper.isAlive() && creeper.getFuseSpeed() > 0 && player.distanceTo(creeper) < CREEPER_FLEE)
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    /** Sprint away from {@code threat} along the safest heading near straight away from it; stand if none is safe. */
    private boolean flee(NativeNavigator navigator, net.minecraft.entity.Entity threat) {
        var player = body.entity();
        float away = (float) Math.toDegrees(Math.atan2(-(player.getX() - threat.getX()), player.getZ() - threat.getZ()));
        for (float turn : new float[]{0, 35, -35, 70, -70, 105, -105}) {
            float yaw = away + turn;
            if (!navigator.safeHeading(player, yaw)) continue;
            return body.move(issuedLease, 1.0f, 0, player.isOnGround() && player.horizontalCollision || player.isTouchingWater(), true, yaw);
        }
        return body.move(issuedLease, 0, 0, false, false, away);
    }

    /** Lost nothing but weapon or armor wear (a worn-out weapon may break); anything gained was picked up. */
    private boolean conserved(InventoryEvidence after) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) {
            if (entry.getValue() <= 0) continue;
            boolean brokenWeapon = entry.getValue() == 1 && slot >= 0 && slot < before.stacks().length
                    && before.stacks()[slot].isOf(entry.getKey()) && before.stacks()[slot].isDamageable();
            if (!brokenWeapon) return false;
        }
        return true;
    }

    private void settle(String reason, boolean won) {
        body.stopInputs();
        var after = InventoryEvidence.capture(body);
        if (conserved(after)) {
            admission.verify(after.description() + "; hits=" + hits, reason);
            result = won ? Step.completed(Map.of("hits", String.valueOf(hits), "outcome", reason)) : Step.blocked(reason, true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; owned items changed beyond wear and pickups");
            result = Step.blocked("Fight outcome requires reconciliation", true);
        }
    }

    @Override boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || !fighting || result != null) return false;
        return intent == NativePlayerBody.Intent.MOVE || intent == NativePlayerBody.Intent.ATTACK || intent == NativePlayerBody.Intent.SELECT_SLOT;
    }
    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    @Override CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) settle("The fight was interrupted", false);
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
