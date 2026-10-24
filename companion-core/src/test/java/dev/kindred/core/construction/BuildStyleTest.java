package dev.kindred.core.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BuildStyleTest {
    @Test
    void theWoodComesFromTheSeedBlockElseTheWoodAtHandElseOak() {
        assertEquals("cherry", BuildStyle.woodOf("minecraft:cherry_log"));
        assertEquals("dark_oak", BuildStyle.woodOf("minecraft:stripped_dark_oak_wood"));
        assertEquals("spruce", BuildStyle.woodOf("spruce_planks"));
        assertNull(BuildStyle.woodOf("minecraft:cobblestone"));
        assertNull(BuildStyle.woodOf("minecraft:crimson_stem"));
        assertEquals("cherry", BuildStyle.grown("minecraft:cherry_log", "birch", 7).wood());
        assertEquals("birch", BuildStyle.grown("minecraft:cobblestone", "birch", 7).wood());
        assertEquals("oak", BuildStyle.grown("", null, 7).wood());
        assertEquals("oak", BuildStyle.grown("", "crimson", 7).wood());
    }

    @Test
    void theSameSeedInTheSameWorldGrowsTheSameStyleAndOtherWorldsOthers() {
        assertEquals(BuildStyle.grown("minecraft:cherry_log", "oak", 42), BuildStyle.grown("minecraft:cherry_log", "oak", 42));
        var styles = new HashSet<String>();
        for (long world = 0; world < 64; world++) {
            var style = BuildStyle.grown("minecraft:cherry_log", "oak", world * 7919);
            assertTrue(style.variant() >= 1 && style.variant() <= BuildStyle.VARIANTS, "A variant: " + style);
            styles.add(style.variant() + "/" + style.turns());
        }
        assertTrue(styles.size() >= 32, "Worlds grow different builds: " + styles.size() + " of 64");
        // Another seed block in the same world grows another build as well as another wood.
        var cherry = BuildStyle.grown("minecraft:cherry_log", "oak", 42);
        int differ = 0;
        for (String seed : new String[] {"minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log"}) {
            var other = BuildStyle.grown(seed, "oak", 42);
            if (other.variant() != cherry.variant() || other.turns() != cherry.turns()) differ++;
        }
        assertTrue(differ >= 3, "Seeds grow different plans: " + differ);
    }

    @Test
    void theFirstPlanOfEachStageIsTheOneBasesWereBuiltOnBefore() {
        for (var stage : Houses.Stage.values()) {
            assertEquals(Houses.of(stage, "oak", "cobblestone").blocks(), Houses.of(stage, BuildStyle.CLASSIC).blocks(), stage.noun);
            var shape = Houses.shape(stage, 0);
            assertEquals(stage.width, shape.width());
            assertEquals(stage.depth, shape.depth());
        }
        assertEquals(222, Houses.of(Houses.Stage.CABIN, BuildStyle.CLASSIC).blocks().size(), "The cabin as M-01b and M-01c built it");
    }

    @Test
    void everyVariantKeepsItsRoomsWorkingInsideItsShape() {
        for (var stage : Houses.Stage.values()) {
            var first = Houses.functions(Houses.of(stage, BuildStyle.CLASSIC));
            for (int v = 0; v <= BuildStyle.VARIANTS; v++) {
                var style = new BuildStyle("", "cherry", "cobblestone", v, 0);
                var plan = Houses.of(stage, style);
                var shape = Houses.shape(stage, v);
                String what = stage.noun + " variant " + v + " " + shape;
                // As many of each working block as its first plan, or more.
                var functions = Houses.functions(plan);
                for (var e : first.entrySet()) {
                    assertTrue(functions.getOrDefault(e.getKey(), 0) >= e.getValue(), what + ": " + e.getKey() + " " + functions);
                }
                // Within its shape, on the ground.
                for (var b : plan.blocks()) {
                    assertTrue(b.at().x() >= 0 && b.at().x() < shape.width() && b.at().z() >= 0 && b.at().z() < shape.depth() && b.at().y() >= 0,
                            what + ": " + b.at());
                }
                var at = Blueprint.byPosition(plan.blocks());
                // The door in the middle of the south wall, the way in and the way out clear.
                var door = at.get(new Pos(shape.width() / 2, 1, shape.depth() - 1));
                assertTrue(door != null && door.block().equals("minecraft:cherry_door") && "lower".equals(door.property("half")), what + ": door " + door);
                for (int y = 1; y <= 2; y++) {
                    assertFalse(at.containsKey(new Pos(shape.width() / 2, y, shape.depth() - 2)), what + ": the way in at " + y);
                }
                // Every bed whole: its head the way it faces from its foot.
                for (var b : plan.blocks()) {
                    if (!b.spec().block().endsWith("_bed") || !"foot".equals(b.spec().property("part"))) continue;
                    var head = at.get(b.at().offset(Placement.face(b.spec().property("facing"))));
                    assertTrue(head != null && "head".equals(head.property("part")), what + ": a bed's head at " + b.at());
                }
                // A floor under all of it, walls all round.
                for (int x = 0; x < shape.width(); x++) for (int z = 0; z < shape.depth(); z++) {
                    assertTrue(at.containsKey(new Pos(x, 0, z)), what + ": floor at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void aTurnedPlanCoversTheGroundFromItsCornerAsFarAsItsSpan() {
        var corner = new Pos(100, 64, -40);
        for (int v : new int[] {0, 3, 9, 30}) for (int turns = 0; turns < 4; turns++) {
            var shape = Houses.shape(Houses.Stage.CABIN, v);
            var placed = Houses.of(Houses.Stage.CABIN, new BuildStyle("", "oak", "cobblestone", v, turns)).placed(Houses.originFor(corner, shape, turns), turns);
            int minX = placed.stream().mapToInt(b -> b.at().x()).min().orElseThrow(), maxX = placed.stream().mapToInt(b -> b.at().x()).max().orElseThrow();
            int minZ = placed.stream().mapToInt(b -> b.at().z()).min().orElseThrow(), maxZ = placed.stream().mapToInt(b -> b.at().z()).max().orElseThrow();
            int[] span = Houses.span(shape, turns);
            String what = "variant " + v + " turned " + turns;
            assertEquals(corner.x(), minX, what);
            assertEquals(corner.z(), minZ, what);
            assertEquals(corner.x() + span[0] - 1, maxX, what);
            assertEquals(corner.z() + span[1] - 1, maxZ, what);
        }
    }

    @Test
    void variantsAreDifferentBuildsAndALaidOutOtherWayAboutOneMirrorsTheFirst() {
        Set<Set<String>> cabins = new HashSet<>();
        for (int v = 1; v <= BuildStyle.VARIANTS; v++) {
            var plan = Houses.of(Houses.Stage.CABIN, new BuildStyle("", "oak", "cobblestone", v, 0));
            cabins.add(plan.blocks().stream().map(b -> b.at() + " " + b.spec()).collect(Collectors.toSet()));
        }
        assertTrue(cabins.size() >= 16, "Different cabins: " + cabins.size());
        // No gable spans more than seven: the ridge runs along the long side, and a cabin nine by nine is roofed flat.
        for (int v = 0; v <= BuildStyle.VARIANTS; v++) {
            var shape = Houses.shape(Houses.Stage.CABIN, v);
            int span = switch (shape.roof()) {
                case RIDGE_EAST_WEST -> shape.depth();
                case RIDGE_NORTH_SOUTH -> shape.width();
                case FLAT -> 0;
            };
            assertTrue(span <= 7, "Variant " + v + " " + shape);
            assertEquals(shape.width() == 9 && shape.depth() == 9, shape.roof() == Houses.Roof.FLAT, "Variant " + v + " " + shape);
        }
        // Variant 4 is the first plan laid out the other way about: its bed on the east side, facing the same way.
        var first = Houses.of(Houses.Stage.CABIN, BuildStyle.CLASSIC);
        var other = Blueprint.byPosition(Houses.of(Houses.Stage.CABIN, new BuildStyle("", "oak", "cobblestone", 4, 0)).blocks());
        for (var b : first.blocks()) {
            var mirrored = other.get(new Pos(6 - b.at().x(), b.at().y(), b.at().z()));
            assertTrue(mirrored != null && mirrored.block().equals(b.spec().block()), "Mirrored at " + b.at());
            String facing = b.spec().property("facing");
            if (facing != null) {
                String expected = Map.of("east", "west", "west", "east").getOrDefault(facing, facing);
                assertEquals(expected, mirrored.property("facing"), "Facing at " + b.at());
            }
        }
    }
}
