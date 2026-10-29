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
 * In plain terms: an AI player using a boat to cross water, as a human does: putting one of its own boats on
 * the water and getting in, boarding one already there, or getting out and breaking the boat so it drops to
 * be picked up again. It can also set a boat down by a villager, which then sits in it and stays put, or
 * break a boat with someone in it. Rowing is done separately.
 *
 * Technical: A boat taken onto the water and back, as a player crosses water with one. {@code launch} puts
 * one owned boat on the water within reach and gets in: one boat item is spent under the ledger, and the boat
 * is then in the world. {@code board} gets into an empty boat within reach, or the one named (one holding a
 * villager: a player takes the front seat, the villager moving to the back). {@code land} gets out and breaks
 * the boat with ordinary hits, so it drops as an item to be picked up again, or leaves it on the water if
 * another rider is still in it; nothing owned is lost. Rowing is {@link NativeRowSkill}'s, between them.
 * {@code place} puts one owned boat down on top of a block without getting in (E-45e: just beside a villager,
 * which it takes up, so that the villager stays put); {@code break} breaks a boat within reach, whoever is in
 * it set down where it sat, its item dropped to be picked up again.
 */
final class NativeBoatAction extends NativeRecordedAction {
    enum Mode { LAUNCH, BOARD, LAND, PLACE, BREAK }

    /** Ticks to wait for vanilla to set the player down beside the boat, and to break it. */
    private static final int LEAVE_TICKS = 20, BREAK_TICKS = 160;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Mode mode;
    private final int slot;
    private final BlockPos water;
    private final UUID target;
    private final net.minecraft.util.math.Vec3d aim;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private UUID boat;
    private BlockPos boatAt;
    private int waited;
    private boolean started, halted, unknown, left;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    /**
     * A launch, boarding, landing, placing or breaking of a boat, with the boat in a hotbar slot and the
     * given block where one is needed.
     */
    NativeBoatAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     Mode mode, int slot, BlockPos water) {
        this(body, lease, writer, dispatch, mode, slot, water, null);
    }

    /** As above, with the boat to board or break named. */
    NativeBoatAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     Mode mode, int slot, BlockPos water, UUID target) {
        this(body, lease, writer, dispatch, mode, slot, water, target, null);
    }

    /** With {@code aim}, a point on the block's top that a place looks at, where the boat goes, rather than its middle. */
    NativeBoatAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     Mode mode, int slot, BlockPos water, UUID target, net.minecraft.util.math.Vec3d aim) {
        this.body = body;
        this.aim = aim;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.mode = mode;
        this.slot = slot;
        this.water = water;
        this.target = target;
        if ((mode == Mode.LAUNCH || mode == Mode.PLACE) && (slot < 0 || slot > 8 || water == null)) throw new IllegalArgumentException("A launch or a place needs an owned hotbar slot 0..8 and a block");
        if (mode == Mode.BREAK && target == null) throw new IllegalArgumentException("A break needs its boat");
    }

    /** Moves the boat work on by one tick. */
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
                    if (target != null ? !(player.getServerWorld().getEntity(target) instanceof BoatEntity b) || !b.isAlive() || !player.canInteractWithEntity(b, 0)
                            : nearestEmptyBoat() == null) return Step.blocked(target != null ? "The boat is out of reach" : "No empty boat within reach", true);
                }
                case LAND -> {
                    if (body.boat().isEmpty()) return Step.blocked("Not in a boat", true);
                }
                case PLACE -> {
                    if (!(player.getInventory().getStack(slot).getItem() instanceof BoatItem)) return Step.blocked("The hotbar slot holds no boat", true);
                    if (player.hasVehicle()) return Step.blocked("In a vehicle", true);
                }
                case BREAK -> {
                    if (player.hasVehicle()) return Step.blocked("In a vehicle", true);
                    if (!(player.getServerWorld().getEntity(target) instanceof BoatEntity b) || !b.isAlive()) return Step.blocked("No such boat", true);
                    if (!player.canInteractWithEntity(b, 0)) return Step.blocked("The boat is out of reach", true);
                }
            }
            before = InventoryEvidence.capture(body);
            String at = mode == Mode.LAUNCH || mode == Mode.PLACE ? "slot=" + slot + "; at=" + water.toShortString()
                    : mode == Mode.BREAK ? "boat=" + target : "near=" + player.getBlockPos().toShortString();
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_boat",
                    "mode=" + mode.name().toLowerCase(Locale.ROOT) + "; " + at), before.description(), writer, dispatch);
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
                case BOARD -> board(target != null && player.getServerWorld().getEntity(target) instanceof BoatEntity b ? b : nearestEmptyBoat());
                case LAND -> leave();
                case PLACE -> place();
                case BREAK -> {
                    boat = target;
                    boatAt = player.getServerWorld().getEntity(target).getBlockPos();
                    left = true;
                    waited = 0;
                    yield waiting();
                }
            };
        }
        return switch (mode) {
            case LAUNCH, BOARD -> settleAboard();
            case LAND -> land();
            case PLACE -> settlePlaced();
            case BREAK -> breakUp();
        };
    }

    private Step place() {
        try {
            if (!body.placeBoat(lease.get(), slot, water, aim)) return settle("Placing authority denied", null);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Placing interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        var placed = body.entity().getServerWorld().getEntitiesByClass(BoatEntity.class, new Box(water.up()).expand(1.5),
                b -> b.isAlive() && b.age <= 2);
        if (placed.isEmpty()) {
            var look = body.entity().raycast(5.0, 1.0f, true);
            return settle("The boat was not put down (the look met " + (look instanceof net.minecraft.util.hit.BlockHitResult block
                    ? block.getBlockPos().toShortString() + " " + block.getSide() : look.getType()) + ", for " + water.toShortString() + ")", null);
        }
        boat = placed.getFirst().getUuid();
        boatAt = placed.getFirst().getBlockPos();
        waited = 0;
        return waiting();
    }

    /** Put down, and whoever it was put down on taken up within a few ticks, or left empty; said either way. */
    private Step settlePlaced() {
        var entity = body.entity().getServerWorld().getEntity(boat);
        if (entity instanceof BoatEntity placed && placed.hasPassengers()) return settle(null, Map.of("boat", boat.toString(), "taken", "true"));
        if (++waited < 10) return waiting();
        return settle(null, Map.of("boat", boat.toString(), "taken", "false"));
    }

    /** Hit until it breaks, whoever is in it set down where it sat. */
    private Step breakUp() {
        var player = body.entity();
        var world = player.getServerWorld();
        var entity = world.getEntity(boat);
        if (!(entity instanceof BoatEntity target) || !target.isAlive()) {
            var drops = world.getEntitiesByClass(ItemEntity.class, new Box(boatAt).expand(4), item -> item.isAlive() && item.getStack().getItem() instanceof BoatItem);
            if (!drops.isEmpty()) {
                var drop = drops.getFirst().getBlockPos();
                return settle(null, Map.of("dropX", String.valueOf(drop.getX()), "dropY", String.valueOf(drop.getY()), "dropZ", String.valueOf(drop.getZ())));
            }
            return settle(null, Map.of("picked", "true"));
        }
        boatAt = target.getBlockPos();
        if (++waited > BREAK_TICKS || !player.canInteractWithEntity(target, 0)) return settle("Could not break the boat", null);
        if (player.getAttackCooldownProgress(0.5f) >= 1f) body.strikeBoat(lease.get(), target, true);
        return waiting();
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
        // Out, with the boat left on the water: out of reach, or holding another rider (a villager it brought across).
        if (++waited > BREAK_TICKS || !player.canInteractWithEntity(target, 0) || target.hasPassengers()) {
            return settle(null, Map.of("left", boatAt.toShortString()));
        }
        if (player.getAttackCooldownProgress(0.5f) >= 1f) body.strikeBoat(lease.get(), target);
        return waiting();
    }

    /** Ends the step, checking the inventory: a launch spends exactly one boat; nothing else is ever lost. */
    private Step settle(String failure, Map<String, String> progress) {
        var after = InventoryEvidence.capture(body);
        int spent = boats(before) - boats(after);
        boolean launched = (mode == Mode.LAUNCH || mode == Mode.PLACE) && boat != null;
        if (!conserved(after, launched ? 1 : 0) || launched && spent != 1 || !launched && spent > 0) {
            unknown = true;
            result = Step.blocked("Boat conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        if (failure != null && !launched && mode != Mode.LAND && mode != Mode.BREAK) {
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
                || (mode == Mode.LAND || mode == Mode.BREAK) && intent == NativePlayerBody.Intent.ATTACK;
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
            } else if (before != null && spent == 1 && conserved(after, 1) && (mode == Mode.LAUNCH || mode == Mode.PLACE)) {
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
