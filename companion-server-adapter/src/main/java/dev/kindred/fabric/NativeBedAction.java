package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.item.BedItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sets an owned bed down in the Overworld and uses it, so that the player wakes there after a death (E-39g), as players
 * do by the End portal before going through: the server's edit policy and a claim cover both of the bed's blocks,
 * exactly one bed item is spent, and the step is verified only once vanilla has set the respawn point at the bed.
 */
final class NativeBedAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos floor;
    private final Direction facing;
    private final int slot;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Item bed;
    private boolean placed, halted, unknown;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeBedAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                    Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                    Consumer<Runnable> dispatch, BlockPos floor, Direction facing, int slot) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.floor = floor.toImmutable();
        this.facing = facing;
        this.slot = slot;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A bed is set down from an owned hotbar slot 0..8");
        if (facing == null || facing.getAxis().isVertical()) throw new IllegalArgumentException("A bed faces one of the four sides");
    }

    private BlockPos foot() { return floor.up(); }
    private BlockPos head() { return floor.up().offset(facing); }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded bed cannot replay after cancellation/restart", true);
        var player = body.entity();
        var world = player.getServerWorld();
        if (admission == null) {
            // Beds explode anywhere but a natural dimension: never set one down there to sleep in.
            if (world.getRegistryKey() != World.OVERWORLD) return Step.blocked("A bed is used only in the Overworld", true);
            var stack = player.getInventory().getStack(slot);
            if (!(stack.getItem() instanceof BedItem)) return Step.blocked("The hotbar slot holds no bed", true);
            bed = stack.getItem();
            for (BlockPos pos : List.of(foot(), head())) {
                if (!world.isChunkLoaded(pos) || !world.getBlockState(pos).isReplaceable()) return Step.blocked("No room for the bed at " + pos.toShortString(), true);
                var support = world.getBlockState(pos.down());
                if (!support.isSideSolidFullSquare(world, pos.down(), Direction.UP)) return Step.blocked("Nothing to set the bed on at " + pos.down().toShortString(), true);
                var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.PLACE, pos, Registries.ITEM.getId(bed).toString(), policy.get());
                if (!decision.allowed()) return Step.blocked(decision.reason(), true);
            }
            String dimension = world.getRegistryKey().getValue().toString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(),
                    List.of("block:" + worldId + ":" + dimension + ":" + foot().toShortString(), "block:" + worldId + ":" + dimension + ":" + head().toShortString()),
                    System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds the bed's ground", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_bed",
                    "bed=" + Registries.ITEM.getId(bed) + "; foot=" + foot().toShortString() + "; facing=" + facing.asString()), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable bed acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Bed close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        if (!placed) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!before.matches(body) || !player.isAlive()) {
                result = Step.blocked("Owned inventory changed before the bed was set down; nothing placed", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.placeBed(lease.get(), slot, floor, facing)) return settle("Bed authority denied");
                placed = true;
                if (!world.getBlockState(foot()).isIn(BlockTags.BEDS) || !world.getBlockState(head()).isIn(BlockTags.BEDS)) return settle("The bed was not set down");
                if (!body.useBed(lease.get(), foot())) return settle("The bed could not be used");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Bed interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            return settle(null);
        }
        return waiting();
    }

    private Step settle(String reason) {
        var player = body.entity();
        var world = player.getServerWorld();
        var after = InventoryEvidence.capture(body);
        int spent = count(before, bed) - count(after, bed);
        boolean standing = world.getBlockState(foot()).isIn(BlockTags.BEDS);
        if (spent == 0 && !standing && conserved(after, 0)) {
            result = Step.blocked(reason != null ? reason : "No bed was set down", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent != 1 || !standing || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Bed conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        BlockPos spawn = player.getSpawnPointPosition();
        boolean set = spawn != null && player.getSpawnPointDimension() == World.OVERWORLD
                && (spawn.equals(foot()) || spawn.equals(head()));
        var progress = new HashMap<String, String>(Map.of("foot", foot().toShortString(), "spawnSet", String.valueOf(set)));
        result = set ? Step.completed(Map.copyOf(progress)) : Step.blocked(reason != null ? reason : "The bed is down but the respawn point did not move", true);
        admission.verify(after.description() + "; " + progress, set ? "Native bed set down and the respawn point set" : "Native bed set down");
        return waiting();
    }

    private static int count(InventoryEvidence evidence, Item item) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    private boolean conserved(InventoryEvidence after, int beds) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == bed ? beds : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || result != null) return false;
        return intent == NativePlayerBody.Intent.PLACE && foot().equals(target) && !placed
                || intent == NativePlayerBody.Intent.SLEEP && foot().equals(target) && placed
                || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
        if (claim != null) { claims.release(claim); claim = null; }
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) {
            var after = InventoryEvidence.capture(body);
            if (count(before, bed) == count(after, bed) && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Bed cancelled before it was set down");
            } else {
                unknown = true;
                admission.uncertain("Bed cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
