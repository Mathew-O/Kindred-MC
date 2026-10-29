package dev.kindred.core;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a skill finishes: it waits for the body to settle without running again, a stop
 * while it is finishing makes it cancelled even if the body settles later, and saved progress from a
 * different run is refused before anything starts.
 */
class SupportBatch03RunnerTest {

    private final AtomicLong nanos=new AtomicLong();private final AtomicBoolean settled=new AtomicBoolean(true);
    private final UUID owner=UUID.randomUUID();private final Companion c=Companion.create(UUID.randomUUID(),owner,"fixture",UUID.randomUUID(),"minecraft:overworld");
    private final ActionArbiter a=new ActionArbiter(c,UUID.randomUUID(),new BodyAdapter(){public void start(ActionLease l){}public void cancel(ActionLease l){}public boolean isSettled(ActionLease l){return settled.get();}},Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),nanos::get,Duration.ofMillis(500));
    private final SkillRunner r=new SkillRunner(a,nanos::get);
    private final SkillDescriptor d=new SkillDescriptor("wait",Set.of(),Set.of(),Set.of(),Set.of(),Duration.ofSeconds(10),Duration.ofSeconds(1),0);
    private final AtomicInteger ticks=new AtomicInteger(),halts=new AtomicInteger();
    private SkillBody body(boolean complete){return new SkillBody(){public Step tick(Map<String,String> args,Optional<Checkpoint> cp,boolean fresh){ticks.incrementAndGet();return complete?Step.completed(Map.of("done","true")):Step.running(Map.of(),false);}public void halt(){halts.incrementAndGet();}public boolean settled(){return settled.get();}};}
    @Test void completionWaitsForBodyAndDoesNotTickAgain() {
        r.start(d,Map.of(),body(true),Actor.player(owner),UUID.randomUUID(),Optional.empty());settled.set(false);r.tick();
        assertEquals(SkillRunner.State.COMPLETING,r.state());for(int i=0;i<10;i++)r.tick();assertEquals(1,ticks.get());
        settled.set(true);r.tick();assertEquals(SkillRunner.State.COMPLETED,r.state());for(int i=0;i<10;i++)r.tick();assertEquals(1,ticks.get());
    }
    @Test void stopWhileCompletingCannotBecomeSuccessAfterLateSettlement() {
        r.start(d,Map.of(),body(true),Actor.player(owner),UUID.randomUUID(),Optional.empty());settled.set(false);r.tick();a.stop(Actor.player(owner));r.tick();
        settled.set(true);for(int i=0;i<5;i++)r.tick();assertEquals(SkillRunner.State.CANCELLED,r.state());assertEquals(1,ticks.get());assertEquals(1,halts.get());
    }
    @Test void foreignCheckpointIsRejectedBeforeAcquiringBody() {
        var cp=new Checkpoint("wait",UUID.randomUUID(),1,Map.of(),Map.of(),0);
        assertThrows(IllegalArgumentException.class,()->r.start(d,Map.of(),body(false),Actor.player(owner),UUID.randomUUID(),Optional.of(cp)));
        assertEquals(SkillRunner.State.IDLE,r.state());assertEquals(0,ticks.get());
    }

}
