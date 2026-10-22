package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import dev.kindred.core.construction.BuildSite.Kind;
import dev.kindred.core.construction.ConstructionPlanner.Step;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A build played through the planner step by step, as the adapter's runner plays it: the player is put where each step
 * says to stand (which must be a spot it can walk to), each block goes in as vanilla would place it from the planned
 * look and click, scaffolds and props go up and come down, and wanted elsewhere while on its own scaffold it digs the
 * block underfoot first. Checks that every placement is in reach, every block ends in its state, the clear space is
 * clear and the scaffold is all down.
 */
final class BuildSimulation {
    record Run(int steps, int scaffolds, int props) { }

    private BuildSimulation() { }

    static Run build(List<Blueprint.Block> blocks, List<Pos> clear, Map<Pos, Cell> cells, Pos feet, Set<String> held, String label) {
        var scaffold = new HashSet<Pos>();
        int steps = 0, scaffolds = 0, props = 0;
        while (true) {
            assertTrue(++steps < 6000, label + ": finishes");
            Step step = ConstructionPlanner.next(blocks, clear, new BuildSite(cells), feet, held, scaffold);
            if (step.kind() == ConstructionPlanner.Kind.DONE) break;
            assertNotEquals(ConstructionPlanner.Kind.BLOCKED, step.kind(), label + " at " + feet + ": " + step.reason());
            if (scaffold.contains(feet.down()) && !feet.equals(step.stance())) {
                cells.put(feet.down(), Cell.AIR);
                scaffold.remove(feet.down());
                feet = feet.down();
                continue;
            }
            assertTrue(ConstructionPlanner.walkable(new BuildSite(cells), feet, scaffold).contains(step.stance()), label + ": walks to " + step.stance());
            switch (step.kind()) {
                case PLACE, SUPPORT -> {
                    assertTrue(new BuildSite(cells).standable(step.stance()), label + ": stands on ground");
                    feet = step.stance();
                    double ex = feet.x() + 0.5, ey = feet.y() + ConstructionPlanner.EYE, ez = feet.z() + 0.5;
                    double reach = Math.sqrt(Math.pow(step.hit()[0] - ex, 2) + Math.pow(step.hit()[1] - ey, 2) + Math.pow(step.hit()[2] - ez, 2));
                    assertTrue(reach <= ConstructionPlanner.REACH, label + ": in reach: " + reach);
                    var placed = placedState(step);
                    assertTrue(placed.sameAs(step.spec()), label + ": placed as planned: " + placed + " for " + step.spec());
                    cells.put(step.target(), cellFor(placed));
                    if (step.kind() == ConstructionPlanner.Kind.SUPPORT) {
                        props++;
                        scaffold.add(step.target());
                    }
                }
                case BREAK -> {
                    feet = step.stance();
                    cells.put(step.target(), Cell.AIR);
                    scaffold.remove(step.target());
                    while (!new BuildSite(cells).standable(feet)) feet = feet.down(); // Broken underfoot: it drops.
                }
                case SCAFFOLD -> {
                    scaffolds++;
                    feet = step.stance();
                    for (int h = 0; h < step.height(); h++) {
                        cells.put(feet, BuildTestSites.solid("minecraft:dirt"));
                        scaffold.add(feet);
                        feet = feet.up();
                    }
                }
                default -> fail(step.kind().name());
            }
        }
        var site = new BuildSite(cells);
        for (var b : blocks) assertTrue(site.cell(b.at()).orElseThrow().state().sameAs(b.spec()), label + ": " + b.at() + " holds " + b.spec());
        for (var p : clear) assertTrue(site.cell(p).orElseThrow().passable(), label + ": " + p + " clear, scaffold left " + scaffold);
        assertTrue(scaffold.isEmpty(), label + ": scaffold down");
        return new Run(steps, scaffolds, props);
    }

    /** What vanilla would place for this step: the rules the planner models, applied from the look and the hit. */
    private static BlockSpec placedState(Step step) {
        var spec = step.spec();
        var rule = Placement.rule(spec);
        var props = new HashMap<String, String>();
        Pos.Face looking = Placement.facingOf(step.yaw());
        double within = step.hit()[1] - step.target().y();
        switch (rule) {
            case STAIRS -> {
                props.put("facing", looking.id());
                props.put("half", step.face() == Pos.Face.DOWN || step.face() != Pos.Face.UP && within > 0.5 ? "top" : "bottom");
            }
            case SLAB -> props.put("type", step.face() == Pos.Face.DOWN || step.face() != Pos.Face.UP && within > 0.5 ? "top" : "bottom");
            case AXIS -> props.put("axis", step.face().axis());
            case WALL -> props.put("facing", step.face().id());
            case FACING_PLAYER -> props.put("facing", looking.opposite().id());
            case FACING_AWAY -> props.put("facing", looking.id());
            default -> { }
        }
        var merged = new HashMap<>(spec.properties());
        merged.putAll(props);
        return new BlockSpec(spec.block(), Map.copyOf(merged));
    }

    /** The cell a placed block makes, as the adapter's snapshot would see it. */
    private static Cell cellFor(BlockSpec spec) {
        String id = spec.block();
        if (id.endsWith("torch")) return new Cell(Kind.PASSABLE, spec, false);
        boolean top = "top".equals(spec.property("half")) || "top".equals(spec.property("type"));
        boolean fullTop = top || !id.endsWith("_stairs") && !id.endsWith("_slab") && !id.endsWith("lantern");
        return new Cell(Kind.SOLID, spec, fullTop);
    }
}
