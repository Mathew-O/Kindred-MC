package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.tactics.Ballistics;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One arrow from an owned bow at one target, as a player shoots (E-39b): the bow is drawn for a full second with the aim
 * kept on the target, on the low arc vanilla's arrow flies ({@link Ballistics}) and ahead of a target that moves, then
 * loosed, and the arrow watched until it lands. Exactly one arrow is spent under the durable ledger and the bow wears
 * by one; the step reports whether the target fell. The player stands still while it draws, as a moving shooter's arrow
 * is thrown off.
 */
final class NativeShootAction extends NativeRecordedAction {
    /** Ticks of draw: a bow is at full strength after twenty. */
    static final int DRAW_TICKS = 21;
    private static final int WATCH_TICKS = 80;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final UUID targetId;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown, drawing, loosed;
    private int drawn, watched;
    private UUID arrow;
    private Vec3d lastTarget, targetMotion = Vec3d.ZERO, landed;
    private CompletableFuture<ActionRecord> flush;

    NativeShootAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                      int slot, UUID targetId) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.targetId = targetId;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A bow is shot from an owned hotbar slot 0..8");
    }

    private Entity target() {
        Entity entity = body.entity().getServerWorld().getEntity(targetId);
        return entity != null && entity.isAlive() && !entity.isRemoved() ? entity : null;
    }

    /** Where on the target to aim: a crystal's middle, the dragon's body. */
    private static Vec3d aimPoint(Entity target) {
        if (target instanceof EnderDragonEntity dragon) {
            for (var part : dragon.getBodyParts()) if ("body".equals(part.name)) return part.getBoundingBox().getCenter();
        }
        return target.getBoundingBox().getCenter();
    }

    /** The yaw and the pitch that put the arrow on the target, led by its motion over the flight; null if out of reach. */
    private float[] aim(Entity target) {
        var player = body.entity();
        Vec3d start = new Vec3d(player.getX(), player.getEyeY() - 0.1, player.getZ());
        Vec3d at = aimPoint(target);
        Optional<Ballistics.Shot> shot = Optional.empty();
        for (int pass = 0; pass < 3; pass++) {
            double dx = at.x - start.x, dz = at.z - start.z;
            shot = Ballistics.aim(Math.hypot(dx, dz), at.y - start.y, Ballistics.FULL_DRAW);
            if (shot.isEmpty()) return null;
            at = aimPoint(target).add(targetMotion.multiply(shot.get().ticks()));
        }
        double dx = at.x - start.x, dz = at.z - start.z;
        return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)), shot.get().pitch()};
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded shot cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (!player.getInventory().getStack(slot).isOf(Items.BOW)) return Step.blocked("The hotbar slot holds no bow", true);
            if (player.getInventory().count(Items.ARROW) == 0) return Step.blocked("No arrows", true);
            if (target() == null) return Step.blocked("Nothing to shoot", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_shoot",
                    "slot=" + slot + "; target=" + targetId), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable shot acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Shot close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        Entity target = target();
        if (target != null) {
            Vec3d now = aimPoint(target);
            if (lastTarget != null) targetMotion = now.subtract(lastTarget);
            lastTarget = now;
        }
        if (!drawing) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!before.matches(body) || !player.isAlive()) {
                result = Step.blocked("The inventory changed before the draw; nothing shot", true);
                flush = admission.close();
                return waiting();
            }
            float[] aim = target == null ? null : aim(target);
            if (aim == null) {
                result = Step.blocked(target == null ? "The target is gone" : "The target is out of the bow's reach", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.drawBow(lease.get(), slot, aim[0], aim[1]) || !player.isUsingItem()) return settle("The bow could not be drawn");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Draw interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            drawing = true;
            return waiting();
        }
        if (!loosed) {
            if (!player.isUsingItem() || !player.getActiveItem().isOf(Items.BOW)) return settle("The draw was broken off");
            float[] aim = target == null ? null : aim(target);
            if (aim != null) body.aimBow(lease.get(), aim[0], aim[1]);
            if (++drawn < DRAW_TICKS) return waiting();
            // Gone or out of reach while drawing: let go all the same, as a player does, and the arrow is spent.
            if (!body.looseBow(lease.get())) return settle("The arrow could not be loosed");
            loosed = true;
            var world = player.getServerWorld();
            var fresh2 = world.getEntitiesByClass(PersistentProjectileEntity.class, new Box(player.getEyePos(), player.getEyePos()).expand(4),
                    projectile -> projectile.getOwner() == player && projectile.age <= 2);
            if (!fresh2.isEmpty()) arrow = fresh2.getFirst().getUuid();
            return waiting();
        }
        var world = player.getServerWorld();
        Entity flying = arrow == null ? null : world.getEntity(arrow);
        if (flying != null) landed = flying.getPos();
        boolean down = flying == null || flying.isRemoved() || flying instanceof PersistentProjectileEntity p && p.isOnGround();
        if (++watched >= WATCH_TICKS || down || target() == null) return settle(null);
        return waiting();
    }

    private Step settle(String failure) {
        body.stopInputs();
        var after = InventoryEvidence.capture(body);
        int spent = count(before, Items.ARROW) - count(after, Items.ARROW);
        if (spent == 0 && conserved(after, 0) && !loosed) {
            result = Step.blocked(failure != null ? failure : "Nothing was shot", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent > 1 || spent < 0 || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Shot conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        boolean hit = target() == null;
        var progress = new HashMap<String, String>(Map.of("shots", "1", "hit", String.valueOf(hit)));
        if (landed != null) progress.put("landed", BlockPos.ofFloored(landed).toShortString());
        result = Step.completed(Map.copyOf(progress));
        admission.verify(after.description() + "; one arrow loosed" + (hit ? "; the target fell" : ""), "Native shot verified");
        return waiting();
    }

    private static int count(InventoryEvidence evidence, Item item) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    /** At most {@code arrows} arrows gone and nothing else lost (the bow wears, and may break). */
    private boolean conserved(InventoryEvidence after, int arrows) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) {
            int allowed = entry.getKey() == Items.ARROW ? arrows : entry.getKey() == Items.BOW ? 1 : 0;
            if (entry.getValue() > allowed) return false;
        }
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || loosed) return false;
        return intent == NativePlayerBody.Intent.SHOOT || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
        body.stopInputs();
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            var after = InventoryEvidence.capture(body);
            int spent = count(before, Items.ARROW) - count(after, Items.ARROW);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native shot verified");
            } else if (spent == 0 && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Shot cancelled before the arrow was loosed");
            } else if (spent == 1 && conserved(after, 1)) {
                admission.verify(after.description() + "; one arrow loosed, its landing not watched", "Native shot verified");
            } else {
                unknown = true;
                admission.uncertain("Shot cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
