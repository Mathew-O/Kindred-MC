package dev.kindred.core.route;

import dev.kindred.core.tactics.MobSenses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * In plain terms: finds a walking route for an AI player through the world as it really is: walking, stepping
 * up, dropping a few blocks or swimming, but never into lava, fire or other harmful blocks. It only looks at
 * parts of the world that are loaded, and never changes anything.
 *
 * Technical: Bounded A* over standing positions for a player-sized body, over a {@link Terrain}'s per-cell facts. It
 * only asks about loaded cells the body could see by walking there; it never edits the world. Moves: walk (including
 * diagonals without corner cutting), step up one block with a jump, drop up to three blocks (further into water),
 * swim, and, when asked, jump a gap of one or two blocks. Lava, fire and other damaging blocks are never entered, and
 * positions beside lava cost extra. The server adapter's navigator wraps it over the live world.
 */
public final class Navigator {
    public static final int DEFAULT_MAX_NODES = 6_000;
    public static final int MAX_SAFE_DROP = 3;
    public static final int MAX_WATER_DROP = 12;
    /**
     * The deepest deliberate drop, taken only with a water bucket ready to break it (E-07d): forty blocks down a fall
     * is about two blocks a tick, inside the 2.8 blocks a tick at which the ground is surely in reach for a tick.
     */
    public static final int MAX_CLUTCH_DROP = 40;
    /** Extra cost per block, beside a mob, of a route that would rather not be noticed. */
    public static final double DANGER_WEIGHT = 10;
    /** What a step through a portal cell costs a route not going through it. */
    public static final double PORTAL_COST = 500;
    /** The cells it crosses a roof of slabs or stairs by, at most this many. */
    static final int LOW_STANDS = 16;

    /** A route: the cells walked in turn, whether it reaches the goal, and how many positions the search expanded. */
    public record Path(List<Cell> nodes, boolean complete, int expanded) {
        public Cell end() { return nodes.isEmpty() ? null : nodes.getLast(); }
    }

    /** One move from a stand: where it ends, and what it costs. */
    public record Move(Cell to, double cost) { }

    /** A mob to keep clear of: where it is, how it senses, and whether it can see the player now. */
    public record Danger(double x, double y, double z, MobSenses.Profile profile, boolean seen) { }

    private final Terrain terrain;
    /**
     * What one search has worked out, so each test is made once: a search asks the same few hundred cells about many
     * times over. A navigator lives for one search or one tick, so nothing here goes stale.
     */
    private final CellMemo passables = new CellMemo(), standables = new CellMemo(), costs = new CellMemo();
    private final int maxDrop;
    private List<Danger> dangers = List.of();
    /** An extra cost for each block of the route at a place, as its atlas weighs a night's danger there (F-30); none by default. */
    private ToDoubleFunction<Cell> shun;
    /** Where an unfinished route may end, when not just anywhere it can stand (E-49); null for anywhere. */
    private Predicate<Cell> endable;
    /** The deepest drop a route may take, below {@link #maxDrop}'s floor of {@link #MAX_SAFE_DROP}; none by default. */
    private int fallCap = Integer.MAX_VALUE;
    /** The widest gap a route may jump; 0 for none. */
    private int gapJump;

    /** A navigator over the terrain that drops the body no further than is safe. */
    public Navigator(Terrain terrain) {
        this(terrain, MAX_SAFE_DROP);
    }

    /**
     * With a larger {@code maxDrop}, routes may include one deliberate fall that costs some health, or, past twelve
     * blocks, one broken with water (callers allow that only with a water bucket ready).
     */
    public Navigator(Terrain terrain, int maxDrop) {
        this.terrain = terrain;
        this.maxDrop = Math.clamp(maxDrop, MAX_SAFE_DROP, MAX_CLUTCH_DROP);
    }

    /** The terrain it reads. */
    public Terrain terrain() { return terrain; }

    /** Routes priced dearer through places it has been hurt or killed (F-30): {@code cost} is the extra for each block there. */
    public Navigator shunning(ToDoubleFunction<Cell> cost) {
        this.shun = cost;
        costs.clear();
        return this;
    }

    /** Routes priced to keep out of these mobs' notice: dearer the nearer they pass (F-10g). */
    public Navigator avoiding(List<Danger> dangers) {
        this.dangers = List.copyOf(dangers);
        costs.clear();
        return this;
    }

    /** The same navigator, ending an unfinished route only where {@code where} allows. */
    public Navigator endingOnly(Predicate<Cell> where) {
        endable = where;
        return this;
    }

    /**
     * The same navigator, its routes taking no drop deeper than {@code blocks}, nor into water deeper: one it can step
     * back up from, when that is wanted (M-01a: leading an animal, which must follow it back up).
     */
    public Navigator fallingAtMost(int blocks) {
        if (blocks < 0) throw new IllegalArgumentException("A fall is 0 blocks or more");
        fallCap = blocks;
        return this;
    }

    /**
     * The same navigator, now jumping gaps up to {@code blocks} wide (1 or 2) as a player does (MOVEMENT): straight across
     * to ground at the same level, with head room for the jump and nothing harmful below the gap. Only a walk that can
     * make the jump asks for it (a two-block gap needs a sprint); other plans keep to the ground.
     */
    public Navigator jumpingGaps(int blocks) {
        if (blocks < 0 || blocks > 2) throw new IllegalArgumentException("A gap jump is 0 to 2 blocks");
        gapJump = blocks;
        return this;
    }

    /**
     * Forgets which cells it has found a body passes through or stands in, for a terrain whose answer to
     * {@link Terrain#passThrough} has just changed (the adapter's, when closed doors start to count as a way through).
     */
    public void forgetPassage() {
        passables.clear();
        standables.clear();
    }

    /**
     * Searches from {@code start} (feet position) toward {@code target}. A node satisfying {@code goal} ends the
     * search; otherwise the path to the explored node closest to the target is returned as incomplete.
     */
    public Path find(Cell start, Cell target, Predicate<Cell> goal, int maxNodes, int maxHorizontalRadius) {
        Cell origin = start;
        if (!standable(origin) && !swimmable(origin)) {
            // A body mid-jump or on a slab edge: search from the block below if that is where it really stands.
            if (standable(origin.down())) {
                origin = origin.down();
            } else {
                // On something lower than a full block that no route starts from (its bed, woken on it): from the
                // cell beside it it would step off into. Woken on its bed in its cabin, every walk out of it was "no
                // walkable route", and its scaffold outside was never taken down (suite run 33).
                for (Side side : Side.HORIZONTAL) {
                    Cell off = origin.offset(side);
                    Cell stand = standable(off) ? off : standable(off.down()) ? off.down() : null;
                    if (stand != null) {
                        origin = stand;
                        break;
                    }
                }
            }
        }
        record Node(Cell pos, double g, double f) { }
        var open = new PriorityQueue<Node>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Cell, Double> best = new HashMap<>();
        Map<Cell, Cell> parent = new HashMap<>();
        if (!standable(origin) && !swimmable(origin) && lowStand(origin)) {
            // On a roof of slabs or stairs, every cell beside it another: across them to the nearest stand, those cells
            // kept at the head of the route, walked in turn. From the slab's cell, which is no stand, the search went no
            // further, and a cabin's builder on its roof found "no walkable route" eight times in a row, and the cabin
            // stopped eight blocks short (#78's CI, run 42556777789) (F-22c35).
            Cell stand = acrossLowStands(origin, parent);
            if (stand != null) origin = stand;
        }
        open.add(new Node(origin, 0, heuristic(origin, target)));
        best.put(origin, 0.0);
        Cell closest = origin;
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
                if (Math.abs(move.to().x() - start.x()) > maxHorizontalRadius
                        || Math.abs(move.to().z() - start.z()) > maxHorizontalRadius) continue;
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

    /**
     * A cell a body stands in on the lower half of a block, as on a bottom slab or a bottom stair, with room above it: no
     * stand to the search, which wants a full block's top under an empty cell.
     */
    public boolean lowStand(Cell cell) {
        if (!loaded(cell) || !passable(cell.up()) || !passable(cell.up(2))) return false;
        if (terrain.harms(cell.x(), cell.y(), cell.z())) return false;
        return terrain.bottomHalf(cell.x(), cell.y(), cell.z());
    }

    /**
     * The nearest stand beside a run of low stands from {@code from}, a step up or down or level, found across at most
     * {@link #LOW_STANDS} of them; the cells crossed go into {@code parent}, so the route traced back from it begins at
     * {@code from}. Null when there is none.
     */
    private Cell acrossLowStands(Cell from, Map<Cell, Cell> parent) {
        var queue = new java.util.ArrayDeque<Cell>();
        var seen = new HashMap<Cell, Cell>();
        queue.add(from);
        seen.put(from, null);
        while (!queue.isEmpty() && seen.size() <= LOW_STANDS) {
            Cell at = queue.poll();
            for (Side side : Side.HORIZONTAL) {
                Cell off = at.offset(side);
                if (seen.containsKey(off) || !loaded(off)) continue;
                Cell stand = standable(off) ? off : standable(off.up()) ? off.up() : standable(off.down()) ? off.down() : null;
                if (stand != null) {
                    parent.put(stand, at);
                    for (Cell cell = at; seen.get(cell) != null; cell = seen.get(cell)) parent.put(cell, seen.get(cell));
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

    private static double heuristic(Cell a, Cell b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.8;
    }

    private static List<Cell> trace(Map<Cell, Cell> parent, Cell end) {
        var path = new ArrayList<Cell>();
        for (Cell at = end; at != null; at = parent.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Every move a body standing at the given spot can make next, with what each costs. */
    public List<Move> moves(Cell from) {
        var moves = new ArrayList<Move>(12);
        boolean inWater = swimmable(from);
        for (int[] d : HORIZONTAL) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            Cell flat = from.add(d[0], 0, d[1]);
            if (!loaded(flat)) continue;
            if (diagonal && (!clear(from.add(d[0], 0, 0)) || !clear(from.add(0, 0, d[1])))) continue;
            double base = diagonal ? 1.414 : 1.0;
            if (clear(flat) && (standable(flat) || swimmable(flat))) {
                moves.add(new Move(flat, base * terrainCost(flat)));
                continue;
            }
            if (diagonal) continue;
            // Step up: the next block is an obstacle we can jump onto, with headroom above both positions.
            Cell up = flat.up();
            if (clear(up) && (standable(up) || swimmable(up)) && passable(from.up(2)) && !hazard(flat)) {
                moves.add(new Move(up, 2.0 * terrainCost(up)));
                continue;
            }
            // Drop: walk off an edge onto ground (or water) a few blocks below.
            if (clear(flat)) {
                int limit = Math.min(fallCap, inWater ? 1 : Math.max(MAX_WATER_DROP, maxDrop));
                for (int k = 1; k <= limit; k++) {
                    Cell landing = flat.down(k);
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
                    Cell over = from.add(d[0] * (span - 1), 0, d[1] * (span - 1));
                    if (!loaded(over) || !clear(over) || standable(over) || swimmable(over) || !passable(over.up(2)) || hazard(over.down())) break;
                    Cell land = from.add(d[0] * span, 0, d[1] * span);
                    if (!loaded(land)) break;
                    if (clear(land) && standable(land) && !hazard(land) && passable(land.up(2))) {
                        moves.add(new Move(land, (span == 2 ? 3.0 : 5.0) * terrainCost(land)));
                        break;
                    }
                }
            }
        }
        if (inWater) {
            Cell up = from.up();
            if (clear(up) && (swimmable(up) || standable(up))) moves.add(new Move(up, 2.0));
            Cell down = from.down();
            if (loaded(down) && swimmable(down) && clear(down)) moves.add(new Move(down, 6.0));
        }
        return moves;
    }

    /**
     * Nearest dry place to stand around {@code from}, searched in growing square rings so the first ring with a
     * match gives the closest shore. Used to leave deep water before breath runs out.
     */
    public Optional<Cell> nearestDryStand(Cell from, int radius, int vertical) {
        for (int ring = 1; ring <= radius; ring++) {
            Cell best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    for (int dy = -vertical; dy <= vertical; dy++) {
                        Cell feet = from.add(dx, dy, dz);
                        if (!standable(feet) || !dry(feet) || !dry(feet.down())) continue;
                        double distance = feet.squaredDistance(from);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = feet;
                        }
                    }
                }
            }
            if (best != null) return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * The nearest dry ground the body can actually get to from {@code from}, swimming if it must, passing over dry
     * ground {@code allowed} refuses: the search follows routes, so dry ground behind a ceiling or across a wall, however
     * close, is passed over.
     */
    public Optional<Cell> nearestReachableDryStand(Cell from, int maxNodes, int maxHorizontalRadius, Predicate<Cell> allowed) {
        Path path = find(from, from, feet -> standable(feet) && dry(feet) && dry(feet.down()) && allowed.test(feet),
                maxNodes, maxHorizontalRadius);
        return path.complete() && !path.nodes().isEmpty() ? Optional.of(path.nodes().getLast()) : Optional.empty();
    }

    private boolean dry(Cell cell) {
        return terrain.fluid(cell.x(), cell.y(), cell.z()) == Terrain.Fluid.NONE;
    }

    /**
     * Whether a body may walk into the cell ahead: standable or swimmable, a wall or one-block step up, or a drop no
     * deeper than {@link #MAX_SAFE_DROP}, and never lava or fire, there or under it. Direct steering (a fight chase, a
     * knockback taken) checks this where it does not follow a planned route.
     */
    public boolean safeToEnter(Cell ahead) {
        if (hazard(ahead) || hazard(ahead.down())) return false;
        if (standable(ahead) || swimmable(ahead)) return true;
        if (!clear(ahead)) return !hazard(ahead.up()); // A wall or a step up: collision, not a fall.
        for (int k = 1; k <= MAX_SAFE_DROP; k++) {
            Cell landing = ahead.down(k);
            if (swimmable(landing)) return true;
            if (!passable(landing)) return false; // Lava below is not passable either.
            if (standable(landing)) return true;
        }
        return false;
    }

    /** Feet and head positions are both free of collision and hazards. */
    public boolean clear(Cell feet) {
        return passable(feet) && passable(feet.up());
    }

    /** No collision box (or only a thin one like a carpet) and nothing that hurts or traps. */
    public boolean passable(Cell pos) {
        long key = pos.asLong();
        double known = passables.get(key, 0);
        if (known == 0) passables.put(key, known = computePassable(pos) ? 1 : 2);
        return known == 1;
    }

    private boolean computePassable(Cell pos) {
        if (!loaded(pos)) return false;
        int x = pos.x(), y = pos.y(), z = pos.z();
        if (terrain.harms(x, y, z)) return false;
        if (terrain.fluid(x, y, z) == Terrain.Fluid.LAVA) return false;
        return terrain.passThrough(x, y, z);
    }

    /**
     * A body can stand at {@code feet}: solid support below and room for the body, and no lava beside the body or
     * just above it, which would flow in. A hole left by a bucket filled at the edge of a lava pool fills again
     * from the sources around it within a couple of seconds.
     */
    public boolean standable(Cell feet) {
        long key = feet.asLong();
        double known = standables.get(key, 0);
        if (known == 0) standables.put(key, known = computeStandable(feet) ? 1 : 2);
        return known == 1;
    }

    private boolean computeStandable(Cell feet) {
        if (!clear(feet) || lavaAt(feet.up(2))) return false;
        for (Side side : Side.HORIZONTAL) {
            if (lavaAt(feet.offset(side)) || lavaAt(feet.up().offset(side))) return false;
        }
        Cell below = feet.down();
        if (!loaded(below)) return false;
        int x = below.x(), y = below.y(), z = below.z();
        if (terrain.harms(x, y, z) || terrain.scorches(x, y, z)) return false;
        if (terrain.fluid(x, y, z) == Terrain.Fluid.LAVA) return false;
        return terrain.supports(x, y, z);
    }

    /**
     * A stand to end a walk on that someone else asked for (E-49): one it can stand on with nothing that harms beside it,
     * where a push or a misstep would hurt. No lava or fire at the feet or the head or just below them on any side, and
     * no open side with a drop deeper than {@link #MAX_SAFE_DROP} onto anything but water. The route's own way there is
     * as any route's: only where it ends is held to this.
     */
    public boolean safeStand(Cell feet) {
        if (!standable(feet)) return false;
        for (Side side : Side.HORIZONTAL) {
            Cell beside = feet.offset(side);
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
    public boolean swimmable(Cell feet) {
        return loaded(feet) && terrain.fluid(feet.x(), feet.y(), feet.z()) == Terrain.Fluid.WATER
                && !terrain.harms(feet.x(), feet.y(), feet.z());
    }

    private boolean lavaAt(Cell pos) {
        return loaded(pos) && terrain.fluid(pos.x(), pos.y(), pos.z()) == Terrain.Fluid.LAVA;
    }

    /** Whether the block there would hurt or trap the body. */
    public boolean hazard(Cell pos) {
        return loaded(pos) && terrain.harms(pos.x(), pos.y(), pos.z());
    }

    /** Extra cost near lava and for wading, so routes keep a margin from danger. */
    private double terrainCost(Cell feet) {
        long key = feet.asLong();
        if (costs.contains(key)) return costs.get(key, 0);
        double cost = computeTerrainCost(feet);
        costs.put(key, cost);
        return cost;
    }

    private double computeTerrainCost(Cell feet) {
        double unease = 0;
        for (Danger danger : dangers) {
            double dx = feet.x() + 0.5 - danger.x(), dy = feet.y() + 0.5 - danger.y(), dz = feet.z() + 0.5 - danger.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            unease += MobSenses.unease(danger.profile(), distance, danger.seen());
        }
        return DANGER_WEIGHT * unease + groundCost(feet) + (shun == null ? 0 : shun.applyAsDouble(feet));
    }

    private double groundCost(Cell feet) {
        // Swimming costs more than walking, and swimming with the head under far more: a route across water keeps to
        // the surface, where there is air, rather than the bottom (the staged End run drowned crossing an ocean bed).
        double cost = !swimmable(feet) ? 1.0 : terrain.fluid(feet.x(), feet.y() + 1, feet.z()) == Terrain.Fluid.WATER ? 9.0 : 3.0;
        for (Side direction : Side.values()) {
            Cell near = feet.offset(direction);
            if (lavaAt(near)) cost += 20;
            Cell nearHead = feet.up().offset(direction);
            if (direction.horizontal() && lavaAt(nearHead)) cost += 20;
        }
        if (lavaAt(feet.down(2))) cost += 10;
        // Through a portal only to go through it: a walk to it still ends there, and every other walk goes round (a
        // fixture's player, digging stone beside its lit portal, walked over the frame and on into the Nether).
        if (portal(feet)) cost += PORTAL_COST;
        return cost;
    }

    /** A portal's own block at the feet or the head: a step there takes the player to another dimension. */
    public boolean portal(Cell feet) {
        return loaded(feet) && (terrain.portal(feet.x(), feet.y(), feet.z())
                || loaded(feet.up()) && terrain.portal(feet.x(), feet.y() + 1, feet.z()));
    }

    /** Whether the spot is in a loaded part of the world, inside its border. */
    public boolean loaded(Cell pos) {
        return terrain.loaded(pos.x(), pos.y(), pos.z());
    }
}
