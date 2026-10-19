package dev.kindred.model;

import dev.kindred.core.ProposalFence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ModelGatewayTest {
    private static final Set<String> SKILLS = Set.of("move_to", "return_home", "wait", "gather_logs", "deposit");

    private ModelGateway.Request request(ModelGateway.Lane lane) {
        return new ModelGateway.Request(UUID.randomUUID(), UUID.randomUUID(), lane, "test",
                new ProposalFence(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(30)),
                List.of(), Instant.now());
    }

    @Test void validatesReturnedPlanEvenWhenInputHasNoSteps() {
        for (List<String> steps : List.of(List.<String>of(), List.of("invent_items"),
                java.util.Collections.nCopies(7, "wait"))) {
            ModelGateway gateway = new ModelGateway(SKILLS, req -> CompletableFuture.completedFuture(
                    ModelGateway.Response.success(req.requestId(), "plan", steps, 100)));
            assertFalse(gateway.submit(request(ModelGateway.Lane.PLANNER), System.currentTimeMillis(), true).join().success());
            assertEquals(0, gateway.activeCount());
            assertEquals(100, gateway.globalTokensUsed()); // Invalid output still costs tokens.
        }
        ModelGateway gateway = new ModelGateway(SKILLS, req -> CompletableFuture.completedFuture(
                ModelGateway.Response.success(req.requestId(), "plan", List.of("gather_logs", "deposit"), 100)));
        assertTrue(gateway.submit(request(ModelGateway.Lane.PLANNER), System.currentTimeMillis(), true).join().success());
    }

    @Test void chatCannotReturnActionsAndResponsesMustMatchRequests() {
        ModelGateway chat = new ModelGateway(SKILLS, req -> CompletableFuture.completedFuture(
                ModelGateway.Response.success(req.requestId(), "do this", List.of("gather_logs"), 20)));
        assertFalse(chat.submit(request(ModelGateway.Lane.CHAT), System.currentTimeMillis(), true).join().success());
        ModelGateway mismatch = new ModelGateway(SKILLS, req -> CompletableFuture.completedFuture(
                ModelGateway.Response.success(UUID.randomUUID(), "wrong request", List.of(), 20)));
        assertFalse(mismatch.submit(request(ModelGateway.Lane.CHAT), System.currentTimeMillis(), true).join().success());
    }

    @Test void missingOrFailedProvidersReleaseCapacityAndRetainUnknownUsageCharge() {
        List<ModelGateway.Provider> providers = List.of(req -> null,
                req -> CompletableFuture.completedFuture(null),
                req -> CompletableFuture.failedFuture(new IllegalStateException("offline")),
                req -> { throw new IllegalStateException("offline"); });
        for (ModelGateway.Provider provider : providers) {
            ModelGateway gateway = new ModelGateway(SKILLS, provider);
            assertFalse(gateway.submit(request(ModelGateway.Lane.CHAT), System.currentTimeMillis(), true).join().success());
            assertEquals(0, gateway.activeCount());
            assertEquals(ModelGateway.Lane.CHAT.reservedTokens(), gateway.globalTokensUsed());
        }
    }

    @Test
    void unconfiguredProviderRejectsImmediately() {
        ModelGateway gateway = new ModelGateway(SKILLS, null);
        UUID comp = UUID.randomUUID();
        ModelGateway.Request req = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "Hello",
                new ProposalFence(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(60)),
                List.of(), Instant.now());

        ModelGateway.Response resp = gateway.submit(req, System.currentTimeMillis(), true).join();
        assertFalse(resp.success());
        assertTrue(resp.error().orElse("").contains("no model provider is configured"));
    }

    @Test
    void rejectsConcurrentRequestInSameLaneForSameCompanion() {
        CompletableFuture<ModelGateway.Response> pendingCall = new CompletableFuture<>();
        ModelGateway.Provider provider = request -> pendingCall;
        ModelGateway gateway = new ModelGateway(SKILLS, provider);

        UUID comp = UUID.randomUUID();
        ProposalFence fence = new ProposalFence(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(60));
        ModelGateway.Request req1 = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "msg 1", fence, List.of(), Instant.now());
        ModelGateway.Request req2 = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "msg 2", fence, List.of(), Instant.now());

        long now = 1000L;
        CompletableFuture<ModelGateway.Response> f1 = gateway.submit(req1, now, true);
        CompletableFuture<ModelGateway.Response> f2 = gateway.submit(req2, now, true);

        ModelGateway.Response r2 = f2.join();
        assertFalse(r2.success());
        assertTrue(r2.error().orElse("").contains("already in-flight"));
        assertFalse(f1.isDone());

        // Completing f1 releases the lane
        pendingCall.complete(ModelGateway.Response.success(req1.requestId(), "reply", List.of(), 50));
        ModelGateway.Response r1 = f1.join();
        assertTrue(r1.success());

        // Now a new request in the same lane can proceed
        CompletableFuture<ModelGateway.Response> f3 = gateway.submit(req2, now + 100, true);
        assertNotNull(f3);
    }

    @Test
    void enforcesCompanionRateLimit() {
        ModelGateway.Provider provider = request -> CompletableFuture.completedFuture(
                ModelGateway.Response.success(request.requestId(), "ok", List.of(), 10));
        ModelGateway gateway = new ModelGateway(SKILLS, provider);

        UUID comp = UUID.randomUUID();
        ProposalFence fence = new ProposalFence(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(60));

        long now = 10_000L;
        for (int i = 0; i < ModelGateway.MAX_COMPANION_RATE_PER_MINUTE; i++) {
            ModelGateway.Request req = new ModelGateway.Request(
                    UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "msg " + i, fence, List.of(), Instant.now());
            ModelGateway.Response resp = gateway.submit(req, now + i * 100, true).join();
            assertTrue(resp.success(), "Request " + i + " must succeed");
        }

        // 7th request within the same minute is rate-limited
        ModelGateway.Request overflow = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "overflow", fence, List.of(), Instant.now());
        ModelGateway.Response rejected = gateway.submit(overflow, now + 1000, true).join();
        assertFalse(rejected.success());
        assertTrue(rejected.error().orElse("").contains("companion request rate limit exceeded"));

        // After 61 seconds, request passes
        ModelGateway.Response later = gateway.submit(overflow, now + 61_000, true).join();
        assertTrue(later.success());
    }

    @Test
    void enforcesTokenBudgetCap() {
        ModelGateway.Provider provider = request -> CompletableFuture.completedFuture(
                ModelGateway.Response.success(request.requestId(), "ok", List.of("wait"), 25_000));
        ModelGateway gateway = new ModelGateway(SKILLS, provider);

        UUID comp = UUID.randomUUID();
        ProposalFence fence = new ProposalFence(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(60));

        // Use 25,000 tokens
        ModelGateway.Request req1 = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.PLANNER, "p1", fence, List.of("wait"), Instant.now());
        assertTrue(gateway.submit(req1, 1000L, true).join().success());

        // Use another 25,000 tokens -> total 50,000
        ModelGateway.Request req2 = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.PLANNER, "p2", fence, List.of("wait"), Instant.now());
        assertTrue(gateway.submit(req2, 2000L, true).join().success());

        // Next request exceeds 50,000 companion budget
        ModelGateway.Request req3 = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "p3", fence, List.of(), Instant.now());
        ModelGateway.Response rejected = gateway.submit(req3, 3000L, true).join();
        assertFalse(rejected.success());
        assertTrue(rejected.error().orElse("").contains("daily token budget exhausted"));
    }

    @Test
    void rejectsInvalidPlanStepsStaleRevisionsAndExpiry() {
        ModelGateway.Provider provider = request -> CompletableFuture.completedFuture(
                ModelGateway.Response.success(request.requestId(), "ok", request.plannedSteps(), 100));
        ModelGateway gateway = new ModelGateway(SKILLS, provider);

        UUID comp = UUID.randomUUID();

        // 1. Unknown skill in plan
        ProposalFence validFence = new ProposalFence(UUID.randomUUID(), 0, 0, Instant.now().plusSeconds(60));
        ModelGateway.Request unknownSkill = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.PLANNER, "plan", validFence,
                List.of("move_to", "slay_ender_dragon"), Instant.now());
        ModelGateway.Response r1 = gateway.submit(unknownSkill, 1000L, true).join();
        assertFalse(r1.success());
        assertTrue(r1.error().orElse("").contains("unknown skill"));

        // 2. Stale revision
        ModelGateway.Request stale = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "chat", validFence, List.of(), Instant.now());
        ModelGateway.Response r2 = gateway.submit(stale, 1000L, false).join();
        assertFalse(r2.success());
        assertTrue(r2.error().orElse("").contains("revisions are stale"));

        // 3. Expired deadline
        ProposalFence expiredFence = new ProposalFence(UUID.randomUUID(), 0, 0, Instant.ofEpochMilli(500L));
        ModelGateway.Request expired = new ModelGateway.Request(
                UUID.randomUUID(), comp, ModelGateway.Lane.CHAT, "chat", expiredFence, List.of(), Instant.now());
        ModelGateway.Response r3 = gateway.submit(expired, 1000L, true).join();
        assertFalse(r3.success());
        assertTrue(r3.error().orElse("").contains("proposal has expired"));
    }
}
