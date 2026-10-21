package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.BoatItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
 * A boat taken onto the water and back, as a player crosses water with one. {@code launch} puts one owned boat on the
 * water within reach and gets in: one boat item is spent under the ledger, and the boat is then in the world. {@code
 * board} gets into an empty boat within reach. {@code land} gets out and breaks the boat with ordinary hits, so it
 * drops as an item to be picked up again; nothing owned is lost. Rowing is {@link NativeRowSkill}'s, between them.
 */
final class NativeBoatAction extends NativeRecordedAction {
    enum Mode { LAUNCH, BOARD, LAND }

    /** Ticks to wait for vanilla to set the player down beside the boat, and to break it. */
    private static final int LEAVE_TICKS = 20, BREAK_TICKS = 160;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Mode mode;
    private final int slot;
    private final BlockPos water;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private UUID boat;
    private BlockPos boatAt;
    private int waited;
    private boolean started, halted, unknown, left;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeBoatAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     Mode mode, int slot, BlockPos water) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.mode = mode;
        this.slot = slot;
        this.water = water;
        if (mode == Mode.LAUNCH && (slot < 0 || slot > 8 || water == null)) throw new IllegalArgumentException("A launch needs an owned hotbar slot 0..8 and a water cell");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded boat step cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            switch (mode) {
                case LAUNCH -> {
                    if (!(player.getInventory().getStack(slot).getItem() instanceof BoatItem)) return Step.blocked("The hotbar slot holds no boat", true);
                    if (player.hasVehicle()) return Step.blocked("Already in a boat", true);
                }
                case BOARD -> {
                    if (player.hasVehicle()) return Step.blocked("Already in a boat", true);
                    if (nearestEmptyBoat() == null) return Step.blocked("No empty boat within reach", true);
                }
                case LAND -> {
                    if (body.boat().isEmpty()) return Step.blocked("Not in a boat", true);
                }
            }
            before = InventoryEvidence.capture(body);
            String target = mode == Mode.LAUNCH ? "slot=" + slot + "; water=" + water.toShortString() : "near=" + player.getBlockPos().toShortString();
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_boat",
                    "mode=" + mode.name().toLowerCase(Locale.ROOT) + "; " + target), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable boat acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Boat close outcome uncertain", true);
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
                result = Step.blocked("Owned inventory changed before the boat step; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            started = true;
            return switch (mode) {
                case LAUNCH -> launch();
                case BOARD -> board(nearestEmptyBoat());
                case LAND -> leave();
            };
        }
        return switch (mode) {
            case LAUNCH, BOARD -> settleAboard();
            case LAND -> land();
        };
    }

    private Step launch() {
        try {
            if (!body.launchBoat(lease.get(), slot, water)) return settle("Launch authority denied", null);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Launch interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        var placed = body.entity().getServerWorld().getEntitiesByClass(BoatEntity.class, new Box(water).expand(2.5),
                b -> b.isAlive() && b.age <= 2 && !b.hasPassengers());
        if (placed.isEmpty()) return settle("The boat did not go on the water", null);
        return board(placed.getFirst());
    }

    private Step board(BoatEntity target) {
        if (target == null) return settle("No empty boat within reach", null);
        boat = target.getUuid();
        boatAt = target.getBlockPos();
        body.board(lease.get(), target);
        waited = 0;
        return waiting();
    }

    /** Aboard within a few ticks (vanilla seats a rider at once), or the boat is left on the water, said plainly. */
    private Step settleAboard() {
        var current = body.boat();
        if (current.isPresent() && current.get().getUuid().equals(boat)) return settle(null, Map.of("boat", boat.toString(), "aboard", "true"));
        if (++waited < 5) return waiting();
        return settle(mode == Mode.LAUNCH ? "On the water but not aboard" : "Could not get in", boat == null ? null : Map.of("boat", boat.toString(), "aboard", "false"));
    }

    private Step leave() {
        var current = body.boat().orElse(null);
        if (current == null) return settle("Not in a boat", null);
        boat = current.getUuid();
        boatAt = current.getBlockPos();
        if (!body.leaveBoat(lease.get(), towardLand(current))) return settle("Leaving authority denied", null);
        waited = 0;
        return waiting();
    }

    /** Out of the boat, then hit it until it breaks: it drops as an item, where the step says. */
    private Step land() {
        var player = body.entity();
        var world = player.getServerWorld();
        if (!left) {
            if (player.hasVehicle()) {
                if (++waited > LEAVE_TICKS) return settle("Could not get out of the boat", null);
                return waiting();
            }
            left = true;
            waited = 0;
        }
        var entity = world.getEntity(boat);
        if (!(entity instanceof BoatEntity target) || !target.isAlive()) {
            // Broken: its item lies where it was, or is already picked up.
            var drops = world.getEntitiesByClass(ItemEntity.class, new Box(boatAt).expand(4),
                    item -> item.isAlive() && item.getStack().getItem() instanceof BoatItem);
            if (!drops.isEmpty()) {
                var drop = drops.getFirst().getBlockPos();
                return settle(null, Map.of("dropX", String.valueOf(drop.getX()), "dropY", String.valueOf(drop.getY()), "dropZ", String.valueOf(drop.getZ())));
            }
            return settle(null, Map.of("picked", "true"));
        }
        boatAt = target.getBlockPos();
        if (++waited > BREAK_TICKS || !player.canInteractWithEntity(target, 0)) {
            return settle(null, Map.of("left", boatAt.toShortString())); // Out, with the boat left on the water.
        }
        if (player.getAttackCooldownProgress(0.5f) >= 1f) body.strikeBoat(lease.get(), target);
        return waiting();
    }

    /** Ends the step, checking the inventory: a launch spends exactly one boat; nothing else is ever lost. */
    private Step settle(String failure, Map<String, String> progress) {
        var after = InventoryEvidence.capture(body);
        int spent = boats(before) - boats(after);
        boolean launched = mode == Mode.LAUNCH && boat != null;
        if (!conserved(after, launched ? 1 : 0) || launched && spent != 1 || !launched && spent > 0) {
            unknown = true;
            result = Step.blocked("Boat conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        if (failure != null && !launched && mode != Mode.LAND) {
            result = Step.blocked(failure, true);
            admission.noEffect(before.description() + "; nothing owned lost", failure);
            return waiting();
        }
        result = failure != null && progress == null ? Step.blocked(failure, true)
                : failure != null ? Step.blocked(failure + " (" + progress + ")", true) : Step.completed(progress);
        admission.verify(after.description() + "; " + (progress == null ? failure : progress), "Native boat step verified");
        return waiting();
    }

    private static int boats(InventoryEvidence evidence) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.getItem() instanceof BoatItem) total += stack.getCount();
        return total;
    }

    /** At most {@code spentBoats} boats gone and nothing else lost; pickups on the way are fine. */
    private boolean conserved(InventoryEvidence after, int spentBoats) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        int boatsLost = 0;
        for (var entry : lost.entrySet()) {
            if (entry.getKey() instanceof BoatItem) boatsLost += Math.max(0, entry.getValue());
            else if (entry.getValue() > 0) return false;
        }
        return boatsLost <= spentBoats;
    }

    /** The way to the nearest dry ground beside the boat, or the way the boat faces when there is none. */
    private float towardLand(BoatEntity boat) {
        var world = body.entity().getServerWorld();
        var navigator = new NativeNavigator(world);
        var at = boat.getPos();
        for (int k = 0; k < 16; k++) {
            float yaw = boat.getYaw() + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * 22.5f;
            double r = Math.toRadians(yaw);
            BlockPos stand = BlockPos.ofFloored(at.x - Math.sin(r) * 1.3, boat.getBoundingBox().maxY, at.z + Math.cos(r) * 1.3);
            for (BlockPos feet : new BlockPos[] {stand, stand.down()}) {
                if (navigator.standable(feet) && world.getFluidState(feet).isEmpty() && world.getFluidState(feet.down()).isEmpty()) return yaw;
            }
        }
        return boat.getYaw();
    }

    private BoatEntity nearestEmptyBoat() {
        var player = body.entity();
        return player.getServerWorld().getEntitiesByClass(BoatEntity.class, player.getBoundingBox().expand(3),
                        b -> b.isAlive() && !b.hasPassengers() && player.canInteractWithEntity(b, 0))
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || result != null) return false;
        return intent == NativePlayerBody.Intent.BOAT || intent == NativePlayerBody.Intent.SELECT_SLOT
                || mode == Mode.LAND && intent == NativePlayerBody.Intent.ATTACK;
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
            int spent = before == null ? 0 : boats(before) - boats(after);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native boat step verified");
            } else if (before != null && spent == 0 && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Boat step cancelled with nothing spent");
            } else if (before != null && spent == 1 && conserved(after, 1) && mode == Mode.LAUNCH) {
                admission.verify(after.description() + "; launched, boarding not seen", "Native boat launch verified");
            } else {
                unknown = true;
                admission.uncertain("Boat step cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
