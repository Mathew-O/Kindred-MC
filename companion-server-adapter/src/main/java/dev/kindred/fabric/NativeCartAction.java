package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MinecartItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A minecart put on a rail and broken again, as a player brings a villager home in one (E-45e). {@code place} puts one
 * owned minecart on the rail at a block within reach: one minecart item is spent under the ledger, and the cart is then
 * in the world on that rail. {@code break} hits a cart within reach until it breaks, setting down whoever rides it, so
 * that it drops as an item to be picked up again; nothing owned is lost. Pushing it is {@link NativePushSkill}'s.
 */
final class NativeCartAction extends NativeRecordedAction {
    enum Mode { PLACE, BREAK }

    /** Ticks to see the cart on its rail, and to break one. */
    private static final int PLACE_TICKS = 5, BREAK_TICKS = 200;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Mode mode;
    private final int slot;
    private final BlockPos rail;
    private UUID cart;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private BlockPos cartAt;
    private int waited;
    private boolean started, halted, unknown;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeCartAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     Mode mode, int slot, BlockPos rail, UUID cart) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.mode = mode;
        this.slot = slot;
        this.rail = rail;
        this.cart = cart;
        if (mode == Mode.PLACE && (slot < 0 || slot > 8 || rail == null)) throw new IllegalArgumentException("A cart is put down from an owned hotbar slot 0..8 onto a rail");
        if (mode == Mode.BREAK && cart == null) throw new IllegalArgumentException("A cart to break");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded cart step cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (mode == Mode.PLACE && !(player.getInventory().getStack(slot).getItem() instanceof MinecartItem)) {
                return Step.blocked("The hotbar slot holds no minecart", true);
            }
            if (mode == Mode.BREAK && target() == null) return Step.blocked("No cart there to break", true);
            before = InventoryEvidence.capture(body);
            String what = mode == Mode.PLACE ? "slot=" + slot + "; rail=" + rail.toShortString() : "cart=" + cart;
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_cart",
                    "mode=" + mode.name().toLowerCase(Locale.ROOT) + "; " + what), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable cart acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Cart close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!started) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!before.matches(body) || !player.isAlive()) {
                result = Step.blocked("Owned inventory changed before the cart step; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            started = true;
            if (mode == Mode.PLACE) return place();
        }
        return mode == Mode.PLACE ? placed() : strike();
    }

    private Step place() {
        try {
            if (!body.placeCart(lease.get(), slot, rail)) return settle("Placing authority denied", null);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Placing interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        waited = 0;
        return placed();
    }

    /** On its rail within a few ticks (vanilla puts it there at once), or none, said plainly. */
    private Step placed() {
        var carts = body.entity().getServerWorld().getEntitiesByClass(AbstractMinecartEntity.class, new Box(rail).expand(0.5),
                c -> c.isAlive() && c.age <= PLACE_TICKS + 2 && !c.hasPassengers());
        if (!carts.isEmpty()) {
            cart = carts.getFirst().getUuid();
            return settle(null, Map.of("cart", cart.toString()));
        }
        if (++waited < PLACE_TICKS) return waiting();
        return settle("The cart did not go on the rail", null);
    }

    /** Hit until it breaks: it drops as an item where it was, its rider set down. */
    private Step strike() {
        var player = body.entity();
        var target = target();
        if (target == null) {
            var drops = player.getServerWorld().getEntitiesByClass(ItemEntity.class, new Box(cartAt == null ? player.getBlockPos() : cartAt).expand(4),
                    item -> item.isAlive() && item.getStack().getItem() instanceof MinecartItem);
            if (!drops.isEmpty()) {
                var drop = drops.getFirst().getBlockPos();
                return settle(null, Map.of("dropX", String.valueOf(drop.getX()), "dropY", String.valueOf(drop.getY()), "dropZ", String.valueOf(drop.getZ())));
            }
            return settle(null, Map.of("picked", "true"));
        }
        cartAt = target.getBlockPos();
        if (++waited > BREAK_TICKS) return settle("The cart did not break", null);
        if (!player.canInteractWithEntity(target, 0)) return settle("The cart is out of reach", null);
        if (player.getAttackCooldownProgress(0.5f) >= 1f) body.strikeCart(lease.get(), target);
        return waiting();
    }

    private AbstractMinecartEntity target() {
        return cart != null && body.entity().getServerWorld().getEntity(cart) instanceof AbstractMinecartEntity c && c.isAlive() ? c : null;
    }

    /** Ends the step, checking the inventory: a placing spends exactly one minecart; nothing else is ever lost. */
    private Step settle(String failure, Map<String, String> progress) {
        var after = InventoryEvidence.capture(body);
        int spent = carts(before) - carts(after);
        boolean put = mode == Mode.PLACE && failure == null;
        if (!conserved(after, put ? 1 : 0) || put && spent != 1 || !put && spent > 0) {
            unknown = true;
            result = Step.blocked("Cart conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        if (failure != null) {
            result = Step.blocked(failure, true);
            admission.noEffect(before.description() + "; nothing owned lost", failure);
            return waiting();
        }
        result = Step.completed(progress);
        admission.verify(after.description() + "; " + progress, "Native cart step verified");
        return waiting();
    }

    private static int carts(InventoryEvidence evidence) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.getItem() instanceof MinecartItem) total += stack.getCount();
        return total;
    }

    /** At most {@code spentCarts} minecarts gone and nothing else lost; pickups on the way are fine. */
    private boolean conserved(InventoryEvidence after, int spentCarts) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        int cartsLost = 0;
        for (var entry : lost.entrySet()) {
            if (entry.getKey() instanceof MinecartItem) cartsLost += Math.max(0, entry.getValue());
            else if (entry.getValue() > 0) return false;
        }
        return cartsLost <= spentCarts;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || result != null) return false;
        return intent == NativePlayerBody.Intent.CART;
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
            int spent = before == null ? 0 : carts(before) - carts(after);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native cart step verified");
            } else if (before != null && spent == 0 && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Cart step cancelled with nothing spent");
            } else if (before != null && spent == 1 && conserved(after, 1) && mode == Mode.PLACE) {
                admission.verify(after.description() + "; put down, not seen on its rail", "Native cart placing verified");
            } else {
                unknown = true;
                admission.uncertain("Cart step cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
