package dev.kindred.model;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch02ModelGatewayTest {

    private ModelGateway.Request req(UUID id, ModelGateway.Lane lane) {
        return new ModelGateway.Request(UUID.randomUUID(), id, lane, "fixture",
            new dev.kindred.core.ProposalFence(UUID.randomUUID(), 0, 0, Instant.ofEpochMilli(1_000_000)), List.of(), Instant.EPOCH);
    }
    @Test void cancelledCallerRetainsReservationThroughProviderFailure() {
        var provider = new CompletableFuture<ModelGateway.Response>();
        var gateway = new ModelGateway(Set.of("wait"), r -> provider);
        var result = gateway.submit(req(UUID.randomUUID(), ModelGateway.Lane.CHAT), 1, true);
        result.cancel(true); provider.completeExceptionally(new IllegalStateException("offline"));
        assertTrue(result.isCancelled()); assertEquals(0, gateway.activeCount()); assertEquals(500, gateway.globalTokensUsed());
    }
    @Test void oneCancelledCallCannotReleaseAnotherLane() {
        var calls = new ArrayList<CompletableFuture<ModelGateway.Response>>();
        var gateway = new ModelGateway(Set.of("wait"), r -> { var f = new CompletableFuture<ModelGateway.Response>(); calls.add(f); return f; });
        UUID player = UUID.randomUUID(); var first = req(player, ModelGateway.Lane.CHAT); var second = req(player, ModelGateway.Lane.PLANNER);
        var cancelled = gateway.submit(first, 1, true); gateway.submit(second, 1, true); cancelled.cancel(false);
        assertFalse(gateway.submit(req(UUID.randomUUID(), ModelGateway.Lane.CHAT), 2, true).join().success());
        calls.get(0).complete(ModelGateway.Response.success(first.requestId(), "old", List.of(), 10));
        assertEquals(1, gateway.activeCount());
        var replacement = req(player, ModelGateway.Lane.CHAT); gateway.submit(replacement, 3, true);
        calls.get(0).complete(ModelGateway.Response.success(first.requestId(), "duplicate", List.of(), 500));
        assertEquals(2, gateway.activeCount());
        calls.get(1).complete(ModelGateway.Response.success(second.requestId(), "plan", List.of("wait"), 20));
        assertEquals(1, gateway.activeCount());
        calls.get(2).complete(ModelGateway.Response.success(replacement.requestId(), "new", List.of(), 30));
        assertEquals(0, gateway.activeCount()); assertEquals(60, gateway.globalTokensUsed());
    }
    @Test void providerCancellationReleasesButUnknownUsageIsCharged() {
        var provider = new CompletableFuture<ModelGateway.Response>(); var gateway = new ModelGateway(Set.of("wait"), r -> provider);
        var caller = gateway.submit(req(UUID.randomUUID(), ModelGateway.Lane.PLANNER), 1, true); provider.cancel(false);
        assertFalse(caller.join().success()); assertEquals(0, gateway.activeCount()); assertEquals(1000, gateway.globalTokensUsed());
    }
    @Test void normalConcurrentReconciliationConservesBothReservations() {
        var calls = new ArrayList<CompletableFuture<ModelGateway.Response>>();
        var gateway = new ModelGateway(Set.of("wait"), r -> {var f=new CompletableFuture<ModelGateway.Response>();calls.add(f);return f;});
        UUID player=UUID.randomUUID(); var a=req(player,ModelGateway.Lane.CHAT);var b=req(player,ModelGateway.Lane.PLANNER);
        gateway.submit(a,1,true);gateway.submit(b,1,true);assertEquals(1500,gateway.globalTokensUsed());
        calls.get(1).complete(ModelGateway.Response.success(b.requestId(),"plan",List.of("wait"),750));
        assertEquals(1250,gateway.companionTokensUsed(player));
        calls.get(0).complete(ModelGateway.Response.success(a.requestId(),"chat",List.of(),100));assertEquals(850,gateway.globalTokensUsed());
    }
    @Test void staleAndExpiredSubmissionsNeverReachProviderOrSpendBudget() {
        var count=new AtomicInteger();var gateway=new ModelGateway(Set.of("wait"),r->{count.incrementAndGet();return new CompletableFuture<>();});
        assertFalse(gateway.submit(req(UUID.randomUUID(),ModelGateway.Lane.CHAT),1,false).join().success());
        assertFalse(gateway.submit(req(UUID.randomUUID(),ModelGateway.Lane.CHAT),1_000_001,true).join().success());
        assertEquals(0,count.get());assertEquals(0,gateway.globalTokensUsed());assertEquals(0,gateway.activeCount());
    }
    @Test void rateLimitBoundaryIncludesExactlySixtySeconds() {
        var gateway=new ModelGateway(Set.of("wait"),r->CompletableFuture.completedFuture(ModelGateway.Response.success(r.requestId(),"ok",List.of(),1)));
        UUID player=UUID.randomUUID();for(int i=0;i<6;i++)assertTrue(gateway.submit(req(player,ModelGateway.Lane.CHAT),1000,true).join().success());
        assertFalse(gateway.submit(req(player,ModelGateway.Lane.CHAT),61000,true).join().success());
        assertTrue(gateway.submit(req(player,ModelGateway.Lane.CHAT),61001,true).join().success());assertEquals(7,gateway.globalTokensUsed());
    }

}
