package dev.kindred.core.route;

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
 * so a route builds only where walking round is far longer. It reads only the navigator's {@link Terrain} and edits
 * nothing itself: each step is carried out by the ordinary recorded skills, one at a time, and the route is planned
 * again from wherever the player then stands.
 */
public final class RoutePlanner {
    /** What a step needs doing before the body can stand at {@link Step#to()}. */
    public enum Kind { WALK, DIG, BRIDGE, PILLAR, STAIR }

    /** One step: where it ends, what it takes, and the cells it acts on (to clear, or the one to place). */
    public record Step(Cell from, Cell to, Kind kind, List<Cell> cells) { }

    /** A route: its steps, whether it reaches the goal, and how many positions the search expanded. */
    public record Route(List<Step> steps, boolean complete, int expanded) {
        /** The first step that is not a walk, or -1 when the route walks all the way. */
        public int firstEdit() {
            for (int i = 0; i < steps.size(); i++) if (steps.get(i).kind() != Kind.WALK) return i;
            return -1;
        }
    }

    /**
     * What the player may do on this route, as its caller decides: dig the blocks {@code diggable} allows, and place up
     * to {@code blocksHeld} of its own blocks anywhere but the cells {@code setAside} names (where a placing has just
     * failed, say).
     */
    public record Allowance(Predicate<Cell> diggable, Predicate<Cell> setAside, int blocksHeld) {
        /** Nothing dug and nothing placed: walking only. */
        public static Allowance none() { return new Allowance(pos -> false, pos -> true, 0); }
    }

    /**
     * A bridge block's cost in blocks walked: the block spent and the time (E-71a). A run with the crouch timed to each
     * edge takes about 7 ticks a block, against 12.75 placed and walked onto one at a time and 4.6 walking on the
     * flat (NativeBridgeGameTests), so it costs a walk and a fifth less than it did. Over lava the crouch is held, at
     * about 15.5 ticks a block, and the risk is still the larger part: {@link #OVER_LAVA}.
     */
    public static final double BRIDGE_COST = 5, PILLAR_COST = 7, OVER_LAVA = 14;
    /**
     * A step of a staircase (F-10h): a block, and a block's height and a block's way gained in one movement, so a little
     * less than a pillar's step, which gains only the height. A filler block under it, in the open, costs a bridge's
     * block more.
     */
    public static final double STAIR_COST = 6.5, FILLER_COST = 5;
    /**
     * The search leans toward the target by this much (weighted A*): a route a little dearer than the cheapest, found
     * in a fraction of the positions. Unweighted, digging at about three times a block's distance flooded the search
     * past its cap in the staged Nether runs; walking round a short way still beats digging through.
     */
    public static final double LEAN = 2.0;

    private final Navigator navigator;
    private final Terrain terrain;
    private final Allowance allowance;
    /** The allowance's answer for each cell asked about, once per plan. */
    private final CellMemo diggable = new CellMemo();

    /** A planner that walks as the navigator does, and digs and places only where the allowance says. */
    public RoutePlanner(Navigator navigator, Allowance allowance) {
        this.navigator = navigator;
        this.terrain = navigator.terrain();
        this.allowance = allowance;
    }

    /**
     * Searches from {@code start} toward {@code target}; a position satisfying {@code goal} ends it. An incomplete
     * route leads to the explored position nearest the target.
     */
    public Route find(Cell start, Cell target, Predicate<Cell> goal, int maxNodes, int maxHorizontalRadius) {
        record Node(Cell pos, double g, double f) { }
        var open = new PriorityQueue<Node>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Cell, Double> best = new HashMap<>();
        Map<Cell, Step> via = new HashMap<>();
        Cell origin = start;
        open.add(new Node(origin, 0, LEAN * heuristic(origin, target)));
        best.put(origin, 0.0);
        Cell closest = origin;
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
                Cell to = edge.step().to();
                if (Math.abs(to.x() - start.x()) > maxHorizontalRadius || Math.abs(to.z() - start.z()) > maxHorizontalRadius) continue;
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
    private List<Edge> edges(Cell from, Step into) {
        var edges = new ArrayList<Edge>(16);
        for (var move : navigator.moves(from)) edges.add(new Edge(new Step(from, move.to(), Kind.WALK, List.of()), move.cost()));
        boolean wet = navigator.swimmable(from);
        for (Side side : Side.HORIZONTAL) {
            Cell flat = from.offset(side);
            if (allowance.blocksHeld() > 0 && !wet) stair(from, side, into, edges);
            if (!navigator.loaded(flat) || navigator.loaded(flat) && navigator.clear(flat) && navigator.standable(flat)) continue;
            // Dig through: what blocks the body's way at this level, with ground to stand on beyond.
            if (!wet) {
                List<Cell> cut = new ArrayList<>(2);
                boolean possible = true;
                double cost = 1.0;
                for (Cell cell : List.of(flat, flat.up())) {
                    if (navigator.passable(cell)) continue;
                    float hardness = terrain.hardness(cell.x(), cell.y(), cell.z());
                    if (hardness < 0 || terrain.fluid(cell.x(), cell.y(), cell.z()) != Terrain.Fluid.NONE || !diggable(cell)) { possible = false; break; }
                    cut.add(cell);
                    cost += 0.5 + hardness * 1.5;
                }
                if (possible && !cut.isEmpty() && supported(flat)) {
                    edges.add(new Edge(new Step(from, flat, Kind.DIG, List.copyOf(cut)), cost));
                    continue;
                }
            }
            // Bridge: open for the body but nothing to stand on: a block of its own placed there first.
            if (allowance.blocksHeld() > 0 && !wet && navigator.clear(flat) && !supported(flat) && replaceable(flat.down())
                    && placeable(flat.down())) {
                double cost = 1.0 + BRIDGE_COST + (lavaBelow(flat) ? OVER_LAVA : 0);
                edges.add(new Edge(new Step(from, flat, Kind.BRIDGE, List.of(flat.down())), cost));
            }
        }
        // Pillar: jump and place a block underfoot, with room overhead to rise into.
        if (allowance.blocksHeld() > 0 && !wet && navigator.passable(from.up(2)) && navigator.clear(from) && placeable(from)) {
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
    private void stair(Cell from, Side side, Step into, List<Edge> edges) {
        Cell block = from.offset(side), to = block.up();
        if (!navigator.loaded(block) || !navigator.loaded(to.up()) || !navigator.loaded(from.up(2))) return;
        if (!terrain.replaceable(block.x(), block.y(), block.z()) || terrain.fluid(block.x(), block.y(), block.z()) != Terrain.Fluid.NONE
                || navigator.hazard(block) || !placeable(block)) return;
        // Up into the cell over the block: room for the body there, and for its head on the jump.
        if (!navigator.clear(to) || !navigator.passable(from.up(2))) return;
        if (face(block, side)) {
            edges.add(new Edge(new Step(from, to, Kind.STAIR, List.of(block)), STAIR_COST + (lavaBelow(block) ? OVER_LAVA : 0)));
            return;
        }
        Cell filler = block.down();
        boolean footing = placeAgainst(from.down())
                || into != null && !into.cells().isEmpty() && into.cells().getLast().equals(from.down());
        if (!footing || !navigator.loaded(filler)) return;
        if (terrain.replaceable(filler.x(), filler.y(), filler.z()) && terrain.fluid(filler.x(), filler.y(), filler.z()) == Terrain.Fluid.NONE
                && !navigator.hazard(filler) && placeable(filler)) {
            edges.add(new Edge(new Step(from, to, Kind.STAIR, List.of(filler, block)), STAIR_COST + FILLER_COST + (lavaBelow(block) ? OVER_LAVA : 0)));
        }
    }

    /**
     * Whether a block set in {@code cell}, coming along {@code along}, has a face to go against: the block under it,
     * beside it, or ahead of it, never the one behind (where the player stands).
     */
    private boolean face(Cell cell, Side along) {
        if (placeAgainst(cell.down())) return true;
        for (Side beside : List.of(along.clockwise(), along.counterclockwise())) {
            if (placeAgainst(cell.offset(beside))) return true;
        }
        return placeAgainst(cell.offset(along));
    }

    private boolean placeAgainst(Cell cell) {
        return terrain.placeAgainst(cell.x(), cell.y(), cell.z());
    }

    private boolean placeable(Cell cell) {
        return !allowance.setAside().test(cell);
    }

    private boolean diggable(Cell cell) {
        long key = cell.asLong();
        if (diggable.contains(key)) return diggable.get(key, 0) != 0;
        boolean allowed = allowance.diggable().test(cell);
        diggable.put(key, allowed ? 1 : 0);
        return allowed;
    }

    /** Solid ground under {@code feet} (as the navigator counts it), so the body can stand there once it is clear. */
    private boolean supported(Cell feet) {
        Cell below = feet.down();
        if (!navigator.loaded(below)) return false;
        if (navigator.hazard(below) || terrain.fluid(below.x(), below.y(), below.z()) == Terrain.Fluid.LAVA) return false;
        return terrain.bears(below.x(), below.y(), below.z());
    }

    private boolean replaceable(Cell pos) {
        return navigator.loaded(pos) && terrain.replaceable(pos.x(), pos.y(), pos.z());
    }

    /** Lava under the cell within a fall's reach, where a slip off the bridge would end in it. */
    private boolean lavaBelow(Cell feet) {
        for (int k = 1; k <= 6; k++) {
            Cell at = feet.down(k);
            if (!navigator.loaded(at)) return false;
            if (terrain.fluid(at.x(), at.y(), at.z()) == Terrain.Fluid.LAVA) return true;
            if (!navigator.passable(at) && !terrain.replaceable(at.x(), at.y(), at.z())) return false;
        }
        return false;
    }

    private static double heuristic(Cell a, Cell b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.8;
    }

    private static List<Step> trace(Map<Cell, Step> via, Cell end) {
        var steps = new ArrayList<Step>();
        for (Step step = via.get(end); step != null; step = via.get(step.from())) {
            steps.add(step);
            if (steps.size() > 10_000) break;
        }
        Collections.reverse(steps);
        return steps;
    }
}
