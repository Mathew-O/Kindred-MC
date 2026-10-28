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
 * A step of brewing (E-16b), as a player does it: a glass bottle filled at water ({@code FILL}); a brewing stand of its
 * own loaded with three bottles, one of the ingredient and, when its fuel is spent, one blaze powder ({@code LOAD}); or
 * what it brewed taken out ({@code UNLOAD}). Verified against the durable ledger by what moved: for a fill, one bottle
 * gone and one water bottle come; for a load, exactly what went into the stand gone from the inventory and in the stand;
 * for an unload, exactly what the stand's bottle slots held come into the inventory. Using a stand is a workstation's use
 * under the server's edit policy.
 */
final class NativeBrewAction extends NativeRecordedAction {
    enum Mode { FILL, LOAD, UNLOAD }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final Mode mode;
    private final BlockPos at;
    private final int[] bottles;
    private final int ingredient, fuel, slot;

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private Step result;
    private boolean halted, unknown, used;
    private int waited;
    private CompletableFuture<ActionRecord> flush;
    private Map<String, Integer> moved = Map.of();

    NativeBrewAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                     Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                     Consumer<Runnable> dispatch, Mode mode, BlockPos at, int[] bottles, int ingredient, int fuel, int slot) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.mode = mode;
        this.at = at.toImmutable();
        this.bottles = bottles == null ? new int[0] : bottles.clone();
        this.ingredient = ingredient;
        this.fuel = fuel;
        this.slot = slot;
    }

    /** What an item stack is, for counting what moved: its id, and for a potion what it holds. */
    static String key(ItemStack stack) {
        String id = Valuables.id(stack);
        var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents != null && contents.potion().isPresent()) {
            id += "[" + contents.potion().get().getKey().map(k -> k.getValue().toString()).orElse("?") + "]";
        }
        return id;
    }

    private static Map<String, Integer> counts(ItemStack[] stacks) {
        var out = new HashMap<String, Integer>();
        for (ItemStack s : stacks) if (!s.isEmpty()) out.merge(key(s), s.getCount(), Integer::sum);
        return out;
    }

    private static Map<String, Integer> counts(List<ItemStack> stacks) {
        return counts(stacks.toArray(new ItemStack[0]));
    }

    /** {@code after} less {@code before}, by what each is: what came (positive) and what went (negative). */
    private static Map<String, Integer> change(Map<String, Integer> before, Map<String, Integer> after) {
        var out = new HashMap<String, Integer>(after);
        for (var e : before.entrySet()) out.merge(e.getKey(), -e.getValue(), Integer::sum);
        out.values().removeIf(v -> v == 0);
        return out;
    }

    private String denial() {
        var player = body.entity();
        if (mode == Mode.FILL) return null;
        if (!player.getServerWorld().getBlockState(at).isOf(net.minecraft.block.Blocks.BREWING_STAND)) return "no brewing stand there";
        var decision = NativeEdits.useWorkstation(player, setup.get(), at, "minecraft:brewing_stand", policy.get());
        return decision.allowed() ? null : decision.reason();
    }

    private boolean inReach() {
        return body.entity().getEyePos().squaredDistanceTo(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) <= 20.25;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded brewing step cannot replay after cancellation/restart", true);
        var player = body.entity();
        if (admission == null) {
            if (!inReach()) return Step.blocked(mode == Mode.FILL ? "The water is out of reach" : "The brewing stand is out of reach", true);
            String denial = denial();
            if (denial != null) return Step.blocked("Brewing stand policy denied: " + denial, true);
            if (mode != Mode.FILL) {
                String key = "block:" + worldId + ":" + player.getServerWorld().getRegistryKey().getValue() + ":" + at.toShortString();
                claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
                if (claim == null) return Step.blocked("Another task holds this resource claim", true);
            }
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_brew",
                    "mode=" + mode + "; at=" + at.toShortString() + "; bottles=" + java.util.Arrays.toString(bottles) + "; ingredient=" + ingredient
                            + "; fuel=" + fuel + "; slot=" + slot), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable brewing acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Brewing close outcome uncertain", true);
                }
                return result;
            }
            if (admission.phase() == ActionAdmission.Phase.VERIFIED || admission.phase() == ActionAdmission.Phase.FAILED
                    || admission.phase() == ActionAdmission.Phase.UNCERTAIN) return result;
            return waiting();
        }
        if (!used) {
            if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
            if (!body.connected() || !player.isAlive() || !before.matches(body) || denial() != null
                    || claim != null && !claims.holdsAll(claim, System.currentTimeMillis())) {
                result = Step.blocked("The stand or the inventory changed first; nothing moved", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            try {
                used = true;
                if (mode == Mode.FILL) {
                    if (!body.fillBottle(lease.get(), slot, at)) return settle(null, "No bottle filled: no still water within reach, or no bottle in hand");
                } else {
                    var stand = body.useStand(lease.get(), at, bottles, ingredient, fuel, mode == Mode.UNLOAD);
                    return settle(stand, stand == null ? "The brewing stand would not open" : null);
                }
            } catch (RuntimeException failure) {
                unknown = true;
                result = Step.blocked("Brewing interrupted with an unknown outcome; reconcile", true);
                if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(result.reason());
                return waiting();
            }
        }
        // A bottle fills on the click itself; a tick for the inventory to show it.
        if (++waited < 2) return waiting();
        return settle(null, null);
    }

    private Step settle(NativePlayerBody.Stand stand, String failure) {
        var after = InventoryEvidence.capture(body);
        var change = change(counts(before.stacks()), counts(after.stacks()));
        if (failure != null || change.isEmpty()) {
            if (change.isEmpty()) {
                result = Step.blocked(failure != null ? failure : "Nothing moved", true);
                admission.noEffect(before.description() + "; nothing changed", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Brewing conservation could not be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
            return waiting();
        }
        boolean ok = switch (mode) {
            // One bottle gone, one water bottle come.
            case FILL -> change.equals(Map.of("minecraft:glass_bottle", -1, "minecraft:potion[minecraft:water]", 1));
            // What left the inventory is what the stand gained.
            case LOAD -> stand != null && negate(change).equals(change(counts(stand.before()), counts(stand.after())));
            // What came into the inventory is what the stand's bottles were.
            case UNLOAD -> stand != null && change.equals(negate(change(counts(stand.before()), counts(stand.after()))));
        };
        if (!ok) {
            unknown = true;
            result = Step.blocked("Brewing conservation could not be verified (" + change + "); reconcile", true);
            admission.uncertain(result.reason());
            return waiting();
        }
        moved = Map.copyOf(change);
        KindredMod.LOGGER.info("{} brewing: {} at {}: {}{}", body.entity().getName().getString(), mode.name().toLowerCase(java.util.Locale.ROOT),
                at.toShortString(), change, stand == null ? "" : ", the stand brewing " + stand.brewTime() + " ticks, fuel " + stand.fuel());
        result = Step.completed(Map.of("moved", change.toString(), "brewTime", String.valueOf(stand == null ? 0 : stand.brewTime())));
        admission.verify(after.description() + "; " + mode + " " + change, "Native brewing verified");
        return waiting();
    }

    private static Map<String, Integer> negate(Map<String, Integer> m) {
        var out = new HashMap<String, Integer>();
        m.forEach((k, v) -> out.put(k, -v));
        return out;
    }

    /** What moved, by what each is (into the inventory positive), once verified. */
    Map<String, Integer> moved() { return moved; }

    Mode mode() { return mode; }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        return intent == NativePlayerBody.Intent.BREW && at.equals(target) || mode == Mode.FILL && intent == NativePlayerBody.Intent.SELECT_SLOT;
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native brewing verified");
            } else if (!used && before.matches(body)) {
                admission.noEffect(before.description() + "; nothing changed", "Brewing cancelled before anything moved");
            } else {
                unknown = true;
                admission.uncertain("Brewing cancelled with an unverified inventory");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
