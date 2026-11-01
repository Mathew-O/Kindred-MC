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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player fighting the Ender Dragon while it rests on the pillar in the middle of the
 * End, as players do: standing just in front of its head where the wings cannot fling it away, striking the
 * head when it can, jumping for harder hits, and stepping out of the dragon's breath. It lasts while the
 * dragon sits, twenty seconds at most.
 *
 * Technical: Recorded fight with the Ender Dragon while it perches on the exit portal's pillar (E-39d), as
 * players fight it: from a spot in front of its head, a little more than a block off it, where vanilla
 * 1.21.1's wings do not fling a player away (each wing's box widened by four) and the head's own touch does
 * not hurt (its box widened by one), striking the head whenever reach and the swing allow, jumping for
 * critical hits, and out of its breath the moment a cloud of it comes down by the player. One continuous
 * action for as long as the dragon sits, at most twenty seconds; it ends when the dragon takes off or dies.
 * Its only effects on owned items are the weapon's wear (or its breaking) and pickups. The head takes the
 * full blow; with the head out of reach a while, the neck or body is struck, for a quarter.
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
    /** Where it strikes from, kept up as the head turns; null until one is found. */
    private BlockPos spot;
    private ActionLease issuedLease;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private boolean halted, unknown, fighting;
    private int ticks, hits, headHits, crits, outOfReach;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    /** A fight with the given dragon from the given spot, with the weapon in a hotbar slot. */
    NativeDragonAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                       UUID dragonId, int slot, BlockPos spot) {
        this.body = body; this.lease = lease; this.writer = writer; this.dispatch = dispatch;
        this.dragonId = dragonId; this.slot = slot; this.spot = spot;
        if (!(body.entity().getServerWorld().getEntity(dragonId) instanceof EnderDragonEntity dragon) || !dragon.isAlive()) {
            throw new IllegalArgumentException("No dragon to fight");
        }
    }

    /** The dragon sits on its pillar: scanning for a player, about to roar and charge, or breathing on the portal. */
    static boolean perched(EnderDragonEntity dragon) {
        var type = dragon.getPhaseManager().getCurrent().getType();
        return type == PhaseType.SITTING_SCANNING || type == PhaseType.SITTING_ATTACKING || type == PhaseType.SITTING_FLAMING;
    }

    /** The dragon's body part with the given name (its head, neck, body and so on), or none. */
    static EnderDragonPart part(EnderDragonEntity dragon, String name) {
        for (var part : dragon.getBodyParts()) if (name.equals(part.name)) return part;
        return null;
    }

    /** Where the dragon's wings fling a player away, as vanilla reckons it each tick: each wing's box widened by four, lowered by two. */
    static List<Box> wingZones(EnderDragonEntity dragon) {
        var zones = new ArrayList<Box>(2);
        for (var part : dragon.getBodyParts()) if ("wing".equals(part.name)) zones.add(part.getBoundingBox().expand(4.0, 2.0, 4.0).offset(0.0, -2.0, 0.0));
        return zones;
    }

    /** Where touching the head hurts (its box widened by a block), with a little to spare. */
    static Box headZone(EnderDragonPart head) {
        return head.getBoundingBox().expand(1.3);
    }

    /** A player's box standing with its feet at {@code feet}. */
    private static Box standing(Vec3d feet) {
        return new Box(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
    }

    /** Distance from {@code from} to the nearest point of {@code box}. */
    private static double distance(Vec3d from, Box box) {
        double x = Math.clamp(from.x, box.minX, box.maxX), y = Math.clamp(from.y, box.minY, box.maxY), z = Math.clamp(from.z, box.minZ, box.maxZ);
        return from.distanceTo(new Vec3d(x, y, z));
    }

    /**
     * The spot nearest the player to strike the perched dragon's head from: ground a player stands on (never the
     * portal), the eyes within a sword's reach of the head, out of both wing zones, and clear of the head's touch even
     * at the top of a jump; empty when there is none.
     */
    static Optional<BlockPos> strikeSpot(net.minecraft.server.network.ServerPlayerEntity player, EnderDragonEntity dragon) {
        EnderDragonPart head = part(dragon, "head");
        if (head == null) return Optional.empty();
        var world = player.getServerWorld();
        var wings = wingZones(dragon);
        Box hurt = headZone(head), headBox = head.getBoundingBox();
        Vec3d at = headBox.getCenter();
        int hx = (int) Math.floor(at.x), hz = (int) Math.floor(at.z), hy = (int) Math.floor(at.y);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int y = hy + 1; y >= hy - 6; y--) {
            BlockPos feet = new BlockPos(hx + dx, y, hz + dz);
            if (!world.isChunkLoaded(feet)) break;
            var below = world.getBlockState(feet.down());
            if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) continue;
            if (below.isOf(net.minecraft.block.Blocks.END_PORTAL) || world.getBlockState(feet).isOf(net.minecraft.block.Blocks.END_PORTAL)) break;
            if (!below.isSideSolidFullSquare(world, feet.down(), net.minecraft.util.math.Direction.UP)) continue;
            // The first ground from the top down in this column: judged, and nothing under it looked at.
            Vec3d foot = Vec3d.ofBottomCenter(feet);
            Box box = standing(foot);
            boolean clear = !box.intersects(hurt) && !box.offset(0, 1.25, 0).intersects(hurt) && wings.stream().noneMatch(box::intersects);
            if (clear && distance(foot.add(0, 1.62, 0), headBox) <= 2.8) {
                double away = Math.hypot(foot.x - player.getX(), foot.z - player.getZ());
                if (away < bestDistance) {
                    bestDistance = away;
                    best = feet;
                }
            }
            break;
        }
        return Optional.ofNullable(best);
    }

    /** The nearest cloud of the dragon's breath within {@code reach} blocks of the player's feet, or null. */
    static AreaEffectCloudEntity breath(net.minecraft.server.network.ServerPlayerEntity player, double reach) {
        return player.getServerWorld().getEntitiesByClass(AreaEffectCloudEntity.class, player.getBoundingBox().expand(reach + 4, 3, reach + 4),
                        cloud -> cloud.isAlive() && cloud.getParticleType().getType() == ParticleTypes.DRAGON_BREATH
                                && Math.hypot(cloud.getX() - player.getX(), cloud.getZ() - player.getZ()) < cloud.getRadius() + reach)
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "hits", String.valueOf(hits)), false); }

    /** Moves the fight on by one tick. */
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
        // A full swing: anything short of it costs damage as its square (vanilla scales a hit by 0.2 + 0.8 * charge²).
        boolean ready = player.getAttackCooldownProgress(0.5f) >= 1.0f;
        Box hurt = head == null ? null : headZone(head);
        boolean tooClose = hurt != null && player.getBoundingBox().intersects(hurt);
        boolean flung = wingZones(dragon).stream().anyMatch(player.getBoundingBox()::intersects);
        // The head turns as the dragon looks about: the spot is found again every second, and at once if lost.
        if (head != null && (spot == null || ticks % 20 == 0)) spot = strikeSpot(player, dragon).orElse(spot);
        if (cloud != null) {
            // Out of the breath, along the safest heading away from the cloud's middle.
            float away = (float) Math.toDegrees(Math.atan2(-(player.getX() - cloud.getX()), player.getZ() - cloud.getZ()));
            moved = flee(navigator, away);
        } else if (tooClose || flung) {
            // A step back from the head (its touch hurts) or out from under a wing (it throws the player off).
            moved = body.move(issuedLease, -0.8f, 0, false, false, yaw);
        } else if (head != null && player.canInteractWithEntity(head, 0)) {
            outOfReach = 0;
            boolean airborne = !player.isOnGround();
            // A jump for a critical hit, only where the top of it stays clear of the head.
            boolean room = !player.getBoundingBox().offset(0, 1.25, 0).intersects(hurt);
            boolean jump = !airborne && room && player.getAttackCooldownProgress(0.5f) >= 0.5f && !player.isTouchingWater();
            moved = body.move(issuedLease, 0, 0, jump, false, yaw);
            boolean falling = airborne && player.getVelocity().y < 0 && player.fallDistance > 0;
            if (moved && ready && (falling || !room)) {
                boolean critical = falling && !player.isSprinting();
                if (body.strikeDragon(issuedLease, head, slot)) {
                    hits++;
                    headHits++;
                    if (critical) crits++;
                }
            }
        } else {
            // Head out of reach: to the spot in front of it; after a while, whatever part is in reach.
            outOfReach++;
            EnderDragonPart other = null;
            if (outOfReach > HEAD_PATIENCE) {
                for (var part : dragon.getBodyParts()) if (!"wing".equals(part.name) && player.canInteractWithEntity(part, 0)) { other = part; break; }
            }
            if (other != null && ready) {
                moved = body.move(issuedLease, 0, 0, false, false, yaw);
                if (moved && body.strikeDragon(issuedLease, other, slot)) hits++;
            } else if (spot != null && Math.hypot(spot.getX() + 0.5 - player.getX(), spot.getZ() + 0.5 - player.getZ()) > 0.35) {
                double sx = spot.getX() + 0.5 - player.getX(), sz = spot.getZ() + 0.5 - player.getZ();
                float toSpot = (float) Math.toDegrees(Math.atan2(-sx, sz));
                boolean climb = player.isOnGround() && (player.horizontalCollision || spot.getY() > player.getY() + 0.5);
                moved = navigator.safeHeading(player, toSpot)
                        ? body.move(issuedLease, Math.hypot(sx, sz) > 1.0 ? 1.0f : 0.4f, 0, climb, false, toSpot)
                        : body.move(issuedLease, 0, 0, false, false, yaw);
            } else {
                moved = body.move(issuedLease, 0, 0, false, false, yaw);
            }
            if (outOfReach > HEAD_PATIENCE * 3) { settle(spot == null ? "No spot to strike the head from" : "The head stayed out of reach", true); return waiting(); }
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

    /** Lost nothing but wear (one worn-out weapon, tool, armor piece or shield may break); anything gained was picked up. */
    private boolean conserved(InventoryEvidence after) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) {
            if (entry.getValue() <= 0) continue;
            // One worn-out item broke: whatever it struck with, held from the hotbar (a pickaxe, with no sword), or the
            // armour or shield it was struck through. Counted as lost, not as an outcome to reconcile: in the live run
            // on a286cc8, Sol's stone pickaxe broke on its last blow, and its body was taken out for the rest of the run.
            boolean brokenWorn = entry.getValue() == 1 && java.util.stream.IntStream.range(0, before.stacks().length)
                    .anyMatch(i -> (i == slot || i < 9 || i >= 36) && before.stacks()[i].isOf(entry.getKey()) && before.stacks()[i].isDamageable());
            if (!brokenWorn) return false;
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
