package dev.kindred.design.planning;

import dev.kindred.design.model.SemanticDesign.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** In-memory policy for semantic fingerprints; persistence and global reservation ownership remain adapter work. */
public final class NoveltyRegistry {
    public record Reservation(String fingerprint, String projectId, Instant expiresAt) {
        public Reservation { Objects.requireNonNull(fingerprint); projectId = dev.kindred.design.model.DesignModel.id(projectId); Objects.requireNonNull(expiresAt); }
    }
    private final Map<String, Reservation> reservations = new HashMap<>();

    public static String fingerprint(Revision revision) {
        StringBuilder canonical = new StringBuilder(revision.style().id()).append('\n');
        revision.nodes().values().stream().sorted(Comparator.comparing(Node::id)).forEach(n -> canonical.append(n.id()).append(':')
                .append(n.kind()).append(':').append(n.function()).append(':').append(n.tombstone()).append('\n'));
        revision.edges().stream().sorted(Comparator.comparing(Edge::sourceId).thenComparing(e -> e.relation().name()).thenComparing(Edge::targetId))
                .forEach(e -> canonical.append(e.sourceId()).append(':').append(e.relation()).append(':').append(e.targetId()).append('\n'));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public synchronized Reservation reserve(String projectId, Revision revision, Instant now, Duration duration) {
        Objects.requireNonNull(now); Objects.requireNonNull(duration);
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofDays(7)) > 0) throw new IllegalArgumentException("Invalid reservation duration");
        purge(now); String fingerprint = fingerprint(revision); Reservation current = reservations.get(fingerprint);
        if (current != null && !current.projectId().equals(projectId)) throw new IllegalStateException("Semantic fingerprint reserved by another project");
        Reservation next = new Reservation(fingerprint, projectId, now.plus(duration)); reservations.put(fingerprint, next); return next;
    }
    public synchronized void release(String projectId, String fingerprint) {
        Reservation current = reservations.get(fingerprint); if (current != null && current.projectId().equals(projectId)) reservations.remove(fingerprint);
    }
    public synchronized int purge(Instant now) {
        int before = reservations.size(); reservations.values().removeIf(r -> !r.expiresAt().isAfter(now)); return before - reservations.size();
    }
}
