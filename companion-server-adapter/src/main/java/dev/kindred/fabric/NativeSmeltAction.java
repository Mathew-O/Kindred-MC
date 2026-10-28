package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native furnace interaction action using the durable action ledger.
 * Evaluates edit policy, claims furnace block, transfers fuel and smeltable inputs into
 * furnace slots or extracts finished outputs into player inventory, enforcing 100% item conservation.
 */
final class NativeSmeltAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos targetPos;
    private final String actionArg;
    private final Integer fuelSlotArg;
    private final Integer inputSlotArg;
    private final Integer countArg;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private FurnaceEvidence furnaceBefore;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private NativePlayerBody.SmeltPlan plan;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;
    private long setupRevision;
    private AbstractFurnaceBlockEntity furnaceInstance;

    NativeSmeltAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                      Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                      ClaimRegistry claims, ActionAdmission.Writer writer,
                      Consumer<Runnable> dispatch, BlockPos targetPos, String actionArg,
                      Integer fuelSlotArg, Integer inputSlotArg, Integer countArg) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.targetPos = targetPos != null ? targetPos.toImmutable() : null;
        this.actionArg = actionArg;
        this.fuelSlotArg = fuelSlotArg;
        this.inputSlotArg = inputSlotArg;
        this.countArg = countArg;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded smelting cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            var planned = body.planSmelt(targetPos, actionArg, fuelSlotArg, inputSlotArg, countArg);
            if (planned.isEmpty()) {
                return Step.blocked("No valid furnace operation planned (check furnace proximity, fuel, inputs, or inventory space)", true);
            }
            plan = planned.get();

            var player = body.entity();
            var world = player.getServerWorld();
            if (!world.isChunkLoaded(plan.furnacePos())) {
                return Step.blocked("Furnace is in an unloaded chunk", true);
            }
            var entity = world.getBlockEntity(plan.furnacePos());
            if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) {
                return Step.blocked("Target block is not a furnace", true);
            }
            furnaceInstance = furnace;

            var decision = NativeEdits.useWorkstation(player, setup.get(), plan.furnacePos(), "minecraft:furnace", policy.get());
            if (!decision.allowed()) {
                return Step.blocked("Furnace edit policy denied: " + decision.reason(), true);
            }

            setupRevision = setup.get() != null ? setup.get().revision() : -1L;
            claimKey = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + plan.furnacePos().toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), java.util.List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            furnaceBefore = FurnaceEvidence.capture(furnace);

            String intentSummary = "furnace=" + plan.furnacePos().toShortString()
                    + (plan.extractOutput() ? "; extract=" + Registries.ITEM.getId(plan.expectedOutput().getItem()) : "")
                    + (plan.inputCount() > 0 ? "; inputCount=" + plan.inputCount() : "")
                    + (plan.fuelCount() > 0 ? "; fuelCount=" + plan.fuelCount() : "");

            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_smelt", intentSummary),
                    before.description() + "; " + furnaceBefore.description(),
                    writer, dispatch);
        }

        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable smelt acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Smelt close outcome uncertain", true);
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
            result = Step.blocked("Furnace authority or preconditions changed before dispatch; no smelt issued", true);
            flush = admission.close();
            return waiting();
        }

        if (admission.take().isEmpty()) return waiting();

        try {
            var world = body.entity().getServerWorld();
            var be = world.getBlockEntity(plan.furnacePos());
            var furnace = be instanceof AbstractFurnaceBlockEntity f ? f : null;
            if (furnace == null || (furnaceInstance != null && furnace != furnaceInstance)) {
                unknown = true;
                result = Step.blocked("Target furnace missing or replaced during execution; reconcile", true);
                admission.uncertain(result.reason());
                return waiting();
            }
            boolean executed = body.smelt(lease.get(), plan);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during smelt; reconcile", true);
            } else if (executed) {
                var after = InventoryEvidence.capture(body);
                var furnaceAfter = FurnaceEvidence.capture(furnace);
                if (verifyConservation(before, after, furnaceBefore, furnaceAfter)) {
                    result = Step.completed(Map.of(
                            "furnace", plan.furnacePos().toShortString(),
                            "extracted", String.valueOf(plan.extractOutput()),
                            "inputCount", String.valueOf(plan.inputCount()),
                            "fuelCount", String.valueOf(plan.fuelCount())));
                    admission.verify(after.description() + "; " + furnaceAfter.description() + "; " + result.progress(),
                            "Native furnace interaction verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Smelt conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body) && furnaceBefore.matches(furnace)) {
                result = Step.blocked("Smelt produced no verified effect", true);
                admission.noEffect(before.description() + "; all owned and furnace slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native smelt effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Smelt action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private static boolean verifyConservation(InventoryEvidence playerBefore, InventoryEvidence playerAfter,
                                              FurnaceEvidence furnaceBefore, FurnaceEvidence furnaceAfter) {
        Map<Item, Integer> totalDelta = new HashMap<>();

        // Player inventory delta
        for (ItemStack s : playerAfter.stacks()) {
            if (!s.isEmpty()) totalDelta.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        for (ItemStack s : playerBefore.stacks()) {
            if (!s.isEmpty()) totalDelta.merge(s.getItem(), -s.getCount(), Integer::sum);
        }

        // Furnace inventory delta
        for (ItemStack s : new ItemStack[]{furnaceAfter.input(), furnaceAfter.fuel(), furnaceAfter.output()}) {
            if (!s.isEmpty()) totalDelta.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        for (ItemStack s : new ItemStack[]{furnaceBefore.input(), furnaceBefore.fuel(), furnaceBefore.output()}) {
            if (!s.isEmpty()) totalDelta.merge(s.getItem(), -s.getCount(), Integer::sum);
        }

        totalDelta.entrySet().removeIf(e -> e.getValue() == 0);
        return totalDelta.isEmpty();
    }

    private boolean validAuthorityAndPreconditions() {
        if (plan == null) return false;
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(plan.furnacePos())) return false;
        var entity = world.getBlockEntity(plan.furnacePos());
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) return false;
        if (furnaceInstance != null && furnace != furnaceInstance) return false;
        var state = world.getBlockState(plan.furnacePos());
        if (!state.isOf(Blocks.FURNACE) && !state.isOf(Blocks.BLAST_FURNACE) && !state.isOf(Blocks.SMOKER)) return false;
        if (player.getEyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(plan.furnacePos())) > 4.5 * 4.5) return false;
        if (before != null && !before.matches(body)) return false;
        if (furnaceBefore != null && !furnaceBefore.matches(furnace)) return false;

        var currentSetup = setup.get();
        if ((currentSetup == null ? -1L : currentSetup.revision()) != setupRevision) return false;
        var decision = NativeEdits.useWorkstation(player, currentSetup, plan.furnacePos(), "minecraft:furnace", policy.get());
        if (!decision.allowed()) return false;
        if (claim == null || !claims.holdsAll(claim, System.currentTimeMillis())) return false;

        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && intent == NativePlayerBody.Intent.SMELT
                && (target == null || (plan != null && target.equals(plan.furnacePos())))
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
        if (claim != null) {
            claims.release(claim);
            claim = null;
        }
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            var world = body.entity().getServerWorld();
            var entity = plan != null ? world.getBlockEntity(plan.furnacePos()) : null;
            var furnace = entity instanceof AbstractFurnaceBlockEntity f ? f : null;
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                var afterDesc = InventoryEvidence.capture(body).description()
                        + (furnace != null ? "; " + FurnaceEvidence.capture(furnace).description() : "");
                admission.verify(afterDesc + "; " + result.progress(), "Native furnace interaction verified");
            } else if (before != null && before.matches(body) && (furnace == null || furnaceBefore == null || furnaceBefore.matches(furnace))) {
                admission.noEffect(before.description() + "; all owned and furnace slots unchanged", "Smelt cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Smelt cancelled with uncertain outcome");
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
