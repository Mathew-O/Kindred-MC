package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch04SectionTest {

    @Test void signedBlockCoordinatesMapToCorrectSectionsAtEdges() {
        for(int x:new int[]{Integer.MIN_VALUE,-17,-16,-1,0,15,16,Integer.MAX_VALUE}){
            var point=new WorkSetup.BlockPoint(x,x,x);var key=SectionKey.of(point);assertEquals(Math.floorDiv(x,16),key.x());assertTrue(key.contains(point));assertTrue(key.minBlockX()<=x);
        }
    }
    @Test void plansClipToAreaHeightAndNearestFirstWithStableTies() {
        var center=new WorkSetup.BlockPoint(0,0,0);var area=new WorkSetup.WorkRegion(-16,-16,31,31);
        var plan=SectionPlanner.plan(area,center,new SectionPlanner.Range(1,2),0,2);
        assertEquals(18,plan.size());assertEquals(new SectionKey(0,0,0),plan.getFirst());
        for(int i=1;i<plan.size();i++)assertTrue(plan.get(i-1).squaredDistance(new SectionKey(0,0,0))<=plan.get(i).squaredDistance(new SectionKey(0,0,0)));
        assertEquals(plan,SectionPlanner.plan(area,center,new SectionPlanner.Range(1,2),0,2));
        assertTrue(SectionPlanner.plan(area,center,SectionPlanner.DEFAULT,2,2).isEmpty());assertThrows(UnsupportedOperationException.class,()->plan.clear());
    }
    @Test void extremeSectionDistanceCharacterizesIntSubtractionOverflowOnly() {
        // Out-of-domain arithmetic reproducer, not evidence of a Minecraft-coordinate defect.
        var a=new SectionKey(Integer.MAX_VALUE,0,0);var b=new SectionKey(Integer.MIN_VALUE,0,0);
        assertEquals(1,a.squaredDistance(b));assertNotEquals(1L,(long)Integer.MAX_VALUE-Integer.MIN_VALUE);
    }

}
