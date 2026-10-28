package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch03CheckpointTest {

    @Test void checkpointOwnsIndependentBoundedCopies() {
        var args=new HashMap<String,String>();var progress=new HashMap<String,String>();args.put("target","a");progress.put("count","1");
        var cp=new Checkpoint("wait",UUID.randomUUID(),0,args,progress,0);args.put("target","b");progress.clear();
        assertEquals("a",cp.arguments().get("target"));assertEquals("1",cp.progress().get("count"));
        assertThrows(UnsupportedOperationException.class,()->cp.progress().put("count","2"));
    }
    @Test void exactBoundsPassButOversizedOrNullEntriesFail() {
        Map<String,String> map=new HashMap<>();for(int i=0;i<32;i++)map.put("k"+i,"v".repeat(256));
        assertDoesNotThrow(()->new Checkpoint("wait",UUID.randomUUID(),0,map,Map.of(),0));map.put("extra","v");
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("wait",UUID.randomUUID(),0,map,Map.of(),0));
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("wait",UUID.randomUUID(),-1,Map.of(),Map.of(),0));
        assertThrows(IllegalArgumentException.class,()->new Checkpoint("wait",UUID.randomUUID(),0,Map.of("x","v".repeat(257)),Map.of(),0));
    }

}
