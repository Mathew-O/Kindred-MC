package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import dev.kindred.core.logistics.ChestOrganizationPolicy;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Applies bounded {@link ChestOrganizationPolicy} moves inside an owned storage chest (CHEST-01). */
final class NativeChestOrganizeAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos chestPos;
    private final int maxMoves;

    private ActionAdmission admission;
    private InventoryEvidence playerBefore;
    private ChestEvidence chestBefore;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private List<ChestOrganizationPolicy.ChestSlotMove> plannedMoves = List.of();
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;
    private long setupRevision;
    private Inventory container;

    NativeChestOrganizeAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                              Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                              ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                              BlockPos chestPos, int maxMoves) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.chestPos = chestPos != null ? chestPos.toImmutable() : null;
        this.maxMoves = Math.max(1, Math.min(maxMoves, 8));
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded chest organize cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            if (chestPos == null) {
                return Step.blocked("Chest organize requires a target position", true);
            }
            var player = body.entity();
            var world = player.getServerWorld();
            if (!world.isChunkLoaded(chestPos)) {
                return Step.blocked("Chest is in an unloaded chunk", true);
            }
            BlockEntity entity = world.getBlockEntity(chestPos);
            if (!(entity instanceof Inventory inv)) {
                return Step.blocked("Target block is not a container", true);
            }
            container = inv;
            if (player.getEyePos().squaredDistanceTo(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) > 20.25) {
                return Step.blocked("Chest is out of reach", true);
            }

            var point = new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ());
            var blockId = Registries.BLOCK.getId(world.getBlockState(chestPos).getBlock()).toString();
            var decision = EditPolicy.evaluate(setup.get(), EditPolicy.Kind.CONTAINER,
                    world.getRegistryKey().getValue().toString(), point, blockId, policy.get());
            if (!decision.allowed()) {
                return Step.blocked("Container edit policy denied: " + decision.reason(), true);
            }

            setupRevision = setup.get() != null ? setup.get().revision() : -1L;
            claimKey = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + chestPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            List<ChestOrganizationPolicy.SlotSnapshot> snapshots = new ArrayList<>();
            for (int i = 0; i < container.size(); i++) {
                var stack = container.getStack(i);
                String itemId = stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
                snapshots.add(new ChestOrganizationPolicy.SlotSnapshot(
                        i, itemId, stack.getCount(), stack.getMaxCount(), stack.getComponents().hashCode()));
            }
            plannedMoves = ChestOrganizationPolicy.planChestOrganization(snapshots, maxMoves);
            if (plannedMoves.isEmpty()) {
                return Step.blocked("Chest is already organized; no moves planned", true);
            }

            playerBefore = InventoryEvidence.capture(body);
            chestBefore = ChestEvidence.capture(container);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_organize_chest",
                            "chest=" + chestPos.toShortString() + "; moves=" + plannedMoves.size()),
                    playerBefore.description() + "; " + chestBefore.description(),
                    writer, dispatch);
        }

        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable chest organize acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Chest organize close outcome uncertain", true);
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
            result = Step.blocked("Chest authority or preconditions changed before dispatch; no organize issued", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();

        try {
            int applied = 0;
            for (var move : plannedMoves) {
                if (!body.applyChestMove(lease.get(), chestPos, move)) {
                    break;
                }
                applied++;
            }
            var chestAfter = ChestEvidence.capture(container);
            var playerAfter = InventoryEvidence.capture(body);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during chest organize; reconcile", true);
            } else if (applied > 0 && playerBefore.matches(body)
                    && ChestEvidence.sameMultiset(chestBefore, chestAfter)) {
                result = Step.completed(Map.of(
                        "chest", chestPos.toShortString(),
                        "movesApplied", String.valueOf(applied)));
                admission.verify(playerAfter.description() + "; " + chestAfter.description(),
                        "Native chest organization verified");
            } else if (playerBefore.matches(body) && chestBefore.matches(container)) {
                result = Step.blocked("Chest organize produced no verified effect", true);
                admission.noEffect(playerBefore.description() + "; " + chestBefore.description(), result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Chest organize conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Chest organize interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        if (container == null || chestPos == null) return false;
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(chestPos)) return false;
        if (!(world.getBlockEntity(chestPos) instanceof Inventory inv) || inv != container) return false;
        var state = world.getBlockState(chestPos);
        if (!state.isOf(Blocks.CHEST) && !state.isOf(Blocks.TRAPPED_CHEST) && !state.isOf(Blocks.BARREL)) return false;
        if (player.getEyePos().squaredDistanceTo(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5) > 20.25) {
            return false;
        }
        if (playerBefore != null && !playerBefore.matches(body)) return false;
        var currentSetup = setup.get();
        if (currentSetup == null || currentSetup.revision() != setupRevision) return false;
        if (claim == null || !claims.holdsAll(claim, System.currentTimeMillis())) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && intent == NativePlayerBody.Intent.CHEST_ORGANIZE
                && (target == null || target.equals(chestPos))
                && validAuthorityAndPreconditions();
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
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(InventoryEvidence.capture(body).description(), "Native chest organize verified on close");
            } else if (playerBefore != null && playerBefore.matches(body) && chestBefore != null && container != null && chestBefore.matches(container)) {
                admission.noEffect(playerBefore.description() + "; chest unchanged", "Chest organize cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Chest organize cancelled with uncertain outcome");
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
