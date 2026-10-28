package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * A rail line from a villager to its cell in a trading hall (E-45e), as a player lays one to bring a villager home by
 * minecart: over the ground the player knows, one rail a column on firm ground with room over it, the line stepping up
 * or down a block at most from one column to the next and never on a turn (a rail cannot curve on a slope); two rails
 * behind the villager for the cart and the push that sends it rolling; and at its end straight in at the hall's door,
 * along its corridor and through the cell's front onto the cell's floor, where the cart stops against the bed. Over
 * water it is laid on a bridge, a plain block put down at the water's top in each column it crosses, as players lay a
 * line across a river. The hall's part is fixed by its plan; the ground's is found by a search for the cheapest line,
 * a rail a column and a bridge's block one more, with fewest turns among the cheapest. Pure.
 */
public final class RailRoute {
    private RailRoute() { }

    /**
     * The rails from the cart's place to the cell's floor, in order, where the cart goes, where the push starts, and the
     * blocks of the bridge it is laid on over water, in the line's order.
     */
    public record Route(List<Pos> rails, Pos cart, Pos villager, Pos push, List<Pos> bridge) {
        public Route {
            rails = List.copyOf(rails);
            bridge = List.copyOf(bridge);
        }
    }

    /** How long a line it lays over the ground, at most: a stack of rails and a half, from six iron and a stick a sixteen. */
    public static final int MAX_LENGTH = 96;
    /** How many columns of water a line crosses on its bridge, at most: half a stack of plain blocks. */
    public static final int MAX_BRIDGE = 32;

    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * The hall's own part of the line into {@code cell}, in the hall's frame: the doorway (its door taken off for
     * it), the corridor's south row to the cell's column, and north through the cell's front onto its floor.
     */
    public static List<Pos> inside(int cells, TradingHall.Cell cell) {
        int door = TradingHall.width(cells) / 2, x = cell.stand().x();
        var rails = new ArrayList<Pos>();
        rails.add(new Pos(door, 1, TradingHall.FRONT_WALL));
        rails.add(new Pos(door, 1, TradingHall.FRONT_WALL - 1));
        int step = Integer.signum(x - door);
        for (int at = door + step; step != 0 && at != x + step; at += step) rails.add(new Pos(at, 1, TradingHall.FRONT_WALL - 1));
        for (int z = TradingHall.FRONT_WALL - 2; z >= cell.stand().z(); z--) rails.add(new Pos(x, 1, z));
        return List.copyOf(rails);
    }

    /** The column outside the hall's door, in the hall's frame, where the line from the ground comes in. */
    public static Pos approach(int cells) {
        return new Pos(TradingHall.width(cells) / 2, 0, TradingHall.FRONT_WALL + 1);
    }

    /**
     * The line from a villager standing on the column at {@code villagerX}, {@code villagerZ} to the hall of {@code
     * cells} at {@code origin}, into {@code cell}, over {@code ground}; empty when there is none within {@link
     * #MAX_LENGTH}. The ground outside the hall's door must be level with its floor's underside (the hall's floor is
     * laid on top of it, BaseSite): the line climbs one block into the doorway. A column of water with room over it is
     * crossed on a bridge block at its top; the villager, the cart's place and the push stand on firm ground.
     */
    public static Optional<Route> plan(BaseSite.Ground ground, int villagerX, int villagerZ, Pos origin, int cells, TradingHall.Cell cell) {
        var in = approach(cells);
        int goalX = origin.x() + in.x(), goalZ = origin.z() + in.z();
        // The goal is reached heading north, straight in at the door: from the column south of it.
        var ground_ = new HashMap<Long, Optional<BaseSite.Column>>();
        java.util.function.BiFunction<Integer, Integer, Optional<BaseSite.Column>> at = (x, z) ->
                ground_.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> ground.at(x, z));
        var goalColumn = at.apply(goalX, goalZ);
        if (goalColumn.isEmpty() || !railable(goalColumn.get()) || goalColumn.get().top() != origin.y() - 1) return Optional.empty();
        var start = at.apply(villagerX, villagerZ);
        if (start.isEmpty() || !railable(start.get())) return Optional.empty();
        // A search over columns and the way the line came into them: cheapest first (a rail a column, a bridge's block
        // one more), fewest turns among the cheapest.
        record State(int x, int z, int heading) { }
        record Seen(State from, int length, int bridges, int turns) {
            int cost() {
                return length + bridges;
            }
        }
        record Queued(State state, int cost, int turns) { }
        var seen = new HashMap<State, Seen>();
        var queue = new PriorityQueue<Queued>(Comparator.comparingInt(Queued::cost).thenComparingInt(Queued::turns));
        for (int h = 0; h < 4; h++) {
            var s = new State(villagerX, villagerZ, h);
            seen.put(s, new Seen(null, 0, 0, 0));
            queue.add(new Queued(s, 0, 0));
        }
        var done = new java.util.HashSet<State>();
        State end = null;
        while (!queue.isEmpty()) {
            var s = queue.poll().state();
            if (!done.add(s)) continue;
            var here = seen.get(s);
            if (s.x() == goalX && s.z() == goalZ && s.heading() == 0) {
                end = s;
                break;
            }
            if (here.length() >= MAX_LENGTH) continue;
            int top = at.apply(s.x(), s.z()).orElseThrow().top();
            for (int h = 0; h < 4; h++) {
                if (here.from() != null && h == (s.heading() + 2) % 4) continue;
                int nx = s.x() + SIDES[h][0], nz = s.z() + SIDES[h][1];
                var next = at.apply(nx, nz);
                if (next.isEmpty() || !(railable(next.get()) || bridgeable(next.get())) || Math.abs(next.get().top() - top) > 1) continue;
                boolean bridge = !railable(next.get());
                if (bridge && here.bridges() >= MAX_BRIDGE) continue;
                boolean turn = here.from() != null && h != s.heading();
                // No turn where the line climbs or falls into or out of the column.
                if (turn && here.from() != null && at.apply(here.from().x(), here.from().z()).orElseThrow().top() != top) continue;
                if (next.get().top() != top && turn) continue;
                var n = new State(nx, nz, h);
                if (done.contains(n)) continue;
                var was = seen.get(n);
                var now = new Seen(s, here.length() + 1, here.bridges() + (bridge ? 1 : 0), here.turns() + (turn ? 1 : 0));
                if (was != null && (was.cost() < now.cost() || was.cost() == now.cost() && was.turns() <= now.turns())) continue;
                seen.put(n, now);
                queue.add(new Queued(n, now.cost(), now.turns()));
            }
        }
        if (end == null) return Optional.empty();
        var columns = new ArrayList<State>();
        for (State s = end; s != null; s = seen.get(s).from()) columns.add(0, s);
        // Two rails behind the villager, against the line's first step, on level ground: the cart's and its run-up's.
        int first = columns.size() > 1 ? columns.get(1).heading() : 0;
        int bx = -SIDES[first][0], bz = -SIDES[first][1];
        int villagerTop = start.get().top();
        var rails = new ArrayList<Pos>();
        for (int back = 2; back >= 1; back--) {
            var behind = at.apply(villagerX + bx * back, villagerZ + bz * back);
            if (behind.isEmpty() || !railable(behind.get()) || behind.get().top() != villagerTop) return Optional.empty();
            rails.add(new Pos(villagerX + bx * back, villagerTop + 1, villagerZ + bz * back));
        }
        var bridge = new ArrayList<Pos>();
        for (var s : columns) {
            var column = at.apply(s.x(), s.z()).orElseThrow();
            if (!railable(column)) bridge.add(new Pos(s.x(), column.top(), s.z()));
            rails.add(new Pos(s.x(), column.top() + 1, s.z()));
        }
        // Where the push starts: behind the cart, on the ground the run-up is on.
        var push = at.apply(villagerX + bx * 3, villagerZ + bz * 3);
        if (push.isEmpty() || !push.get().firm() || push.get().open() < 2 || Math.abs(push.get().top() - villagerTop) > 1) return Optional.empty();
        for (var p : inside(cells, cell)) rails.add(new Pos(origin.x() + p.x(), origin.y() + p.y(), origin.z() + p.z()));
        return Optional.of(new Route(rails, rails.getFirst(), new Pos(villagerX, villagerTop + 1, villagerZ),
                new Pos(villagerX + bx * 3, push.get().top() + 1, villagerZ + bz * 3), bridge));
    }

    /** Ground a rail can lie on: firm, with room over it for the cart and the villager in it. */
    private static boolean railable(BaseSite.Column column) {
        return column.firm() && column.open() >= 2;
    }

    /** Water a rail can be laid over on a bridge block at its top, with room over it as on the ground. */
    private static boolean bridgeable(BaseSite.Column column) {
        return column.water() && column.open() >= 2;
    }

    /** The rails a route takes, and the iron and sticks they are made of (six iron and a stick make sixteen). */
    public static Map<String, Integer> bill(Route route) {
        int rails = route.rails().size();
        int batches = (rails + 15) / 16;
        return Map.of("minecraft:rail", rails, "minecraft:iron_ingot", batches * 6, "minecraft:stick", batches);
    }
}
