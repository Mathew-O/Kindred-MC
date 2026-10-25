package dev.kindred.core.team;

import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HandOversTest {
    private static final UUID WREN = new UUID(0, 1), MASON = new UUID(0, 2);
    private static final List<ShareOut.Grant> ELEVEN = List.of(new ShareOut.Grant(WREN, "Wren", Slot.BOOTS, Material.DIAMOND, 4),
            new ShareOut.Grant(WREN, "Wren", Slot.HELMET, Material.DIAMOND, 5),
            new ShareOut.Grant(MASON, "Mason", Slot.SWORD, Material.DIAMOND, 2));

    @Test
    void aTaskForEachPieceItsOwnIncludedAndTheProjectSaysWhat() {
        var project = HandOvers.project(new ProjectId("share-1"), MASON, Material.DIAMOND, 11, ELEVEN);
        assertEquals("Share out 11 diamonds", project.name());
        assertEquals("I've got 11 diamonds: diamond boots and a diamond helmet for Wren and a diamond sword for me.", project.description());
        assertEquals(3, project.tasks().size(), "Its own sword too, as it says it will make it");
        var pieces = project.tasks().values().stream().map(HandOvers.Piece::of).map(HandOvers.Piece::piece).sorted().toList();
        assertEquals(List.of("minecraft:diamond_boots", "minecraft:diamond_helmet", "minecraft:diamond_sword"), pieces);
        var sword = project.tasks().values().stream().filter(t -> t.title().equals("a diamond sword for Mason")).findFirst().orElseThrow();
        assertEquals(MASON, HandOvers.Piece.of(sword).to(), "Kept, not handed over");
        for (var task : project.tasks().values()) {
            assertEquals(HandOvers.SKILL, task.skill());
            assertEquals(SharedTask.TaskState.OPEN, task.state());
            assertEquals(HandOvers.held(MASON, "minecraft:diamond"), task.arguments().get("reserved_item"));
            assertEquals(task.arguments().get("cost"), task.arguments().get("reserved_count"));
            assertTrue(HandOvers.givenBy(task, MASON));
            assertFalse(HandOvers.givenBy(task, WREN), "Only the one that found it hands it over");
        }
    }

    @Test
    void theFindIsReservedFromWhatTheGiverHoldsSoItIsNeverPromisedTwice() {
        var team = new TeamCoordinator();
        team.stockLedger().registerStock(HandOvers.held(MASON, "minecraft:diamond"), 11);
        var project = HandOvers.project(new ProjectId("share-1"), MASON, Material.DIAMOND, 11, ELEVEN);
        team.registerProject(project);
        for (var task : project.tasks().values()) assertTrue(team.claimTask(task.id(), MASON, 0, 1200).isPresent(), task.title());
        assertEquals(11, team.stockLedger().reservedCount(HandOvers.held(MASON, "minecraft:diamond")), "All eleven promised");
        assertEquals(0, team.stockLedger().unreservedCount(HandOvers.held(MASON, "minecraft:diamond")));
        // The same eleven shared again: none are left to promise.
        var again = HandOvers.project(new ProjectId("share-2"), MASON, Material.DIAMOND, 11, ELEVEN);
        team.registerProject(again);
        var boots = again.tasks().values().stream().filter(t -> t.title().startsWith("diamond boots")).findFirst().orElseThrow();
        assertTrue(team.claimTask(boots.id(), MASON, 0, 1200).isEmpty(), "Four more are not there to reserve");
        // One piece handed over: its four are spent, not freed.
        var given = project.tasks().values().stream().filter(t -> t.title().startsWith("diamond boots")).findFirst().orElseThrow();
        assertTrue(team.completeTask(given.id(), MASON, "Handed over"));
        assertEquals(7, team.stockLedger().reservedCount(HandOvers.held(MASON, "minecraft:diamond")));
        assertTrue(team.findEligibleTasks(WREN).stream().noneMatch(t -> t.projectId().id().equals("share-2")),
                "Nothing of it is open to take up while its stock is short");
    }

    @Test
    void piecesAndMaterialsNameTheirItems() {
        assertEquals("minecraft:golden_helmet", HandOvers.pieceItem(Slot.HELMET, Material.GOLD));
        assertEquals("minecraft:iron_sword", HandOvers.pieceItem(Slot.SWORD, Material.IRON));
        assertEquals("minecraft:leather_boots", HandOvers.pieceItem(Slot.BOOTS, Material.LEATHER));
        assertEquals("minecraft:iron_ingot", HandOvers.materialItem(Material.IRON));
        assertThrows(IllegalArgumentException.class, () -> HandOvers.pieceItem(Slot.SWORD, Material.LEATHER));
        assertThrows(IllegalArgumentException.class, () -> HandOvers.materialItem(Material.CHAIN));
        assertNull(HandOvers.Piece.of(SharedTask.open(TaskId.random(), new ProjectId("p"), "Mine", "native_break", java.util.Map.of(), java.util.Set.of())));
    }
}
