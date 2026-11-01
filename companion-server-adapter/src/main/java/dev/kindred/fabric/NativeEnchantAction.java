package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.Enchanting;
import dev.kindred.core.WorkSetup;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
 * In plain terms: an AI player enchanting a weapon, tool or armour piece at an enchanting table, as a human
 * does: it looks at what the table offers and takes the best it can pay for in lapis and levels. Only the
 * piece's enchantments change, and nothing else it carries.
 *
 * Technical: Enchants a weapon, tool or piece of armour at an enchanting table (E-16a), as a player does: the
 * piece in one inventory slot and the lapis in another put in, the table's offers looked at, the best it can
 * pay for taken ({@link Enchanting#choose}), the piece and the lapis left put back. Verified against the
 * durable ledger: the piece is the same in every way but the enchantments it gained, the lapis spent and the
 * levels spent are both the button's number and one, and nothing else held changed. Using a table is a
 * workstation's use under the server's edit policy.
 */
final class NativeEnchantAction extends NativeRecordedAction {
    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos table;
    private final int slot, lapisSlot;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, opened;
    private CompletableFuture<ActionRecord> flush;
    private Map<String, Integer> gained = Map.of();
    private String item = "";

    /** Enchanting the piece in one inventory slot, with lapis from another, at the given table. */
    NativeEnchantAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                        Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                        Consumer<Runnable> dispatch, BlockPos table, int slot, int lapisSlot) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.table = table.toImmutable();
        this.slot = slot;
        this.lapisSlot = lapisSlot;
    }

    private boolean inReach() {
        return body.entity().getEyePos().squaredDistanceTo(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5) <= 20.25;
    }

    private String denial() {
        var player = body.entity();
        if (!player.getServerWorld().getBlockState(table).isOf(net.minecraft.block.Blocks.ENCHANTING_TABLE)) return "no enchanting table there";
        var decision = NativeEdits.useWorkstation(player, setup.get(), table, "minecraft:enchanting_table", policy.get());
        return decision.allowed() ? null : decision.reason();
    }

    /** Moves the enchanting on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded enchanting cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (slot < 0 || slot >= 36 || player.getInventory().getStack(slot).isEmpty()) return Step.blocked("Nothing in that slot to enchant", true);
            if (lapisSlot < 0 || lapisSlot >= 36 || slot == lapisSlot || !player.getInventory().getStack(lapisSlot).isOf(Items.LAPIS_LAZULI)) {
                return Step.blocked("No lapis in that slot", true);
            }
            if (!inReach()) return Step.blocked("The enchanting table is out of reach", true);
            String denial = denial();
            if (denial != null) return Step.blocked("Enchanting table policy denied: " + denial, true);
            String key = "block:" + worldId + ":" + player.getServerWorld().getRegistryKey().getValue() + ":" + table.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            before = InventoryEvidence.capture(body);
            item = Valuables.id(player.getInventory().getStack(slot));
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_enchant",
                    "table=" + table.toShortString() + "; slot=" + slot + "; lapis=" + lapisSlot), before.description()
                    + "; levels=" + player.experienceLevel, writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable enchanting acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Enchanting close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
        if (!valid()) {
            result = Step.blocked("The table or the inventory changed before enchanting; nothing moved", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        var piece = new Enchanting.Piece(slot, item, Map.of());
        int lapis = player.getInventory().getStack(lapisSlot).getCount();
        int levels = player.experienceLevel;
        NativePlayerBody.Enchanted done;
        try {
            opened = true;
            done = body.enchant(lease.get(), table, slot, lapisSlot, offers -> Enchanting.choose(piece, offers, levels, lapis).orElse(-1));
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Enchanting interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
            return waiting();
        }
        if (done == null || done.button() < 0) {
            if (before.matches(body)) {
                result = Step.blocked(done == null ? "The enchanting table would not open" : "Nothing the table offered that it could pay for: "
                        + done.offers(), true);
                admission.noEffect(before.description() + "; nothing changed", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Enchanting conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        var after = InventoryEvidence.capture(body);
        if (!conserved(after, done)) {
            unknown = true;
            result = Step.blocked("Enchanting conservation could not be verified; reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        var now = new HashMap<String, Integer>();
        for (var each : done.after().getEnchantments().getEnchantmentEntries()) {
            each.getKey().getKey().ifPresent(k -> now.put(k.getValue().toString(), each.getIntValue()));
        }
        gained = Map.copyOf(now);
        int cost = done.button() + 1;
        KindredMod.LOGGER.info("{} enchanted its {} at the table at {} (button {} of the offers {}): {}, for {} lapis and {} levels",
                player.getName().getString(), item.substring(10).replace('_', ' '), table.toShortString(), done.button() + 1, done.offers(), gained, cost, cost);
        result = Step.completed(Map.of("button", String.valueOf(done.button()), "enchantments", gained.toString()));
        admission.verify(after.description() + "; slot " + slot + " enchanted " + gained + " for " + cost + " lapis and " + cost + " levels",
                "Native enchanting verified");
        return waiting();
    }

    /**
     * The piece enchanted and nothing else of it changed, every other slot as it was but the lapis's, down by the
     * button's cost; the levels spent the same.
     */
    private boolean conserved(InventoryEvidence after, NativePlayerBody.Enchanted done) {
        int cost = done.button() + 1;
        var stacks = before.stacks();
        if (after.stacks().length != stacks.length) return false;
        for (int i = 0; i < stacks.length; i++) {
            if (i == slot || i == lapisSlot) continue;
            if (!ItemStack.areEqual(stacks[i], after.stacks()[i])) return false;
        }
        ItemStack lapisWas = stacks[lapisSlot], lapisNow = after.stacks()[lapisSlot];
        int left = lapisNow.isEmpty() ? 0 : lapisNow.isOf(Items.LAPIS_LAZULI) ? lapisNow.getCount() : -1;
        if (left != lapisWas.getCount() - cost) return false;
        ItemStack was = stacks[slot].copy(), now = after.stacks()[slot].copy();
        if (!now.hasEnchantments() || was.hasEnchantments()) return false;
        was.remove(DataComponentTypes.ENCHANTMENTS);
        now.remove(DataComponentTypes.ENCHANTMENTS);
        return ItemStack.areEqual(was, now) && done.levelsAfter() == done.levelsBefore() - cost;
    }

    /** What it gained, once verified; empty before. */
    Map<String, Integer> gained() { return gained; }

    /** The item it enchanted ("minecraft:diamond_sword"). */
    String item() { return item; }

    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive() || !inReach() || !before.matches(body) || denial() != null) return false;
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        return !halted && admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && intent == NativePlayerBody.Intent.ENCHANT
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native enchanting verified");
            } else if (!opened && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing changed", "Enchanting cancelled before the table was opened");
            } else {
                unknown = true;
                admission.uncertain("Enchanting cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
