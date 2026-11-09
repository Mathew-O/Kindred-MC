package dev.kindred.fabric;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * In plain terms: finds a walking route for an AI player through the world as it really is: walking, stepping
 * up, dropping a few blocks or swimming, but never into lava, fire or other harmful blocks. It only looks at
 * parts of the world that are loaded, and never changes anything.
 *
 * Technical: Bounded A* over standing positions for a player-sized body. It only reads loaded blocks the body
 * could see by walking there; it never edits the world. Moves: walk (including diagonals without corner
 * cutting), step up one block with a jump, drop up to three blocks (further into water), and swim. Lava, fire
 * and other damaging blocks are never entered, and positions beside lava cost extra.
 */
final class NativeNavigator {
    static final int DEFAULT_MAX_NODES = 6_000;
    static final int MAX_SAFE_DROP = 3;
    static final int MAX_WATER_DROP = 12;
    /**
     * The deepest deliberate drop, taken only with a water bucket ready to break it (E-07d): forty blocks down a fall
     * is about two blocks a tick, inside the 2.8 blocks a tick at which the ground is surely in reach for a tick.
     */
    static final int MAX_CLUTCH_DROP = 40;

    record Path(List<BlockPos> nodes, boolean complete, int expanded) {
        BlockPos end() { return nodes.isEmpty() ? null : nodes.getLast(); }
    }

    private final ServerWorld world;
    /**
     * What one search has read, so each block, chunk and test is worked out once: a search asks the same few hundred
     * cells about many times over (move generation was about 50 microseconds a position without them). A navigator
     * lives for one search or one tick, so nothing here goes stale.
     */
    private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
    private final Long2ByteOpenHashMap chunks = new Long2ByteOpenHashMap(), passables = new Long2ByteOpenHashMap(),
            standables = new Long2ByteOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap terrain = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
    private final int maxDrop;

    /** A navigator for the given world that drops the body no further than is safe. */
    NativeNavigator(ServerWorld world) {
        this(world, MAX_SAFE_DROP);
    }

    ServerWorld world() { return world; }

    /** A mob to keep clear of: where it is, how it senses, and whether it can see the player now. */
    record Danger(net.minecraft.util.math.Vec3d at, dev.kindred.core.tactics.MobSenses.Profile profile, boolean seen) { }

    /** Extra cost per block, beside a mob, of a route that would rather not be noticed. */
    static final double DANGER_WEIGHT = 10;
    private List<Danger> dangers = List.of();

    /** A water bucket in the hotbar where water stays poured (not the Nether): a long fall can be broken (E-07d). */
    static boolean clutchReady(net.minecraft.server.network.ServerPlayerEntity player) {
        if (player.getServerWorld().getDimension().ultrawarm()) return false;
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getStack(slot).isOf(net.minecraft.item.Items.WATER_BUCKET)) return true;
        return false;
    }

    /** An extra cost for each block of the route at a place, as its atlas weighs a night's danger there (F-30); none by default. */
    private java.util.function.ToDoubleFunction<BlockPos> shun;

    /** Routes priced dearer through places it has been hurt or killed (F-30): {@code cost} is the extra for each block there. */
    NativeNavigator shunning(java.util.function.ToDoubleFunction<BlockPos> cost) {
        this.shun = cost;
        terrain.clear();
        return this;
    }

    /** Routes priced to keep out of these mobs' notice: dearer the nearer they pass (F-10g). */
    NativeNavigator avoiding(List<Danger> dangers) {
        this.dangers = List.copyOf(dangers);
        terrain.clear();
        return this;
    }

    /**
     * With a larger {@code maxDrop}, routes may include one deliberate fall that costs some health, or, past twelve
     * blocks, one broken with water (callers allow that only with a water bucket ready; see {@link #clutchReady}).
     */
    NativeNavigator(ServerWorld world, int maxDrop) {
        this.world = world;
        this.maxDrop = Math.clamp(maxDrop, MAX_SAFE_DROP, MAX_CLUTCH_DROP);
    }

    /** Where an unfinished route may end, when not just anywhere it can stand (E-49); null for anywhere. */
    private Predicate<BlockPos> endable;

    /** The same navigator, ending an unfinished route only where {@code where} allows. */
    NativeNavigator endingOnly(Predicate<BlockPos> where) {
        endable = where;
        return this;
    }

    /** Whether a closed wooden door counts as a way through: for a walk that opens the doors on its way (M-01b). */
    private boolean doors;

    /** The deepest drop a route may take, below {@link #maxDrop}'s floor of {@link #MAX_SAFE_DROP}; none by default. */
    private int fallCap = Integer.MAX_VALUE;

    /**
     * The same navigator, its routes taking no drop deeper than {@code blocks}, nor into water deeper: one it can step
     * back up from, when that is wanted (M-01a: leading an animal, which must follow it back up).
     */
    NativeNavigator fallingAtMost(int blocks) {
        if (blocks < 0) throw new IllegalArgumentException("A fall is 0 blocks or more");
        fallCap = blocks;
        return this;
    }

    /**
     * The same navigator, now jumping gaps up to {@code blocks} wide (1 or 2) as a player does (MOVEMENT): straight across
     * to ground at the same level, with head room for the jump and nothing harmful below the gap. Only a walk that can
     * make the jump asks for it (a two-block gap needs a sprint); other plans keep to the ground.
     */
    NativeNavigator jumpingGaps(int blocks) {
        if (blocks < 0 || blocks > 2) throw new IllegalArgumentException("A gap jump is 0 to 2 blocks");
        gapJump = blocks;
        return this;
    }

    /** The widest gap a route may jump; 0 for none. */
    private int gapJump;

    /** The same navigator, now counting closed wooden doors as a way through. */
    NativeNavigator throughDoors() {
        doors = true;
        passables.clear();
        standables.clear();
        return this;
    }

    /**
     * Searches from {@code start} (feet position) toward {@code target}. A node satisfying {@code goal} ends the
     * search; otherwise the path to the explored node closest to the target is returned as incomplete.
     */
    Path find(BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes, int maxHorizontalRadius) {
        BlockPos origin = start.toImmutable();
        if (!standable(origin) && !swimmable(origin)) {
            // A body mid-jump or on a slab edge: search from the block below if that is where it really stands.
            if (standable(origin.down())) {
                origin = origin.down();
            } else {
                // On something lower than a full block that no route starts from (its bed, woken on it): from the
                // cell beside it it would step off into. Woken on its bed in its cabin, every walk out of it was "no
                // walkable route", and its scaffold outside was never taken down (suite run 33).
                for (Direction side : Direction.Type.HORIZONTAL) {
                    BlockPos off = origin.offset(side);
                    BlockPos stand = standable(off) ? off : standable(off.down()) ? off.down() : null;
                    if (stand != null) {
                        origin = stand;
                        break;
                    }
                }
            }
        }
        record Node(BlockPos pos, double g, double f) { }
        var open = new PriorityQueue<Node>((a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        if (!standable(origin) && !swimmable(origin) && lowStand(origin)) {
            // On a roof of slabs or stairs, every cell beside it another: across them to the nearest stand, those cells
            // kept at the head of the route, walked in turn. From the slab's cell, which is no stand, the search went no
            // further, and a cabin's builder on its roof found "no walkable route" eight times in a row, and the cabin
            // stopped eight blocks short (#78's CI, run 42556777789) (F-22c35).
            BlockPos stand = acrossLowStands(origin, parent);
            if (stand != null) origin = stand;
        }
        open.add(new Node(origin, 0, heuristic(origin, target)));
        best.put(origin, 0.0);
        BlockPos closest = origin;
        double closestH = heuristic(origin, target);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.poll();
            if (current.g() > best.getOrDefault(current.pos(), Double.MAX_VALUE)) continue;
            expanded++;
            if (goal.test(current.pos())) return new Path(trace(parent, current.pos()), true, expanded);
            double h = heuristic(current.pos(), target);
            if (h < closestH && (endable == null || endable.test(current.pos()))) {
                closestH = h;
                closest = current.pos();
            }
            for (Move move : moves(current.pos())) {
                if (Math.abs(move.to().getX() - start.getX()) > maxHorizontalRadius
                        || Math.abs(move.to().getZ() - start.getZ()) > maxHorizontalRadius) continue;
                double g = current.g() + move.cost();
                if (g < best.getOrDefault(move.to(), Double.MAX_VALUE)) {
                    best.put(move.to(), g);
                    parent.put(move.to(), current.pos());
                    open.add(new Node(move.to(), g, g + heuristic(move.to(), target)));
                }
            }
        }
        return new Path(trace(parent, closest), false, expanded);
    }

    /** The cells it crosses a roof of slabs or stairs by, at most this many. */
    private static final int LOW_STANDS = 16;

    /**
     * A cell a body stands in on the lower half of a block, as on a bottom slab or a bottom stair, with room above it: no
     * stand to the search, which wants a full block's top under an empty cell.
     */
    boolean lowStand(BlockPos cell) {
        if (!loaded(cell) || !passable(cell.up()) || !passable(cell.up(2))) return false;
        BlockState state = state(cell);
        if (hazard(state)) return false;
        if (state.getBlock() instanceof net.minecraft.block.SlabBlock) {
            return state.get(net.minecraft.block.SlabBlock.TYPE) == net.minecraft.block.enums.SlabType.BOTTOM;
        }
        if (state.getBlock() instanceof net.minecraft.block.StairsBlock) {
            return state.get(net.minecraft.block.StairsBlock.HALF) == net.minecraft.block.enums.BlockHalf.BOTTOM;
        }
        return false;
    }

    /**
     * The nearest stand beside a run of low stands from {@code from}, a step up or down or level, found across at most
     * {@link #LOW_STANDS} of them; the cells crossed go into {@code parent}, so the route traced back from it begins at
     * {@code from}. Null when there is none.
     */
    private BlockPos acrossLowStands(BlockPos from, Map<BlockPos, BlockPos> parent) {
        var queue = new java.util.ArrayDeque<BlockPos>();
        var seen = new java.util.HashMap<BlockPos, BlockPos>();
        queue.add(from);
        seen.put(from, null);
        while (!queue.isEmpty() && seen.size() <= LOW_STANDS) {
            BlockPos at = queue.poll();
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos off = at.offset(side);
                if (seen.containsKey(off) || !loaded(off)) continue;
                BlockPos stand = standable(off) ? off : standable(off.up()) ? off.up() : standable(off.down()) ? off.down() : null;
                if (stand != null) {
                    parent.put(stand, at);
                    for (BlockPos cell = at; seen.get(cell) != null; cell = seen.get(cell)) parent.put(cell, seen.get(cell));
                    return stand;
                }
                if (lowStand(off)) {
                    seen.put(off, at);
                    queue.add(off);
                }
            }
        }
        return null;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.8;
    }

    private static List<BlockPos> trace(Map<BlockPos, BlockPos> parent, BlockPos end) {
        var path = new ArrayList<BlockPos>();
        for (BlockPos at = end; at != null; at = parent.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    record Move(BlockPos to, double cost) { }

    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Every move a body standing at the given spot can make next, with what each costs. */
    List<Move> moves(BlockPos from) {
        var moves = new ArrayList<Move>(12);
        boolean inWater = swimmable(from);
        for (int[] d : HORIZONTAL) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            BlockPos flat = from.add(d[0], 0, d[1]);
            if (!loaded(flat)) continue;
            if (diagonal && (!clear(from.add(d[0], 0, 0)) || !clear(from.add(0, 0, d[1])))) continue;
            double base = diagonal ? 1.414 : 1.0;
            if (clear(flat) && (standable(flat) || swimmable(flat))) {
                moves.add(new Move(flat, base * terrainCost(flat)));
                continue;
            }
            if (diagonal) continue;
            // Step up: the next block is an obstacle we can jump onto, with headroom above both positions.
            BlockPos up = flat.up();
            if (clear(up) && (standable(up) || swimmable(up)) && passable(from.up(2)) && !hazard(flat)) {
                moves.add(new Move(up, 2.0 * terrainCost(up)));
                continue;
            }
            // Drop: walk off an edge onto ground (or water) a few blocks below.
            if (clear(flat)) {
                int limit = Math.min(fallCap, inWater ? 1 : Math.max(MAX_WATER_DROP, maxDrop));
                for (int k = 1; k <= limit; k++) {
                    BlockPos landing = flat.down(k);
                    if (!loaded(landing)) break;
                    if (swimmable(landing) && clear(landing)) {
                        moves.add(new Move(landing, base + k * 0.3 + 2.0));
                        break;
                    }
                    if (!passable(landing)) break;
                    if (standable(landing) && passable(landing.up())) {
                        if (k <= maxDrop) moves.add(new Move(landing, base + k * 0.5 + (k > MAX_SAFE_DROP ? 20 : 0)));
                        break;
                    }
                }
            }
            // A gap jumped (MOVEMENT): no floor under the next block, ground at the same level one or two blocks past it,
            // head room all the way for the jump's arc, and nothing harmful below to fall into on a missed landing.
            if (gapJump > 0 && clear(flat) && !standable(flat) && !swimmable(flat) && !inWater && passable(from.up(2))) {
                for (int span = 2; span <= gapJump + 1; span++) {
                    BlockPos over = from.add(d[0] * (span - 1), 0, d[1] * (span - 1));
                    if (!loaded(over) || !clear(over) || standable(over) || swimmable(over) || !passable(over.up(2)) || hazard(over.down())) break;
                    BlockPos land = from.add(d[0] * span, 0, d[1] * span);
                    if (!loaded(land)) break;
                    if (clear(land) && standable(land) && !hazard(land) && passable(land.up(2))) {
                        moves.add(new Move(land, (span == 2 ? 3.0 : 5.0) * terrainCost(land)));
                        break;
                    }
                }
            }
        }
        if (inWater) {
            BlockPos up = from.up();
            if (clear(up) && (swimmable(up) || standable(up))) moves.add(new Move(up, 2.0));
            BlockPos down = from.down();
            if (loaded(down) && swimmable(down) && clear(down)) moves.add(new Move(down, 6.0));
        }
        return moves;
    }

    /**
     * Nearest dry place to stand around {@code from}, searched in growing square rings so the first ring with a
     * match gives the closest shore. Used to leave deep water before breath runs out.
     */
    java.util.Optional<BlockPos> nearestDryStand(BlockPos from, int radius, int vertical) {
        for (int ring = 1; ring <= radius; ring++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    for (int dy = -vertical; dy <= vertical; dy++) {
                        BlockPos feet = from.add(dx, dy, dz);
                        if (!standable(feet) || !state(feet).getFluidState().isEmpty() || !state(feet.down()).getFluidState().isEmpty()) continue;
                        double distance = feet.getSquaredDistance(from);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = feet;
                        }
                    }
                }
            }
            if (best != null) return java.util.Optional.of(best.toImmutable());
        }
        return java.util.Optional.empty();
    }

    /**
     * The nearest dry ground the body can actually get to from {@code from}, swimming if it must: the search follows
     * routes, so dry ground behind a ceiling or across a wall, however close, is passed over.
     */
    java.util.Optional<BlockPos> nearestReachableDryStand(BlockPos from, int maxNodes, int maxHorizontalRadius) {
        return nearestReachableDryStand(from, maxNodes, maxHorizontalRadius, feet -> true);
    }

    /** As above, passing over dry ground {@code allowed} refuses: one a walk out of the water could not get to, say. */
    java.util.Optional<BlockPos> nearestReachableDryStand(BlockPos from, int maxNodes, int maxHorizontalRadius,
                                                          java.util.function.Predicate<BlockPos> allowed) {
        Path path = find(from, from, feet -> standable(feet) && state(feet).getFluidState().isEmpty()
                && state(feet.down()).getFluidState().isEmpty() && allowed.test(feet), maxNodes, maxHorizontalRadius);
        return path.complete() && !path.nodes().isEmpty() ? java.util.Optional.of(path.nodes().getLast()) : java.util.Optional.empty();
    }

    /**
     * The block a body moving along {@code yaw} is about to enter is one it may walk into: standable or swimmable,
     * a wall or one-block step up, or a drop no deeper than {@link #MAX_SAFE_DROP}, and never lava or fire.
     * Direct steering (a fight chase, walking onto a drop) checks this where it does not follow a planned route.
     */
    boolean safeHeading(net.minecraft.entity.Entity body, float yaw) {
        double radians = Math.toRadians(yaw);
        double sx = -Math.sin(radians), sz = Math.cos(radians);
        BlockPos feet = BlockPos.ofFloored(body.getX(), body.getY() + 0.2, body.getZ());
        for (double reach : new double[]{0.6, 1.1}) {
            BlockPos ahead = BlockPos.ofFloored(body.getX() + sx * reach, body.getY() + 0.2, body.getZ() + sz * reach);
            if (ahead.getX() == feet.getX() && ahead.getZ() == feet.getZ()) continue;
            if (!safeToEnter(ahead)) return false;
        }
        return true;
    }

    /**
     * Whether a push along {@code yaw} for up to {@code reach} blocks (a hit's knockback) would leave the body on safe
     * ground: no hazard, lava or drop beyond what is safe on the way, or a wall that stops it first.
     */
    boolean safeKnockback(net.minecraft.entity.Entity body, float yaw, double reach) {
        return safeKnockback(body.getX(), body.getY(), body.getZ(), yaw, reach);
    }

    /** As {@link #safeKnockback(net.minecraft.entity.Entity, float, double)}, for a body standing at x, y, z. */
    boolean safeKnockback(double x, double y, double z, float yaw, double reach) {
        double radians = Math.toRadians(yaw);
        double sx = -Math.sin(radians), sz = Math.cos(radians);
        BlockPos feet = BlockPos.ofFloored(x, y + 0.2, z);
        for (double r = 0.6; r <= reach + 0.01; r += 0.5) {
            BlockPos ahead = BlockPos.ofFloored(x + sx * r, y + 0.2, z + sz * r);
            if (ahead.getX() == feet.getX() && ahead.getZ() == feet.getZ()) continue;
            if (!clear(ahead)) return !hazard(ahead.up()); // A wall stops the push.
            if (!safeToEnter(ahead)) return false;
        }
        return true;
    }

    private boolean safeToEnter(BlockPos ahead) {
        if (hazard(ahead) || hazard(ahead.down())) return false;
        if (standable(ahead) || swimmable(ahead)) return true;
        if (!clear(ahead)) return !hazard(ahead.up()); // A wall or a step up: collision, not a fall.
        for (int k = 1; k <= MAX_SAFE_DROP; k++) {
            BlockPos landing = ahead.down(k);
            if (swimmable(landing)) return true;
            if (!passable(landing)) return false; // Lava below is not passable either.
            if (standable(landing)) return true;
        }
        return false;
    }

    /** Feet and head positions are both free of collision and hazards. */
    boolean clear(BlockPos feet) {
        return passable(feet) && passable(feet.up());
    }

    /** No collision box (or only a thin one like a carpet) and nothing that hurts or traps. */
    boolean passable(BlockPos pos) {
        long key = pos.asLong();
        byte known = passables.get(key);
        if (known == 0) passables.put(key, known = computePassable(pos) ? (byte) 1 : (byte) 2);
        return known == 1;
    }

    private boolean computePassable(BlockPos pos) {
        if (!loaded(pos)) return false;
        BlockState state = state(pos);
        if (hazard(state)) return false;
        if (state.getFluidState().isIn(FluidTags.LAVA)) return false;
        if (doors && state.getBlock() instanceof net.minecraft.block.DoorBlock door && door.getBlockSetType().canOpenByHand()) return true;
        var shape = state.getCollisionShape(world, pos);
        return shape.isEmpty() || shape.getMax(Direction.Axis.Y) <= 0.2;
    }

    /**
     * A body can stand at {@code feet}: solid support below and room for the body, and no lava beside the body or
     * just above it, which would flow in. A hole left by a bucket filled at the edge of a lava pool fills again
     * from the sources around it within a couple of seconds.
     */
    boolean standable(BlockPos feet) {
        long key = feet.asLong();
        byte known = standables.get(key);
        if (known == 0) standables.put(key, known = computeStandable(feet) ? (byte) 1 : (byte) 2);
        return known == 1;
    }

    private boolean computeStandable(BlockPos feet) {
        if (!clear(feet) || lavaAt(feet.up(2))) return false;
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (lavaAt(feet.offset(side)) || lavaAt(feet.up().offset(side))) return false;
        }
        BlockPos below = feet.down();
        if (!loaded(below)) return false;
        BlockState support = state(below);
        if (hazard(support) || support.isOf(Blocks.MAGMA_BLOCK) || support.isIn(BlockTags.FIRE)) return false;
        if (support.getFluidState().isIn(FluidTags.LAVA)) return false;
        var shape = support.getCollisionShape(world, below);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.8 && shape.getMax(Direction.Axis.Y) <= 1.0;
    }

    /**
     * A stand to end a walk on that someone else asked for (E-49): one it can stand on with nothing that harms beside it,
     * where a push or a misstep would hurt. No lava or fire at the feet or the head or just below them on any side, and
     * no open side with a drop deeper than {@link #MAX_SAFE_DROP} onto anything but water. The route's own way there is
     * as any route's: only where it ends is held to this.
     */
    boolean safeStand(BlockPos feet) {
        if (!standable(feet)) return false;
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos beside = feet.offset(side);
            if (hazard(beside) || hazard(beside.up()) || hazard(beside.down()) || lavaAt(beside.down())) return false;
            if (!clear(beside) || standable(beside) || swimmable(beside)) continue;
            // An open side with nothing to stand on: how far down the ground is.
            int depth = 1;
            while (depth <= MAX_SAFE_DROP + 1 && passable(beside.down(depth)) && !swimmable(beside.down(depth))) depth++;
            if (depth > MAX_SAFE_DROP + 1 || lavaAt(beside.down(depth))) return false;
        }
        return true;
    }

    /** Feet in water deep enough to swim, never in lava. */
    boolean swimmable(BlockPos feet) {
        return loaded(feet) && state(feet).getFluidState().isIn(FluidTags.WATER) && !hazard(state(feet));
    }

    private boolean lavaAt(BlockPos pos) {
        return loaded(pos) && state(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    /** Whether the block there would hurt or trap the body. */
    boolean hazard(BlockPos pos) {
        return loaded(pos) && hazard(state(pos));
    }

    private static boolean hazard(BlockState state) {
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.POINTED_DRIPSTONE);
    }

    /** Extra cost near lava and for wading, so routes keep a margin from danger. */
    private double terrainCost(BlockPos feet) {
        long key = feet.asLong();
        if (terrain.containsKey(key)) return terrain.get(key);
        double cost = computeTerrainCost(feet);
        terrain.put(key, cost);
        return cost;
    }

    private double computeTerrainCost(BlockPos feet) {
        double unease = 0;
        for (Danger danger : dangers) {
            double distance = Math.sqrt(danger.at().squaredDistanceTo(feet.getX() + 0.5, feet.getY() + 0.5, feet.getZ() + 0.5));
            unease += dev.kindred.core.tactics.MobSenses.unease(danger.profile(), distance, danger.seen());
        }
        return DANGER_WEIGHT * unease + groundCost(feet) + (shun == null ? 0 : shun.applyAsDouble(feet));
    }

    private double groundCost(BlockPos feet) {
        // Swimming costs more than walking, and swimming with the head under far more: a route across water keeps to
        // the surface, where there is air, rather than the bottom (the staged End run drowned crossing an ocean bed).
        double cost = !swimmable(feet) ? 1.0 : state(feet.up()).getFluidState().isIn(FluidTags.WATER) ? 9.0 : 3.0;
        for (Direction direction : Direction.values()) {
            BlockPos near = feet.offset(direction);
            if (loaded(near) && state(near).getFluidState().isIn(FluidTags.LAVA)) cost += 20;
            BlockPos nearHead = feet.up().offset(direction);
            if (direction.getAxis().isHorizontal() && loaded(nearHead) && state(nearHead).getFluidState().isIn(FluidTags.LAVA)) cost += 20;
        }
        BlockPos below = feet.down(2);
        if (loaded(below) && state(below).getFluidState().isIn(FluidTags.LAVA)) cost += 10;
        // Through a portal only to go through it: a walk to it still ends there, and every other walk goes round (a
        // fixture's player, digging stone beside its lit portal, walked over the frame and on into the Nether).
        if (portal(feet)) cost += PORTAL_COST;
        return cost;
    }

    /** What a step through a portal cell costs a route not going through it. */
    static final double PORTAL_COST = 500;

    /** A portal's own block at the feet or the head: a step there takes the player to another dimension. */
    boolean portal(BlockPos feet) {
        return loaded(feet) && (portalBlock(state(feet)) || loaded(feet.up()) && portalBlock(state(feet.up())));
    }

    private static boolean portalBlock(BlockState state) {
        return state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL) || state.isOf(Blocks.END_GATEWAY);
    }

    /** Whether the spot is in a loaded part of the world, inside its border. */
    boolean loaded(BlockPos pos) {
        if (!world.isInBuildLimit(pos)) return false;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        long chunk = net.minecraft.util.math.ChunkPos.toLong(cx, cz);
        byte known = chunks.get(chunk);
        if (known == 0) chunks.put(chunk, known = world.isChunkLoaded(cx, cz) ? (byte) 1 : (byte) 2);
        return known == 1 && world.getWorldBorder().contains(pos);
    }

    /** The block at the spot, read once and remembered for this search. */
    BlockState state(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = states.get(key);
        if (state == null) states.put(key, state = world.getBlockState(pos));
        return state;
    }
}
