package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native Nether portal ignition action using the durable action ledger (E-15).
 * Evaluates server edit policy, verifies portal frame geometry, strikes the frame interior
 * using owned flint & steel or fire charge, incurs 1 durability wear or consumption,
 * and verifies NETHER_PORTAL block creation and inventory conservation.
 */
final class NativeIgnitePortalAction extends NativeRecordedAction {
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

    private ActionAdmission admission;
    private InventoryEvidence before;
    private ClaimRegistry.Grant claim;
    private String claimKey;
    private String dimensionKey;
    private BlockPos portalPos;
    private long setupRevision;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeIgnitePortalAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId,
                             Supplier<WorkSetup> setup, Supplier<EditPolicy.ServerPolicy> policy,
                             ClaimRegistry claims, ActionAdmission.Writer writer,
                             Consumer<Runnable> dispatch, BlockPos target, int slot) {
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
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("Ignition needs an owned hotbar slot 0..8");
        var s = setup.get();
        if (s == null || s.area() == null) throw new IllegalArgumentException("No work area is assigned.");
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) {
            return Step.blocked("Recorded portal ignition cannot replay after cancellation/restart", true);
        }
        if (admission == null) {
            if (slot < 0 || slot > 8) {
                return Step.blocked("Portal ignition needs an owned hotbar slot 0..8", true);
            }
            var player = body.entity();
            var world = player.getServerWorld();
            var stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || (stack.getItem() != Items.FLINT_AND_STEEL && stack.getItem() != Items.FIRE_CHARGE)) {
                return Step.blocked("Owned hotbar slot does not hold flint and steel or fire charge", true);
            }

            if (!world.isChunkLoaded(target)) {
                return Step.blocked("Portal target is in an unloaded chunk", true);
            }

            portalPos = target;
            if (world.getBlockState(target).isOf(Blocks.OBSIDIAN)) {
                portalPos = target.up();
            }
            if (!world.isChunkLoaded(portalPos)) {
                return Step.blocked("Portal ignition opening is in an unloaded chunk", true);
            }
            if (!world.getBlockState(portalPos).isAir() && !world.getBlockState(portalPos).isOf(Blocks.FIRE)) {
                return Step.blocked("Portal opening position is obstructed", true);
            }
            if (player.getEyePos().squaredDistanceTo(portalPos.getX() + 0.5, portalPos.getY() + 0.5, portalPos.getZ() + 0.5) > 25.0) {
                return Step.blocked("Portal ignition target is out of reach", true);
            }

            dimensionKey = world.getRegistryKey().getValue().toString();
            setupRevision = setup.get() != null ? setup.get().revision() : 0;

            var point = new WorkSetup.BlockPoint(portalPos.getX(), portalPos.getY(), portalPos.getZ());
            var decision = EditPolicy.evaluate(setup.get(), EditPolicy.Kind.PLACE, dimensionKey, point, "minecraft:nether_portal", policy.get());
            if (!decision.allowed()) {
                return Step.blocked(decision.reason(), true);
            }

            claimKey = "block:" + worldId + ":" + dimensionKey + ":" + portalPos.toShortString();
            claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
            if (claim == null) {
                return Step.blocked("Another task holds this resource claim", true);
            }

            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(
                    ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_ignite_portal",
                            "tool=" + stack.getItem() + "; pos=" + portalPos.toShortString() + "; setupRevision=" + setupRevision),
                    before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable portal ignition acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Portal ignition close outcome uncertain", true);
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
            result = Step.blocked("Portal ignition preconditions, claim, or policy no longer valid; no ignition occurred", true);
            flush = admission.close();
            return waiting();
        }
        if (admission.take().isEmpty()) return waiting();
        try {
            boolean ignited = body.ignitePortal(lease.get(), slot, target);
            var world = body.entity().getServerWorld();
            if (admission.phase() == ActionAdmission.Phase.CLOSED) {
                unknown = true;
                result = Step.blocked("Authority ended during portal ignition; reconcile", true);
            } else if (ignited && world.getBlockState(portalPos).isOf(Blocks.NETHER_PORTAL)) {
                var after = InventoryEvidence.capture(body);
                if (verifyConservation(before, after)) {
                    result = Step.completed(Map.of(
                            "ignited", "true",
                            "x", String.valueOf(portalPos.getX()),
                            "y", String.valueOf(portalPos.getY()),
                            "z", String.valueOf(portalPos.getZ())));
                    admission.verify(after.description() + "; " + result.progress(), "Native portal ignition verified");
                } else {
                    unknown = true;
                    result = Step.blocked("Portal ignition conservation check failed; reconcile", true);
                    admission.uncertain(result.reason());
                }
            } else if (before.matches(body)) {
                result = Step.blocked("Portal ignition produced no effect", true);
                admission.noEffect(before.description() + "; all owned slots unchanged", result.reason());
            } else {
                unknown = true;
                result = Step.blocked("Native portal ignition effect cannot be verified; reconcile", true);
                admission.uncertain(result.reason());
            }
        } catch (RuntimeException failure) {
            unknown = true;
            result = Step.blocked("Portal ignition action interrupted with an unknown outcome; reconcile", true);
            if (admission.phase() == ActionAdmission.Phase.ISSUED) admission.uncertain(failure.getMessage());
        }
        return waiting();
    }

    private boolean validAuthorityAndPreconditions() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        var world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        if (!world.isChunkLoaded(portalPos)) return false;

        var state = world.getBlockState(portalPos);
        if (!state.isAir() && !state.isOf(Blocks.FIRE) && !state.isOf(Blocks.NETHER_PORTAL)) return false;
        if (player.getEyePos().squaredDistanceTo(portalPos.getX() + 0.5, portalPos.getY() + 0.5, portalPos.getZ() + 0.5) > 25.0) return false;

        if (before != null && !before.matches(body)) return false;
        var currentSlotStack = player.getInventory().getStack(slot);
        if (currentSlotStack.isEmpty()) return false;
        if (currentSlotStack.getItem() != Items.FLINT_AND_STEEL && currentSlotStack.getItem() != Items.FIRE_CHARGE) return false;

        var currentSetup = setup.get();
        if (currentSetup == null || currentSetup.revision() != setupRevision) return false;
        var point = new WorkSetup.BlockPoint(portalPos.getX(), portalPos.getY(), portalPos.getZ());
        var decision = EditPolicy.evaluate(currentSetup, EditPolicy.Kind.PLACE, dimensionKey, point, "minecraft:nether_portal", policy.get());
        if (!decision.allowed()) return false;

        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    private boolean verifyConservation(InventoryEvidence before, InventoryEvidence after) {
        if (before.stacks().length != after.stacks().length) return false;
        for (int i = 0; i < before.stacks().length; i++) {
            ItemStack b = before.stacks()[i];
            ItemStack a = after.stacks()[i];
            if (i == slot) {
                if (b.isOf(Items.FLINT_AND_STEEL)) {
                    if (a.isEmpty()) {
                        // Broke from durability wear
                        if (b.getDamage() + 1 < b.getMaxDamage()) return false;
                    } else {
                        if (!a.isOf(Items.FLINT_AND_STEEL) || a.getCount() != 1) return false;
                        if (a.getDamage() != b.getDamage() + 1) return false;
                    }
                } else if (b.isOf(Items.FIRE_CHARGE)) {
                    if (a.isEmpty()) {
                        if (b.getCount() != 1) return false;
                    } else {
                        if (!a.isOf(Items.FIRE_CHARGE) || a.getCount() != b.getCount() - 1) return false;
                    }
                } else {
                    return false;
                }
            } else {
                if (b.getCount() != a.getCount() || !ItemStack.areItemsAndComponentsEqual(b, a)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos targetPos) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT) return true;
        if (intent == NativePlayerBody.Intent.IGNITE_PORTAL) {
            return targetPos == null || portalPos.equals(targetPos) || target.equals(targetPos);
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
                admission.verify(InventoryEvidence.capture(body).description() + "; " + result.progress(), "Native portal ignition verified");
            } else if (before != null && before.matches(body)) {
                admission.noEffect(before.description() + "; all owned slots unchanged", "Portal ignition cancelled with intact inventory");
            } else {
                unknown = true;
                admission.uncertain("Portal ignition cancelled with uncertain outcome");
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
