package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Takes up to a count of one item from the player's own assigned storage (E-08c), as a player takes stacks out of a
 * chest it has open: only the storage its administrator assigned, verified unchanged since, under the container
 * edit policy and a claim on the chest. Exactly what left the chest arrives in the player's inventory, recorded in the
 * durable ledger; nothing else of either moves.
 */
final class NativeTakeAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Item item;
    private final int count;

    private BlockPos chestPos;
    private Inventory container;
    private ActionAdmission admission;
    private InventoryEvidence playerBefore;
    private ChestEvidence chestBefore;
    private ClaimRegistry.Grant claim;
    private long setupRevision;
    private int chestHadBefore, playerHadBefore;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeTakeAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                     Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                     Consumer<Runnable> dispatch, String item, int count) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        var id = Identifier.tryParse(item);
        if (id == null || !Registries.ITEM.containsId(id)) throw new IllegalArgumentException("No such item: " + item);
        this.item = Registries.ITEM.get(id);
        if (count < 1 || count > 36 * 64) throw new IllegalArgumentException("Take 1 to a full inventory's worth");
        this.count = count;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded take cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            var target = setup.get() == null ? null : setup.get().storage();
            if (target == null) return Step.blocked("No storage assigned to take from", true);
            var world = player.getServerWorld();
            var verdict = StorageTargets.validate(world, target);
            if (verdict != StorageTargets.Verdict.OK) return Step.blocked(StorageTargets.explain(verdict), true);
            chestPos = new BlockPos(target.position().x(), target.position().y(), target.position().z());
            if (!(world.getBlockEntity(chestPos) instanceof Inventory inventory)) return Step.blocked("The storage is not a container", true);
            container = inventory;
            if (!inReach()) return Step.blocked("The storage is out of reach", true);
            var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.CONTAINER, chestPos, target.blockId(), policy.get());
            if (!decision.allowed()) return Step.blocked("Container edit policy denied: " + decision.reason(), true);
            chestHadBefore = count(container);
            if (chestHadBefore == 0) return Step.blocked("The storage holds no " + name(), true);
            setupRevision = setup.get().revision();
            String key = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + chestPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            playerBefore = InventoryEvidence.capture(body);
            playerHadBefore = player.getInventory().count(item);
            chestBefore = ChestEvidence.capture(container);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_take",
                    "storage=" + chestPos.toShortString() + "; item=" + Registries.ITEM.getId(item) + "; count=" + count),
                    playerBefore.description() + "; " + chestBefore.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable take acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Take close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!valid()) {
            result = Step.blocked("The storage or the inventory changed before the take; nothing moved", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        int moved;
        try {
            moved = body.takeFromContainer(lease.get(), chestPos, item, count);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Take interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        var chestAfter = ChestEvidence.capture(container);
        int chestLost = chestHadBefore - count(container), playerGained = player.getInventory().count(item) - playerHadBefore;
        if (moved == 0 && chestBefore.matches(container) && playerBefore.matches(body)) {
            result = Step.blocked("Nothing taken: no room, or none left", true);
            admission.noEffect(playerBefore.description() + "; " + chestBefore.description(), result.reason());
        } else if (moved > 0 && chestLost == moved && playerGained == moved && onlyThisMoved(chestAfter)) {
            result = Step.completed(Map.of("item", Registries.ITEM.getId(item).toString(), "taken", String.valueOf(moved)));
            admission.verify(InventoryEvidence.capture(body).description() + "; " + chestAfter.description() + "; " + moved + " " + name()
                    + " taken from the storage at " + chestPos.toShortString(), "Native take verified");
        } else {
            unknown = true;
            result = Step.blocked("Take conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason());
        }
        return waiting();
    }

    /** The chest lost only this item; every other stack in it is as it was. */
    private boolean onlyThisMoved(ChestEvidence after) {
        var before = new java.util.HashMap<Item, Integer>();
        for (ItemStack stack : chestBefore.stacks()) if (!stack.isEmpty() && !stack.isOf(item)) before.merge(stack.getItem(), stack.getCount(), Integer::sum);
        var now = new java.util.HashMap<Item, Integer>();
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty() && !stack.isOf(item)) now.merge(stack.getItem(), stack.getCount(), Integer::sum);
        return before.equals(now);
    }

    private boolean inReach() {
        return body.entity().getEyePos().squaredDistanceTo(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) <= 20.25;
    }

    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive() || !inReach()) return false;
        if (!(player.getServerWorld().getBlockEntity(chestPos) instanceof Inventory inventory) || inventory != container) return false;
        if (!playerBefore.matches(body) || !chestBefore.matches(container)) return false;
        var current = setup.get();
        if (current == null || current.revision() != setupRevision || current.storage() == null) return false;
        if (!NativeEdits.decide(player, current, EditPolicy.Kind.CONTAINER, chestPos, current.storage().blockId(), policy.get()).allowed()) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    private int count(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    private String name() {
        return Registries.ITEM.getId(item).getPath().replace('_', ' ');
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.TAKE
                && (target == null || target.equals(chestPos));
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
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(InventoryEvidence.capture(body).description(), "Native take verified on close");
            } else if (playerBefore.matches(body) && chestBefore.matches(container)) {
                admission.noEffect(playerBefore.description() + "; storage unchanged", "Take cancelled with nothing moved");
            } else {
                unknown = true;
                admission.uncertain("Take cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
