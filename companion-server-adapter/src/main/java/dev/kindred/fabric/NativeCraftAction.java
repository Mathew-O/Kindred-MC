package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player crafting something from items in its own inventory, in its 2x2 grid or at a
 * crafting table, just as a human would. The ingredients really go and the product really arrives; nothing is
 * made from thin air.
 *
 * Technical: Checkpointed native player 2x2 crafting action using the durable action ledger.
 * Consumes owned ingredients from player inventory and places verified crafted products
 * into inventory with exact item conservation.
 */
final class NativeCraftAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final String recipeName;
    private final BlockPos workstationHint;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private NativePlayerBody.CraftPlan plan;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeCraftAction(NativePlayerBody body, Supplier<ActionLease> lease,
                      ActionAdmission.Writer writer, Consumer<Runnable> dispatch, String recipeName) {
        this(body, lease, writer, dispatch, recipeName, null);
    }

    NativeCraftAction(NativePlayerBody body, Supplier<ActionLease> lease,
                      ActionAdmission.Writer writer, Consumer<Runnable> dispatch, String recipeName, BlockPos workstationHint) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.recipeName = recipeName;
        this.workstationHint = workstationHint;
    }

    /** Moves the crafting on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded crafting cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            var planned = workstationHint != null ? body.planCraft(recipeName, workstationHint) : body.planCraft(recipeName);
            if (planned.isEmpty()) {
                planned = body.planCraft(recipeName);
            }
            if (planned.isEmpty()) {
                return Step.blocked("Insufficient ingredients or unavailable workstation for recipe: " + recipeName, true);
            }
            plan = planned.get();
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_craft",
                            "recipe=" + recipeName + (plan.requiresWorkstation() ? "; workstation=" + plan.workstationPos().toShortString() : "")),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable craft acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Craft close outcome uncertain", true);
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
        if (!before.matches(body)) {
            result = Step.blocked("Owned inventory changed before craft dispatch; no craft issued", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean crafted = body.craft(lease.get(), plan);
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during craft; reconcile", true);
            } else if (crafted) {
                var after = InventoryEvidence.capture(body);
                if (verifyConservation(before, after, plan)) {
                    result = Step.completed(Map.of(
                            "recipe", recipeName,
                            "crafted", Registries.ITEM.getId(plan.expectedResult().getItem()).toString(),
                            "count", String.valueOf(plan.expectedResult().getCount())));
                    admission.verify(after.description() + "; " + result.progress(), "Native crafting verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Crafting conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body)) {
                result = Step.blocked("Craft produced no verified effect", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native craft effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Crafting action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
        }
        return waiting();
    }

    private static boolean verifyConservation(InventoryEvidence before, InventoryEvidence after, NativePlayerBody.CraftPlan plan) {
        Map<Item, Integer> expectedDelta = new HashMap<>();
        expectedDelta.merge(plan.expectedResult().getItem(), plan.expectedResult().getCount(), Integer::sum);
        for (int gridSlot = 0; gridSlot < plan.gridSize(); gridSlot++) {
            int sourceSlot = plan.sourceSlotForGrid(gridSlot);
            if (sourceSlot >= 0 && sourceSlot < before.stacks().length) {
                ItemStack stack = before.stacks()[sourceSlot];
                if (!stack.isEmpty()) {
                    expectedDelta.merge(stack.getItem(), -1, Integer::sum);
                }
            }
        }

        Map<Item, Integer> actualDelta = new HashMap<>();
        for (ItemStack stack : after.stacks()) {
            if (!stack.isEmpty()) {
                actualDelta.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (ItemStack stack : before.stacks()) {
            if (!stack.isEmpty()) {
                actualDelta.merge(stack.getItem(), -stack.getCount(), Integer::sum);
            }
        }

        actualDelta.entrySet().removeIf(entry -> entry.getValue() == 0);
        expectedDelta.entrySet().removeIf(entry -> entry.getValue() == 0);
        return expectedDelta.equals(actualDelta);
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED
                && intent == NativePlayerBody.Intent.CRAFT;
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native crafting verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Craft cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Craft cancelled with uncertain outcome");
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
