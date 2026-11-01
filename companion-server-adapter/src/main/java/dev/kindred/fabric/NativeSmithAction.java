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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player upgrading a diamond weapon, tool or armour piece to netherite at a smithing
 * table, as a human does. One upgrade template and one netherite ingot are used, the piece keeps its
 * enchantments, name and wear, and nothing else it carries changes.
 *
 * Technical: Upgrades a diamond piece to netherite at a smithing table (E-16c), as a player does: an upgrade
 * template, the piece and a netherite ingot put in, the upgraded piece taken out into the piece's slot.
 * Verified against the durable ledger: the piece is its netherite self with everything else of it kept (its
 * enchantments, name and wear), one template and one ingot spent, and nothing else held changed. Using a
 * smithing table is a workstation's use under the server's policy.
 */
final class NativeSmithAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos table;
    private final int template, slot, material;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, opened;
    private CompletableFuture<ActionRecord> flush;
    private String piece = "";

    /**
     * An upgrade at the given table, with the template, the piece and the ingot from the given inventory
     * slots.
     */
    NativeSmithAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                      Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                      Consumer<Runnable> dispatch, BlockPos table, int template, int slot, int material) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.table = table.toImmutable();
        this.template = template;
        this.slot = slot;
        this.material = material;
    }

    private boolean inReach() {
        return body.entity().getEyePos().squaredDistanceTo(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5) <= 20.25;
    }

    private String denial() {
        var player = body.entity();
        if (!player.getServerWorld().getBlockState(table).isOf(net.minecraft.block.Blocks.SMITHING_TABLE)) return "no smithing table there";
        var decision = NativeEdits.useWorkstation(player, setup.get(), table, "minecraft:smithing_table", policy.get());
        return decision.allowed() ? null : decision.reason();
    }

    /** Moves the smithing on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded smithing cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (slot < 0 || slot >= 36 || player.getInventory().getStack(slot).isEmpty()) return Step.blocked("Nothing in that slot to upgrade", true);
            if (!inReach()) return Step.blocked("The smithing table is out of reach", true);
            String denial = denial();
            if (denial != null) return Step.blocked("Smithing table policy denied: " + denial, true);
            String key = "block:" + worldId + ":" + player.getServerWorld().getRegistryKey().getValue() + ":" + table.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            before = InventoryEvidence.capture(body);
            piece = Valuables.id(player.getInventory().getStack(slot));
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_smith",
                    "table=" + table.toShortString() + "; template=" + template + "; slot=" + slot + "; material=" + material),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable smithing acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Smithing close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!body.connected() || !player.isAlive() || !inReach() || !before.matches(body) || denial() != null
                || claim == null || !claims.holdsAll(claim, System.currentTimeMillis())) {
            result = Step.blocked("The table or the inventory changed before smithing; nothing moved", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        NativePlayerBody.Smithed done;
        try {
            opened = true;
            done = body.smith(lease.get(), table, template, slot, material);
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Smithing interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (done == null || before.matches(body)) {
            if (before.matches(body)) {
                result = Step.blocked(done == null ? "The smithing table would not open" : "The table made nothing of it", true);
                admission.noEffect(before.description() + "; nothing changed", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Smithing conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        var after = InventoryEvidence.capture(body);
        if (!conserved(after, done)) {
            unknown = true;
            result = Step.blocked("Smithing conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        KindredMod.LOGGER.info("{} upgraded its {} to {} at the smithing table at {}", player.getName().getString(),
                piece.substring(10).replace('_', ' '), Valuables.id(done.after()).substring(10).replace('_', ' '), table.toShortString());
        result = Step.completed(Map.of("piece", piece, "now", Valuables.id(done.after())));
        admission.verify(after.description() + "; slot " + slot + " " + piece + " upgraded to " + Valuables.id(done.after()), "Native smithing verified");
        return waiting();
    }

    /**
     * The piece its netherite self in its slot, its enchantments and the rest of it kept; one template and one ingot gone;
     * nothing else changed.
     */
    private boolean conserved(InventoryEvidence after, NativePlayerBody.Smithed done) {
        String was = Valuables.id(done.before()), now = Valuables.id(done.after());
        if (!was.startsWith("minecraft:diamond_") || !now.equals("minecraft:netherite_" + was.substring("minecraft:diamond_".length()))) return false;
        if (!java.util.Objects.equals(done.before().get(DataComponentTypes.ENCHANTMENTS), done.after().get(DataComponentTypes.ENCHANTMENTS))
                || !java.util.Objects.equals(done.before().get(DataComponentTypes.CUSTOM_NAME), done.after().get(DataComponentTypes.CUSTOM_NAME))) return false;
        var change = new HashMap<String, Integer>();
        for (ItemStack s : after.stacks()) if (!s.isEmpty()) change.merge(Valuables.id(s), s.getCount(), Integer::sum);
        for (ItemStack s : before.stacks()) if (!s.isEmpty()) change.merge(Valuables.id(s), -s.getCount(), Integer::sum);
        change.values().removeIf(v -> v == 0);
        return change.equals(Map.of(was, -1, now, 1, "minecraft:netherite_upgrade_smithing_template", -1, "minecraft:netherite_ingot", -1));
    }

    /** The piece it upgraded ("minecraft:diamond_sword"). */
    String piece() { return piece; }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.SMITH
                && table.equals(target);
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native smithing verified");
            } else if (!opened && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing changed", "Smithing cancelled before the table was opened");
            } else {
                unknown = true;
                admission.uncertain("Smithing cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
