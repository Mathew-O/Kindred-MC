package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One scoped native log break. Completion claims a break, never drop collection. */
final class NativeMiningAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;
    private final BlockState state;
    private final ItemStack tool;
    private final int slot;
    private final long setupRevision, priorReceipt;
    private final String resource, claimKey;
    private ActionLease issuedLease;
    private ClaimRegistry.Grant claim;
    private ActionAdmission admission;
    private boolean started, halted, unknown;
    private Step result;
    private CompletableFuture<ActionRecord> flush;

    NativeMiningAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                       Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                       Consumer<Runnable> dispatch, BlockPos target, int slot) {
        this.body = body; this.lease = lease; this.setup = setup; this.policy = policy; this.claims = claims;
        this.writer = writer; this.dispatch = dispatch; this.target = target.toImmutable(); this.slot = slot;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Mining needs an owned hotbar slot 0..8");
        var world = body.entity().getServerWorld();
        if (!world.isChunkLoaded(target)) throw new IllegalArgumentException("Mining target is unloaded");
        state = world.getBlockState(target);
        if ((!state.isIn(BlockTags.LOGS) && !state.isIn(BlockTags.PICKAXE_MINEABLE) && !state.isOf(Blocks.STONE) && !state.isOf(Blocks.COBBLESTONE)) || state.hasBlockEntity()) {
            throw new IllegalArgumentException("This native mining slice supports non-entity logs, stone, and pickaxe-mineable blocks only");
        }
        resource = Registries.BLOCK.getId(state.getBlock()).toString();
        setupRevision = setup.get().revision();
        var decision = EditPolicy.evaluate(setup.get(), EditPolicy.Kind.BREAK, world.getRegistryKey().getValue().toString(), point(), resource, policy.get());
        if (!decision.allowed()) throw new IllegalArgumentException(decision.reason());
        if (!body.miningEligible(target)) throw new IllegalArgumentException("Mining target is not visible/reachable or native permissions deny it");
        tool = body.entity().getInventory().getStack(slot).copy(); // Comparison only; native selection uses the real slot.
        priorReceipt = body.miningReceipt().map(NativePlayerBody.MiningReceipt::sequence).orElse(0L);
        claimKey = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + target.toShortString();
    }

    private WorkSetup.BlockPoint point() { return new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ()); }
    private boolean intact() { return body.entity().getWorld().isChunkLoaded(target) && body.entity().getWorld().getBlockState(target) == state; }
    private boolean unchangedTool() {
        var actual = body.entity().getInventory().getStack(slot);
        return actual.getCount() == tool.getCount() && ItemStack.areItemsAndComponentsEqual(actual, tool);
    }
    private boolean eligible() {
        var world = body.entity().getServerWorld();
        return setup.get().revision() == setupRevision && intact() && unchangedTool() && body.miningEligible(target)
                && EditPolicy.evaluate(setup.get(), EditPolicy.Kind.BREAK, world.getRegistryKey().getValue().toString(), point(), resource, policy.get()).allowed();
    }
    private boolean ownsClaim() { return claim != null && claims.holdsAll(claim, System.currentTimeMillis()); }
    private Optional<NativePlayerBody.MiningReceipt> receipt() {
        return body.miningReceipt().filter(r -> r.sequence() > priorReceipt && r.lease().equals(issuedLease)
                && r.position().equals(target) && r.previousBlockState() == Block.getRawIdFromState(state));
    }
    private String evidence() {
        var actual = body.entity().getInventory().getStack(slot);
        return "target=" + target.toShortString() + "; observed=" + (body.entity().getWorld().isChunkLoaded(target)
                ? body.entity().getWorld().getBlockState(target) : "unloaded") + "; toolSlot=" + slot
                + "; tool=" + Registries.ITEM.getId(actual.getItem()) + "; count=" + actual.getCount() + "; damage=" + actual.getDamage();
    }
    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "mining", String.valueOf(started)), false); }

    @Override public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("Native mining never replays a saved attempt", true);
        if (admission == null) {
            issuedLease = lease.get();
            claim = claims.acquire(issuedLease.taskId(), issuedLease.sessionEpoch(), java.util.List.of(claimKey), System.currentTimeMillis(), 10_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_mine", resource + " at " + target.toShortString()
                    + "; setupRevision=" + setupRevision), evidence(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable mining acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Mining close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        if (admission.phase() == ActionAdmission.Phase.READY) {
            if (!ownsClaim() || !eligible()) {
                result = Step.blocked("Mining preconditions changed before dispatch; no break issued", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try { started = body.selectHotbar(issuedLease, slot) && body.mine(issuedLease, target); }
            catch (RuntimeException failure) { settle("Native mining call interrupted", false); return waiting(); }
            if (!started) settle("Native mining controls denied", false);
        } else if (admission.phase() == ActionAdmission.Phase.ISSUED) {
            if (receipt().isPresent()) settle("Native break observed", true);
            else if (!intact() || !unchangedTool() || !ownsClaim()) settle("Mining evidence or resource claim changed", false);
        }
        return waiting();
    }

    private void settle(String reason, boolean completed) {
        body.stopInputs();
        var observed = receipt();
        if (observed.isPresent()) {
            admission.verify(evidence() + "; nativeReceipt=" + observed.orElseThrow().sequence() + "; drops=unverified", "Actual native log break; collection not claimed");
            result = completed ? Step.completed(Map.of("broken", resource, "target", target.toShortString(), "drops", "unverified"))
                    : Step.blocked(reason + "; actual break recorded", true);
        } else if (intact() && unchangedTool()) {
            admission.noEffect(evidence() + "; target/tool unchanged; native mining cancelled", reason);
            result = Step.blocked(reason + "; no native break observed", true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; no attributable postcondition");
            result = Step.blocked("Native mining outcome requires reconciliation", true);
        }
    }

    @Override boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || !ownsClaim() || !eligible()) return false;
        return intent == NativePlayerBody.Intent.SELECT_SLOT
                || (intent == NativePlayerBody.Intent.MINE && target.equals(requested) && body.entity().getInventory().selectedSlot == slot);
    }
    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    @Override CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) { if (claim != null) claims.release(claim); return flush; }
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) settle("Authority ended", false);
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        if (claim != null) claims.release(claim);
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
