package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks where a player is allowed to work, and that anything left unset never counts as
 * permission.
 */
class WorkSetupTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private final UUID id = UUID.randomUUID();
    private final WorkSetup.BlockPoint homeSpot = new WorkSetup.BlockPoint(100, 64, 100);

    @Test void oversizedRegionsCannotWrapAroundTheChunkCap() {
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.WorkRegion(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.WorkRegion(0, 0, 1_048_575, 1_048_575));
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.WorkRegion(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
        assertEquals(1, new WorkSetup.WorkRegion(Integer.MAX_VALUE - 15, 0, Integer.MAX_VALUE, 15).chunkCount());
        assertEquals(4, new WorkSetup.WorkRegion(-1, -1, 0, 0).chunkCount());
    }

    @Test void patchDimensionsCannotOverflowIntoAnAllowedVolume() {
        var minimum = new WorkSetup.BlockPoint(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        var maximum = new WorkSetup.BlockPoint(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.ResourcePatch(minimum, maximum, "minecraft:oak_log"));
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(Integer.MIN_VALUE, 0, 0),
                new WorkSetup.BlockPoint(Integer.MAX_VALUE, 0, 0), "minecraft:oak_log"));
        assertDoesNotThrow(() -> new WorkSetup.ResourcePatch(maximum, maximum, "minecraft:oak_log"));
    }

    @Test void exactPatchVolumeBoundaryRemainsAvailable() {
        var origin = new WorkSetup.BlockPoint(0, 0, 0);
        assertDoesNotThrow(() -> new WorkSetup.ResourcePatch(origin, new WorkSetup.BlockPoint(15, 15, 15), "minecraft:oak_log"));
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.ResourcePatch(origin,
                new WorkSetup.BlockPoint(4096, 0, 0), "minecraft:oak_log"));
    }

    @Test
    void incompleteSetupIsSavedAndExplainedFieldByFieldWithoutGrantingAnything() {
        WorkSetup empty = WorkSetup.empty(id, OVERWORLD);
        assertEquals(List.of(WorkSetup.Field.HOME, WorkSetup.Field.AREA, WorkSetup.Field.STORAGE, WorkSetup.Field.PATCH), empty.missing());
        assertFalse(empty.readyForChores());
        assertTrue(empty.explanation().contains("/companion home set"));
        WorkSetup withHome = empty.withHome(new WorkSetup.HomeMarker(homeSpot));
        assertEquals(1, withHome.revision());
        assertFalse(withHome.missing().contains(WorkSetup.Field.HOME));
        assertTrue(withHome.explanation().contains("area"));
        var decision = EditPolicy.evaluate(withHome, EditPolicy.Kind.PLACE, OVERWORLD, homeSpot, null, EditPolicy.ServerPolicy.deferredD4());
        assertEquals(EditPolicy.Verdict.NO_SETUP, decision.verdict());
    }

    @Test
    void regionsAreBoundedToNineChunksAndContainEveryAssignedField() {
        WorkSetup.WorkRegion region = WorkSetup.WorkRegion.around(homeSpot);
        assertEquals(9, region.chunkCount());
        assertTrue(region.contains(homeSpot));
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.WorkRegion(0, 0, 63, 48)); // 4 x 4 chunks
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.WorkRegion(10, 0, 0, 0));
        WorkSetup setup = WorkSetup.empty(id, OVERWORLD).withHome(new WorkSetup.HomeMarker(homeSpot)).withArea(region);
        assertThrows(IllegalArgumentException.class, () -> setup.withStorage(new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(500, 64, 500), "minecraft:chest", 1)));
        assertThrows(IllegalArgumentException.class, () -> setup.withArea(new WorkSetup.WorkRegion(0, 0, 15, 15)), "home must stay inside a new area");
        var patchOutside = new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(90, 60, 90), new WorkSetup.BlockPoint(160, 61, 95), "minecraft:oak_log");
        assertThrows(IllegalArgumentException.class, () -> setup.withPatch(patchOutside));
        assertThrows(IllegalArgumentException.class, () -> new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(0, 0, 0), new WorkSetup.BlockPoint(63, 63, 63), "minecraft:oak_log"));
        WorkSetup complete = setup.withStorage(new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(102, 64, 100), "minecraft:chest", 7))
                .withPatch(new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(90, 60, 90), new WorkSetup.BlockPoint(105, 70, 105), "minecraft:oak_log"));
        assertTrue(complete.readyForChores());
        assertEquals(4, complete.revision());
    }

    @Test
    void editPolicyBlocksEverythingOutsideAuthorityAndEverythingWhileD4IsDeferred() {
        WorkSetup setup = WorkSetup.empty(id, OVERWORLD).withHome(new WorkSetup.HomeMarker(homeSpot))
                .withArea(WorkSetup.WorkRegion.around(homeSpot))
                .withStorage(new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(102, 64, 100), "minecraft:chest", 7))
                .withPatch(new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(90, 60, 90), new WorkSetup.BlockPoint(105, 70, 105), "minecraft:oak_log"));
        var deferred = EditPolicy.ServerPolicy.deferredD4();
        var fixture = new EditPolicy.ServerPolicy(true, true);
        var inPatch = new WorkSetup.BlockPoint(95, 65, 95);
        var inAreaOutsidePatch = new WorkSetup.BlockPoint(120, 65, 120);
        // Deferred D4: nothing autonomous is allowed, and the reason names the real blocker.
        assertEquals(EditPolicy.Verdict.PROTECTION_UNKNOWN, EditPolicy.evaluate(setup, EditPolicy.Kind.BREAK, OVERWORLD, inPatch, "minecraft:oak_log", deferred).verdict());
        assertEquals(EditPolicy.Verdict.EDITS_DISABLED, EditPolicy.evaluate(setup, EditPolicy.Kind.BREAK, OVERWORLD, inPatch, "minecraft:oak_log", new EditPolicy.ServerPolicy(false, true)).verdict());
        // Controlled fixture policy: authority is still narrow.
        assertTrue(EditPolicy.evaluate(setup, EditPolicy.Kind.BREAK, OVERWORLD, inPatch, "minecraft:oak_log", fixture).allowed());
        assertEquals(EditPolicy.Verdict.WRONG_RESOURCE, EditPolicy.evaluate(setup, EditPolicy.Kind.BREAK, OVERWORLD, inPatch, "minecraft:birch_log", fixture).verdict());
        assertEquals(EditPolicy.Verdict.OUTSIDE_PATCH, EditPolicy.evaluate(setup, EditPolicy.Kind.BREAK, OVERWORLD, inAreaOutsidePatch, "minecraft:oak_log", fixture).verdict(),
                "a matching log outside the patch is never permission");
        assertEquals(EditPolicy.Verdict.OUTSIDE_AREA, EditPolicy.evaluate(setup, EditPolicy.Kind.PLACE, OVERWORLD, new WorkSetup.BlockPoint(900, 65, 900), null, fixture).verdict());
        assertEquals(EditPolicy.Verdict.WRONG_DIMENSION, EditPolicy.evaluate(setup, EditPolicy.Kind.PLACE, "minecraft:the_nether", homeSpot, null, fixture).verdict());
        assertTrue(EditPolicy.evaluate(setup, EditPolicy.Kind.CONTAINER, OVERWORLD, new WorkSetup.BlockPoint(102, 64, 100), "minecraft:chest", fixture).allowed());
        assertEquals(EditPolicy.Verdict.NOT_ASSIGNED_STORAGE, EditPolicy.evaluate(setup, EditPolicy.Kind.CONTAINER, OVERWORLD, new WorkSetup.BlockPoint(102, 64, 100), "minecraft:barrel", fixture).verdict(),
                "a replaced container is not the assigned one");
        assertEquals(EditPolicy.Verdict.NOT_ASSIGNED_STORAGE, EditPolicy.evaluate(setup, EditPolicy.Kind.CONTAINER, OVERWORLD, new WorkSetup.BlockPoint(103, 64, 100), "minecraft:chest", fixture).verdict());
    }
}
