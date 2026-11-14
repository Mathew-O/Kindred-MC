package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.WorkSetup;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In plain terms: an AI player bridging a gap as a practised player does (E-71a): its back to the gap, looking down at
 * the edge of the block it stands on, it walks backward upright and crouches only as its feet reach the edge; the
 * moment its look meets the side of that block it places the next, stands up as the block lands, and walks back onto
 * it. Where a fall would kill it (over lava or a drop it could not live through, with no water bucket to land in), with
 * a mob near, or when it has just been hit or shot at, it keeps the crouch the whole way instead, at a crouch's pace.
 * Each block uses exactly one item and is checked against the server's building rules.
 *
 * Technical: One straight run of up to {@link #MAX_RUN} bridge blocks along a horizontal direction from the feet's
 * start. The sneak is a movement input of the body ({@link NativePlayerBody#crouchWalk}): while it is held vanilla's
 * own edge rule keeps the player on the block it stands on, so a crouch timed to the edge is as safe as one held, for
 * as long as it is held. With {@code crouch=auto} the choice is made every tick from what the player itself perceives:
 * the column under the next cell it is looking down into (lava, a drop it would not survive, or no ground in sight,
 * unless it carries a water bucket), a hostile in sight within {@link #NEAR_MOB}, and a hit or an arrow at it in the
 * last {@link #SHOT_TICKS}; {@code timed} and {@code held} are the caller's choice, as a mode's rules may make it.
 * <p>
 * Every block is its own durable record under the edit policy and a claim, prepared one block ahead while the player
 * walks, so the click at the edge never waits on it; when it does, the player waits crouched at the edge. A placement
 * refused (a claim, the policy, the blocks run out) ends the run with the player crouched at the edge of the last block
 * it placed, never in the gap, with the reason, the cell it stopped at and the cells placed. An outcome it cannot verify
 * blocks for reconciliation and is never replayed.
 */
final class NativeBridgeAction extends NativeRecordedAction {
    /** The most blocks one run takes; the route is planned again after it. */
    static final int MAX_RUN = 16;
    /** A hostile in sight this near keeps the crouch held. */
    static final double NEAR_MOB = 8;
    /** Ticks after a hit, or an arrow at it, that the crouch stays held. */
    static final int SHOT_TICKS = 40;
    /** How far down it looks for ground under the next cell; none that far is a drop it does not trust. */
    static final int SIGHT_DOWN = 24;
    /** The look down at the edge, as a bridging player holds it for the whole run. */
    static final float PITCH = 80f;
    /** Longest it waits to land before the run, when it was planned mid-jump. */
    private static final int GROUND_WAIT = 12;
    /** Ticks with no way made before the run is given up as stuck. */
    private static final int STUCK_TICKS = 100;
    /** How far the box hangs past the edge of the block it stands on before it would leave it (half its width). */
    private static final double HANG = 0.3;

    /** The crouch: chosen as it goes, timed to each edge, or held the whole way. */
    enum Crouch { AUTO, TIMED, HELD;
        static Crouch of(String name) {
            return name == null ? AUTO : switch (name) {
                case "timed" -> TIMED;
                case "held" -> HELD;
                case "auto" -> AUTO;
                default -> throw new IllegalArgumentException("Unknown crouch " + name);
            };
        }
    }

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final UUID worldId;
    private final Supplier<WorkSetup> setup;
    private final Supplier<EditPolicy.ServerPolicy> policy;
    private final ClaimRegistry claims;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final BlockPos start;
    private final Direction dir;
    private final int count;
    private final int slot;
    private final Crouch crouch;

    /** One block to place, in the cell beyond the block the player stands on, clicked against that block's side. */
    private final class Placement {
        final BlockPos cell;
        final int index;
        ActionAdmission admission;
        ClaimRegistry.Grant claim;
        boolean settled;

        Placement(BlockPos cell, int index) {
            this.cell = cell.toImmutable();
            this.index = index;
        }
    }

    private BlockItem blockItem;
    private String dimensionKey, resource;
    private long setupRevision;
    private boolean begun;
    private int groundWait;
    /** The block to place next, the one after it (readied one ahead), and why the next could not be readied. */
    private Placement current, ahead;
    private boolean aheadTried;
    private String refusal, lastRefusal;
    private int placedAtLastWay = -1;
    private final List<Placement> all = new ArrayList<>();
    private final List<BlockPos> placedCells = new ArrayList<>();
    private int ticks, sneakTicks, switches;
    private boolean lastSneak;
    /** A refused run reached the edge crouched; it steps back from there before it ends. */
    private boolean atEdge;
    private long lastShotAt = Long.MIN_VALUE / 2;
    private int stillTicks;
    private double furthest = Double.NEGATIVE_INFINITY;
    private Step result;
    private boolean halted, unknown;
    private CompletableFuture<ActionRecord> flush;

    /** A run of {@code count} blocks from {@code start} along {@code dir}, with the block in hotbar {@code slot}. */
    NativeBridgeAction(NativePlayerBody body, Supplier<ActionLease> lease, UUID worldId, Supplier<WorkSetup> setup,
                       Supplier<EditPolicy.ServerPolicy> policy, ClaimRegistry claims, ActionAdmission.Writer writer,
                       Consumer<Runnable> dispatch, BlockPos start, Direction dir, int count, int slot, Crouch crouch) {
        this.body = body;
        this.lease = lease;
        this.worldId = worldId;
        this.setup = setup;
        this.policy = policy;
        this.claims = claims;
        this.writer = writer;
        this.dispatch = dispatch;
        this.start = start.toImmutable();
        this.dir = dir;
        this.count = count;
        this.slot = slot;
        this.crouch = crouch;
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("A bridge needs an owned hotbar slot 0..8");
        if (dir == null || dir.getAxis().isVertical()) throw new IllegalArgumentException("A bridge runs along one horizontal direction");
        if (count < 1 || count > MAX_RUN) throw new IllegalArgumentException("A run is one to " + MAX_RUN + " blocks");
    }

    /** The claim a bridge block holds on its cell: the same key every placing skill holds for a block. */
    static String claimKey(UUID worldId, String dimensionKey, BlockPos cell) {
        return "block:" + worldId + ":" + dimensionKey + ":" + cell.toShortString();
    }

    /** The {@code i}th cell of the run, one onward: under the feet's level, {@code i} blocks along. */
    private BlockPos cell(int i) { return start.offset(dir, i).down(); }

    /** How far along the run the player is, from the middle of the start's block. */
    private double along() {
        var player = body.entity();
        return (player.getX() - (start.getX() + 0.5)) * dir.getOffsetX() + (player.getZ() - (start.getZ() + 0.5)) * dir.getOffsetZ();
    }

    /** Moves the bridge on by one tick. */
    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded bridge cannot replay after cancellation/restart", true);
        if (result != null) return finishing();
        try {
            return run();
        } catch (RuntimeException failure) {
            unknown = true;
            for (Placement p : all) {
                if (!p.settled && p.admission != null && p.admission.phase() == ActionAdmission.Phase.ISSUED) p.admission.uncertain("Bridge interrupted");
            }
            return end(stopped("unverified", "Bridge interrupted with an unknown outcome; reconcile", true));
        }
    }

    private Step run() {
        var player = body.entity();
        long now = player.getServerWorld().getTime();
        if (!begun) {
            if (!player.isOnGround() && groundWait++ < GROUND_WAIT) return Step.running(Map.of("waiting", "to land"), false);
            Step refused = begin();
            if (refused != null) return refused;
            begun = true;
        }
        if (!valid()) return end(stopped("policy", "The bridge's block, claim or policy changed", false));
        if (player.hurtTime > 0 || shotAt(player)) lastShotAt = now;
        if (player.getY() < start.getY() - 0.6) {
            return end(stopped(now - lastShotAt <= SHOT_TICKS ? "hit" : "fell", "Off the bridge", false));
        }
        double s = along();
        int placed = placedCells.size();
        double edge = placed + 0.5;
        Vec3d velocity = player.getVelocity();
        double v = velocity.x * dir.getOffsetX() + velocity.z * dir.getOffsetZ();
        // Way made: further along, or a block down.
        if (s > furthest + 0.02 || placed != placedAtLastWay) {
            furthest = Math.max(furthest, s);
            placedAtLastWay = placed;
            stillTicks = 0;
        } else if (++stillTicks > STUCK_TICKS) {
            return end(stopped("stuck", "The bridge made no way", false));
        }
        float yaw = dir.getOpposite().asRotation();
        if (placed == count) {
            // The last block in: stand on it, crouched at its edge as it stands, and the run is done.
            boolean done = player.isOnGround() && Math.abs(v) < 0.01;
            if (done) return finish();
            return steer(0, true, yaw);
        }
        if (current == null && refusal == null) {
            // The block readied one ahead, or, refused then, tried again now it is next.
            current = ahead != null ? ahead : plan(placed + 1);
            ahead = null;
            aheadTried = false;
            if (current == null) refusal = lastRefusal;
        }
        if (current != null && ahead == null && !aheadTried && placed + 2 <= count) {
            ahead = plan(placed + 2);
            aheadTried = true;
        }
        boolean ready = current != null && current.admission.phase() == ActionAdmission.Phase.READY;
        if (current != null && current.admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return end(stopped("unverified", "Durable bridge acknowledgement is uncertain; reconcile", true));
        }
        boolean held = crouch == Crouch.HELD || current == null || !ready || crouch == Crouch.AUTO && danger(player, placed + 1, now);
        // Timed: upright until the next tick's step would take the feet past the edge, then crouched for the click.
        boolean sneak = held || s + 1.5 * Math.max(v, 0) >= edge + HANG - 0.05;
        if (current == null) {
            // Refused: crouched back to the edge, held there by vanilla's edge rule.
            if (!atEdge) {
                if (player.isSneaking() && player.isOnGround() && (s >= edge + HANG - 0.06 || Math.abs(v) < 0.003 && s >= edge - 0.1)) atEdge = true;
                else return steer(-1, true, yaw);
            }
            // The edge rule stops the step, not the way it was going: let go there and the player would slide off. Still
            // crouched, it comes to a stand and steps back onto the block, and only then does the run end.
            if (s > edge - 0.1) return steer(1, true, yaw);
            if (Math.abs(v) > 0.003) return steer(0, true, yaw);
            return end(stopped(refusal, "The next bridge block was refused (" + refusal + ")", false));
        }
        if (ready && player.isSneaking()) {
            BlockHitResult face = faceInView(player, placed);
            if (face != null) {
                Step failed = place(current, face);
                if (failed != null) return failed;
                placedCells.add(current.cell);
                current = null;
                if (placedCells.size() == count) return steer(0, true, yaw);
                // Up again as the block lands, and back onto it.
                return steer(-1, held, yaw);
            }
        }
        return steer(-1, sneak, yaw);
    }

    /** Backward along the run (or still), the head down at the edge, the sneak as asked. */
    private Step steer(float forward, boolean sneak, float yaw) {
        if (!body.crouchWalk(lease.get(), forward, sneak, yaw, PITCH)) return end(stopped("policy", "Movement authority denied", false));
        ticks++;
        if (sneak) sneakTicks++;
        if (ticks > 1 && sneak != lastSneak) switches++;
        lastSneak = sneak;
        return waiting();
    }

    /**
     * Where its look meets the side of the block it stands on that faces the gap, or null: the moment to click. The look
     * is its own, along the way it faces with the head down; nothing is placed out of its sight.
     */
    private BlockHitResult faceInView(net.minecraft.server.network.ServerPlayerEntity player, int placed) {
        BlockPos against = cell(placed);
        Vec3d eye = player.getEyePos();
        Vec3d reach = eye.add(player.getRotationVec(1f).multiply(4.5));
        var hit = player.getServerWorld().raycast(new RaycastContext(eye, reach, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(against) || hit.getSide() != dir) return null;
        return hit;
    }

    /**
     * Whether, by what the player itself sees, bridging upright could kill it: lava under the next cell, whatever it
     * holds (water does not save a fall into lava); a drop it would not survive, or no ground in sight, with no water
     * bucket in its hotbar to land in; a hostile in sight within {@link #NEAR_MOB}; or a hit or an arrow at it in the
     * last {@link #SHOT_TICKS}.
     */
    private boolean danger(net.minecraft.server.network.ServerPlayerEntity player, int next, long now) {
        if (now - lastShotAt <= SHOT_TICKS) return true;
        var world = player.getServerWorld();
        boolean hostile = !world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(NEAR_MOB),
                mob -> mob.isAlive() && mob.squaredDistanceTo(player) <= NEAR_MOB * NEAR_MOB && player.canSee(mob)).isEmpty();
        if (hostile) return true;
        boolean bucket = false;
        for (int s = 0; s < 9; s++) if (player.getInventory().getStack(s).isOf(Items.WATER_BUCKET)) bucket = true;
        BlockPos column = cell(next);
        for (int down = 1; down <= SIGHT_DOWN; down++) {
            BlockPos at = column.down(down);
            if (!world.isChunkLoaded(at) || at.getY() < world.getBottomY()) return !bucket;
            var fluid = world.getFluidState(at);
            if (fluid.isIn(FluidTags.LAVA)) return true;
            if (fluid.isIn(FluidTags.WATER)) return false;
            var state = world.getBlockState(at);
            if (!state.getCollisionShape(world, at).isEmpty()) {
                // A fall of {@code drop} blocks onto it: a point of damage for each block past three.
                int drop = start.getY() - (at.getY() + 1);
                return !bucket && Math.max(0, drop - 3) >= player.getHealth() - 1;
            }
        }
        return !bucket;
    }

    /** An arrow in flight toward it, near: being shot at. */
    private static boolean shotAt(net.minecraft.server.network.ServerPlayerEntity player) {
        for (var arrow : player.getServerWorld().getEntitiesByClass(PersistentProjectileEntity.class, player.getBoundingBox().expand(8),
                a -> a.isAlive() && a.getOwner() != player)) {
            Vec3d to = player.getPos().subtract(arrow.getPos());
            if (!arrow.isOnGround() && arrow.getVelocity().dotProduct(to) > 0) return true;
        }
        return false;
    }

    /** Checks the hotbar block and where it stands before anything is recorded; a refusal, else null. */
    private Step begin() {
        var player = body.entity();
        var stack = player.getInventory().getStack(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return end(stopped("no_blocks", "Owned hotbar slot does not hold a placeable block", true));
        blockItem = item;
        resource = Registries.BLOCK.getId(item.getBlock()).toString();
        dimensionKey = player.getServerWorld().getRegistryKey().getValue().toString();
        setupRevision = setup.get() != null ? setup.get().revision() : 0;
        if (!player.getBlockPos().equals(start)) return end(stopped("policy", "The player is no longer where the bridge was planned", true));
        if (!NativeStairAction.solid(player.getServerWorld(), start.down())) return end(stopped("policy", "No block underfoot to bridge from", true));
        return null;
    }

    /**
     * The {@code i}th block, recorded and claimed, or null with {@link #lastRefusal} set: no open cell, too few blocks, the
     * policy refuses it or another task holds it.
     */
    private Placement plan(int i) {
        var player = body.entity();
        var world = player.getServerWorld();
        BlockPos cell = cell(i);
        if (!world.isChunkLoaded(cell) || !world.isInBuildLimit(cell) || !open(world, cell)) {
            lastRefusal = "policy";
            return null;
        }
        int blocks = player.getInventory().getStack(slot).isOf(blockItem) ? player.getInventory().getStack(slot).getCount() : 0;
        long owed = all.stream().filter(p -> !p.settled).count();
        if (blocks < owed + 1) {
            lastRefusal = "no_blocks";
            return null;
        }
        var decision = NativeEdits.decide(player, setup.get(), EditPolicy.Kind.PLACE, cell, resource, policy.get());
        if (!decision.allowed()) {
            lastRefusal = "policy";
            return null;
        }
        var p = new Placement(cell, i);
        p.claim = claims.acquire(lease.get().taskId(), lease.get().sessionEpoch(), List.of(claimKey(worldId, dimensionKey, cell)),
                System.currentTimeMillis(), 15_000).orElse(null);
        if (p.claim == null) {
            lastRefusal = "claim";
            return null;
        }
        p.admission = new ActionAdmission(ActionRecord.prepare(UUID.randomUUID(), lease.get(), "native_bridge",
                "block=" + resource + "; pos=" + cell.toShortString() + "; block=" + i + " of " + count + "; setupRevision=" + setupRevision),
                InventoryEvidence.capture(body).description(), writer, dispatch);
        all.add(p);
        return p;
    }

    /** Places one recorded block where the look meets the face; a step ending the run if it fails, else null. */
    private Step place(Placement p, BlockHitResult face) {
        var player = body.entity();
        var world = player.getServerWorld();
        if (!open(world, p.cell) || !NativeStairAction.solid(world, p.cell.offset(dir.getOpposite()))) {
            return end(stopped("policy", "The bridge's cell or its face changed", false));
        }
        if (p.admission.take().isEmpty()) return end(stopped("unverified", "The bridge block's record was not ready", false));
        var before = InventoryEvidence.capture(body);
        float yaw = player.getYaw(), pitch = player.getPitch();
        boolean done = body.placeBlock(lease.get(), slot, p.cell, dir, new NativePlayerBody.PlaceLook(yaw, pitch, face.getPos()))
                && world.getBlockState(p.cell).isOf(blockItem.getBlock());
        var after = InventoryEvidence.capture(body);
        p.settled = true;
        if (done && conserved(before, after, 1)) {
            p.admission.verify(after.description() + "; block=" + resource + "; pos=" + p.cell.toShortString(), "Native bridge block verified");
            return null;
        }
        if (!done && conserved(before, after, 0) && open(world, p.cell)) {
            p.admission.noEffect(before.description() + "; nothing owned lost", "The block could not be set for the bridge");
            return end(stopped("policy", "The block could not be set for the bridge", false));
        }
        unknown = true;
        p.admission.uncertain("Bridge block effect cannot be verified; reconcile");
        return end(stopped("unverified", "Bridge block effect cannot be verified; reconcile", true));
    }

    /**
     * What the run did, for its end: the cells placed, in order (a run is straight, so they are {@code placed_first} on
     * along {@code dir} to {@code placed_last}, {@code blocks} of them), the ticks and those crouched, and the crouch's
     * changes. A checkpoint's entries are short, so the cells are not listed one by one.
     */
    private Map<String, String> report() {
        var out = new LinkedHashMap<String, String>();
        out.put("dir", dir.asString());
        out.put("blocks", String.valueOf(placedCells.size()));
        if (!placedCells.isEmpty()) {
            out.put("placed_first", placedCells.getFirst().toShortString());
            out.put("placed_last", placedCells.getLast().toShortString());
        }
        out.put("ticks", String.valueOf(ticks));
        out.put("sneak_ticks", String.valueOf(sneakTicks));
        out.put("switches", String.valueOf(switches));
        out.put("crouch", crouch.name().toLowerCase());
        out.put("pos", body.entity().getBlockPos().toShortString());
        return out;
    }

    private Step finish() {
        return end(Step.completed(report()));
    }

    /**
     * The run stopped short: refused, with {@code reason} ({@code claim}, {@code policy}, {@code no_blocks}, {@code hit},
     * {@code fell}, {@code stuck} or {@code unverified}), the cell it stopped at, the cells placed and whether it is
     * crouched at the edge.
     */
    private Step stopped(String reason, String why, boolean permanent) {
        var progress = report();
        var player = body.entity();
        KindredMod.LOGGER.info("{} bridge stopped ({}): {} placed, at {} along, {}, on the ground {}", player.getName().getString(), reason,
                placedCells.size(), String.format("%.2f", along()), player.isSneaking() ? "crouched" : "upright", player.isOnGround());
        progress.put("reason", reason);
        progress.put("stopped_at", cell(placedCells.size() + 1).toShortString());
        progress.put("crouched", String.valueOf(player.isSneaking()));
        progress.put("at_edge", String.valueOf(atEdge));
        return new Step(Status.BLOCKED, progress, true, why, permanent);
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
            return Step.blocked("Bridge close outcome uncertain", true);
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
                p.admission.uncertain("Bridge block issued without a verified outcome");
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

    /** The block, the setup, the policy and every claim of the run still as they were. */
    private boolean valid() {
        var player = body.entity();
        if (!body.connected() || !player.isAlive()) return false;
        if (!player.getServerWorld().getRegistryKey().getValue().toString().equals(dimensionKey)) return false;
        var stack = player.getInventory().getStack(slot);
        boolean owed = all.stream().anyMatch(p -> !p.settled);
        if (owed && (stack.isEmpty() || !stack.isOf(blockItem))) return false;
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

    private static boolean open(World world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockState(pos).isReplaceable() && world.getFluidState(pos).isEmpty();
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
        return all.isEmpty() ? "NOT_PREPARED" : lastSneak ? "CROUCHED" : "WALKING";
    }

    private Step waiting() {
        return Step.running(Map.of("blocks", String.valueOf(placedCells.size()), "durableAction", phase()), false);
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
