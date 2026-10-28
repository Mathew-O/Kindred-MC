package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Checkpointed native player log gathering with survival mining timing, owned tool wear,
 * patch containment, and attributable drop pickup into player inventory.
 */
final class NativeGatherAction extends NativeRecordedAction {
    private enum Phase { SELECT_TARGET, MINING, COLLECTING_DROPS, TRANSITION_TO_NEXT, FINISHED }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int requestedCount;
    private final int slot;

    private Phase phase = Phase.SELECT_TARGET;
    private int initialInventory = -1;
    private int targetTotal = -1;
    private Block targetBlock;
    private Item targetItem;
    private String resource;

    private BlockPos currentTarget;
    private BlockState currentState;
    private ItemStack toolSnapshot;
    private long setupRevision;
    private long priorReceipt;
    private String claimKey;
    private ActionLease issuedLease;
    private ClaimRegistry.Grant claim;
    private ActionAdmission admission;
    private boolean startedMining;
    private boolean halted;
    private boolean unknown;
    private int dropTicks;
    private Step finalStep;
    private CompletableFuture<ActionRecord> flush;

    private int gatherSubstep = 0;
    private final java.util.Set<UUID> trackedDropUuids = new java.util.HashSet<>();
    private final java.util.Set<UUID> preExistingDrops = new java.util.HashSet<>();
    private int inventoryBeforePickup = -1;

    NativeGatherAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                       Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                       Consumer<Runnable> dispatch, int requestedCount, int slot) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.requestedCount = requestedCount;
        this.slot = slot;

        if (requestedCount < 1 || requestedCount > 32) {
            throw new IllegalArgumentException("Gather count must be 1..32");
        }
        if (slot < 0 || slot > 8) {
            throw new IllegalArgumentException("Mining needs an owned hotbar slot 0..8");
        }
    }

    private WorkSetup.BlockPoint point(BlockPos pos) {
        return new WorkSetup.BlockPoint(pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean ownsClaim() {
        return claim != null && claims.holdsAll(claim, System.currentTimeMillis());
    }

    private Optional<NativePlayerBody.MiningReceipt> receipt() {
        if (currentTarget == null || currentState == null || issuedLease == null) return Optional.empty();
        return body.miningReceipt().filter(r -> r.sequence() > priorReceipt && r.lease().equals(issuedLease)
                && r.position().equals(currentTarget) && r.previousBlockState() == Block.getRawIdFromState(currentState));
    }

    private String evidence() {
        var actual = body.entity().getInventory().getStack(slot);
        return "target=" + (currentTarget != null ? currentTarget.toShortString() : "none") + "; observed="
                + (currentTarget != null && body.entity().getWorld().isChunkLoaded(currentTarget)
                ? body.entity().getWorld().getBlockState(currentTarget) : "unloaded") + "; toolSlot=" + slot
                + "; tool=" + Registries.ITEM.getId(actual.getItem()) + "; count=" + actual.getCount() + "; damage=" + actual.getDamage()
                + "; inventoryLogs=" + (targetItem != null ? ItemMoves.count(body.entity().getInventory(), targetItem) : 0);
    }

    private Map<String, String> currentProgress() {
        int current = targetItem != null ? ItemMoves.count(body.entity().getInventory(), targetItem) : 0;
        int verified = initialInventory >= 0 ? Math.max(0, current - initialInventory) : 0;
        int remaining = targetTotal >= 0 ? Math.max(0, targetTotal - current) : requestedCount;
        return Map.of(
                "initial", String.valueOf(initialInventory >= 0 ? initialInventory : current),
                "target", String.valueOf(targetTotal >= 0 ? targetTotal : current + requestedCount),
                "collected", String.valueOf(verified),
                "remaining", String.valueOf(remaining),
                "need", String.valueOf(requestedCount)
        );
    }

    private Step waiting() {
        return Step.running(currentProgress(), false);
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (fresh) {
            halted = false;
            flush = null;
            admission = null;
            phase = Phase.SELECT_TARGET;
            currentTarget = null;
            currentState = null;
            startedMining = false;
            dropTicks = 0;
            finalStep = null;
            gatherSubstep = 0;
            trackedDropUuids.clear();
            preExistingDrops.clear();
            inventoryBeforePickup = -1;
        }
        if (halted) return Step.blocked("Native gather cancelled", true);
        if (finalStep != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) {
                    unknown = true;
                    return Step.blocked("Gather close outcome uncertain", true);
                }
                return finalStep;
            }
            if (admission != null) {
                return switch (admission.phase()) {
                    case VERIFIED, FAILED, UNCERTAIN -> finalStep;
                    default -> waiting();
                };
            }
            return finalStep;
        }

        var currentSetup = setup.get();
        if (currentSetup.patch() == null) {
            return Step.blocked("no resource patch is assigned", true);
        }
        Identifier blockId = Identifier.tryParse(currentSetup.patch().resourceId());
        if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
            return Step.blocked("unknown block", true);
        }
        targetBlock = Registries.BLOCK.get(blockId);
        targetItem = targetBlock.asItem();
        if (targetItem == net.minecraft.item.Items.AIR) {
            return Step.blocked(blockId + " has no item to pick up", true);
        }
        resource = blockId.toString();

        var player = body.entity();
        int currentInventory = ItemMoves.count(player.getInventory(), targetItem);

        // Checkpoint / Inventory initialization
        if (fresh || initialInventory < 0) {
            if (checkpoint.isPresent() && checkpoint.get().progress().containsKey("initial")) {
                initialInventory = Integer.parseInt(checkpoint.get().progress().get("initial"));
                targetTotal = Integer.parseInt(checkpoint.get().progress().get("target"));
            } else {
                initialInventory = currentInventory;
                targetTotal = initialInventory + requestedCount;
            }
        }

        // Completion check on resume only
        if (checkpoint.isPresent() && currentInventory >= targetTotal) {
            phase = Phase.FINISHED;
            finalStep = Step.completed(currentProgress());
            return finalStep;
        }

        switch (phase) {
            case SELECT_TARGET -> {
                if (claim != null) {
                    claims.release(claim);
                    claim = null;
                }
                admission = null;
                currentTarget = null;
                currentState = null;
                startedMining = false;
                dropTicks = 0;

                var patch = currentSetup.patch();
                int minX = patch.min().x();
                int maxX = patch.max().x();
                int minY = patch.min().y();
                int maxY = patch.max().y();
                int minZ = patch.min().z();
                int maxZ = patch.max().z();

                var world = player.getServerWorld();
                List<BlockPos> candidates = new ArrayList<>();
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (world.isChunkLoaded(pos) && world.getBlockState(pos).isOf(targetBlock)) {
                                candidates.add(pos);
                            }
                        }
                    }
                }

                if (candidates.isEmpty()) {
                    return Step.blocked("no " + blockId + " left in the patch", true);
                }

                candidates.sort(Comparator.comparingDouble(p -> player.getEyePos().squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
                BlockPos best = null;
                for (BlockPos candidate : candidates) {
                    if (body.miningEligible(candidate)) {
                        best = candidate;
                        break;
                    }
                }

                if (best == null) {
                    return Step.blocked("no reachable " + blockId + " left in the patch", true);
                }

                currentTarget = best;
                currentState = world.getBlockState(best);
                setupRevision = currentSetup.revision();
                var decision = EditPolicy.evaluate(currentSetup, EditPolicy.Kind.BREAK, world.getRegistryKey().getValue().toString(),
                        point(best), resource, policy.get());
                if (!decision.allowed()) {
                    return Step.blocked(decision.reason(), true);
                }

                toolSnapshot = player.getInventory().getStack(slot).copy();
                priorReceipt = body.miningReceipt().map(NativePlayerBody.MiningReceipt::sequence).orElse(0L);
                claimKey = "block:" + worldId + ":" + world.getRegistryKey().getValue() + ":" + currentTarget.toShortString();
                issuedLease = lease.get();
                claim = claims.acquire(issuedLease.taskId(), issuedLease.sessionEpoch(), List.of(claimKey), System.currentTimeMillis(), 15_000).orElse(null);
                if (claim == null) {
                    return Step.blocked("Another task holds this resource claim", true);
                }

                preExistingDrops.clear();
                trackedDropUuids.clear();
                for (net.minecraft.entity.ItemEntity drop : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                        new net.minecraft.util.math.Box(currentTarget).expand(3.0), d -> d.getStack().isOf(targetItem))) {
                    preExistingDrops.add(drop.getUuid());
                }

                inventoryBeforePickup = ItemMoves.count(player.getInventory(), targetItem);
                UUID actionId = UUID.randomUUID();
                admission = new ActionAdmission(ActionRecord.prepare(actionId, issuedLease, "native_gather",
                        resource + " at " + currentTarget.toShortString() + "; substep=" + gatherSubstep + "; setupRevision=" + setupRevision),
                        evidence(), writer, dispatch);
                phase = Phase.MINING;
                return Step.running(currentProgress(), true);
            }

            case MINING -> {
                if (admission == null) {
                    phase = Phase.SELECT_TARGET;
                    return Step.running(currentProgress(), true);
                }
                if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
                    unknown = true;
                    return Step.blocked("Durable mining acknowledgement is uncertain; reconcile", true);
                }
                if (admission.phase() == ActionAdmission.Phase.READY) {
                    if (!ownsClaim() || !body.miningEligible(currentTarget) || player.getWorld().getBlockState(currentTarget) != currentState) {
                        finalStep = Step.blocked("Mining preconditions changed before dispatch; no break issued", true);
                        flush = admission.close();
                        return waiting();
                    }
                    if (admission.take().isEmpty()) return waiting();
                    try {
                        startedMining = body.selectHotbar(issuedLease, slot) && body.mine(issuedLease, currentTarget);
                    } catch (RuntimeException failure) {
                        body.stopInputs();
                        finalStep = Step.blocked("Native mining call interrupted", true);
                        flush = admission.close();
                        return waiting();
                    }
                    if (!startedMining) {
                        body.stopInputs();
                        finalStep = Step.blocked("Native mining controls denied", true);
                        flush = admission.close();
                        return waiting();
                    }
                } else if (admission.phase() == ActionAdmission.Phase.ISSUED) {
                    if (receipt().isPresent()) {
                        body.stopInputs();
                        var world = player.getServerWorld();
                        trackedDropUuids.clear();
                        for (net.minecraft.entity.ItemEntity drop : world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                                new net.minecraft.util.math.Box(currentTarget).expand(3.0), d -> d.getStack().isOf(targetItem))) {
                            if (!preExistingDrops.contains(drop.getUuid())) {
                                trackedDropUuids.add(drop.getUuid());
                            }
                        }
                        phase = Phase.COLLECTING_DROPS;
                        dropTicks = 0;
                    } else if (player.getWorld().getBlockState(currentTarget) != currentState && !receipt().isPresent()) {
                        body.stopInputs();
                        unknown = true;
                        admission.uncertain("Block changed without break receipt");
                        finalStep = Step.blocked("Block changed externally without break receipt; reconcile", true);
                        flush = admission.close();
                        return waiting();
                    }
                }
                return Step.running(currentProgress(), true);
            }

            case COLLECTING_DROPS -> {
                dropTicks++;
                var world = player.getServerWorld();
                boolean anyTrackedAlive = false;
                net.minecraft.entity.ItemEntity trackedEntity = null;
                for (UUID uid : trackedDropUuids) {
                    var e = world.getEntity(uid);
                    if (e instanceof net.minecraft.entity.ItemEntity drop && drop.isAlive() && !drop.isRemoved()) {
                        anyTrackedAlive = true;
                        trackedEntity = drop;
                        break;
                    }
                }

                int currentCount = ItemMoves.count(player.getInventory(), targetItem);
                int gained = currentCount - inventoryBeforePickup;

                // If drops from our break were collected into our inventory
                if (gained > 0 && (!anyTrackedAlive || currentCount >= targetTotal)) {
                    body.stopInputs();
                    var observed = receipt();
                    admission.verify(evidence() + "; nativeReceipt=" + observed.map(NativePlayerBody.MiningReceipt::sequence).orElse(0L)
                            + "; drops=verified; collected=" + (currentCount - initialInventory), "Actual native log break and verified drop collection");
                    if (currentCount >= targetTotal) {
                        phase = Phase.FINISHED;
                        finalStep = Step.completed(currentProgress());
                        return waiting();
                    } else {
                        gatherSubstep++;
                        phase = Phase.TRANSITION_TO_NEXT;
                        return waiting();
                    }
                }

                // If all tracked drops from our break are gone, but we did NOT gain them (someone else picked them up or lost)
                if (!anyTrackedAlive && !trackedDropUuids.isEmpty() && gained <= 0) {
                    body.stopInputs();
                    var observed = receipt();
                    admission.verify(evidence() + "; nativeReceipt=" + observed.map(NativePlayerBody.MiningReceipt::sequence).orElse(0L)
                            + "; drops=uncollected; recipient=other", "Break observed without attributable pickup by breaker");
                    finalStep = Step.blocked("Drop was collected by another entity or lost; gathering blocked", true);
                    flush = admission.close();
                    return waiting();
                }

                // If tracked drops are still on the ground, move into ordinary collision range
                if (trackedEntity != null) {
                    double distSq = player.squaredDistanceTo(trackedEntity);
                    double dx = trackedEntity.getX() - player.getX();
                    double dz = trackedEntity.getZ() - player.getZ();
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    // Never over an edge or into lava after a drop that fell.
                    if (distSq > 0.8 && new NativeNavigator(player.getServerWorld()).safeHeading(player, yaw)) {
                        body.move(issuedLease, 1.0f, 0.0f, false, false, yaw);
                    } else {
                        body.stopInputs();
                    }
                }

                if (dropTicks >= 80) {
                    body.stopInputs();
                    admission.verify(evidence() + "; drops=uncollected", "Break observed without attributable pickup");
                    finalStep = Step.blocked("Breaking without pickup cannot complete gather", true);
                    flush = admission.close();
                    return waiting();
                }

                return Step.running(currentProgress(), true);
            }

            case TRANSITION_TO_NEXT -> {
                if (admission != null && admission.phase() != ActionAdmission.Phase.VERIFIED) {
                    return waiting();
                }
                if (claim != null) {
                    claims.release(claim);
                    claim = null;
                }
                admission = null;
                currentTarget = null;
                currentState = null;
                startedMining = false;
                dropTicks = 0;
                trackedDropUuids.clear();
                preExistingDrops.clear();
                inventoryBeforePickup = -1;
                phase = Phase.SELECT_TARGET;
                return Step.running(currentProgress(), true);
            }

            case FINISHED -> {
                return finalStep != null ? finalStep : Step.completed(currentProgress());
            }
        }

        return Step.running(currentProgress(), true);
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || !ownsClaim() || currentTarget == null) return false;
        return intent == NativePlayerBody.Intent.SELECT_SLOT
                || intent == NativePlayerBody.Intent.MOVE
                || (intent == NativePlayerBody.Intent.MINE && currentTarget.equals(requested) && body.entity().getInventory().selectedSlot == slot);
    }

    @Override
    boolean uncertain() { return unknown; }

    @Override
    ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }

    @Override
    String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }

    @Override
    CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) {
            if (claim != null) claims.release(claim);
            return flush;
        }
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED) {
            var observed = receipt();
            if (observed.isPresent()) {
                admission.verify(evidence() + "; nativeReceipt=" + observed.orElseThrow().sequence() + "; drops=interrupted", "Break recorded before close");
            } else if (currentTarget != null && body.entity().getWorld().isChunkLoaded(currentTarget)
                    && body.entity().getWorld().getBlockState(currentTarget).equals(currentState)) {
                admission.noEffect(evidence() + "; cancelled", "Gather cancelled with intact target");
            } else {
                unknown = true;
                admission.uncertain("Gather cancelled with uncertain outcome");
            }
        }
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        if (claim != null) claims.release(claim);
        return flush;
    }

    @Override
    public void halt() { close(); }

    @Override
    public boolean settled() { return true; }
}
