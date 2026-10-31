package dev.kindred.model;

import java.time.Duration;

/**
 * In plain terms: the two kinds of request an AI model is sent, chat and planning, each with its own deadline and the
 * tokens set aside for it.
 *
 * Technical: The model lanes (AI-05): chat and planning, with their deadlines and reservations, as
 * {@link ReasoningPolicy} keys them. The gateway's own admission, rate limits and daily budgets are retired (F-29):
 * they duplicated {@code StrategistBridge}'s, which is what every request goes through, and nothing called them.
 * {@link dev.kindred.core.ProposalGate} checks a returned plan's skills and fences, as F-29's rescue plans use it.
 */
public final class ModelGateway {
    private ModelGateway() { }

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
}
