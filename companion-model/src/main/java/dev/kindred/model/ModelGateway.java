package dev.kindred.model;

import dev.kindred.core.ProposalFence;
import dev.kindred.core.ProposalGate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded asynchronous gateway for model chat and planning proposals (F-19).
 * Enforces lane separation (CHAT vs PLANNER), concurrency bounds (1 per lane per companion, 2 globally),
 * rate limits (6/min companion, 12/min global), daily token budgets (50k companion, 100k global),
 * and skill-name validation via ProposalGate. This is not yet a runtime integration:
 * deadlines, argument schemas, fresh authority checks and durable billing remain F-19 work.
 */
public final class ModelGateway {
    public enum Lane {
        CHAT(Duration.ofSeconds(8), 500),
        PLANNER(Duration.ofSeconds(15), 1000);

        private final Duration timeout;
        private final int reservedTokens;

        Lane(Duration timeout, int reservedTokens) {
            this.timeout = timeout;
            this.reservedTokens = reservedTokens;
        }

        public Duration timeout() { return timeout; }
        public int reservedTokens() { return reservedTokens; }
    }

    public record Request(
            UUID requestId,
            UUID companionId,
            Lane lane,
            String prompt,
            ProposalFence fence,
            List<String> plannedSteps,
            Instant created) {
        public Request {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(companionId, "companionId");
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(fence, "fence");
            Objects.requireNonNull(created, "created");
            plannedSteps = plannedSteps == null ? List.of() : List.copyOf(plannedSteps);
        }
    }

    public record Response(
            UUID requestId,
            boolean success,
            String text,
            List<String> validatedSteps,
            int tokensUsed,
            Optional<String> error) {
        public Response {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(error, "error");
            validatedSteps = validatedSteps == null ? List.of() : List.copyOf(validatedSteps);
        }

        public static Response success(UUID requestId, String text, List<String> steps, int tokens) {
            return new Response(requestId, true, text, steps, tokens, Optional.empty());
        }

        public static Response rejected(UUID requestId, String reason) {
            return new Response(requestId, false, "", List.of(), 0, Optional.of(reason));
        }
    }

    public interface Provider {
        CompletableFuture<Response> call(Request request);
    }

    public static final int MAX_COMPANION_RATE_PER_MINUTE = 6;
    public static final int MAX_GLOBAL_RATE_PER_MINUTE = 12;
    public static final int COMPANION_DAILY_TOKEN_BUDGET = 50_000;
    public static final int GLOBAL_DAILY_TOKEN_BUDGET = 100_000;
    public static final int MAX_GLOBAL_CONCURRENT = 2;

    private final ProposalGate proposalGate;
    private final Provider provider;
    private final Map<UUID, Map<Lane, Request>> activeCompanionRequests = new HashMap<>();
    private final Map<UUID, Deque<Long>> companionRequestTimestamps = new HashMap<>();
    private final Deque<Long> globalRequestTimestamps = new ArrayDeque<>();
    private final Map<UUID, Integer> companionTokenUsage = new HashMap<>();
    private int globalTokenUsage = 0;
    private int globalActiveCount = 0;

    public ModelGateway(Set<String> installedSkills, Provider provider) {
        this.proposalGate = new ProposalGate(installedSkills);
        this.provider = provider;
    }

    public synchronized CompletableFuture<Response> submit(Request request, long nowMs, boolean revisionsCurrent) {
        Objects.requireNonNull(request, "request");
        UUID companionId = request.companionId();
        Lane lane = request.lane();

        // 1. Provider availability check
        if (provider == null) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "no model provider is configured"));
        }

        // 2. Concurrency check: max 1 per lane per companion, max 2 globally
        Map<Lane, Request> companionActive = activeCompanionRequests.computeIfAbsent(companionId, k -> new HashMap<>());
        if (companionActive.containsKey(lane)) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "a request is already in-flight for this lane"));
        }
        if (globalActiveCount >= MAX_GLOBAL_CONCURRENT) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "global model concurrency limit reached"));
        }

        // 3. Rate limit check (6/min companion, 12/min global)
        purgeOlderThan(globalRequestTimestamps, nowMs - 60_000);
        if (globalRequestTimestamps.size() >= MAX_GLOBAL_RATE_PER_MINUTE) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "global request rate limit exceeded"));
        }
        Deque<Long> companionTimestamps = companionRequestTimestamps.computeIfAbsent(companionId, k -> new ArrayDeque<>());
        purgeOlderThan(companionTimestamps, nowMs - 60_000);
        if (companionTimestamps.size() >= MAX_COMPANION_RATE_PER_MINUTE) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "companion request rate limit exceeded"));
        }

        // 4. Token budget check
        int estimatedTokens = lane.reservedTokens();
        int currentCompanionTokens = companionTokenUsage.getOrDefault(companionId, 0);
        if (currentCompanionTokens + estimatedTokens > COMPANION_DAILY_TOKEN_BUDGET) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "companion daily token budget exhausted"));
        }
        if (globalTokenUsage + estimatedTokens > GLOBAL_DAILY_TOKEN_BUDGET) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "global daily token budget exhausted"));
        }

        // 5. Proposal validation for PLANNER lane
        boolean expired = Instant.ofEpochMilli(nowMs).isAfter(request.fence().deadline());
        if (lane == Lane.PLANNER && !request.plannedSteps().isEmpty()) {
            List<String> rejections = proposalGate.reject(request.plannedSteps(), true, revisionsCurrent, expired);
            if (!rejections.isEmpty()) {
                return CompletableFuture.completedFuture(Response.rejected(request.requestId(), String.join("; ", rejections)));
            }
        } else if (expired) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "the proposal has expired"));
        } else if (!revisionsCurrent) {
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "the proposal's revisions are stale"));
        }

        // Register active request & reserve rate/budget slots
        companionActive.put(lane, request);
        globalActiveCount++;
        companionTimestamps.addLast(nowMs);
        globalRequestTimestamps.addLast(nowMs);
        companionTokenUsage.put(companionId, currentCompanionTokens + estimatedTokens);
        globalTokenUsage += estimatedTokens;

        CompletableFuture<Response> future;
        try {
            future = Objects.requireNonNull(provider.call(request), "provider future");
        } catch (Throwable t) {
            release(companionId, lane, estimatedTokens, estimatedTokens);
            return CompletableFuture.completedFuture(Response.rejected(request.requestId(), "provider error: " + t.getMessage()));
        }

        return future.handle((resp, failure) -> {
            synchronized (this) {
                int actualTokens = resp != null && resp.tokensUsed() > 0 ? resp.tokensUsed() : estimatedTokens;
                release(companionId, lane, estimatedTokens, actualTokens);
                if (failure != null) {
                    return Response.rejected(request.requestId(), "model call failed: " + failure.getMessage());
                }
                return validateResponse(request, resp);
            }
        });
    }

    private Response validateResponse(Request request, Response response) {
        if (response == null || !request.requestId().equals(response.requestId())) {
            return Response.rejected(request.requestId(), "missing or mismatched provider response");
        }
        if (!response.success()) return Response.rejected(request.requestId(), "provider rejected the request");
        if (response.text() == null || response.tokensUsed() < 0 || response.error().isPresent()) {
            return Response.rejected(request.requestId(), "malformed provider response");
        }
        if (request.lane() == Lane.CHAT && !response.validatedSteps().isEmpty()) {
            return Response.rejected(request.requestId(), "chat cannot propose body actions");
        }
        if (request.lane() == Lane.PLANNER) {
            // Provider field names are not proof: validate the actual output, not just the input hint.
            List<String> rejections = proposalGate.reject(response.validatedSteps(), true, true, false);
            if (!rejections.isEmpty()) return Response.rejected(request.requestId(), String.join("; ", rejections));
        }
        return response; // Caller must still revalidate current authority and arguments before dispatch.
    }

    private synchronized void release(UUID companionId, Lane lane, int reservedTokens, int actualTokens) {
        Map<Lane, Request> companionActive = activeCompanionRequests.get(companionId);
        if (companionActive != null) {
            companionActive.remove(lane);
        }
        globalActiveCount = Math.max(0, globalActiveCount - 1);
        int diff = actualTokens - reservedTokens;
        if (diff != 0) {
            int compUsage = companionTokenUsage.getOrDefault(companionId, 0);
            companionTokenUsage.put(companionId, Math.max(0, compUsage + diff));
            globalTokenUsage = Math.max(0, globalTokenUsage + diff);
        }
    }

    public synchronized int activeCount() { return globalActiveCount; }
    public synchronized int globalTokensUsed() { return globalTokenUsage; }
    public synchronized int companionTokensUsed(UUID companionId) { return companionTokenUsage.getOrDefault(companionId, 0); }

    public synchronized void resetDailyBudgets() {
        companionTokenUsage.clear();
        globalTokenUsage = 0;
    }

    private static void purgeOlderThan(Deque<Long> queue, long thresholdMs) {
        while (!queue.isEmpty() && queue.peekFirst() < thresholdMs) {
            queue.pollFirst();
        }
    }
}
