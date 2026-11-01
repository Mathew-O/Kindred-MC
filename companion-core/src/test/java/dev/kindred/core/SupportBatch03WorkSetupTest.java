package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks the work area a player is given: its edges count correctly at negative coordinates,
 * a matching block gives no permission to break it until a work area is set up, and a new work area may not
 * quietly leave out the saved home.
 */
class SupportBatch03WorkSetupTest {

    @Test void negativeCoordinateChunkEdgesAndVolumeAreInclusive() {
        assertEquals(4,new WorkSetup.WorkRegion(-1,-1,0,0).chunkCount());
        var patch=new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(-16,-16,-16),new WorkSetup.BlockPoint(-1,-1,-1),"minecraft:oak_log");
        assertTrue(patch.contains(new WorkSetup.BlockPoint(-1,-1,-1)));assertFalse(patch.contains(new WorkSetup.BlockPoint(0,-1,-1)));
        assertThrows(IllegalArgumentException.class,()->new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(0,0,0),new WorkSetup.BlockPoint(16,15,15),"minecraft:oak_log"));
    }
    @Test void matchingResourceIsNotAuthorityWithoutSetup() {
        var setup=WorkSetup.empty(UUID.randomUUID(),"minecraft:overworld");var p=new WorkSetup.BlockPoint(0,64,0);
        assertFalse(EditPolicy.evaluate(setup,EditPolicy.Kind.BREAK,"minecraft:overworld",p,"minecraft:oak_log",new EditPolicy.ServerPolicy(true,true)).allowed());
        assertFalse(setup.readyForChores());assertEquals(0,setup.revision());
    }
    @Test void areaReplacementCannotSilentlyExcludeSavedHome() {
        var setup=WorkSetup.empty(UUID.randomUUID(),"minecraft:overworld").withHome(new WorkSetup.HomeMarker(new WorkSetup.BlockPoint(-1,64,-1)));
        assertThrows(IllegalArgumentException.class,()->setup.withArea(new WorkSetup.WorkRegion(0,0,15,15)));assertNull(setup.area());
    }

}
