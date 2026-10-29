package dev.kindred.fabric;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
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
 * In plain terms: plans how an AI player gets from one place to another the way a human would: walking where
 * it can, and otherwise digging through, bridging a gap, or building up a pillar or a staircase with its own
 * blocks, whichever is least trouble. It only plans; each step is then done by the player's ordinary actions,
 * and the plan is made again from wherever it ends up.
 *
 * Technical: Routes that may change the world on the way, the way a player gets about: walking where it can,
 * and otherwise digging through what is in the way, bridging a gap with a block of its own, pillaring up, or
 * building a staircase up as it walks, whichever costs least. Walking is cheap; digging costs by how hard the
 * block is; a placed block costs more again (it spends one, and a bridge over lava or a long drop is a risk),
 * so a route builds only where walking round is far longer. It reads only loaded blocks and edits nothing
 * itself: each step is carried out by the ordinary recorded skills, one at a time, and the route is planned
 * again from wherever the player then stands.
 */
final class NativeRoutePlanner {
    /** What a step needs doing before the body can stand at {@link Step#to()}. */
    enum Kind { WALK, DIG, BRIDGE, PILLAR, STAIR }

    /** One step: where it ends, what it takes, and the cells it acts on (to clear, or the one to place). */
    record Step(BlockPos from, BlockPos to, Kind kind, List<BlockPos> cells) { }

    record Route(List<Step> steps, boolean complete, int expanded) {
        /** The first step that is not a walk, or -1 when the route walks all the way. */
        int firstEdit() {
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).kind() != Kind.WALK) return i;
            return -1;
        }
    }

    /**
     * What the player may do on this route: dig the blocks {@code diggable} allows, and place up to {@code blocks}
     * where {@code placeable} allows (not where a placing has just failed).
     */
    record Allowance(Predicate<BlockPos> diggable, Predicate<BlockPos> placeable, int blocks) {
        static Allowance none() { return new Allowance(pos -> false, pos -> false, 0); }
    }

    static final double BRIDGE_COST = 6, PILLAR_COST = 7, OVER_LAVA = 14;
    /**
     * A step of a staircase (F-10h): a block, and a block's height and a block's way gained in one movement, so a little
     * less than a pillar's step, which gains only the height. A filler block under it, in the open, costs a bridge's
     * block more.
     */
    static final double STAIR_COST = 6.5, FILLER_COST = 5;
    /**
     * The search leans toward the target by this much (weighted A*): a route a little dearer than the cheapest, found
     * in a fraction of the positions. Unweighted, digging at about three times a block's distance flooded the search
     * past its cap in the staged Nether runs; walking round a short way still beats digging through.
     */
    static final double LEAN = 2.0;

    private final NativeNavigator navigator;
    private final Allowance allowance;
    /** The allowance's answer for each cell asked about, once per plan. */
    private final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap diggable = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();

    /** A planner that walks as the navigator does, and digs and places only where the allowance says. */
    NativeRoutePlanner(NativeNavigator navigator, Allowance allowance) {
        this.navigator = navigator;
        this.allowance = allowance;
    }

    /**
     * Searches from {@code start} toward {@code target}; a position satisfying {@code goal} ends it. An incomplete
     * route leads to the explored position nearest the target.
     */
    Route find(BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes, int maxHorizontalRadius) {
        record Node(BlockPos pos, double g, double f) { }
        var open = new PriorityQueue<Node>((a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, Step> via = new HashMap<>();
        BlockPos origin = start.toImmutable();
        open.add(new Node(origin, 0, LEAN * heuristic(origin, target)));
        best.put(origin, 0.0);
        BlockPos closest = origin;
        double closestH = heuristic(origin, target);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.poll();
            if (current.g() > best.getOrDefault(current.pos(), Double.MAX_VALUE)) continue;
            expanded++;
            if (goal.test(current.pos())) return new Route(trace(via, current.pos()), true, expanded);
            double h = heuristic(current.pos(), target);
            if (h < closestH) {
                closestH = h;
                closest = current.pos();
            }
            for (var edge : edges(current.pos(), via.get(current.pos()))) {
                BlockPos to = edge.step().to();
                if (Math.abs(to.getX() - start.getX()) > maxHorizontalRadius || Math.abs(to.getZ() - start.getZ()) > maxHorizontalRadius) continue;
                double g = current.g() + edge.cost();
                if (g < best.getOrDefault(to, Double.MAX_VALUE)) {
                    best.put(to, g);
                    via.put(to, edge.step());
                    open.add(new Node(to, g, g + LEAN * heuristic(to, target)));
                }
            }
        }
        return new Route(trace(via, closest), false, expanded);
    }

    private record Edge(Step step, double cost) { }

    /** The ways on from {@code from}, which {@code into} reached (null at the start). */
    private List<Edge> edges(BlockPos from, Step into) {
        var edges = new ArrayList<Edge>(16);
        for (var move : navigator.moves(from)) edges.add(new Edge(new Step(from, move.to(), Kind.WALK, List.of()), move.cost()));
        boolean wet = navigator.swimmable(from);
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos flat = from.offset(side);
            if (allowance.blocks() > 0 && !wet) stair(from, side, into, edges);
            if (!navigator.loaded(flat) || navigator.loaded(flat) && navigator.clear(flat) && navigator.standable(flat)) continue;
            // Dig through: what blocks the body's way at this level, with ground to stand on beyond.
            if (!wet) {
                List<BlockPos> cut = new ArrayList<>(2);
                boolean possible = true;
                double cost = 1.0;
                for (BlockPos cell : List.of(flat, flat.up())) {
                    if (navigator.passable(cell)) continue;
                    BlockState state = navigator.state(cell);
                    float hardness = state.getHardness(navigator.world(), cell);
                    if (hardness < 0 || !state.getFluidState().isEmpty() || !diggable(cell)) { possible = false; break; }
                    cut.add(cell);
                    cost += 0.5 + hardness * 1.5;
                }
                if (possible && !cut.isEmpty() && supported(flat)) {
                    edges.add(new Edge(new Step(from, flat, Kind.DIG, List.copyOf(cut)), cost));
                    continue;
                }
            }
            // Bridge: open for the body but nothing to stand on: a block of its own placed there first.
            if (allowance.blocks() > 0 && !wet && navigator.clear(flat) && !supported(flat) && replaceable(flat.down())
                    && allowance.placeable().test(flat.down())) {
                double cost = 1.0 + BRIDGE_COST + (lavaBelow(flat) ? OVER_LAVA : 0);
                edges.add(new Edge(new Step(from, flat, Kind.BRIDGE, List.of(flat.down())), cost));
            }
        }
        // Pillar: jump and place a block underfoot, with room overhead to rise into.
        if (allowance.blocks() > 0 && !wet && navigator.passable(from.up(2)) && navigator.clear(from) && allowance.placeable().test(from)) {
            edges.add(new Edge(new Step(from, from.up(), Kind.PILLAR, List.of(from)), PILLAR_COST));
        }
        return edges;
    }

    /**
     * A step of a staircase along {@code side}: a block of its own set in the open cell ahead at the feet's level, and up
     * onto it in the same movement. The block goes against a face under, beside or ahead of it; with none, in the open,
     * a filler block goes under it first, against the block the player stands on (placed by the step that came here,
     * when that was a placing step).
     */
    private void stair(BlockPos from, Direction side, Step into, List<Edge> edges) {
        BlockPos block = from.offset(side), to = block.up();
        if (!navigator.loaded(block) || !navigator.loaded(to.up()) || !navigator.loaded(from.up(2))) return;
        BlockState state = navigator.state(block);
        if (!state.isReplaceable() || !state.getFluidState().isEmpty() || navigator.hazard(block) || !allowance.placeable().test(block)) return;
        // Up into the cell over the block: room for the body there, and for its head on the jump.
        if (!navigator.clear(to) || !navigator.passable(from.up(2))) return;
        if (NativeStairAction.face(navigator.world(), block, side) != null) {
            edges.add(new Edge(new Step(from, to, Kind.STAIR, List.of(block)), STAIR_COST + (lavaBelow(block) ? OVER_LAVA : 0)));
            return;
        }
        BlockPos filler = block.down();
        boolean footing = NativeStairAction.solid(navigator.world(), from.down())
                || into != null && !into.cells().isEmpty() && into.cells().getLast().equals(from.down());
        if (!footing || !navigator.loaded(filler)) return;
        BlockState under = navigator.state(filler);
        if (under.isReplaceable() && under.getFluidState().isEmpty() && !navigator.hazard(filler) && allowance.placeable().test(filler)) {
            edges.add(new Edge(new Step(from, to, Kind.STAIR, List.of(filler, block)), STAIR_COST + FILLER_COST + (lavaBelow(block) ? OVER_LAVA : 0)));
        }
    }

    private boolean diggable(BlockPos cell) {
        long key = cell.asLong();
        if (diggable.containsKey(key)) return diggable.get(key);
        boolean allowed = allowance.diggable().test(cell);
        diggable.put(key, allowed);
        return allowed;
    }

    /** Solid ground under {@code feet} (as the navigator counts it), so the body can stand there once it is clear. */
    private boolean supported(BlockPos feet) {
        BlockPos below = feet.down();
        if (!navigator.loaded(below)) return false;
        BlockState support = navigator.state(below);
        if (navigator.hazard(below) || support.getFluidState().isIn(FluidTags.LAVA)) return false;
        var shape = support.getCollisionShape(navigator.world(), below);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.8;
    }

    private boolean replaceable(BlockPos pos) {
        return navigator.loaded(pos) && navigator.state(pos).isReplaceable();
    }

    /** Lava under the cell within a fall's reach, where a slip off the bridge would end in it. */
    private boolean lavaBelow(BlockPos feet) {
        for (int k = 1; k <= 6; k++) {
            BlockPos at = feet.down(k);
            if (!navigator.loaded(at)) return false;
            if (navigator.state(at).getFluidState().isIn(FluidTags.LAVA)) return true;
            if (!navigator.passable(at) && !navigator.state(at).isReplaceable()) return false;
        }
        return false;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.8;
    }

    private static List<Step> trace(Map<BlockPos, Step> via, BlockPos end) {
        var steps = new ArrayList<Step>();
        for (Step step = via.get(end); step != null; step = via.get(step.from())) {
            steps.add(step);
            if (steps.size() > 10_000) break;
        }
        Collections.reverse(steps);
        return steps;
    }
}
