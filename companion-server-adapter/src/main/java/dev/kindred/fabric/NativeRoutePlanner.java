package dev.kindred.fabric;

import dev.kindred.core.route.Cell;
import dev.kindred.core.route.RoutePlanner;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * In plain terms: plans how an AI player gets from one place to another the way a human would: walking where
 * it can, and otherwise digging through, bridging a gap, or building up a pillar or a staircase with its own
 * blocks, whichever is least trouble. It only plans; each step is then done by the player's ordinary actions,
 * and the plan is made again from wherever it ends up.
 *
 * Technical: The server's side of {@link RoutePlanner}, the core's planner of routes that may dig, bridge, pillar and
 * build a staircase on the way (F-35d). It reads the live world through its {@link NativeNavigator}'s terrain, keeps
 * the types its callers use, in block positions, and hands each search to the core, which decides every step and
 * cost. It edits nothing itself: each step is carried out by the ordinary recorded skills, one at a time, and the
 * route is planned again from wherever the player then stands.
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

    static final double BRIDGE_COST = RoutePlanner.BRIDGE_COST, PILLAR_COST = RoutePlanner.PILLAR_COST, OVER_LAVA = RoutePlanner.OVER_LAVA;
    /** A step of a staircase (F-10h), and the filler block under one in the open. */
    static final double STAIR_COST = RoutePlanner.STAIR_COST, FILLER_COST = RoutePlanner.FILLER_COST;
    /** The search leans toward the target by this much (weighted A*). */
    static final double LEAN = RoutePlanner.LEAN;

    private final RoutePlanner core;

    /** A planner that walks as the navigator does, and digs and places only where the allowance says. */
    NativeRoutePlanner(NativeNavigator navigator, Allowance allowance) {
        // The core asks which cells are set aside; a cell is set aside where the caller's allowance would not place.
        this.core = new RoutePlanner(navigator.core(), new RoutePlanner.Allowance(
                cell -> allowance.diggable().test(NativeNavigator.pos(cell)),
                cell -> !allowance.placeable().test(NativeNavigator.pos(cell)),
                allowance.blocks()));
    }

    /**
     * Searches from {@code start} toward {@code target}; a position satisfying {@code goal} ends it. An incomplete
     * route leads to the explored position nearest the target.
     */
    Route find(BlockPos start, BlockPos target, Predicate<BlockPos> goal, int maxNodes, int maxHorizontalRadius) {
        var route = core.find(NativeNavigator.cell(start), NativeNavigator.cell(target),
                cell -> goal.test(NativeNavigator.pos(cell)), maxNodes, maxHorizontalRadius);
        var steps = new ArrayList<Step>(route.steps().size());
        for (var step : route.steps()) {
            var cells = new ArrayList<BlockPos>(step.cells().size());
            for (Cell cell : step.cells()) cells.add(NativeNavigator.pos(cell));
            steps.add(new Step(NativeNavigator.pos(step.from()), NativeNavigator.pos(step.to()),
                    Kind.values()[step.kind().ordinal()], List.copyOf(cells)));
        }
        return new Route(steps, route.complete(), route.expanded());
    }
}
