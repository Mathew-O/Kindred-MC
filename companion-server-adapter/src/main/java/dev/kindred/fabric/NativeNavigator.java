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
 * Bounded A* over standing positions for a player-sized body. It only reads loaded blocks the body could see
 * by walking there; it never edits the world. Moves: walk (including diagonals without corner cutting), step
 * up one block with a jump, drop up to three blocks (further into water), and swim. Lava, fire and other
 * damaging blocks are never entered, and positions beside lava cost extra.
 */
final class NativeNavigator {
    static final int DEFAULT_MAX_NODES = 6_000;
    static final int MAX_SAFE_DROP = 3;
    static final int MAX_WATER_DROP = 12;

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

    NativeNavigator(ServerWorld world) {
        this(world, MAX_SAFE_DROP);
    }

    ServerWorld world() { return world; }

    /** With a larger {@code maxDrop}, routes may include one deliberate fall that costs some health. */
    NativeNavigator(ServerWorld world, int maxDrop) {
        this.world = world;
        this.maxDrop = Math.clamp(maxDrop, MAX_SAFE_DROP, 12);
    }

    /**
     * Searches from {@code start} (feet position) toward {@code target}. A node satisfying {@code goal} ends the
     * search; otherwise the path to the explored node closest to the target is returned as incomplete.
     */
    Path find(BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes, int maxHorizontalRadius) {
        BlockPos origin = start.toImmutable();
        if (!standable(origin) && !swimmable(origin)) {
            // A body mid-jump or on a slab edge: search from the block below if that is where it really stands.
            if (standable(origin.down())) origin = origin.down();
        }
        record Node(BlockPos pos, double g, double f) { }
        var open = new PriorityQueue<Node>((a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
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
            if (h < closestH) {
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
                int limit = inWater ? 1 : MAX_WATER_DROP;
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
        Path path = find(from, from, feet -> standable(feet) && state(feet).getFluidState().isEmpty()
                && state(feet.down()).getFluidState().isEmpty(), maxNodes, maxHorizontalRadius);
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

    /** Feet in water deep enough to swim, never in lava. */
    boolean swimmable(BlockPos feet) {
        return loaded(feet) && state(feet).getFluidState().isIn(FluidTags.WATER) && !hazard(state(feet));
    }

    private boolean lavaAt(BlockPos pos) {
        return loaded(pos) && state(pos).getFluidState().isIn(FluidTags.LAVA);
    }

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
        return cost;
    }

    boolean loaded(BlockPos pos) {
        if (!world.isInBuildLimit(pos)) return false;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        long chunk = net.minecraft.util.math.ChunkPos.toLong(cx, cz);
        byte known = chunks.get(chunk);
        if (known == 0) chunks.put(chunk, known = world.isChunkLoaded(cx, cz) ? (byte) 1 : (byte) 2);
        return known == 1 && world.getWorldBorder().contains(pos);
    }

    BlockState state(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = states.get(key);
        if (state == null) states.put(key, state = world.getBlockState(pos));
        return state;
    }
}
