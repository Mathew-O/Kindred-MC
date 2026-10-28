package dev.kindred.model;

import dev.kindred.core.ProposalFence;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SupportBatch03GatewayRegressionTest {
    private static ModelGateway.Request request(UUID player, ModelGateway.Lane lane) {
        return new ModelGateway.Request(UUID.randomUUID(), player, lane, "fixture",
                new ProposalFence(UUID.randomUUID(), 0, 0, Instant.ofEpochMilli(1_000_000)),
                List.of(), Instant.EPOCH);
    }

    @Test void cancelledCallerStillReleasesCapacityWhenProviderCompletes() {
        List<CompletableFuture<ModelGateway.Response>> calls = new ArrayList<>();
        ModelGateway gateway = new ModelGateway(Set.of("wait"), req -> {
            var call = new CompletableFuture<ModelGateway.Response>(); calls.add(call); return call;
        });
        UUID player = UUID.randomUUID();
        var first = request(player, ModelGateway.Lane.CHAT);
        var result = gateway.submit(first, 1000, true);
        assertTrue(result.cancel(false));
        assertFalse(calls.getFirst().isCancelled(), "Caller cancellation cannot prove provider stopped billing");
        assertEquals(1, gateway.activeCount(), "Provider still occupies capacity until terminal");
        calls.getFirst().complete(ModelGateway.Response.success(first.requestId(), "late", List.of(), 37));
        assertTrue(result.isCancelled());
        assertEquals(0, gateway.activeCount(), "Cancelled dependent must not suppress cleanup");
        assertEquals(37, gateway.globalTokensUsed());
        var second = request(player, ModelGateway.Lane.CHAT);
        assertFalse(gateway.submit(second, 2000, true).isDone(), "Same lane must be reusable");
        calls.get(1).complete(ModelGateway.Response.success(second.requestId(), "ok", List.of(), 13));
        assertEquals(50, gateway.globalTokensUsed());
    }

    @Test void concurrentExtremeUsageCannotWrapTokenCountersAndReopenAdmission() {
        List<CompletableFuture<ModelGateway.Response>> calls = new ArrayList<>();
        ModelGateway gateway = new ModelGateway(Set.of("wait"), req -> {
            var call = new CompletableFuture<ModelGateway.Response>(); calls.add(call); return call;
        });
        UUID player = UUID.randomUUID();
        var chat = request(player, ModelGateway.Lane.CHAT);
        var planner = request(player, ModelGateway.Lane.PLANNER);
        gateway.submit(chat, 1000, true);
        gateway.submit(planner, 1000, true);
        calls.getFirst().complete(ModelGateway.Response.success(chat.requestId(), "extreme", List.of(), Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, gateway.globalTokensUsed());
        assertEquals(Integer.MAX_VALUE, gateway.companionTokensUsed(player));
        calls.get(1).complete(ModelGateway.Response.success(planner.requestId(), "plan", List.of("wait"), Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, gateway.globalTokensUsed());
        assertEquals(Integer.MAX_VALUE, gateway.companionTokensUsed(player));
        assertFalse(gateway.submit(request(player, ModelGateway.Lane.CHAT), 2000, true).join().success());
        assertFalse(gateway.submit(request(UUID.randomUUID(), ModelGateway.Lane.CHAT), 2000, true).join().success());
        assertEquals(2, calls.size());
        assertEquals(0, gateway.activeCount());
    }
}
