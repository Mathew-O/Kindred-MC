package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch04PerceptionTest {

    private final String log="minecraft:oak_log";private final SectionKey a=new SectionKey(0,0,0),b=new SectionKey(1,0,0);
    private BlockPerception perception(){return new BlockPerception("minecraft:overworld",new BlockPerception.Budget(2,1,3,2),()->100,()->0);}
    private BlockPerception.SectionView view(boolean empty,boolean palette,AtomicInteger visits){return new BlockPerception.SectionView(){
        public boolean isEmpty(){return empty;}public boolean mayContain(Set<String> ids){assertFalse(empty,"empty short-circuits palette");return palette;}
        public void visitMatches(Set<String> ids,BlockPerception.MatchVisitor visitor){assertTrue(palette);for(int i=0;i<20;i++){visits.incrementAndGet();if(!visitor.visit(new WorkSetup.BlockPoint(i,0,0),log,true))break;}}
    };}
    @Test void detailedAndMatchBudgetsBoundOneTickAndCompletePassLater() {
        var p=perception();var visited=new AtomicInteger();p.retarget(List.of(a,b),Set.of(log));
        p.tick(key->view(false,true,visited));assertEquals(1,p.stats().scanned());assertEquals(3,visited.get());assertEquals(2,p.snapshot().exposedCount(log));assertEquals(1,p.cursor());
        p.tick(key->view(false,true,visited));assertEquals(1,p.stats().passes());assertEquals(6,visited.get());assertEquals(2,p.stats().lastPassTicks());
    }
    @Test void emptyPaletteAndUnloadedSectionsNeverWalkBlocks() {
        var p=perception();var visits=new AtomicInteger();p.retarget(List.of(a,b,new SectionKey(2,0,0)),Set.of(log));
        BlockPerception.SectionSource source=key->key.equals(a)?null:key.equals(b)?view(true,false,visits):view(false,false,visits);
        p.tick(source);p.tick(source);assertEquals(0,visits.get());assertEquals(1,p.stats().unloaded());assertEquals(1,p.stats().empty());assertEquals(1,p.stats().skippedByPalette());
    }
    @Test void unloadingAndRetargetingRemoveFactsInsteadOfReusingStaleKnowledge() {
        var p=perception();p.retarget(List.of(a),Set.of(log));p.tick(key->view(false,true,new AtomicInteger()));var old=p.snapshot();assertEquals(3,old.matchCount(log));
        p.tick(key->null);assertEquals(0,p.snapshot().matchCount(log));assertEquals(3,old.matchCount(log));
        p.retarget(List.of(b),Set.of(log));assertTrue(p.snapshot().sections().isEmpty());
        p.tick(key->view(false,true,new AtomicInteger()));p.retarget(List.of(b),Set.of("minecraft:stone"));assertTrue(p.snapshot().sections().isEmpty());
    }
    @Test void idleAndUnchangedTargetDoNoWorkOrResetProgress() {
        var p=perception();p.tick(key->{fail("idle queried source");return null;});assertEquals(0,p.stats().ticks());
        var plan=new ArrayList<>(List.of(a,b));p.retarget(plan,Set.of(log));plan.clear();p.tick(key->view(false,true,new AtomicInteger()));
        assertFalse(p.retarget(List.of(a,b),Set.of(log)));assertEquals(1,p.cursor());assertEquals(2,p.plan().size());
    }

}
