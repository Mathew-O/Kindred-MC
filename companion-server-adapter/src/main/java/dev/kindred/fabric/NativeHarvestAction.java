package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native crop harvesting action using the durable action ledger (E-13).
 * Evaluates crop maturity, harvests mature yield, enforces replanting conservation
 * (immediately sowing a seed back onto farmland before keeping surplus food),
 * and verifies complete item conservation.
 */
final class NativeHarvestAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;
    private final int slot;
    private final boolean replant;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private String dimensionKey;
    private String cropBlockId;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeHarvestAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                        Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                        ClaimRegistry claims, ActionAdmission.Writer writer,
                        Consumer<Runnable> dispatch, BlockPos target, int slot, boolean replant) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.target = target.toImmutable();
        this.slot = slot;
        this.replant = replant;
        var s = setup.get();
        if (s == null || s.area() == null) throw new IllegalArgumentException("No work area is assigned.");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded harvest cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            var player = body.entity();
            var world = player.getServerWorld();

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Harvest target is in an unloaded chunk", true);
            }

            var cropState = world.getBlockState(target);
            if (!(cropState.getBlock() instanceof CropBlock crop) || !crop.isMature(cropState)) {
                return Step.blocked("Target block is not a mature crop ready for harvest", true);
            }
            if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
                return Step.blocked("Harvest target is out of reach", true);
            }

            var currentSetup = setup.get();
            if (currentSetup == null || currentSetup.area() == null) {
                return Step.blocked("No work area is assigned.", true);
            }
            var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
            if (!currentSetup.area().contains(point)) {
                return Step.blocked("Harvest target is outside assigned work area", true);
            }

            setupRevision = currentSetup.revision();
            dimensionKey = world.getRegistryKey().getValue().toString();
            cropBlockId = Registries.BLOCK.getId(cropState.getBlock()).toString();

            var breakDecision = EditPolicy.evaluate(currentSetup, EditPolicy.Kind.BREAK, dimensionKey, point, cropBlockId, policy.get());
            if (!breakDecision.allowed()) {
                return Step.blocked(breakDecision.reason(), true);
            }

            claimKey = "block:" + worldId + ":" + dimensionKey + ":" + target.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_harvest",
                            "crop=" + cropBlockId + "; pos=" + target.toShortString() + "; replant=" + replant + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable harvest acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Harvest close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) {
                return result;
            }
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!validAuthorityAndPreconditions()) {
            result = Step.blocked("Harvest preconditions, claim, or policy no longer valid; no harvest performed", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean harvested = body.harvestCrop(lease.get(), slot, target, replant);
            var world = body.entity().getServerWorld();
            var targetState = world.getBlockState(target);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during harvest; reconcile", true);
            } else if (harvested && (replant ? (targetState.getBlock() instanceof CropBlock && world.getBlockState(target.down()).isOf(Blocks.FARMLAND))
                    : (targetState.isAir() || targetState.isReplaceable()))) {
                var after = InventoryEvidence.capture(body);
                result = Step.completed(Map.of(
                        "harvested", cropBlockId,
                        "pos", target.toShortString(),
                        "replanted", String.valueOf(replant)));
                admission.verify(after.description() + "; " + result.progress(), "Native crop harvest verified");
            } else if (before.matches(body)) {
                result = Step.blocked("Crop harvest produced no effect", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native harvest effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Harvest action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(failure.getMessage());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        if (!world.isChunkLoaded(target)) return false;

        var state = world.getBlockState(target);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMature(state)) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) return false;

        var currentSetup = setup.get();
        if (currentSetup == null || currentSetup.revision() != setupRevision) return false;
        var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
        var decision = EditPolicy.evaluate(currentSetup, EditPolicy.Kind.BREAK, dimensionKey, point, cropBlockId, policy.get());
        if (!decision.allowed()) return false;

        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos targetPos) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT) return true;
        if (intent == NativePlayerBody.Intent.HARVEST || intent == NativePlayerBody.Intent.MINE) {
            return targetPos == null || target.equals(targetPos);
        }
        return false;
    }

    @Override
    boolean uncertain() {
        return unknown;
    }

    @Override
    ActionRecord snapshot() {
        return admission == null ? null : admission.snapshot();
    }

    @Override
    String phase() {
        return admission == null ? "NOT_PREPARED" : admission.phase().name();
    }

    private Step waiting() {
        return Step.running(Map.of("durableAction", phase()), false);
    }

    @Override
    CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (claim != null) {
            claims.release(claim);
            claim = null;
        }
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native crop harvest verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Harvest cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Harvest cancelled with uncertain outcome");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override
    public void halt() {
        close();
    }

    @Override
    public boolean settled() {
        return true;
    }
}
