package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player naming one of its weapons or tools at an anvil, as a human does, paying the
 * experience levels the anvil asks. Only the name changes; the piece goes back where it was and nothing else
 * it carries changes.
 *
 * Technical: Names a weapon or tool at an anvil (E-16d), as a player does: the piece in one inventory slot
 * put in, the name typed, the named piece taken out and put back where it was, for the levels the anvil asks.
 * Verified against the durable ledger: the piece is the same in every way but its name, nothing else held
 * changed, and exactly the anvil's cost in levels was spent. Using an anvil is a workstation's use under the
 * server's edit policy.
 */
final class NativeRenameAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos anvil;
    private final int slot;
    private final String name;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, opened;
    private CompletableFuture<ActionRecord> flush;
    private int spent = -1;

    /** Giving the piece in an inventory slot the given name at the given anvil. */
    NativeRenameAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                       Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                       Consumer<Runnable> dispatch, BlockPos anvil, int slot, String name) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.anvil = anvil.toImmutable();
        this.slot = slot;
        this.name = name;
    }

    private boolean inReach() {
        return body.entity().getEyePos().squaredDistanceTo(anvil.getX() + 0.5, anvil.getY() + 0.5, anvil.getZ() + 0.5) <= 20.25;
    }

    private String denial() {
        var player = body.entity();
        if (!player.getServerWorld().getBlockState(anvil).isIn(net.minecraft.registry.tag.BlockTags.ANVIL)) return "no anvil there";
        var decision = NativeEdits.useWorkstation(player, setup.get(), anvil, "minecraft:anvil", policy.get());
        return decision.allowed() ? null : decision.reason();
    }

    /** Moves the naming on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded rename cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (slot < 0 || slot >= 36 || player.getInventory().getStack(slot).isEmpty()) return Step.blocked("Nothing in that slot to name", true);
            if (!inReach()) return Step.blocked("The anvil is out of reach", true);
            String denial = denial();
            if (denial != null) return Step.blocked("Anvil policy denied: " + denial, true);
            String key = "block:" + worldId + ":" + player.getServerWorld().getRegistryKey().getValue() + ":" + anvil.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_rename",
                    "anvil=" + anvil.toShortString() + "; slot=" + slot + "; name=" + name), before.description()
                    + "; levels=" + player.experienceLevel, writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable rename acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Rename close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!valid()) {
            result = Step.blocked("The anvil or the inventory changed before the rename; nothing moved", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        NativePlayerBody.Renamed renamed;
        try {
            opened = true;
            renamed = body.rename(lease.get(), anvil, slot, name);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Rename interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (renamed == null || before.matches(body) && renamed.levelsAfter() == renamed.levelsBefore()) {
            if (before.matches(body)) {
                result = Step.blocked(renamed == null ? "The anvil would not open" : "The anvil asked " + renamed.cost()
                        + " levels, more than it has", true);
                admission.noEffect(before.description() + "; nothing changed", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Rename conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        var after = InventoryEvidence.capture(body);
        if (!conserved(after, renamed)) {
            unknown = true;
            result = Step.blocked("Rename conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        spent = renamed.cost();
        KindredMod.LOGGER.info("{} named its {} {} at the anvil at {}, for {} level{}", player.getName().getString(),
                Valuables.id(renamed.before()).substring(10).replace('_', ' '), name, anvil.toShortString(), spent, spent == 1 ? "" : "s");
        result = Step.completed(Map.of("name", name, "levels", String.valueOf(spent)));
        admission.verify(after.description() + "; slot " + slot + " named " + name + " for " + spent + " levels", "Native rename verified");
        return waiting();
    }

    /**
     * The piece named and nothing else of it changed (its prior work aside, which a rename alone leaves as it was); every
     * other slot as it was; the levels spent the anvil's cost.
     */
    private boolean conserved(InventoryEvidence after, NativePlayerBody.Renamed renamed) {
        var stacks = before.stacks();
        if (after.stacks().length != stacks.length) return false;
        for (int i = 0; i < stacks.length; i++) {
            if (i != slot && !ItemStack.areEqual(stacks[i], after.stacks()[i])) return false;
        }
        ItemStack was = stacks[slot].copy(), now = after.stacks()[slot].copy();
        var given = now.get(DataComponentTypes.CUSTOM_NAME);
        if (given == null || !name.equals(given.getString())) return false;
        was.remove(DataComponentTypes.CUSTOM_NAME);
        now.remove(DataComponentTypes.CUSTOM_NAME);
        if (!java.util.Objects.equals(was.get(DataComponentTypes.REPAIR_COST), now.get(DataComponentTypes.REPAIR_COST))) return false;
        return ItemStack.areEqual(was, now) && renamed.cost() > 0 && renamed.levelsAfter() == renamed.levelsBefore() - renamed.cost();
    }

    /** The levels it spent, once verified; -1 before. */
    int spent() { return spent; }

    /** The name it gave. */
    String name() { return name; }

    /** The slot of the piece it named. */
    int slot() { return slot; }

    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive() || !inReach() || !before.matches(body) || denial() != null) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.RENAME
                && anvil.equals(target);
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native rename verified");
            } else if (!opened && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing changed", "Rename cancelled before the anvil was opened");
            } else {
                unknown = true;
                admission.uncertain("Rename cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
