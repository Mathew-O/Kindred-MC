package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recorded fight with the Ender Dragon while it perches on the exit portal's pillar (E-39d), as players fight it: in
 * under its head and strike the head whenever reach and the swing allow, jumping for critical hits, and out of its
 * breath the moment a cloud of it comes down by the player. One continuous action for as long as the dragon sits, at
 * most twenty seconds; it ends when the dragon takes off or dies. Its only effects on owned items are the weapon's wear
 * (or its breaking) and pickups. The head takes the full blow; with the head out of reach a while, the neck or body is
 * struck, for a quarter.
 */
final class NativeDragonAction extends NativeRecordedAction {
    static final int MAX_TICKS = 400;
    private static final int HEAD_PATIENCE = 60;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final UUID dragonId;
    private final int slot;
    private ActionLease issuedLease;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private boolean halted, unknown, fighting;
    private int ticks, hits, headHits, crits, outOfReach;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeDragonAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                       UUID dragonId, int slot) {
        this.body = body; this.lease = lease; this.writer = writer; this.dispatch = dispatch;
        this.dragonId = dragonId; this.slot = slot;
        if (!(body.entity().getServerWorld().getEntity(dragonId) instanceof EnderDragonEntity dragon) || !dragon.isAlive()) {
            throw new IllegalArgumentException("No dragon to fight");
        }
    }

    /** The dragon sits on its pillar: scanning for a player, about to roar and charge, or breathing on the portal. */
    static boolean perched(EnderDragonEntity dragon) {
        var type = dragon.getPhaseManager().getCurrent().getType();
        return type == PhaseType.SITTING_SCANNING || type == PhaseType.SITTING_ATTACKING || type == PhaseType.SITTING_FLAMING;
    }

    static EnderDragonPart part(EnderDragonEntity dragon, String name) {
        for (var part : dragon.getBodyParts()) if (name.equals(part.name)) return part;
        return null;
    }

    /** The nearest cloud of the dragon's breath within {@code reach} blocks of the player's feet, or null. */
    static AreaEffectCloudEntity breath(net.minecraft.server.network.ServerPlayerEntity player, double reach) {
        return player.getServerWorld().getEntitiesByClass(AreaEffectCloudEntity.class, player.getBoundingBox().expand(reach + 4, 3, reach + 4),
                        cloud -> cloud.isAlive() && cloud.getParticleType().getType() == ParticleTypes.DRAGON_BREATH
                                && Math.hypot(cloud.getX() - player.getX(), cloud.getZ() - player.getZ()) < cloud.getRadius() + reach)
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "hits", String.valueOf(hits)), false); }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded fight never replays", true);
        if (admission == null) {
            issuedLease = lease.get();
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_dragon",
                    "dragon=" + dragonId + "; slot=" + slot), before.description(), writer, dispatch);
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
        var entity = player.getServerWorld().getEntity(dragonId);
        EnderDragonEntity dragon = entity instanceof EnderDragonEntity d && d.isAlive() && !d.isRemoved() ? d : null;
        if (admission.phase() == ActionAdmission.Phase.READY) {
            if (dragon == null || !perched(dragon)) {
                result = Step.blocked(dragon == null ? "The dragon is gone" : "The dragon is not on its perch", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            fighting = true;
            if (slot >= 0 && slot < 9) body.selectHotbar(issuedLease, slot);
        }
        if (admission.phase() != ActionAdmission.Phase.ISSUED || !fighting) return waiting();
        if (dragon == null || dragon.getPhaseManager().getCurrent().getType() == PhaseType.DYING) { settle("The dragon is dying", true); return waiting(); }
        if (!perched(dragon)) { settle("The dragon took off", true); return waiting(); }
        if (++ticks > MAX_TICKS) { settle("The fight on the perch went on long enough", true); return waiting(); }

        var navigator = new NativeNavigator(player.getServerWorld());
        boolean moved;
        var cloud = breath(player, 1.0);
        EnderDragonPart head = part(dragon, "head");
        Vec3d aim = head == null ? dragon.getPos() : head.getBoundingBox().getCenter();
        double dx = aim.x - player.getX(), dz = aim.z - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        boolean ready = player.getAttackCooldownProgress(0.5f) >= 0.9f;
        if (cloud != null) {
            // Out of the breath, along the safest heading away from the cloud's middle.
            float away = (float) Math.toDegrees(Math.atan2(-(player.getX() - cloud.getX()), player.getZ() - cloud.getZ()));
            moved = flee(navigator, away);
        } else if (head != null && player.canInteractWithEntity(head, 0)) {
            outOfReach = 0;
            boolean airborne = !player.isOnGround();
            boolean jump = !airborne && player.getAttackCooldownProgress(0.5f) >= 0.5f && !player.isTouchingWater();
            moved = body.move(issuedLease, 0, 0, jump, false, yaw);
            boolean falling = airborne && player.getVelocity().y < 0 && player.fallDistance > 0;
            if (moved && ready && falling) {
                boolean critical = !player.isSprinting();
                if (body.strikeDragon(issuedLease, head, slot)) {
                    hits++;
                    headHits++;
                    if (critical) crits++;
                }
            }
        } else {
            // Head out of reach: in under it; after a while, whatever part is in reach.
            outOfReach++;
            EnderDragonPart other = null;
            if (outOfReach > HEAD_PATIENCE) {
                for (var part : dragon.getBodyParts()) if (player.canInteractWithEntity(part, 0)) { other = part; break; }
            }
            if (other != null && ready) {
                moved = body.move(issuedLease, 0, 0, false, false, yaw);
                if (moved && body.strikeDragon(issuedLease, other, slot)) hits++;
            } else if (navigator.safeHeading(player, yaw)) {
                boolean climb = player.isOnGround() && player.horizontalCollision;
                moved = body.move(issuedLease, Math.hypot(dx, dz) > 0.8 ? 1.0f : 0, 0, climb, false, yaw);
            } else {
                moved = body.move(issuedLease, 0, 0, false, false, yaw);
            }
        }
        if (!moved) settle("Movement authority denied", false);
        return Step.running(Map.of("hits", String.valueOf(hits), "head", String.valueOf(headHits)), true);
    }

    private boolean flee(NativeNavigator navigator, float away) {
        var player = body.entity();
        for (float turn : new float[]{0, 35, -35, 70, -70, 105, -105}) {
            float yaw = away + turn;
            if (!navigator.safeHeading(player, yaw)) continue;
            return body.move(issuedLease, 1.0f, 0, player.isOnGround() && player.horizontalCollision, true, yaw);
        }
        return body.move(issuedLease, 0, 0, false, false, away);
    }

    /** Lost nothing but weapon wear (a worn-out weapon may break); anything gained was picked up. */
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

    private void settle(String reason, boolean done) {
        body.stopInputs();
        if (hits > 0) KindredMod.LOGGER.info("{} dragon fight: {} hits, {} on the head, {} critical - {}", body.entity().getName().getString(),
                hits, headHits, crits, reason);
        var after = InventoryEvidence.capture(body);
        if (conserved(after)) {
            admission.verify(after.description() + "; hits=" + hits + "; head=" + headHits + "; crits=" + crits, reason);
            result = done ? Step.completed(Map.of("hits", String.valueOf(hits), "head", String.valueOf(headHits), "outcome", reason))
                    : Step.blocked(reason, true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; owned items changed beyond wear and pickups");
            result = Step.blocked("Dragon fight outcome requires reconciliation", true);
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
