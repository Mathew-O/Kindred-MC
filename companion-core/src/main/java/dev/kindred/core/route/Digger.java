package dev.kindred.core.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * In plain terms: how a player gets up, down and along through rock on its own, as a person with a pickaxe does: a
 * staircase dug up one step at a time, a staircase dug down, a two-high tunnel, and the ways out from under sand,
 * gravel or water over its head. It never digs where water or lava would pour in, nor steps into a drop too deep.
 *
 * Technical: The campaign's own dig skills' decisions (F-35e, ported unchanged from {@code PortalRunCampaign}'s
 * {@code climb}, {@code digDown}, {@code descendOrTunnel}, {@code tunnel} and their helpers). Decision only: each call
 * looks at the world through {@link DigTerrain} and at the player through {@link Body}, and returns the one next step,
 * made by the body's own step makers, so the server adapter's steps are its recorded tasks and a simulator's are its
 * own. It keeps the state those skills keep between calls: the heading it digs along, the floor and turns of a tunnel,
 * the cells it has dug and tunnelled, and where it last stepped aside, waited for sand to settle or found water over it.
 * Not thread-safe: one player's decisions use it, on one thread.
 */
public final class Digger {
    /**
     * The player digging, as it stands now, and the steps it can take: answered by whoever holds the body. The facts
     * are the campaign's own tests, each answered as the campaign answers it (reach, tools, edit policy, inventory).
     */
    public interface Body<T> {
        DigTerrain world();
        /** The cell its feet are in. */
        Cell feet();
        /** The game tick now. */
        long now();
        boolean onGround();
        /** In deep water: under it, or afloat on it with water under its feet. */
        boolean swimming();
        /** A floor at the bottom of the world under it: not over the void. */
        boolean groundBelow();
        /** The cell set aside for now (a cell given up on, or cover held from digs). */
        boolean blocked(Cell cell);
        /** The cell set aside until {@code until} at least. */
        void setAside(Cell cell, long until);
        /** A block it could break right now from where it stands. */
        boolean breakableNow(Cell cell);
        /** Rock a player with no pickaxe at all can still punch through. */
        boolean handClearable(Cell cell);
        /** A block with nothing to collide with that a walk will not go through (a cobweb), cleared by hand. */
        boolean snareClearable(Cell cell);
        /** A block it may open from where it stands to show the lava under it: in reach, allowed, and one its tools break. */
        boolean opensOverLava(Cell cell);
        /** A block its tools can harvest. */
        boolean harvestable(Cell cell);
        /** Over the void, a cell of the island's edge, which is never dug. */
        boolean islandEdge(Cell cell);
        /** Near a place where mobs killed or cornered it, at or below this level. */
        boolean dangerBelow(Cell cell);
        /** The slot of a block to make a way with, or -1 for none; nine and over is off the hotbar. */
        int wayBlockSlot();

        /** A walk to within {@code range} of {@code to}; {@code partial}, as far as the way goes. */
        T go(Cell to, double range, boolean partial, String label);
        /** The block at {@code cell} broken, its drop left where it falls. */
        T clear(Cell cell, String label);
        /** The block at {@code cell} broken from where it stands, over the lava under it. */
        T openOverLava(Cell cell, String label);
        /** A moment's wait while what falls comes to rest. */
        T settle(String label);
        /** A block from {@code slot} put under its feet at {@code feet} at the top of a jump. */
        T pillar(Cell feet, int slot, String label);
        /** The block in {@code slot} brought onto the hotbar. */
        T swap(int slot, String label);
        /** One of its own blocks put into {@code cell}, or nothing when it cannot place one there. */
        Optional<T> fill(Cell cell, String label);
        /** A line for its log of what the dig found and why it stopped; nothing by default. */
        default void event(String line) {}
    }

    /** Branch mining: legs this long, side steps this wide (two solid blocks between legs, each wall seen). */
    public static final int BRANCH_LEG = 20, BRANCH_STEP = 3;
    /**
     * Branch mining for lava: the legs run long and far apart, as lakes are wide: dense legs find a small vein, not a
     * lake, and in the world saves of the live pair on a4df9b4 these found lava within 800 blocks mined five times as
     * often as the diamond legs at Y=-55 (F-25c4ac).
     */
    public static final int LAVA_BRANCH_LEG = 48, LAVA_BRANCH_STEP = 16;
    /** How far along under water over its head a climb goes, with no dry ground over it, before handing back (F-35g). */
    public static final int UNDER_WATER_REACH = BRANCH_LEG;

    /** The deepest drop a step down lands after: the navigator's safe drop. */
    private static final int MAX_SAFE_DROP = Navigator.MAX_SAFE_DROP;

    private Side heading = Side.NORTH;
    private int tunnelFloor = Integer.MIN_VALUE, tunnelTurns = 4;
    /** Whether a tunnel bridges any drop ahead with a block of its own, as one toward lava at its level does (F-25c4ak). */
    private boolean bridgeDrops;
    /** Whether the tunnel being dug may be dug by hand, with no pickaxe held (F-25c4bu). */
    private boolean tunnelByHand;
    /** Whether the step down being dug may be dug by hand, with no pickaxe held: out from under the water (F-25c4cq). */
    private boolean digDownByHand;
    /** Where the climb last stepped aside from and to, and when (F-25c4cq). */
    private Cell asideFrom, asideTo;
    private long asideTick;
    /** Where a climb last found water over its ceiling every way up, when, and the way it tunnels out (F-25c4ah). */
    private Cell underWaterAt;
    private long underWaterTick;
    private Side underWaterHeading;
    /** Where its way along under the water began, and where and when it last found the water too wide (F-35g). */
    private Cell underWaterFrom, underWaterGaveUpAt;
    private long underWaterGaveUpTick = Long.MIN_VALUE / 2;
    /** Whether the last staircase step down it chose was the step itself, not a block cleared for it. */
    private boolean steppedDown;
    private Cell settleAt;
    private long settleSince;
    /** Cells it cleared on its way; a pit of its own is filled, not turned from. */
    private final Set<Long> dug = new HashSet<>();
    /** Cells its tunnels advanced into. */
    private final Set<Long> tunnelled = new HashSet<>();
    /** Pillars beside the water refused where it stood, and until when they count (F-25c4bf). */
    private final Map<Long, Integer> pillarMisses = new HashMap<>();
    private final Map<Long, Long> pillarMissesUntil = new HashMap<>();
    private int branchRun;
    private boolean branchSide, branchStepping;

    // ------------------------------------------------------------------ state the campaign shares

    /** The heading it digs along. */
    public Side heading() { return heading; }

    public void heading(Side side) { heading = side; }

    /** The lowest level a tunnel steps down to; {@link Integer#MIN_VALUE} for none. */
    public int tunnelFloor() { return tunnelFloor; }

    public void tunnelFloor(int floor) { tunnelFloor = floor; }

    /** How many headings a tunnel tries before it gives up: four, or one for a straight one. */
    public int tunnelTurns() { return tunnelTurns; }

    public void tunnelTurns(int turns) { tunnelTurns = turns; }

    public void bridgeDrops(boolean bridge) { bridgeDrops = bridge; }

    /** Where a climb last found water over its ceiling every way up, or null; and when. */
    public Cell underWaterAt() { return underWaterAt; }

    public long underWaterTick() { return underWaterTick; }

    /** A cell it cleared: counted as dug. */
    public void dug(Cell cell) {
        if (dug.size() > 20_000) dug.clear();
        dug.add(cell.asLong());
    }

    /** A cell its tunnel advanced into. */
    public void tunnelled(Cell cell) {
        if (tunnelled.size() > 20_000) tunnelled.clear();
        tunnelled.add(cell.asLong());
    }

    /** The cells its tunnels advanced into, forgotten: a dead end paced once more is fine after a move elsewhere. */
    public void forgetTunnelled() { tunnelled.clear(); }

    /**
     * A branch mine's leg a block on: after a leg of {@link #BRANCH_LEG} blocks, a quarter turn to step {@link #BRANCH_STEP}
     * to one side, and another to run the next leg back the other way, alternating sides so the legs zigzag across fresh
     * stone. For lava the legs are {@link #LAVA_BRANCH_LEG} long and {@link #LAVA_BRANCH_STEP} apart.
     */
    public void branchAdvanced(boolean lava) {
        if (++branchRun < (branchStepping ? (lava ? LAVA_BRANCH_STEP : BRANCH_STEP) : (lava ? LAVA_BRANCH_LEG : BRANCH_LEG))) return;
        branchRun = 0;
        heading = branchSide ? heading.counterclockwise() : heading.clockwise();
        if (branchStepping) branchSide = !branchSide;
        branchStepping = !branchStepping;
    }

    /** A pillar beside the water refused with its feet at {@code feet} at {@code tick} (F-25c4bf). */
    public void pillarMissed(Cell feet, long tick) {
        long cell = feet.asLong();
        if (pillarMissesUntil.getOrDefault(cell, Long.MIN_VALUE) < tick) pillarMisses.remove(cell);
        pillarMisses.merge(cell, 1, Integer::sum);
        pillarMissesUntil.put(cell, tick + 1200);
    }

    // ------------------------------------------------------------------ the cell facts it reads

    /** No collision box and no lava: a body moves into it. */
    public static boolean passable(DigTerrain world, Cell pos) {
        return world.empty(pos.x(), pos.y(), pos.z()) && fluid(world, pos) != Terrain.Fluid.LAVA;
    }

    private static Terrain.Fluid fluid(DigTerrain world, Cell pos) {
        return world.fluid(pos.x(), pos.y(), pos.z());
    }

    private static boolean falls(DigTerrain world, Cell pos) {
        return world.falls(pos.x(), pos.y(), pos.z());
    }

    /** Falling blocks in flight in the cell's column, from it to {@code above} cells over it. */
    private static boolean fallingOver(DigTerrain world, Cell cell, int above, int wide) {
        return world.falling(cell.x() - wide, cell.y(), cell.z() - wide, cell.x() + 1 + wide, cell.y() + 1 + above, cell.z() + 1 + wide);
    }

    /** The game's short form of a position: "x, y, z". */
    public static String at(Cell cell) {
        return cell.x() + ", " + cell.y() + ", " + cell.z();
    }

    /** Lava directly under {@code pos}, and no other liquid round it or in it: opened, nothing pours in (F-25c4ac). */
    private static boolean onlyLavaBelow(DigTerrain world, Cell pos) {
        if (fluid(world, pos.down()) != Terrain.Fluid.LAVA || fluid(world, pos) != Terrain.Fluid.NONE) return false;
        for (Side direction : Side.values()) {
            if (direction != Side.DOWN && fluid(world, pos.offset(direction)) != Terrain.Fluid.NONE) return false;
        }
        return true;
    }

    /**
     * Lava on any side, or water above or beside, which would pour into the opening; or a liquid held up by sand or
     * gravel resting on it, which comes down after the column once the block under it is dug (F-25c4bg).
     */
    public static boolean liquidNear(DigTerrain world, Cell pos) {
        for (Side direction : Side.values()) {
            var fluid = fluid(world, pos.offset(direction));
            if (fluid == Terrain.Fluid.LAVA || direction != Side.DOWN && fluid == Terrain.Fluid.WATER) return true;
        }
        return fluid(world, pos) == Terrain.Fluid.LAVA || liquidOverColumn(world, pos);
    }

    /**
     * Sand or gravel resting on the block, up to 16 high, with a liquid over the column or beside any of it: a lake's bed.
     * In E-40's iteration 4 (`e40a4-c237a6e`) climbs dug out the block under such a column, the sand came down and the
     * lake after it: Sol on 101 and 404, Kindred on 202, 404 and 606, 59 stuck spells in the water (F-25c4bg).
     */
    private static boolean liquidOverColumn(DigTerrain world, Cell pos) {
        Cell above = pos.up();
        for (int i = 0; i < 16 && falls(world, above); i++, above = above.up()) {
            for (Side direction : Side.values()) {
                if (direction != Side.DOWN && fluid(world, above.offset(direction)) != Terrain.Fluid.NONE) return true;
            }
        }
        return false;
    }

    /** The first cell over it that is not sand or gravel resting on it: itself over a column, else the cell over it. */
    private static Cell columnTop(DigTerrain world, Cell pos) {
        Cell above = pos.up();
        for (int i = 0; i < 16 && falls(world, above); i++) above = above.up();
        return above;
    }

    // ------------------------------------------------------------------ down

    /**
     * Toward {@code target} height for {@code what}: over the void nothing; above {@code top} (or within three of the
     * target), a staircase down, or with water or lava under every way down, a tunnel on until the ground below is solid;
     * at the richest depth with no ore or cave in view, a branch mine, legs zigzagging through fresh stone.
     */
    public <T> Optional<T> descendOrTunnel(Body<T> body, int top, int target, String what) {
        // Over the void there is nothing below to reach: digging would only spend the island.
        if (!body.groundBelow()) return Optional.empty();
        int y = body.feet().y();
        // Looking for lava, a cell with lava under it is opened to see it (F-25c4ac).
        boolean lava = what.equals("lava");
        // Water or lava under every way down (an aquifer, a lava lake): tunnel on until the ground below is solid.
        if (y > Math.min(top, target + 3)) {
            return digDown(body, target, "Dig down toward " + what).or(() -> tunnel(body, "Tunnel past liquid toward " + what, lava));
        }
        // At the richest depth with no ore or cave in view: branch mine, legs zigzagging through fresh stone.
        return tunnel(body, "Branch mine for " + what, lava);
    }

    /**
     * Staircase one step down along the heading: clear head, feet and step-down cells ahead, then step.
     * Every break is still a separate recorded action with the lava and support checks.
     */
    public <T> Optional<T> digDown(Body<T> body, int targetY, String label) {
        return digDown(body, targetY, label, p -> true);
    }

    /** As {@link #digDown(Body, int, String)}, digging only cells {@code allowed} lets it. */
    public <T> Optional<T> digDown(Body<T> body, int targetY, String label, Predicate<Cell> allowed) {
        steppedDown = false;
        Cell feet = body.feet();
        if (feet.y() <= targetY) return tunnel(body, label);
        var world = body.world();
        for (int turn = 0; turn < 4; turn++) {
            Cell ahead = feet.offset(heading);
            Cell step = ahead.down();
            List<Cell> clear = List.of(ahead.up(), ahead, step);
            // Water or lava under the step: that way down ends in it, so turn before digging anything there. Nor is a
            // step dug that opens onto a drop deeper than the navigator allows, a cave's ceiling: that leaves only a
            // pit (live run 52 dug one on every side and stood stranded between them).
            // Over the void, the island's last layer stays under every step: nothing below it would catch a fall.
            boolean refused = fluid(world, step.down()) != Terrain.Fluid.NONE || !landingWithin(world, step, feet)
                    || !body.groundBelow() && passable(world, step.down())
                    // Nor down toward a place where mobs killed or cornered it: the staircase turns, or the tunnel
                    // fallback carries it off sideways to dig down elsewhere (live runs 52 and 56 dug back down into
                    // the cave under spawn they had died in).
                    || body.dangerBelow(step) || !allowed.test(step.down());
            // Nor a step it would not then take, its landing set aside or by liquid, checked as below once the step is
            // open: dug anyway, the step stood open over a drop, its tunnel filled it as a pit, and the staircase dug it
            // out again. On 505 in E-40's iteration 12 (`e40a12-66a7c0a`) Kindred did so hundreds of times, then waited
            // "for a feasible step" through 29 stuck spells (F-25c4ch).
            if (!refused) {
                Cell landing = step;
                while (passable(world, landing.down()) && landing.y() > feet.y() - MAX_SAFE_DROP) landing = landing.down();
                if (liquidNear(world, landing) || body.blocked(landing)) refused = true;
            }
            for (Cell cell : clear) {
                if (!allowed.test(cell)) refused = true;
                if (refused) break;
                if (passable(world, cell)) continue;
                if (body.blocked(cell) || liquidNear(world, cell) || body.islandEdge(cell)) { refused = true; break; }
                if (body.breakableNow(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell)));
                // A night shelter is dug by hand when there is no pickaxe: slow in rock, but better than waiting in the
                // open (live run 59 died four times at night on the surface just after respawning with nothing).
                if ((label.startsWith("Dig in for the night") || digDownByHand) && body.handClearable(cell)) {
                    return Optional.of(body.clear(cell, label + ": clear " + at(cell) + " by hand"));
                }
                refused = true;
                break;
            }
            if (!refused) {
                // Never step into a drop deeper than the navigator allows; turn away from a pit instead.
                Cell landing = step;
                while (passable(world, landing.down()) && landing.y() > feet.y() - MAX_SAFE_DROP) landing = landing.down();
                if (!passable(world, landing.down()) && !liquidNear(world, landing) && !body.blocked(landing)) {
                    steppedDown = true;
                    return Optional.of(body.go(landing, 0.4, false, label + ": step down"));
                }
            }
            heading = heading.clockwise();
        }
        return Optional.empty();
    }

    /** Solid ground under {@code step}, or under the open cells below it, no deeper than the navigator will drop. */
    private static boolean landingWithin(DigTerrain world, Cell step, Cell feet) {
        Cell landing = step;
        while (passable(world, landing.down()) && landing.y() > feet.y() - MAX_SAFE_DROP) landing = landing.down();
        return !passable(world, landing.down());
    }

    // ------------------------------------------------------------------ along

    /** A tunnel along {@code heading}, as {@link #tunnel(Body, String)} digs along its own. */
    public <T> Optional<T> tunnelAlong(Body<T> body, Side along, String label) {
        heading = along;
        return tunnel(body, label);
    }

    /**
     * Straight on along {@code along} only, never stepping down below {@code floor}: an explorer's tunnel, which
     * must not turn and wander back along ground already crossed (staged run 6 walked 13 blocks back that way).
     */
    public <T> Optional<T> tunnelStraight(Body<T> body, Side along, int floor, String label) {
        heading = along;
        tunnelFloor = floor;
        tunnelTurns = 1;
        try {
            return tunnel(body, label);
        } finally {
            tunnelFloor = Integer.MIN_VALUE;
            tunnelTurns = 4;
            heading = along;
        }
    }

    /**
     * Toward {@code lava} by digging (F-35f, ported from the campaign's {@code digToward}): a staircase up or down until
     * its feet are a block above the lava, then a tunnel along that level, which opens a cell with lava under it to show
     * the lava (F-25c4ad). The staircases turn where a cell is refused. The tunnel goes only the two ways that close on
     * the lava, and bridges a drop on the way with a block of its own: turned aside by a cave's drop at every step,
     * Kindred, in the live pair on 64c298d, tunnelled 81 blocks west at the lava's level, past the lava it saw across the
     * cave (F-25c4ak). With neither way open it digs nothing.
     */
    public <T> Optional<T> toward(Body<T> body, Cell lava, String label) {
        Cell feet = body.feet();
        int dx = lava.x() - feet.x(), dz = lava.z() - feet.z(), rise = lava.y() + 1 - feet.y();
        if (dx == 0 && dz == 0) return rise > 0 ? climb(body, label) : rise < 0 ? digDown(body, lava.y() + 1, label) : Optional.empty();
        Side across = dx > 0 ? Side.EAST : Side.WEST, along = dz > 0 ? Side.SOUTH : Side.NORTH;
        boolean xFirst = Math.abs(dx) >= Math.abs(dz);
        Side major = xFirst ? across : along;
        heading = major;
        if (rise > 0) return climb(body, label);
        if (rise < 0) return digDown(body, lava.y() + 1, label);
        List<Side> ways = (xFirst ? dz : dx) == 0 ? List.of(major) : List.of(major, xFirst ? along : across);
        int floor = tunnelFloor, turns = tunnelTurns;
        try {
            tunnelFloor = lava.y() + 1;
            tunnelTurns = 1;
            bridgeDrops = true;
            for (Side way : ways) {
                heading = way;
                Optional<T> step = tunnel(body, label, true);
                if (step.isPresent()) return step;
            }
            return Optional.empty();
        } finally {
            tunnelFloor = floor;
            tunnelTurns = turns;
            bridgeDrops = false;
            heading = major;
        }
    }

    /** A two-high tunnel forward along the heading, turning where that way is refused. */
    public <T> Optional<T> tunnel(Body<T> body, String label) {
        return tunnel(body, label, false);
    }

    /**
     * As {@link #tunnel(Body, String)}; with {@code revealLava}, the cell ahead at its feet with lava directly
     * under it and no other liquid round it is opened, from where it stands, which shows the lava, as a player's next
     * block would. The lava stays where it is: lava never rises, and the cell is never walked onto (F-25c4ac).
     */
    public <T> Optional<T> tunnel(Body<T> body, String label, boolean revealLava) {
        var world = body.world();
        Cell feet = body.feet();
        for (int turn = 0; turn < tunnelTurns; turn++) {
            Cell ahead = feet.offset(heading);
            // A drop under the way ahead, too deep to step down and not one it would bridge: that way is not dug into at
            // all. Dug, its cells were the steps a climb back would have stood on: in the ten seeds on `e9d33c0` Kindred's
            // branch mine on 505 came out on the crust over a cavern 34 deep, dug the crust out on three sides, found a drop
            // under each, and waited there for 97 minutes with no step left to climb (F-25c4bb).
            if (passable(world, ahead.down()) && passable(world, ahead.down(2)) && !bridgeDrops && !dug.contains(ahead.down().asLong())) {
                heading = heading.clockwise();
                continue;
            }
            boolean refused = false;
            for (Cell cell : List.of(ahead.up(), ahead)) {
                if (passable(world, cell)) continue;
                if (revealLava && cell.equals(ahead) && passable(world, ahead.up()) && !body.blocked(cell) && onlyLavaBelow(world, cell)
                        && body.opensOverLava(cell)) {
                    return Optional.of(body.openOverLava(cell, label + ": open " + at(cell) + " over lava"));
                }
                if (body.blocked(cell) || liquidNear(world, cell) || body.islandEdge(cell)) { refused = true; break; }
                if (body.breakableNow(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell)));
                // Its pickaxe gone, out from under the water by hand, as its climb goes up by hand (F-25c4bu).
                if (tunnelByHand && body.handClearable(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell) + " by hand"));
                refused = true;
                break;
            }
            // Walking dug ground again is fine on the way to rock it can dig; pacing a dead-end corridor is not.
            if (!refused && !passable(world, ahead.down()) && !liquidNear(world, ahead) && fluid(world, ahead) == Terrain.Fluid.NONE && !body.blocked(ahead)
                    && (!tunnelled.contains(ahead.asLong()) || diggableFaceAhead(body, ahead))) {
                if (comingDown(world, ahead)) return Optional.of(body.settle(label + ": let what falls settle"));
                return Optional.of(body.go(ahead, 0.4, false, label + ": advance"));
            }
            // Never a step down into water: wading back out to here and stepping in again would go round for ever.
            if (!refused && passable(world, ahead.down()) && !passable(world, ahead.down(2)) && !body.blocked(ahead.down()) && ahead.y() > tunnelFloor
                    && fluid(world, ahead.down()) == Terrain.Fluid.NONE && !liquidNear(world, ahead.down())) {
                if (comingDown(world, ahead.down())) return Optional.of(body.settle(label + ": let what falls settle"));
                return Optional.of(body.go(ahead.down(), 0.4, false, label + ": step down"));
            }
            // A pit it dug itself over a drop (a staircase step that broke through a cave's ceiling): fill it and go on. Toward
            // lava at its level, any drop on the way, as a player bridges a cave to the lava it sees across it (F-25c4ak).
            if (!refused && passable(world, ahead.down()) && (bridgeDrops || passable(world, ahead.down(2)) && dug.contains(ahead.down().asLong()))
                    && fluid(world, ahead.down()) == Terrain.Fluid.NONE) {
                Optional<T> fill = body.fill(ahead.down(), label + ": fill " + at(ahead.down()));
                if (fill.isPresent()) return fill;
            }
            heading = heading.clockwise();
        }
        return Optional.empty();
    }

    /**
     * Sand or gravel coming down into the cell a player would stand in, or onto its head there: falling now, or resting
     * over the open head cell, which it falls into within two ticks of being left with nothing under it. A tunnel that
     * cleared the head-level cell under such a column saw the cell open while what rested over it was still in the air,
     * walked in and was buried. In E-40's iteration 9 (`e40a9-dd9b037`) three players branch mining at Y=-54, through
     * pockets of gravel in the deepslate, suffocated so, digging out of the wall as the next block came down onto them:
     * Kindred on 20261030, Sol on 606 and Sol on 8675309; Echo on 8675309 the same at Y=19 (F-25c4bv).
     */
    private static boolean comingDown(DigTerrain world, Cell cell) {
        if (fallingOver(world, cell, 8, 0)) return true;
        Cell head = cell.up();
        return passable(world, head) && falls(world, head.up()) && world.fallsThrough(head.x(), head.y(), head.z());
    }

    /**
     * Along the heading from {@code start}, over open floored ground for up to eight blocks, the first rock
     * face is one this player could dig: not set aside, no liquid at it, and a block its tools can harvest.
     */
    private <T> boolean diggableFaceAhead(Body<T> body, Cell start) {
        var world = body.world();
        Cell at = start;
        for (int k = 0; k < 8; k++) {
            Cell next = at.offset(heading);
            boolean open = passable(world, next) && passable(world, next.up());
            if (!open) {
                for (Cell face : List.of(next, next.up())) {
                    if (passable(world, face)) continue;
                    if (body.blocked(face) || liquidNear(world, face) || !body.harvestable(face)) return false;
                }
                return true;
            }
            if (passable(world, next.down()) || liquidNear(world, next)) return false; // A drop or water on the way.
            at = next;
        }
        return false;
    }

    // ------------------------------------------------------------------ up

    /**
     * A staircase one step up along the heading: clear the head room above, then the two cells over the
     * block ahead, and step up onto it. The way out when the job needs the surface.
     */
    public <T> Optional<T> climb(Body<T> body, String label) {
        var world = body.world();
        Cell feet = body.feet();
        long now = body.now();
        // Swimming, from the water's top cell: bobbing at the surface its feet go from one cell to the next, and the step
        // planned from each was another, none dug through before the next (the flooded pit fixture, F-25c4t).
        boolean swimming = body.swimming();
        while (swimming && fluid(world, feet.up()) == Terrain.Fluid.WATER) feet = feet.up();
        // Water over the ceiling, a lake's or a river's, refusing a step up: then every way up here meets the same water.
        boolean waterOver = false;
        // The block over its head refused, for sand or gravel resting on it or a liquid beside it: then so is every way up
        // from here (F-25c4bd).
        boolean headRoomRefused = false;
        // The ways whose step up was refused for a liquid beside the landing, not for the block over its head (F-25c4bf).
        var besideWater = new ArrayList<Side>();
        // A cell with nothing in it to collide with is room to climb through only if a walk will go through it too: a
        // cobweb is not. In E-40's iteration 14 (`e40a14-80b7150`) on 8675309 Sol, in a mineshaft's corridor with a cobweb
        // over its head, had every step up refused for "no walkable route", and walked "to the wall" 1,141 times (F-25c4cm).
        var walk = new Navigator(world);
        for (int turn = 0; turn < 4; turn++) {
            Cell step = feet.offset(heading);
            Cell landing = step.up();
            // Nothing to climb onto this way, or a landing it already failed to reach (live run 49 tried one 232 times).
            boolean refused = passable(world, step) || body.blocked(landing);
            for (Cell cell : List.of(feet.up(2), landing, landing.up())) {
                if (refused || passable(world, cell) && walk.passable(cell)) continue;
                if (body.blocked(cell) || liquidNear(world, cell) || body.islandEdge(cell)) {
                    // Over the sand or gravel resting on it, if any: a lake's bed over the ceiling (F-25c4bg).
                    waterOver |= fluid(world, columnTop(world, cell)) == Terrain.Fluid.WATER;
                    headRoomRefused |= cell.equals(feet.up(2)) && !body.blocked(cell) && liquidNear(world, cell);
                    if (!cell.equals(feet.up(2)) && !body.blocked(cell) && liquidNear(world, cell)) besideWater.add(heading);
                    refused = true;
                    break;
                }
                // Not the block over its head with sand or gravel resting on it: dug out, that falls onto the player, one
                // block after another as each is dug out of its head room (Kindred suffocated twice so in B7's second pairs).
                if (cell.equals(feet.up(2)) && falls(world, cell.up())) {
                    waterOver |= fluid(world, cell.up(2)) == Terrain.Fluid.WATER;
                    headRoomRefused = true;
                    refused = true;
                    break;
                }
                if (body.breakableNow(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell)));
                // Its last pickaxe gone below ground: punch the way up, slowly, as a player would (live run 57 broke
                // its last one climbing out and stood at Y 54 for good).
                if (body.handClearable(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell) + " by hand"));
                if (body.snareClearable(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell) + " by hand"));
                refused = true;
            }
            // The block over its head once up there, sand or gravel resting on it, dug from here first: what rests on it
            // falls into the step's empty cells, not onto the player, and is dug in turn; and no step is taken while any
            // of it is still coming down, which buried it at its first try. Up there every way up was refused, that block
            // never dug from under it, and the walk over to a wall dropped it back down here: in the live pair on
            // `91c1290` Kindred, in a pocket under the desert's sandstone and sand, went between the two for minutes
            // (F-25c4ao).
            Cell over = landing.up(2);
            if (!refused && falls(world, over.up())) {
                if (!passable(world, over)) {
                    if (!body.blocked(over) && !liquidNear(world, over) && !body.islandEdge(over)) {
                        if (body.breakableNow(over)) return Optional.of(body.clear(over, label + ": clear " + at(over)));
                        if (body.handClearable(over)) return Optional.of(body.clear(over, label + ": clear " + at(over) + " by hand"));
                    }
                    waterOver |= fluid(world, columnTop(world, over)) == Terrain.Fluid.WATER;
                    refused = true;
                } else if (settling(over, now)) {
                    return Optional.of(body.settle(label + ": let what falls settle"));
                }
            }
            if (!refused && fallingOver(world, landing, 6, 0)) {
                return Optional.of(body.settle(label + ": let what falls settle"));
            }
            if (!refused) {
                underWaterHeading = null;
                underWaterFrom = null;
                return Optional.of(body.go(landing, 0.4, false, label + ": step up"));
            }
            heading = heading.clockwise();
        }
        // Under water: not over to a wall, whose steps the same water refuses, but along under it, one way, until the
        // ground over its head is dry. In the live pair on 41f9de9 Kindred, under a lake whose floor of gravel lay on its
        // corridor's ceiling, went round once a second for eighteen minutes between a step up with no way on, the wall
        // and the way back (F-25c4ah).
        if (waterOver && !swimming) {
            // So far along under the water from where it began with still no dry ground over its head, or about where it
            // last found that so: the water is too wide to get out from under this way, and the climb ends there, handed
            // back to what the goal does next. On 606 in E-40's iteration 18 (`e40a18`) Kindred, in its tunnel under the
            // open sea, went to and fro under it through 12 stuck spells (F-35g).
            if (underWaterFrom == null || now - underWaterTick > 600) underWaterFrom = feet;
            boolean gaveUp = underWaterGaveUpAt != null && underWaterGaveUpAt.squaredDistance(feet) <= 24 * 24
                    && now - underWaterGaveUpTick < 2400;
            int along = Math.max(Math.abs(feet.x() - underWaterFrom.x()), Math.abs(feet.z() - underWaterFrom.z()));
            if (gaveUp || along >= UNDER_WATER_REACH) {
                if (!gaveUp) {
                    underWaterGaveUpAt = feet;
                    underWaterGaveUpTick = now;
                    body.event("the water over its tunnel at " + feet.x() + ", " + feet.y() + ", " + feet.z() + " too wide to get out from under: "
                            + along + " blocks along under it with no dry ground over its head; the climb handed back");
                }
                underWaterAt = feet;
                underWaterTick = now;
                return Optional.empty();
            }
            underWaterAt = feet;
            underWaterTick = now;
            if (underWaterHeading == null) underWaterHeading = heading;
            heading = underWaterHeading;
            // Along its own level, never a step down: stepped back down into the part below that it had climbed out of, it
            // climbed up again at the next step, and back down. And with no pickaxe, dug by hand, as the climb is: dug only
            // with a tool, the way on was nothing it could dig. In E-40's iteration 9 (`e40a9-dd9b037`) on 8675309 Kindred,
            // its pickaxe broken under a lake, stepped up and back down for 10 stuck spells (F-25c4bu).
            int floor = tunnelFloor;
            tunnelFloor = feet.y();
            tunnelByHand = true;
            Optional<T> out;
            try {
                out = tunnel(body, label + ": out from under the water");
            } finally {
                tunnelByHand = false;
                tunnelFloor = floor;
            }
            underWaterHeading = heading;
            if (out.isPresent()) return out;
            // Every way along its own level refused, the lake's water over the next cell or sand with the water on it: a
            // step down along its heading, by hand if it must, under a block more of cover, and along from there. The cell
            // it leaves is set aside, so that it is not stepped back up into at once. On 707 in E-40's iteration 15
            // (`e40a15-dbf4e5c`) Echo, its pickaxe worn out, climbed by hand to the bed of a frozen lake and went "step
            // aside" and "to the wall" between two cells under it through 10 stuck spells, 10,564 times (F-25c4cq).
            digDownByHand = true;
            Optional<T> lower;
            String lowerLabel = label + ": out from under the water, a level lower";
            try {
                lower = digDown(body, feet.y() - 1, lowerLabel);
            } finally {
                digDownByHand = false;
            }
            underWaterHeading = heading;
            if (lower.isPresent()) {
                if (steppedDown) body.setAside(feet, now + 2400);
                return lower;
            }
        }
        if (headRoomRefused && !swimming) {
            Optional<T> aside = fromBeside(body, feet, label, now);
            if (aside.isPresent()) return aside;
        }
        // Under a hole up through the ceiling, nothing beside it to climb onto: up into the hole on blocks of its own, as a
        // player gets back up the way it fell or dug down (F-25c4be).
        if (!swimming && body.onGround()) {
            Optional<T> hole = intoTheHoleOverhead(body, feet, label);
            if (hole.isPresent()) return hole;
        }
        // Every step up beside the water, and a step up clear of it a few blocks higher by the same wall: up there on blocks
        // of its own, as a player gets back up off a ledge under a lake's wall (F-25c4bf).
        if (!besideWater.isEmpty() && !swimming && body.onGround()) {
            Optional<T> pillar = pillarBesideTheWater(body, feet, besideWater, label, now);
            if (pillar.isPresent()) return pillar;
        }
        // In the open middle of a room, nothing beside it to climb onto: over to its nearest wall first, to dig the stair
        // up from there, as a player boxed into a chamber does (the stranded fixture's chamber, three square). Swimming,
        // across the water to it (F-25c4t).
        Side toWall = null;
        int nearest = Integer.MAX_VALUE;
        for (Side side : Side.HORIZONTAL) {
            for (int n = 1; n <= 4 && n < nearest; n++) {
                Cell cell = feet.add(side.dx * n, 0, side.dz * n);
                if (!passable(world, cell)) {
                    if (n > 1) {
                        nearest = n;
                        toWall = side;
                    }
                    break;
                }
                if (!passable(world, cell.up()) || passable(world, cell.down()) && !swimming) break;
            }
        }
        if (toWall == null) return Optional.empty();
        // Not over to the wall of the cell it has just stepped aside out of, whose head room it may not dig: there it
        // steps aside again, and back, for good (F-25c4cq).
        Cell wall = feet.add(toWall.dx * (nearest - 1), 0, toWall.dz * (nearest - 1));
        if (wall.equals(asideFrom) && feet.equals(asideTo) && now - asideTick < 1200) return Optional.empty();
        // Nor over to the wall of the cell it has just gone out from under the water from: there the water over it sends it
        // out from under the water again, to here, and back, for good. On 606 in E-40's iteration 18 (`e40a18`) Kindred went
        // "out from under the water" to 196, 56, -126 and "to the wall" back to 195 some thirty times each a stuck spell,
        // through 12 of them (F-35g).
        if (wall.equals(underWaterAt) && now - underWaterTick < 1200) return Optional.empty();
        heading = toWall;
        return Optional.of(body.go(wall, 0.4, false, label + ": to the wall"));
    }

    /**
     * Out from under a block over its head it may not dig, as a player gets out: from the cell beside it. The block is
     * refused for sand or gravel resting on it, which would come down onto the player, or for a liquid beside it, which
     * would be let in.
     * <ul>
     * <li>It digs a pocket two high beside it, on solid ground, where the pocket's own head room has no liquid beside it.</li>
     * <li>Under sand, it opens the pocket's ceiling from where it stands, so what rested there comes down into the pocket
     * and not onto the player, and digs what came down out from beside it, the lowest first, until nothing rests over the
     * pocket.</li>
     * <li>Then it steps into the pocket, whose head room it may dig or is clear, and climbs from there.</li>
     * </ul>
     * In E-40's iteration 2 (`e40a2-143806e`) two players, out of sticks with no pickaxe, came up shafts of their own one
     * wide to such a block, and stood there to the end of the run (F-25c4bd). On 31102026 Sol had sandstone with four sand
     * on it over its head at Y=64, 6 stuck spells. On 101 Kindred had stone at Y=55 with a pocket of water beside it, 16.
     */
    private <T> Optional<T> fromBeside(Body<T> body, Cell feet, String label, long now) {
        var world = body.world();
        if (fallingOver(world, feet, 8, 1)) {
            return Optional.of(body.settle(label + ": let what falls settle"));
        }
        var ways = new ArrayList<Side>();
        for (Side way = heading; ways.size() < 4; way = way.clockwise()) ways.add(way);
        // A pocket beside it whose head room it may dig or is clear: into it, and up from there.
        for (Side way : ways) {
            Cell side = feet.offset(way);
            if (pocket(world, side) && !body.blocked(side) && !headRoomRefused(world, side)) {
                heading = way;
                asideFrom = feet;
                asideTo = side;
                asideTick = now;
                return Optional.of(body.go(side, 0.4, false, label + ": step aside"));
            }
        }
        // What came down into a pocket, dug out from beside it, the lowest first: what rests above comes down after it.
        for (Side way : ways) {
            Cell side = feet.offset(way);
            if (passable(world, side.down())) continue;
            Cell fallen = passable(world, side) ? side.up() : side;
            if (passable(world, fallen) || !falls(world, fallen)) continue;
            Optional<T> dig = digBeside(body, fallen, label + ": dig out what came down");
            if (dig.isPresent()) return dig;
        }
        // A pocket beside it under what rests there: its ceiling opened from here, so that comes down into the pocket.
        for (Side way : ways) {
            Cell side = feet.offset(way);
            if (!pocket(world, side) || body.blocked(side) || !capped(world, side)) continue;
            Optional<T> dig = digBeside(body, side.up(2), label + ": open the ceiling beside it");
            if (dig.isPresent()) return dig;
        }
        // No pocket yet: one dug beside it, on solid ground, its head-level cell first; not where the pocket's own head room
        // would have a liquid beside it.
        for (Side way : ways) {
            Cell side = feet.offset(way);
            if (passable(world, side.down()) || body.blocked(side) || body.blocked(side.up())) continue;
            if (!passable(world, side.up(2)) && liquidNear(world, side.up(2))) continue;
            Cell cell = !passable(world, side.up()) ? side.up() : side;
            if (passable(world, cell)) continue;
            Optional<T> dig = digBeside(body, cell, label + ": dig a pocket beside it");
            if (dig.isPresent()) {
                heading = way;
                return dig;
            }
        }
        return Optional.empty();
    }

    /**
     * Up into a hole one wide in the ceiling over it, or over a cell beside it on the same ground, on blocks of its own: a
     * player come down through a cave's ceiling gets back up the same way. On 20261030 in E-40's iteration 3
     * (`e40a3-05b58fd`) Echo's stair down for iron broke through the ceiling of a cave two high over a lava lake, and it
     * came down onto an island of two blocks in the lava. With nothing to climb onto and every walk to a wall across the
     * lava refused, it stood there from tick 4,500 to the end of the run, 38 stuck spells, the hole it came down through
     * over the island's other block (F-25c4be).
     */
    private <T> Optional<T> intoTheHoleOverhead(Body<T> body, Cell feet, String label) {
        var world = body.world();
        if (holeOver(world, feet)) {
            int slot = body.wayBlockSlot();
            if (slot < 0) return Optional.empty();
            if (slot >= 9) return Optional.of(body.swap(slot, "Ready a block"));
            return Optional.of(body.pillar(feet, slot, label + ": pillar up into the hole over it"));
        }
        for (Side side : Side.HORIZONTAL) {
            Cell next = feet.offset(side);
            if (pocket(world, next) && !body.blocked(next) && holeOver(world, next) && body.wayBlockSlot() >= 0) {
                return Optional.of(body.go(next, 0.4, false, label + ": under the hole beside it"));
            }
        }
        return Optional.empty();
    }

    /**
     * Up on blocks of its own beside a wall it may not climb for the water behind it, to the height where a step up onto
     * the wall is clear of the water: one to three blocks up, open over its head all the way. On 303 in E-40's iteration 4
     * (`e40a4-c237a6e`) Kindred swam the top of an aquifer's lake, came onto the stone wall between the lake and a cave, and
     * went down the cave's side onto a ledge. Every step back up was beside the lake's water. From then to the end of the
     * run, 27 stuck spells, it stepped aside from a cell it had dug into the wall and walked "to the wall" back into it,
     * 1,466 times each (F-25c4bf).
     */
    private <T> Optional<T> pillarBesideTheWater(Body<T> body, Cell feet, List<Side> ways, String label, long now) {
        var world = body.world();
        for (int h = 1; h <= 3; h++) {
            // Room over its head to rise h blocks and step up from there.
            if (!passable(world, feet.up(h + 1)) || !passable(world, feet.up(h + 2))) return Optional.empty();
            for (Side way : ways) {
                Cell step = feet.up(h).offset(way);
                Cell landing = step.up();
                if (passable(world, step) || body.blocked(landing)) continue;
                boolean clear = true;
                for (Cell cell : List.of(landing, landing.up())) {
                    if (!passable(world, cell) && (body.blocked(cell) || liquidNear(world, cell) || body.islandEdge(cell))) clear = false;
                }
                if (!clear) continue;
                int slot = body.wayBlockSlot();
                if (slot < 0) return Optional.empty();
                if (slot >= 9) return Optional.of(body.swap(slot, "Ready a block"));
                // Refused twice here within a minute: not from here again for now, not asked for again at once. In a
                // fixture of 303's ledge one cell by a wall refused 151 jumps in a row.
                int misses = pillarMissesUntil.getOrDefault(feet.asLong(), Long.MIN_VALUE) >= now ? pillarMisses.getOrDefault(feet.asLong(), 0) : 0;
                if (misses >= 2) return Optional.empty();
                heading = way;
                return Optional.of(body.pillar(feet, slot, label + ": pillar up beside the water"));
            }
        }
        return Optional.empty();
    }

    /**
     * A hole one wide up through the ceiling over a player standing there: its head room clear, and the cell over its head
     * walled in on every side, or the one over that walled in with room over it.
     */
    private static boolean holeOver(DigTerrain world, Cell feet) {
        if (!passable(world, feet.up()) || !passable(world, feet.up(2))) return false;
        return walledIn(world, feet.up()) || walledIn(world, feet.up(2)) && passable(world, feet.up(3));
    }

    /** Nothing open beside it on any side. */
    private static boolean walledIn(DigTerrain world, Cell cell) {
        for (Side side : Side.HORIZONTAL) if (passable(world, cell.offset(side))) return false;
        return true;
    }

    /** Open at its feet and head, on solid ground. */
    private static boolean pocket(DigTerrain world, Cell feet) {
        return passable(world, feet) && passable(world, feet.up()) && !passable(world, feet.down());
    }

    /** The block over a player's head there with sand or gravel resting on it, which would come down on it if dug out. */
    private static boolean capped(DigTerrain world, Cell feet) {
        return !passable(world, feet.up(2)) && falls(world, feet.up(3));
    }

    /** The block over a player's head there not to be dug: sand or gravel resting on it, or a liquid beside it. */
    private static boolean headRoomRefused(DigTerrain world, Cell feet) {
        return capped(world, feet) || !passable(world, feet.up(2)) && liquidNear(world, feet.up(2));
    }

    /** A block beside the player dug out, with a tool if it has one fit for it, else by hand; never one by a liquid. */
    private static <T> Optional<T> digBeside(Body<T> body, Cell cell, String label) {
        if (body.blocked(cell) || liquidNear(body.world(), cell)) return Optional.empty();
        if (body.breakableNow(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell)));
        if (body.handClearable(cell)) return Optional.of(body.clear(cell, label + ": clear " + at(cell) + " by hand"));
        return Optional.empty();
    }

    /**
     * Sand or gravel over a cell just opened under it comes down in a moment: waited for two seconds at most, as sand
     * left hanging over a cave by the world's making never comes down until something beside it changes (F-25c4ao).
     */
    private boolean settling(Cell at, long now) {
        if (!at.equals(settleAt)) {
            settleAt = at;
            settleSince = now;
        }
        return now - settleSince < 40;
    }
}
