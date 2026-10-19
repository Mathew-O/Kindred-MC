package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.BedBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native bed sleeping action using the durable action ledger (E-13).
 * Verifies night/thunderstorm condition, checks bed occupancy, initiates sleep,
 * and anchors the native player's respawn point to the home shelter bed.
 */
final class NativeSleepAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private String dimensionKey;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeSleepAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                     Supplier<WorkSetup> setup, ClaimRegistry claims,
                     ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                     BlockPos target) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.target = target.toImmutable();
        var s = setup.get();
        if (s == null || s.area() == null) throw new IllegalArgumentException("No work area is assigned.");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded sleep cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            var player = body.entity();
            var world = player.getServerWorld();

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Bed target is in an unloaded chunk", true);
            }

            var bedState = world.getBlockState(target);
            if (!bedState.isIn(BlockTags.BEDS)) {
                return Step.blocked("Target block is not a bed", true);
            }
            if (bedState.get(BedBlock.OCCUPIED)) {
                return Step.blocked("Bed is already occupied by another player", true);
            }

            boolean canSleep = world.isNight() || world.isThundering()
                    || (world.getTimeOfDay() % 24000L >= 12542L && world.getTimeOfDay() % 24000L <= 23460L);
            if (!canSleep) {
                return Step.blocked("Sleeping is only possible at night or during thunderstorms", true);
            }
            if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) {
                return Step.blocked("Bed target is out of reach", true);
            }

            var currentSetup = setup.get();
            if (currentSetup == null || currentSetup.area() == null) {
                return Step.blocked("No work area is assigned.", true);
            }
            var point = new WorkSetup.BlockPoint(target.getX(), target.getY(), target.getZ());
            if (!currentSetup.area().contains(point)) {
                return Step.blocked("Bed is outside assigned work area", true);
            }

            setupRevision = currentSetup.revision();
            dimensionKey = world.getRegistryKey().getValue().toString();

            claimKey = "bed:" + worldId + ":" + dimensionKey + ":" + target.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 20_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_sleep",
                            "bed=" + target.toShortString() + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable sleep acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Sleep close outcome uncertain", true);
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
            result = Step.blocked("Sleep preconditions, claim, or environment no longer valid; no sleep initiated", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean slept = body.sleepInBed(lease.get(), target);
            var player = body.entity();
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during sleep; reconcile", true);
            } else if (slept && (player.isSleeping() || (player.getSpawnPointPosition() != null && player.getSpawnPointPosition().equals(target)))) {
                var after = InventoryEvidence.capture(body);
                result = Step.completed(Map.of(
                        "slept", "true",
                        "pos", target.toShortString(),
                        "spawnAnchored", "true"));
                admission.verify(after.description() + "; " + result.progress(), "Native player bed sleeping verified");
            } else if (before.matches(body)) {
                result = Step.blocked("Sleep attempt produced no state change", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native sleep effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Sleep action interrupted with an unknown outcome; reconcile", true);
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

        var bedState = world.getBlockState(target);
        if (!bedState.isIn(BlockTags.BEDS)) return false;
        if (bedState.get(BedBlock.OCCUPIED)) return false;
        if (player.getEyePos().squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 25.0) return false;

        var currentSetup = setup.get();
        if (currentSetup == null || currentSetup.revision() != setupRevision) return false;

        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos targetPos) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SLEEP) {
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native player bed sleeping verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Sleep cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Sleep cancelled with uncertain outcome");
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
