package dev.kindred.core.construction;

import dev.kindred.core.construction.BuildSite.Cell;
import dev.kindred.core.construction.BuildSite.Kind;
import dev.kindred.core.construction.ConstructionPlanner.Step;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorsTest {
    private static final Set<String> HELD = Set.of("minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_stairs",
            "minecraft:oak_slab", "minecraft:oak_log", "minecraft:torch", "minecraft:dirt");

    @Test
    void aHutHasAFloorWallsADoorwayATorchAndARoof() {
        var hut = Generators.hut(5, 5, 3);
        var at = Blueprint.byPosition(hut.blocks());
        assertEquals("minecraft:oak_planks", at.get(new Pos(2, 0, 2)).block());
        assertEquals("y", at.get(new Pos(0, 2, 0)).property("axis"), "log corners stand upright");
        assertEquals("minecraft:cobblestone", at.get(new Pos(2, 3, 4)).block(), "a lintel over the doorway");
        assertNull(at.get(new Pos(2, 1, 4)), "the doorway");
        assertTrue(hut.clear().contains(new Pos(2, 2, 4)) && hut.clear().contains(new Pos(2, 1, 2)), "kept clear: doorway and inside");
        assertEquals("south", at.get(new Pos(2, 2, 1)).property("facing"), "a torch on the north wall");
        assertEquals("south", at.get(new Pos(0, 4, 0)).property("facing"), "north eaves face in");
        assertEquals("north", at.get(new Pos(0, 4, 4)).property("facing"), "south eaves face in");
        assertEquals("minecraft:oak_slab", at.get(new Pos(3, 5, 2)).block(), "an odd span ridged with slabs beside the last stairs");
        var wide = Blueprint.byPosition(Generators.hut(5, 9, 3).blocks());
        assertEquals("minecraft:oak_planks", wide.get(new Pos(1, 5, 2)).block(), "a course of stairs rests on planks");
        assertEquals("south", wide.get(new Pos(1, 6, 2)).property("facing"));
        assertEquals("kindred:generator/hut?width=5&depth=5&walls=3", hut.source());
        assertThrows(IllegalArgumentException.class, () -> Generators.hut(2, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> Generators.named("castle", List.of()));
        assertEquals(Generators.hut(7, 5, 3), Generators.named("hut", List.of(7, 5, 3)));
    }

    /**
     * The planner builds a whole hut on flat ground, step by step, as the runner would: the player is put where each
     * step says to stand, each block goes in as placed, scaffolds go up under it and come down again. Every block ends
     * in its state, nothing is left in the clear space, every stair is placed facing the way the player looked, and
     * every placement is in reach from its stance.
     */
    @Test
    void thePlannerBuildsAWholeHutAndTakesItsScaffoldDown() {
        for (int[] size : new int[][] {{5, 5, 3}, {7, 9, 4}, {3, 4, 2}}) for (int turns = 0; turns < 4; turns++) {
            var hut = Generators.hut(size[0], size[1], size[2]);
            var origin = new Pos(-size[0] / 2, 1, -size[1] / 2);
            var blocks = hut.placed(origin, turns);
            var clear = hut.placedClear(origin, turns);
            var cells = BuildTestSites.flat();
            var scaffold = new HashSet<Pos>();
            Pos feet = new Pos(-6, 1, 0);
            int steps = 0, scaffolds = 0;
            while (true) {
                assertTrue(++steps < 4000, "finishes");
                Step step = ConstructionPlanner.next(blocks, clear, new BuildSite(cells), feet, HELD, scaffold);
                if (step.kind() == ConstructionPlanner.Kind.DONE) break;
                assertNotEquals(ConstructionPlanner.Kind.BLOCKED, step.kind(), hut.name() + " turned " + turns + " at " + feet + ": " + step.reason());
                // As the runner does: wanted elsewhere while on its own scaffold, it digs down the block underfoot first.
                if (scaffold.contains(feet.down()) && !feet.equals(step.stance())) {
                    cells.put(feet.down(), Cell.AIR);
                    scaffold.remove(feet.down());
                    feet = feet.down();
                    continue;
                }
                assertTrue(ConstructionPlanner.walkable(new BuildSite(cells), feet, scaffold).contains(step.stance()), "walks to " + step.stance());
                switch (step.kind()) {
                    case PLACE -> {
                        assertTrue(new BuildSite(cells).standable(step.stance()), "stands on ground");
                        feet = step.stance();
                        double ex = feet.x() + 0.5, ey = feet.y() + ConstructionPlanner.EYE, ez = feet.z() + 0.5;
                        double reach = Math.sqrt(Math.pow(step.hit()[0] - ex, 2) + Math.pow(step.hit()[1] - ey, 2) + Math.pow(step.hit()[2] - ez, 2));
                        assertTrue(reach <= ConstructionPlanner.REACH, "in reach: " + reach);
                        var placed = placedState(step);
                        assertTrue(placed.sameAs(step.spec()), "placed as planned: " + placed + " for " + step.spec());
                        cells.put(step.target(), cellFor(placed));
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
            for (var b : blocks) assertTrue(site.cell(b.at()).orElseThrow().state().sameAs(b.spec()), b.at() + " holds " + b.spec());
            for (var p : clear) assertTrue(site.cell(p).orElseThrow().passable(), hut.name() + " turned " + turns + ": " + p + " clear, scaffold left " + scaffold + ", feet " + feet);
            assertTrue(scaffold.isEmpty(), "scaffold down");
            assertTrue(scaffolds > 0, "the ridge is out of reach from the ground");
        }
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

    private static Cell cellFor(BlockSpec spec) {
        String id = spec.block();
        if (id.endsWith("torch")) return new Cell(Kind.PASSABLE, spec, false);
        boolean fullTop = !id.endsWith("_stairs") && !id.endsWith("_slab");
        return new Cell(Kind.SOLID, spec, fullTop);
    }
}
