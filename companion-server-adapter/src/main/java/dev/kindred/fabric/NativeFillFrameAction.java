package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player putting one of its own eyes of ender into a frame of the End portal, as a
 * human clicks it in. Exactly one eye is used, a frame that already has one is left alone, and it reports
 * whether the portal has opened.
 *
 * Technical: Puts one owned eye of ender into a frame block of the End portal, as a player clicks it (E-39a):
 * the frame then shows the eye, exactly one eye is spent under the durable ledger, and the step reports
 * whether the ring is full and the portal open. A frame that already holds an eye is never clicked; nothing
 * else of the player's is touched.
 */
final class NativeFillFrameAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final BlockPos frame;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private Step result;
    private boolean halted, unknown, used;
    private int waited;
    private CompletableFuture<ActionRecord> flush;

    /** Filling the given frame block with the eye in a hotbar slot. */
    NativeFillFrameAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                          int slot, BlockPos frame) {
        this.body = body;
        this.lease = lease;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.frame = frame.toImmutable();
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A frame is filled from an owned hotbar slot 0..8");
    }

    private boolean holdsEye() {
        var state = body.entity().getServerWorld().getBlockState(frame);
        return state.isOf(Blocks.END_PORTAL_FRAME) && state.get(EndPortalFrameBlock.EYE);
    }

    /** Moves the frame filling on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded frame fill cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (!player.getInventory().getStack(slot).isOf(Items.ENDER_EYE)) return Step.blocked("The hotbar slot holds no eye of ender", true);
            var state = player.getServerWorld().getBlockState(frame);
            if (!state.isOf(Blocks.END_PORTAL_FRAME)) return Step.blocked("No End portal frame there", true);
            if (state.get(EndPortalFrameBlock.EYE)) return Step.blocked("That frame already holds an eye", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_fill_frame",
                    "slot=" + slot + "; frame=" + frame.toShortString()), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable frame fill acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Frame fill close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!used) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!before.matches(body) || !player.isAlive() || holdsEye()) {
                result = Step.blocked("The inventory or the frame changed before the eye went in; nothing done", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                if (!body.fillFrame(lease.get(), slot, frame)) return settle("Frame fill authority denied or out of reach");
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Frame fill interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
            used = true;
        }
        // Vanilla sets the eye on the click itself; a tick later the portal stands if the ring is full.
        if (++waited < 2) return waiting();
        return settle(null);
    }

    private Step settle(String failure) {
        var after = InventoryEvidence.capture(body);
        int spent = count(before, Items.ENDER_EYE) - count(after, Items.ENDER_EYE);
        boolean set = holdsEye();
        if (spent == 0 && !set && conserved(after, 0)) {
            result = Step.blocked(failure != null ? failure : "The eye did not go in", true);
            admission.noEffect(before.description() + "; nothing owned lost", result.reason());
            return waiting();
        }
        if (spent != 1 || !set || !conserved(after, 1)) {
            unknown = true;
            result = Step.blocked("Frame fill conservation check failed; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        boolean open = portalNear();
        result = Step.completed(Map.of("frame", frame.toShortString(), "portalOpen", String.valueOf(open)));
        admission.verify(after.description() + "; one eye into the frame at " + frame.toShortString() + (open ? "; the End portal is open" : ""),
                "Native frame fill verified");
        return waiting();
    }

    /** The End portal's own blocks within the ring the frame belongs to. */
    private boolean portalNear() {
        var world = body.entity().getServerWorld();
        for (BlockPos at : BlockPos.iterate(frame.add(-4, -1, -4), frame.add(4, 1, 4))) {
            if (world.getBlockState(at).isOf(Blocks.END_PORTAL)) return true;
        }
        return false;
    }

    private static int count(InventoryEvidence evidence, Item item) {
        int total = 0;
        for (ItemStack stack : evidence.stacks()) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }

    /** At most {@code eyes} eyes gone and nothing else lost. */
    private boolean conserved(InventoryEvidence after, int eyes) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == Items.ENDER_EYE ? eyes : 0)) return false;
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || used) return false;
        return intent == NativePlayerBody.Intent.FRAME && frame.equals(target) || intent == NativePlayerBody.Intent.SELECT_SLOT;
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
            int spent = count(before, Items.ENDER_EYE) - count(after, Items.ENDER_EYE);
            if (result != null && result.status() == Status.COMPLETED && !unknown) {
                admission.verify(after.description() + "; " + result.progress(), "Native frame fill verified");
            } else if (spent == 0 && !holdsEye() && conserved(after, 0)) {
                admission.noEffect(before.description() + "; nothing owned lost", "Frame fill cancelled before the eye went in");
            } else if (spent == 1 && holdsEye() && conserved(after, 1)) {
                admission.verify(after.description() + "; one eye into the frame at " + frame.toShortString(), "Native frame fill verified");
            } else {
                unknown = true;
                admission.uncertain("Frame fill cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
