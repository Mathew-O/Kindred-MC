package dev.kindred.core.wire;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * In plain terms: how many messages the Wire refused since start, by why and on which channel, so a long run shows
 * news that never went out (a join refused as a duplicate, a channel's minute spent) without any of what it said.
 *
 * Technical: Counts only, keyed by the refusal and the channel's registered id; never a message's kind, sender, scope
 * or facts, so the console line it feeds leaks nothing the audience rules withhold (F-34e). Not thread-safe: the Wire
 * is sent on the server thread, and a snapshot is taken there.
 */
public final class RefusalTally {
    private final TreeMap<String, Long> counts = new TreeMap<>();
    private long total;

    /** One refusal of {@code reason} on {@code channel}. */
    public void count(Delivery.Refusal reason, Channel channel) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(channel);
        counts.merge(reason.name() + " on " + channel.id(), 1L, Long::sum);
        total++;
    }

    /** Every refusal counted since start. */
    public long total() { return total; }

    /** The counts now, by "REASON on channel", in order. */
    public Map<String, Long> snapshot() {
        return new TreeMap<>(counts);
    }

    /** For the cost line: {@code 3 refused since start (DUPLICATE on faction 2, BUDGET on team 1)}, or {@code none refused since start}. */
    public String describe() {
        if (total == 0) return "none refused since start";
        var parts = new StringBuilder();
        snapshot().forEach((k, v) -> parts.append(parts.isEmpty() ? "" : ", ").append(k).append(' ').append(v));
        return total + " refused since start (" + parts + ")";
    }
}
