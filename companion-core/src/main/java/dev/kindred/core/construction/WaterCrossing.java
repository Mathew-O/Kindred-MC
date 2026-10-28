package dev.kindred.core.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * A villager brought home across water no line can be laid over (E-45e), as players bring one by boat: held in a boat
 * on the ground beside it, the boat rowed a few blocks over level ground into the water, across in a straight line to
 * the far bank, and left there on the water with the villager in it: a line laid along the bank past it ({@link
 * RailRoute}, from the bank's column beside the boat) brings the cart that takes the boat up from the water as it rolls
 * by, and carries it home (let go of, the villager would wade or swim back to its work across the water). A crossing is
 * found by looking out from the villager along straight lines: over at most {@link #MAX_LAND} columns of level ground,
 * then water all the way to a bank of firm ground no higher than a block over the water (a boat goes aground against
 * it, and its riders step out onto it). Of the banks found, those nearest the hall are tried first for a line home, by
 * the room a villager in a boat needs. Pure.
 */
public final class WaterCrossing {
    private WaterCrossing() { }

    /**
     * Where the boat comes to the far bank: the water's column it is rowed to ({@code water}), the bank's column beside it
     * ({@code landing}) that the line home runs past it from, how far it is rowed, and that line.
     */
    public record Crossing(Pos water, Pos landing, double row, RailRoute.Route home) { }

    /** Columns of level ground it rows the boat over from the villager to the water, at most. */
    public static final int MAX_LAND = 4;
    /** How far it rows, at most: across a lake, not a sea. */
    public static final int MAX_ROW = 64;
    /** How many of the banks found it tries for a line home, nearest the hall first. */
    static final int TRIED = 8;
    /** Directions looked along, round the villager. */
    static final int RAYS = 72;

    /**
     * A crossing from a villager standing on the column at {@code villagerX}, {@code villagerZ} to a bank with a line
     * home to the hall of {@code cells} at {@code origin}, into {@code cell}; empty when there is none.
     */
    public static Optional<Crossing> plan(BaseSite.Ground ground, int villagerX, int villagerZ, Pos origin, int cells, TradingHall.Cell cell) {
        var cache = new HashMap<Long, Optional<BaseSite.Column>>();
        java.util.function.BiFunction<Integer, Integer, Optional<BaseSite.Column>> at = (x, z) ->
                cache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> ground.at(x, z));
        var start = at.apply(villagerX, villagerZ);
        if (start.isEmpty() || !start.get().firm() || start.get().water()) return Optional.empty();
        int top = start.get().top();
        // The banks found, each with the water beside it and the row to it.
        record Bank(Pos water, Pos landing, double row) { }
        var banks = new LinkedHashMap<Long, Bank>();
        double cx = villagerX + 0.5, cz = villagerZ + 0.5;
        for (int r = 0; r < RAYS; r++) {
            double angle = 2 * Math.PI * r / RAYS, dx = Math.sin(angle), dz = Math.cos(angle);
            int lastX = villagerX, lastZ = villagerZ, land = 0, waterTop = Integer.MIN_VALUE;
            Pos water = null;
            for (double d = 0.5; d <= MAX_ROW; d += 0.5) {
                int x = (int) Math.floor(cx + dx * d), z = (int) Math.floor(cz + dz * d);
                if (x == lastX && z == lastZ) continue;
                lastX = x;
                lastZ = z;
                var column = at.apply(x, z);
                if (column.isEmpty()) break;
                var c = column.get();
                if (water == null) {
                    // Over level ground to the water's edge, a few columns at most, with room for the boat and its riders.
                    if (c.water() && c.open() >= 2 && Math.abs(c.top() - top) <= 1) {
                        water = new Pos(x, c.top(), z);
                        waterTop = c.top();
                        continue;
                    }
                    if (!c.firm() || c.water() || c.top() != top || c.open() < 2 || ++land > MAX_LAND) break;
                    continue;
                }
                if (c.water()) {
                    if (c.top() != waterTop || c.open() < 2) break;
                    water = new Pos(x, c.top(), z);
                    continue;
                }
                // The far bank: firm ground no higher than a block over the water's top, room on it to step out onto.
                if (c.firm() && c.top() >= waterTop && c.top() <= waterTop + 1 && c.open() >= 2) {
                    banks.putIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), new Bank(water, new Pos(x, c.top(), z), Math.hypot(x + 0.5 - cx, z + 0.5 - cz)));
                }
                break;
            }
        }
        if (banks.isEmpty()) return Optional.empty();
        // The banks nearest the hall's door tried first; of those with a line home, the one least rowed and laid.
        var door = RailRoute.approach(cells);
        double doorX = origin.x() + door.x() + 0.5, doorZ = origin.z() + door.z() + 0.5;
        var order = new ArrayList<>(banks.values());
        order.sort(Comparator.comparingDouble(b -> Math.hypot(b.landing().x() + 0.5 - doorX, b.landing().z() + 0.5 - doorZ)));
        Crossing best = null;
        for (var bank : order.subList(0, Math.min(TRIED, order.size()))) {
            var home = RailRoute.plan(ground, bank.landing().x(), bank.landing().z(), origin, cells, cell, RailRoute.BOAT_ROOM);
            if (home.isEmpty() || !home.get().bridge().isEmpty()) continue;
            var crossing = new Crossing(bank.water(), bank.landing(), bank.row(), home.get());
            if (best == null || cost(crossing) < cost(best)) best = crossing;
        }
        return Optional.ofNullable(best);
    }

    /** A crossing's cost: rowing a block about as dear as laying a rail. */
    private static double cost(Crossing crossing) {
        return crossing.row() + crossing.home().rails().size();
    }
}
