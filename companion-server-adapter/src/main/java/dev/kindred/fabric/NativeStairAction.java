package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A flight of stairs built up as the player walks (F-10h), the way a player builds one: it jumps on the move, and once
 * its feet have risen past the step it sets an owned block in the cell ahead under where it will land, then lands on
 * it and jumps again, step after step, never stopping against a block's side. Each block goes against a face of
 * something solid under, beside or ahead of it. In the open a filler block goes in first, from the ground, against the
 * block the player stands on, so a step that fails in the air drops it no more than a block.
 * <p>
 * Every block is its own durable record under the edit policy and a claim, as for {@link NativePlaceAction}, and
 * exactly one block item is spent on it. The next step's records are prepared while the player is in the air, so it
 * lands ready to go on. A flight that cannot go on ends with the steps it has climbed; one refused before its first
 * step places nothing.
 */
final class NativeStairAction extends NativeRecordedAction {
    /** The most steps one flight takes; the route is planned again after it. */
    static final int MAX_FLIGHT = 12;
    /** Ticks from the jump to landing on the step; past this the step has failed. */
    private static final int AIR_TICKS = 24;
    /** Longest it waits to land before the first step, when the flight was planned mid-jump. */
    private static final int GROUND_WAIT = 12;
    /** How far above the step's top the feet must be before its block goes in under them. */
    private static final double CLEARANCE = 0.05;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final int slot;
    private final BlockPos start;
    private final List<Direction> directions;

    /** One block to place: the cell, and the side of the solid block it is clicked against, as {@link NativePlayerBody#placeBlock} takes it. */
    private final class Placement {
        final BlockPos cell;
        final Direction side;
        final boolean filler;
        ActionAdmission admission;
        ClaimRegistry.Grant claim;
        boolean settled;

        Placement(BlockPos cell, Direction side, boolean filler) {
            this.cell = cell.toImmutable();
            this.side = side;
            this.filler = filler;
        }
    }

    private BlockItem blockItem;
    private String dimensionKey, resource;
    private long setupRevision;
    /** The step under way, the cell it starts from and the cell it ends in. */
    private int step;
    private BlockPos feet, target;
    /** This step's placements (a filler first, when there is one) and the next step's, prepared in the air. */
    private List<Placement> placing, ahead;
    private int airTicks = -1, groundWait;
    private final List<Placement> all = new ArrayList<>();
    private int placed;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    NativeStairAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                      Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                      Consumer<Runnable> dispatch, BlockPos start, List<Direction> directions, int slot) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.slot = slot;
        this.start = start.toImmutable();
        this.directions = List.copyOf(directions);
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A staircase needs an owned hotbar slot 0..8");
        if (directions.isEmpty() || directions.size() > MAX_FLIGHT || directions.stream().anyMatch(d -> d.getAxis().isVertical())) {
            throw new IllegalArgumentException("A flight is one to " + MAX_FLIGHT + " horizontal steps");
        }
    }

    /** A solid block a block can be placed against. */
    static boolean solid(World world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return !state.isReplaceable() && state.getFluidState().isEmpty() && !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * The side to click to set a block in {@code cell}, coming along {@code along}: against the block under it, beside
     * it, or ahead of it, never the one behind (where the player stands); null when none is solid.
     */
    static Direction face(World world, BlockPos cell, Direction along) {
        if (solid(world, cell.down())) return Direction.UP;
        for (Direction beside : List.of(along.rotateYClockwise(), along.rotateYCounterclockwise())) {
            if (solid(world, cell.offset(beside))) return beside.getOpposite();
        }
        if (solid(world, cell.offset(along))) return along.getOpposite();
        return null;
    }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded staircase cannot replay after cancellation/restart", true);
        if (result != null) return finishing();
        try {
            if (airTicks >= 0) {
                Step air = air();
                if (air != null) return air;
            }
            return ground();
        } catch (RuntimeException failure) {
            unknown = true;
            for (Placement p : all) {
                if (!p.settled && p.admission != null && p.admission.phase() == ActionAdmission.Phase.ISSUED) p.admission.uncertain("Staircase interrupted");
            }
            return end(Step.blocked("Staircase interrupted with an unknown outcome; reconcile", true));
        }
    }

    /** On a step: ready its placements, place a filler from the ground, then jump. */
    private Step ground() {
        var player = body.entity();
        if (placing == null) {
            if (step == 0) {
                if (!player.isOnGround() && groundWait++ < GROUND_WAIT) return Step.running(Map.of("waiting", "to land"), false);
                Step refused = begin();
                if (refused != null) return refused;
                feet = start;
                placing = plan(feet, directions.getFirst());
                if (placing == null) return end(Step.blocked("No way to set the first step", true));
            } else {
                placing = ahead;
                ahead = null;
                if (placing == null) return finish("The next step could not be set");
            }
            target = feet.offset(directions.get(step)).up();
        }
        for (Placement p : placing) {
            if (p.admission.phase() == ActionAdmission.Phase.BLOCKED) {
                unknown = true;
                return end(Step.blocked("Durable staircase acknowledgement is uncertain; reconcile", true));
            }
            if (p.admission.phase() != ActionAdmission.Phase.READY && !p.settled) return hold();
        }
        if (!player.isOnGround()) return hold();
        if (!valid() || !standingFor(feet)) return finish("The staircase's ground, claim or policy changed");
        // The filler goes in from the ground; the step's own block is set in the air, under the feet.
        for (Placement p : placing) {
            if (p.filler && !p.settled) {
                Step failed = place(p);
                if (failed != null) return failed;
            }
        }
        airTicks = 0;
        if (!steer(true)) return finish("Movement authority denied");
        return waiting();
    }

    /** Standing still on the step while its records are readied: a step's width is all there is. */
    private Step hold() {
        if (blockItem != null && !body.move(lease.get(), 0, 0, false, false, body.entity().getYaw())) return finish("Movement authority denied");
        return waiting();
    }

    /** In the air: the block under the feet once they clear it, the next step readied, and the landing. Null once landed. */
    private Step air() {
        var player = body.entity();
        Placement block = placing.isEmpty() ? null : placing.getLast();
        // A step onto ground already there places nothing: the next is readied as soon as it is off the ground.
        if (block == null && ahead == null && airTicks == 1 && step + 1 < directions.size()) ahead = plan(target, directions.get(step + 1));
        if (block != null && !block.settled) {
            if (player.getY() >= target.getY() + CLEARANCE) {
                if (block.admission.phase() != ActionAdmission.Phase.READY) return finish("The step's record was not ready in time");
                if (!valid()) return finish("The staircase's claim or policy changed mid-jump");
                Step failed = place(block);
                if (failed != null) return failed;
                // Ready the next step while still in the air, so it is not waited on at the landing.
                if (step + 1 < directions.size()) ahead = plan(target, directions.get(step + 1));
            }
        } else if (player.isOnGround() && player.getBlockY() == target.getY()
                && Math.abs(player.getX() - (target.getX() + 0.5)) < 0.8 && Math.abs(player.getZ() - (target.getZ() + 0.5)) < 0.8) {
            airTicks = -1;
            feet = target;
            placing = null;
            if (++step == directions.size()) return finish(null);
            return null;
        }
        if (++airTicks > AIR_TICKS) return finish("The jump did not land on the step");
        if (!steer(false)) return finish("Movement authority denied");
        return waiting();
    }

    /** Toward the middle of the cell it lands in: forward while short of it, eased off as it nears. */
    private boolean steer(boolean jump) {
        var player = body.entity();
        Direction along = directions.get(step);
        Vec3d aim = Vec3d.ofBottomCenter(target);
        double ahead = (aim.x - player.getX()) * along.getOffsetX() + (aim.z - player.getZ()) * along.getOffsetZ();
        double dx = aim.x - player.getX(), dz = aim.z - player.getZ();
        float yaw = dx * dx + dz * dz > 0.09 ? (float) (MathHelper.atan2(dz, dx) * (180 / Math.PI)) - 90f : along.asRotation();
        float forward = ahead > 0.35 ? 1f : ahead > 0.1 ? 0.4f : 0f;
        return body.move(lease.get(), forward, 0, jump, false, yaw);
    }

    /** Checks the hotbar block and the policy before anything is recorded; a refusal, else null. */
    private Step begin() {
        var player = body.entity();
        var stack = player.getInventory().getStack(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return Step.blocked("Owned hotbar slot does not hold a placeable block", true);
        blockItem = item;
        resource = Registries.BLOCK.getId(item.getBlock()).toString();
        if (!player.getBlockPos().equals(start)) return Step.blocked("The player is no longer where the staircase was planned", true);
        dimensionKey = player.getServerWorld().getRegistryKey().getValue().toString();
        setupRevision = setup.get() != null ? setup.get().revision() : 0;
        return null;
    }

    /**
     * The placements for a step from {@code from} along {@code along}, each recorded and claimed, or null when the step
     * cannot be set: no open cell ahead, no room to rise into, no face to set a block against, too few blocks, or the
     * policy refuses one.
     */
    private List<Placement> plan(BlockPos from, Direction along) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos cell = from.offset(along), to = cell.up();
        if (!world.isChunkLoaded(cell) || !world.isInBuildLimit(to.up())) return null;
        if (!clear(world, to) || !clear(world, to.up()) || !clear(world, from.up(2))) return null;
        // Ground already there a step up, as at the top of a climb: up onto it in the same movement, no block wanted.
        if (solid(world, cell) && world.getBlockState(cell).getCollisionShape(world, cell).getMax(Direction.Axis.Y) >= 1.0) return List.of();
        if (!open(world, cell)) return null;
        var placements = new ArrayList<Placement>(2);
        Direction side = face(world, cell, along);
        if (side == null) {
            BlockPos filler = cell.down();
            // The filler goes against the block the player stands on: the step it is on now, or the one it lands on.
            boolean footing = solid(world, from.down()) || all.stream().anyMatch(p -> p.cell.equals(from.down()));
            if (!footing || !open(world, filler)) return null;
            placements.add(new Placement(filler, along, true));
            side = Direction.UP;
        }
        placements.add(new Placement(cell, side, false));
        int blocks = player.getInventory().getStack(slot).isOf(blockItem) ? player.getInventory().getStack(slot).getCount() : 0;
        long owed = all.stream().filter(p -> !p.settled).count();
        if (blocks < owed + placements.size()) return null;
        for (Placement p : placements) {
            var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.PLACE, p.cell, resource, policy.get());
            if (!decision.allowed()) {
                release(placements);
                return null;
            }
            String key = "block:" + worldId + ":" + dimensionKey + ":" + p.cell.toShortString();
            p.claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(key), System.currentTimeMillis(), 15_000).orElse(null);
            if (p.claim == null) {
                release(placements);
                return null;
            }
        }
        for (Placement p : placements) {
            p.admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_stair",
                    "block=" + resource + "; pos=" + p.cell.toShortString() + "; step=" + (step + 1) + (p.filler ? "; filler" : "")
                            + "; setupRevision=" + setupRevision),
                    InventoryEvidence.capture(body).description(), writer, dispatch);
            all.add(p);
        }
        return placements;
    }

    /** Places one recorded block; a step ending the flight if it fails, else null. */
    private Step place(Placement p) {
        var world = body.entity().getServerWorld();
        if (!open(world, p.cell) || !solid(world, p.cell.offset(p.side.getOpposite())) && !all.stream().anyMatch(q -> q.settled
                && q.cell.equals(p.cell.offset(p.side.getOpposite())))) {
            return finish("The cell for the step or its face changed");
        }
        if (p.admission.take().isEmpty()) return finish("The step's record was not ready");
        var before = InventoryEvidence.capture(body);
        BlockPos against = p.cell.offset(p.side.getOpposite());
        Vec3d hit = Vec3d.ofCenter(against).add(p.side.getOffsetX() * 0.5, p.side.getOffsetY() * 0.5, p.side.getOffsetZ() * 0.5);
        var player = body.entity();
        Vec3d look = hit.subtract(player.getEyePos());
        float yaw = (float) (MathHelper.atan2(look.z, look.x) * (180 / Math.PI)) - 90f;
        float pitch = (float) -(MathHelper.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * (180 / Math.PI));
        // The look returns to the way up at once: the body walks the flight by its yaw.
        float walking = player.getYaw();
        boolean done = body.placeBlock(lease.get(), slot, p.cell, p.side, new NativePlayerBody.PlaceLook(yaw, pitch, hit))
                && world.getBlockState(p.cell).isOf(blockItem.getBlock());
        player.setYaw(walking);
        player.setHeadYaw(walking);
        var after = InventoryEvidence.capture(body);
        p.settled = true;
        if (done && conserved(before, after, 1)) {
            placed++;
            p.admission.verify(after.description() + "; block=" + resource + "; pos=" + p.cell.toShortString(), "Native staircase block verified");
            return null;
        }
        if (!done && conserved(before, after, 0) && open(world, p.cell)) {
            p.admission.noEffect(before.description() + "; nothing owned lost", "The block could not be set for the step");
            return finish("The block could not be set for the step");
        }
        unknown = true;
        p.admission.uncertain("Staircase block effect cannot be verified; reconcile");
        return end(Step.blocked("Staircase block effect cannot be verified; reconcile", true));
    }

    /** Ends the flight with the steps climbed: completed if it climbed any, else refused with {@code reason}. */
    private Step finish(String reason) {
        if (step == 0 && placed == 0) return end(Step.blocked(reason != null ? reason : "The staircase produced no effect", true));
        return end(Step.completed(Map.of("steps", String.valueOf(step), "blocks", String.valueOf(placed),
                "pos", body.entity().getBlockPos().toShortString(), "stopped", reason == null ? "flight done" : reason)));
    }

    private Step end(Step outcome) {
        body.stopInputs();
        result = outcome;
        flush = closeAll();
        return finishing();
    }

    private Step finishing() {
        if (!flush.isDone()) return waiting();
        if (flush.isCompletedExceptionally()) {
            unknown = true;
            return Step.blocked("Staircase close outcome uncertain", true);
        }
        return result;
    }

    /** Every record closed: unplaced ones as never issued, placed ones as settled. The worst of them comes back. */
    private CompletableFuture<ActionRecord> closeAll() {
        for (Placement p : all) {
            if (p.claim != null) {
                claims.release(p.claim);
                p.claim = null;
            }
        }
        var futures = new ArrayList<CompletableFuture<ActionRecord>>();
        for (Placement p : all) {
            if (p.admission == null) continue;
            if (p.admission.phase() == ActionAdmission.Phase.ISSUED) {
                unknown = true;
                p.admission.uncertain("Staircase block issued without a verified outcome");
            }
            futures.add(p.admission.close());
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            ActionRecord worst = null;
            for (var future : futures) {
                ActionRecord saved = future.join();
                if (saved != null && (worst == null || saved.state() == ActionRecord.State.UNCERTAIN || worst.state() != ActionRecord.State.UNCERTAIN)) worst = saved;
            }
            return worst;
        });
    }

    private void release(List<Placement> placements) {
        for (Placement p : placements) {
            if (p.claim != null) claims.release(p.claim);
            p.claim = null;
        }
    }

    /** The block, the setup, the policy and every claim of the flight still as they were. */
    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        if (!player.getServerWorld().getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        var stack = player.getInventory().getStack(slot);
        if (stack.isEmpty() || !stack.isOf(blockItem)) return false;
        var current = setup.get();
        if ((current == null ? 0 : current.revision()) != setupRevision) return false;
        long now = System.currentTimeMillis();
        for (Placement p : all) {
            if (p.settled || p.claim == null) continue;
            if (!claims.holdsAll(p.claim, now)) return false;
            if (!NativeEdits.decide(player, current, EditPolicy.Kind.PLACE, p.cell, resource, policy.get()).allowed()) return false;
        }
        return true;
    }

    /** Standing on the step it starts from: in its cell, or on the block of it a little short of the middle. */
    private boolean standingFor(BlockPos cell) {
        var player = body.entity();
        return player.getBlockY() == cell.getY() && Math.abs(player.getX() - (cell.getX() + 0.5)) < 0.8
                && Math.abs(player.getZ() - (cell.getZ() + 0.5)) < 0.8;
    }

    private static boolean open(World world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockState(pos).isReplaceable() && world.getFluidState(pos).isEmpty();
    }

    private static boolean clear(World world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() && !world.getFluidState(pos).isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    /** At most {@code spent} of the block item gone, nothing else lost. */
    private boolean conserved(InventoryEvidence before, InventoryEvidence after, int spent) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty()) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) if (entry.getValue() > (entry.getKey() == blockItem ? spent : 0)) return false;
        return spent == 0 || lost.getOrDefault(blockItem, 0) == spent;
    }

    @Override
    boolean permits(NativePlayerBody.Intent intent, BlockPos target) {
        if (halted || result != null || blockItem == null) return false;
        if (intent == NativePlayerBody.Intent.SELECT_SLOT || intent == NativePlayerBody.Intent.MOVE) return true;
        if (intent != NativePlayerBody.Intent.PLACE || target == null) return false;
        for (Placement p : all) {
            if (p.cell.equals(target) && !p.settled && p.admission != null && p.admission.phase() == ActionAdmission.Phase.ISSUED) return true;
        }
        return false;
    }

    @Override boolean uncertain() { return unknown; }

    @Override
    ActionRecord snapshot() {
        for (int i = all.size() - 1; i >= 0; i--) {
            var p = all.get(i);
            if (p.settled && p.admission != null) return p.admission.snapshot();
        }
        return all.isEmpty() ? null : all.getFirst().admission.snapshot();
    }

    @Override
    String phase() {
        if (result != null) return "CLOSED";
        return airTicks >= 0 ? "IN_THE_AIR" : all.isEmpty() ? "NOT_PREPARED" : "ON_A_STEP";
    }

    private Step waiting() {
        return Step.running(Map.of("step", String.valueOf(step), "blocks", String.valueOf(placed), "durableAction", phase()), false);
    }

    @Override
    CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush == null) flush = closeAll();
        return flush;
    }

    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
