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

    /**
     * Open blocks a line needs over each rail: two for a villager riding in the cart (its eyes 1.87 over the rail's
     * block); three for one sitting in a boat the cart carries (2.06 over it: the boat rides on the cart's seat, the
     * villager on the boat's), as a villager that strolls is held and then brought. Where the line climbs or falls a
     * block, the column the slope is in needs one more: the rider is a block higher at the slope's top end.
     */
    public static final int CART_ROOM = 2, BOAT_ROOM = 3;

    /**
     * Rails behind the villager for the cart's run-up: two for a villager on its feet; three for one in a boat, whose
     * box reaches the further back (the cart, pushed from rest, takes up only what it reaches rolling).
     */
    static int runUp(int room) {
        return room > CART_ROOM ? 3 : 2;
    }

    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** A column the search has come into, and the way it came. */
    private record State(int x, int z, int heading) { }

    /** How the search came to a state: from which, and the line's length, bridge blocks and turns so far. */
    private record Seen(State from, int length, int bridges, int turns) {
        int cost() {
            return length + bridges;
        }
    }

    private record Queued(State state, int cost, int turns) { }

    /**
     * The hall's own part of the line into {@code cell}, in the hall's frame: the doorway (its door taken off for
     * it), the corridor's south row to the cell's column, and north through the cell's front onto its floor.
     */
    public static List<Pos> inside(int cells, TradingHall.Cell cell) {
        return insideRails(cells, cell);
    }

    /**
     * The hall's blocks a villager riding in a boat in the cart would put its eyes into on the way in, in the hall's
     * frame: the wall over the doorway and the lintel over the cell's front (each a third block over the rail). Taken
     * out for the ride and put back after.
     */
    public static List<Pos> overhead(int cells, TradingHall.Cell cell) {
        int door = TradingHall.width(cells) / 2;
        return List.of(new Pos(door, 3, TradingHall.FRONT_WALL), new Pos(cell.stand().x(), 3, TradingHall.FRONT));
    }

    private static List<Pos> insideRails(int cells, TradingHall.Cell cell) {
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
        return plan(ground, villagerX, villagerZ, origin, cells, cell, CART_ROOM);
    }

    /** As {@link #plan(BaseSite.Ground, int, int, Pos, int, TradingHall.Cell)}, with {@code room} open blocks over each rail. */
    public static Optional<Route> plan(BaseSite.Ground ground, int villagerX, int villagerZ, Pos origin, int cells, TradingHall.Cell cell, int room) {
        if (room < CART_ROOM) throw new IllegalArgumentException("A line needs at least " + CART_ROOM + " open blocks over it");
        var in = approach(cells);
        int goalX = origin.x() + in.x(), goalZ = origin.z() + in.z();
        // The goal is reached heading north, straight in at the door: from the column south of it.
        var ground_ = new HashMap<Long, Optional<BaseSite.Column>>();
        java.util.function.BiFunction<Integer, Integer, Optional<BaseSite.Column>> at = (x, z) ->
                ground_.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> ground.at(x, z));
        var goalColumn = at.apply(goalX, goalZ);
        // Its rail climbs into the doorway: the slope's room is the column's.
        if (goalColumn.isEmpty() || !railable(goalColumn.get(), room + 1) || goalColumn.get().top() != origin.y() - 1) return Optional.empty();
        var start = at.apply(villagerX, villagerZ);
        if (start.isEmpty() || !railable(start.get(), room)) return Optional.empty();
        int villagerTop = start.get().top(), run = runUp(room);
        // A search over columns and the way the line came into them: cheapest first (a rail a column, a bridge's block
        // one more), fewest turns among the cheapest. It sets out from the villager only the ways that leave room behind
        // it, on level ground, for the cart's run-up and the push.
        var seen = new HashMap<State, Seen>();
        var queue = new PriorityQueue<Queued>(Comparator.comparingInt(Queued::cost).thenComparingInt(Queued::turns));
        for (int h = 0; h < 4; h++) {
            boolean room_ = true;
            for (int back = 1; back <= run && room_; back++) {
                var behind = at.apply(villagerX - SIDES[h][0] * back, villagerZ - SIDES[h][1] * back);
                room_ = behind.isPresent() && railable(behind.get(), room) && behind.get().top() == villagerTop;
            }
            var push = at.apply(villagerX - SIDES[h][0] * (run + 1), villagerZ - SIDES[h][1] * (run + 1));
            if (!room_ || push.isEmpty() || !push.get().firm() || push.get().open() < 2 || Math.abs(push.get().top() - villagerTop) > 1) continue;
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
            var column = at.apply(s.x(), s.z()).orElseThrow();
            int top = column.top();
            for (int h = 0; h < 4; h++) {
                if (here.from() != null && h == (s.heading() + 2) % 4) continue;
                // Out from the villager the way it set out, its run-up behind it.
                if (here.from() == null && h != s.heading()) continue;
                int nx = s.x() + SIDES[h][0], nz = s.z() + SIDES[h][1];
                var next = at.apply(nx, nz);
                if (next.isEmpty() || !(railable(next.get(), room) || bridgeable(next.get(), room)) || Math.abs(next.get().top() - top) > 1) continue;
                // A step's slope lies in its lower column, which needs the room the rider's eyes reach at its top end.
                if (next.get().top() > top && column.open() < room + 1 || next.get().top() < top && next.get().open() < room + 1) continue;
                boolean bridge = !railable(next.get(), room);
                if (bridge && here.bridges() >= MAX_BRIDGE) continue;
                boolean turn = here.from() != null && h != s.heading();
                // No turn where the line climbs or falls into or out of the column.
                if (turn && here.from() != null && at.apply(here.from().x(), here.from().z()).orElseThrow().top() != top) continue;
                if (next.get().top() != top && turn) continue;
                // Never onto or beside its own line out of order, nor its run-up, nor where the push starts: a rail takes
                // its shape from the rails beside it, and one joined so would turn the cart off the way it was laid.
                if (!clear(seen, s, nx, nz, villagerX, villagerZ, run)) continue;
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
        // The run-up's rails behind the villager, against the line's first step, on level ground: the cart's place first.
        int first = columns.getFirst().heading();
        int bx = -SIDES[first][0], bz = -SIDES[first][1];
        var rails = new ArrayList<Pos>();
        for (int back = run; back >= 1; back--) rails.add(new Pos(villagerX + bx * back, villagerTop + 1, villagerZ + bz * back));
        var bridge = new ArrayList<Pos>();
        for (var s : columns) {
            var column = at.apply(s.x(), s.z()).orElseThrow();
            if (!railable(column, room)) bridge.add(new Pos(s.x(), column.top(), s.z()));
            rails.add(new Pos(s.x(), column.top() + 1, s.z()));
        }
        // Where the push starts: behind the cart, on the ground the run-up is on.
        var push = at.apply(villagerX + bx * (run + 1), villagerZ + bz * (run + 1)).orElseThrow();
        for (var p : inside(cells, cell)) rails.add(new Pos(origin.x() + p.x(), origin.y() + p.y(), origin.z() + p.z()));
        return Optional.of(new Route(rails, rails.getFirst(), new Pos(villagerX, villagerTop + 1, villagerZ),
                new Pos(villagerX + bx * (run + 1), push.top() + 1, villagerZ + bz * (run + 1)), bridge));
    }

    /**
     * Whether the line can go on from {@code s} into the column at {@code x}, {@code z}: not onto or beside a column it
     * came through before {@code s} (the villager's among them), nor onto or beside the run-up's rails behind the
     * villager, nor onto the spot the push starts from. A cheapest line round something in its way could otherwise come
     * back through its own run-up (suite run 49: the cart's rail, joined to the line's return beside it, turned the cart
     * off along it, away from the villager).
     */
    private static boolean clear(Map<State, Seen> seen, State s, int x, int z, int villagerX, int villagerZ, int run) {
        State root = s;
        for (State p = seen.get(s).from(); p != null; p = seen.get(p).from()) {
            if (Math.abs(p.x() - x) + Math.abs(p.z() - z) <= 1) return false;
            root = p;
        }
        if (seen.get(s).from() == null) root = s;
        int bx = -SIDES[root.heading()][0], bz = -SIDES[root.heading()][1];
        for (int back = 1; back <= run; back++) {
            if (Math.abs(villagerX + bx * back - x) + Math.abs(villagerZ + bz * back - z) <= 1) return false;
        }
        return !(x == villagerX + bx * (run + 1) && z == villagerZ + bz * (run + 1));
    }

    /** Ground a rail can lie on: firm, with {@code room} open over it for the cart and the villager riding. */
    private static boolean railable(BaseSite.Column column, int room) {
        return column.firm() && column.open() >= room;
    }

    /** Water a rail can be laid over on a bridge block at its top, with room over it as on the ground. */
    private static boolean bridgeable(BaseSite.Column column, int room) {
        return column.water() && column.open() >= room;
    }

    /** The rails a route takes, and the iron and sticks they are made of (six iron and a stick make sixteen). */
    public static Map<String, Integer> bill(Route route) {
        int rails = route.rails().size();
        int batches = (rails + 15) / 16;
        return Map.of("minecraft:rail", rails, "minecraft:iron_ingot", batches * 6, "minecraft:stick", batches);
    }
}
