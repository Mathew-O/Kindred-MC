package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player breaking a block the normal survival way with the best tool it has, then
 * walking over the drops to pick them up. The block itself decides what drops, nothing reaches the inventory
 * except by being picked up, and it breaks only what the server's rules for roaming players allow.
 *
 * Technical: One recorded survival break of any block the server's roaming policy allows, with the best owned
 * tool, then collection of that break's own drops by walking over them. The block's loot table decides the
 * drops; the only way anything enters the inventory is vanilla pickup.
 */
final class NativeBreakAction extends NativeRecordedAction {
    private static final int COLLECT_TICKS = 70;

    private enum Phase { PREPARING, SWAPPING, MINING, COLLECTING, DONE }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos target;
    private final BlockState state;
    private final String resource, claimKey;
    private final boolean collect;
    private final boolean guarded;
    private final int toolSlot;
    private final int hotbarSlot;
    private final long priorReceipt;
    private final Set<UUID> preexistingDrops = new HashSet<>();
    private final Set<UUID> drops = new HashSet<>();
    private Phase phase = Phase.PREPARING;
    private ActionLease issuedLease;
    private ClaimRegistry.Grant claim;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private boolean halted, unknown, broken;
    private int collectTicks;
    private Step result;
    private CompletableFuture<ActionRecord> flush;
    private net.minecraft.util.math.Vec3d anchor;
    private boolean holding;
    private final boolean underfoot;

    /**
     * Breaking the given block, picking up its drops if asked; when guarded, a block by lava may be mined
     * where water will fill the gap.
     */
    NativeBreakAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                      ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos target, boolean collect,
                      boolean guarded) {
        this(body, lease, worldId, policy, claims, writer, dispatch, target, collect, guarded, false);
    }

    /** With {@code underfoot}, the block the player stands on, down its own pillar a step at a time (E-39c). */
    NativeBreakAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<EditPolicy.ServerPolicy> policy,
                      ClaimRegistry claims, ActionAdmission.Writer writer, Consumer<Runnable> dispatch, BlockPos target, boolean collect,
                      boolean guarded, boolean underfoot) {
        this.underfoot = underfoot;
        this.body = body; this.lease = lease; this.policy = policy; this.claims = claims; this.writer = writer;
        this.dispatch = dispatch; this.target = target.toImmutable(); this.collect = collect; this.guarded = guarded;
        var player = body.entity();
        var world = player.getServerWorld();
        if (!world.isChunkLoaded(target)) throw new IllegalArgumentException("Break target is unloaded");
        state = world.getBlockState(target);
        String denial = RoamingEdits.breakDenial(player, target, policy.get(), guarded, underfoot);
        if (denial != null) throw new IllegalArgumentException(denial);
        body.faceBlock(target);
        if (!body.miningEligible(target)) throw new IllegalArgumentException("Break target is out of reach or not visible");
        resource = Registries.BLOCK.getId(state.getBlock()).toString();
        int tool = bestTool(body, state);
        // A block that needs a tool still gives way to a bare hand, slowly and dropping nothing: only a clearing
        // (nothing collected) may be dug like that, as a player punches through rock with no pickaxe left.
        // In creative (a build battle's only, M-04a) any block gives way at once, dropping nothing, tool or none.
        if (tool == -2 && collect && !player.isCreative()) throw new IllegalArgumentException("No owned tool can harvest " + resource);
        toolSlot = tool == -2 ? -1 : tool;
        hotbarSlot = toolSlot >= 0 && toolSlot < 9 ? toolSlot : toolSlot < 0 ? bareHandSlot(body) : swapDestination(body);
        priorReceipt = body.miningReceipt().map(NativePlayerBody.MiningReceipt::sequence).orElse(0L);
        claimKey = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + target.toShortString();
    }

    /**
     * Inventory slot holding the fastest owned tool that can harvest {@code state}: -1 means the bare hand is as
     * good as anything owned, -2 means the block needs a tool the player does not have.
     */
    static int bestTool(NativePlayerBody body, BlockState state) {
        var inventory = body.entity().getInventory();
        boolean required = state.isToolRequired();
        // Plain rock is dug with a stone pickaxe if there is one, else the humblest that can, keeping better ones for ore.
        boolean plainRock = state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.COBBLED_DEEPSLATE);
        int best = -1;
        float bestSpeed = required ? 0 : 1.0f;
        int bestTier = Integer.MAX_VALUE;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            if (required && !stack.isSuitableFor(state)) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed <= 1.0f && !required) continue;
            int tier = PortalRunCampaign.tier(stack.getItem());
            tier = tier == 2 ? 0 : tier; // A stone pickaxe first: fast enough for rock and cheap to replace.
            boolean better = plainRock && speed > 1.0f
                    ? tier < bestTier || tier == bestTier && (speed > bestSpeed || best >= 9 && slot < 9)
                    // Prefer a hotbar copy of an equally good tool so no inventory move is needed.
                    : speed > bestSpeed || speed == bestSpeed && best >= 9 && slot < 9;
            if (better) {
                bestSpeed = speed;
                bestTier = tier;
                best = slot;
            }
        }
        return required && best < 0 ? -2 : best;
    }

    /**
     * Hotbar slot to dig with when no owned tool helps: an empty hand, else something that takes no wear, so a
     * pickaxe is not worn down on dirt the bare hand breaks just as fast.
     */
    private static int bareHandSlot(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        if (!inventory.getStack(inventory.selectedSlot).isDamageable()) return inventory.selectedSlot;
        for (int slot = 0; slot < 9; slot++) if (inventory.getStack(slot).isEmpty()) return slot;
        for (int slot = 0; slot < 9; slot++) if (!inventory.getStack(slot).isDamageable()) return slot;
        return inventory.selectedSlot;
    }

    /**
     * Keep the spot mining started from, as a player taps the keys against a water current. Drifting away can take
     * the block out of sight and end the break.
     */
    private void holdPosition() {
        var player = body.entity();
        double dx = anchor.x - player.getX(), dz = anchor.z - player.getZ();
        boolean drifted = dx * dx + dz * dz > 0.15 * 0.15;
        // Afloat under a block it digs above its eyes, it treads water to keep its head up, as a player digs a step out
        // of a flooded pit: one that does nothing sinks, and with its eyes under the dig goes five times slower (F-25c4t).
        boolean tread = player.isTouchingWater() && !player.isOnGround() && target.getY() + 0.5 > player.getEyeY();
        if (!drifted && !holding && !tread) return;
        holding = drifted || tread;
        float yaw = drifted ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : player.getYaw();
        body.move(issuedLease, drifted ? 0.5f : 0f, 0, tread, false, yaw);
    }

    private static int swapDestination(NativePlayerBody body) {
        var inventory = body.entity().getInventory();
        for (int slot = 0; slot < 9; slot++) if (inventory.getStack(slot).isEmpty()) return slot;
        // Otherwise displace whatever is in the last hotbar slot into the tool's old place.
        return 8;
    }

    private boolean intact() { return body.entity().getWorld().isChunkLoaded(target) && body.entity().getWorld().getBlockState(target) == state; }
    private boolean ownsClaim() { return claim != null && claims.holdsAll(claim, System.currentTimeMillis()); }
    private boolean eligible() {
        return intact() && body.miningEligible(target) && RoamingEdits.breakDenial(body.entity(), target, policy.get(), guarded, underfoot) == null;
    }
    private boolean receipt() {
        return body.miningReceipt().filter(r -> r.sequence() > priorReceipt && r.position().equals(target)
                && r.previousBlockState() == Block.getRawIdFromState(state)).isPresent();
    }
    private Step waiting() { return Step.running(Map.of("phase", phase.name(), "durableAction", phase()), false); }

    /** Moves the breaking on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded break never replays a saved attempt", true);
        if (admission == null) {
            issuedLease = lease.get();
            claim = claims.acquire(issuedLease.taskId(), issuedLease.sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 20_000).orElse(null);
            if (claim == null) return Step.blocked("Another task holds this block", true);
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_break",
                    resource + " at " + target.toShortString()), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable break acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Break close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        var player = body.entity();
        switch (phase) {
            case PREPARING -> {
                if (admission.phase() != ActionAdmission.Phase.READY) return waiting();
                if (!ownsClaim() || !eligible()) {
                    result = Step.blocked("Break preconditions changed before dispatch; nothing was broken", true);
                    flush = admission.close();
                    return waiting();
                }
                if (admission.take().isEmpty()) return waiting();
                var world = player.getServerWorld();
                for (ItemEntity drop : world.getEntitiesByClass(ItemEntity.class, new Box(target).expand(4.0), d -> true)) {
                    preexistingDrops.add(drop.getUuid());
                }
                phase = toolSlot >= 9 ? Phase.SWAPPING : Phase.MINING;
                if (phase == Phase.SWAPPING && !body.swapSlots(issuedLease, toolSlot, hotbarSlot)) {
                    settle("Tool could not be moved to the hotbar", false);
                    return waiting();
                }
                phase = Phase.MINING;
                anchor = player.getPos();
                body.faceBlock(target);
                boolean started = body.selectHotbar(issuedLease, hotbarSlot) && body.mine(issuedLease, target);
                if (!started) settle("Native break controls denied", false);
                return waiting();
            }
            case MINING -> {
                if (receipt()) {
                    broken = true;
                    body.stopInputs();
                    var world = player.getServerWorld();
                    for (ItemEntity drop : world.getEntitiesByClass(ItemEntity.class, new Box(target).expand(4.0), d -> true)) {
                        if (!preexistingDrops.contains(drop.getUuid())) drops.add(drop.getUuid());
                    }
                    phase = collect && !drops.isEmpty() ? Phase.COLLECTING : Phase.DONE;
                    if (phase == Phase.DONE) settle("Native break observed", true);
                    return waiting();
                }
                if (!intact()) {
                    settle("The block changed before an attributable break", false);
                    return waiting();
                }
                if (!body.miningActive(target)) {
                    settle("Mining was interrupted before the block broke", false);
                    return waiting();
                }
                if (!ownsClaim()) settle("Block claim expired", false);
                else holdPosition();
                return Step.running(Map.of("phase", phase.name(), "durableAction", phase(), "ticks", String.valueOf(player.age)), false);
            }
            case COLLECTING -> {
                ItemEntity next = null;
                double nextDistance = Double.MAX_VALUE;
                var world = player.getServerWorld();
                for (UUID id : drops) {
                    if (world.getEntity(id) instanceof ItemEntity drop && drop.isAlive()) {
                        double d = drop.squaredDistanceTo(player);
                        if (d < nextDistance) { nextDistance = d; next = drop; }
                    }
                }
                if (next == null || ++collectTicks > COLLECT_TICKS) {
                    body.stopInputs();
                    phase = Phase.DONE;
                    settle("Native break observed", true);
                    return waiting();
                }
                double dx = next.getX() - player.getX();
                double dz = next.getZ() - player.getZ();
                if (dx * dx + dz * dz > 0.25 || Math.abs(next.getY() - player.getY()) > 1.2) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    // A drop that fell down a shaft or into a cave is not followed over the edge.
                    if (!new NativeNavigator(world).safeHeading(player, yaw)) {
                        body.stopInputs();
                        phase = Phase.DONE;
                        settle("Native break observed; the drop fell where it is unsafe to follow", true);
                        return waiting();
                    }
                    boolean jump = player.isOnGround() && (player.horizontalCollision || next.getY() > player.getY() + 0.6);
                    body.move(issuedLease, dx * dx + dz * dz < 1.0 ? 0.4f : 0.9f, 0, jump, false, yaw);
                } else {
                    body.stopInputs();
                }
                return Step.running(Map.of("phase", phase.name(), "collect", String.valueOf(collectTicks)), false);
            }
            default -> {
                return waiting();
            }
        }
    }

    private void settle(String reason, boolean completed) {
        body.stopInputs();
        if (broken || receipt()) {
            int gained = InventoryEvidence.capture(body).itemTotal() - before.itemTotal();
            admission.verify(InventoryEvidence.capture(body).description() + "; broke=" + resource + "; pickedUp=" + gained,
                    "Native survival break with vanilla loot and collision pickup");
            result = completed ? Step.completed(Map.of("broken", resource, "target", target.toShortString(), "pickedUp", String.valueOf(gained)))
                    : Step.blocked(reason + "; the break itself happened", true);
        } else if (before.sameContents(body)) {
            admission.noEffect(before.description() + "; inventory contents unchanged", reason);
            result = Step.blocked(reason + "; nothing was broken", true);
        } else if (intact() && before.onlyGained(body)) {
            // The block stands as it was and nothing held is gone: what is new was picked up from the ground by
            // walking over it (a drop a step earlier left a tick behind), not anything this break made.
            admission.noEffect(InventoryEvidence.capture(body).description() + "; target intact, loose items picked up", reason);
            result = Step.blocked(reason + "; nothing was broken", true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; inventory changed without an attributable break");
            result = Step.blocked("Native break outcome requires reconciliation", true);
        }
    }

    @Override boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || !ownsClaim()) return false;
        return switch (intent) {
            case SWAP -> phase == Phase.SWAPPING;
            case SELECT_SLOT -> phase == Phase.MINING;
            case MINE -> phase == Phase.MINING && target.equals(requested) && eligible()
                    && body.entity().getInventory().selectedSlot == hotbarSlot;
            case MOVE -> phase == Phase.COLLECTING || phase == Phase.MINING;
            default -> false;
        };
    }
    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    @Override CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) { if (claim != null) claims.release(claim); return flush; }
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) settle("Authority ended", false);
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        if (claim != null) claims.release(claim);
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
