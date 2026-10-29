package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks what a player remembers about nearby blocks: it finds the nearest matching block
 * first, with ties settled the same way every time, forgets areas seen too long ago while an earlier copy
 * stays as it was, and records one distance sum that goes wrong only at coordinates far outside any real
 * world.
 */
class SupportBatch04ObservationsTest {

    private final String log="minecraft:oak_log";
    private BlockObservations facts(){
        var a=new BlockObservation(new WorkSetup.BlockPoint(3,0,0),log,100);var b=new BlockObservation(new WorkSetup.BlockPoint(-1,0,0),log,110);var c=new BlockObservation(new WorkSetup.BlockPoint(1,0,0),log,110);
        return BlockObservations.empty("minecraft:overworld",Set.of(log)).withSection(a.section(),new BlockObservations.SectionFacts(100,Map.of(log,2),List.of(a,c)))
            .withSection(b.section(),new BlockObservations.SectionFacts(110,Map.of(log,1),List.of(b)));
    }
    @Test void nearestUsesDistanceThenCoordinatesAndRespectsFilterAndLimit() {
        var f=facts();var p=new WorkSetup.BlockPoint(0,0,0);assertEquals(List.of(-1,1),f.nearest(log,p,2).stream().map(o->o.position().x()).toList());
        assertEquals(1,f.nearest(log,p,10,point->point.x()>0).getFirst().position().x());assertTrue(f.nearest(log,p,0).isEmpty());assertEquals(3,f.matchCount(log));
    }
    @Test void freshnessBoundaryDropsOnlyOlderSectionsAndKeepsOriginalSnapshot() {
        var f=facts();var current=f.dropOlderThan(120,20);assertEquals(2,current.sections().size());var next=f.dropOlderThan(121,20);
        assertEquals(1,next.sections().size());assertEquals(2,f.sections().size());assertEquals(110,next.oldestObservationMillis().orElseThrow());
    }
    @Test void extremeObservationDistanceCharacterizesIntSubtractionOverflowOnly() {
        // Deliberately outside Minecraft's coordinate domain; production change is deferred.
        var observation=new BlockObservation(new WorkSetup.BlockPoint(Integer.MAX_VALUE,0,0),log,0);
        assertEquals(1,observation.squaredDistanceTo(new WorkSetup.BlockPoint(Integer.MIN_VALUE,0,0)));
    }

}
