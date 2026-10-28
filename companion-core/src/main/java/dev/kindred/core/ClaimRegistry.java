package dev.kindred.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * In plain terms: the dibs list. Before a player works on a block, a chest or an area, it claims it, so two
 * AI players never fight over the same tree or chest. Claims run out after a while, and a newer claim always
 * beats an older one.
 *
 * Technical: Central, all-or-nothing coordination of block/container/region keys between companions. A claim
 * carries a monotonically increasing fencing token and an expiry; a stale holder can neither renew nor
 * release what a newer holder owns. Claims coordinate companions only: players still change the world, so the
 * adapter rechecks identity, permission and version immediately before mutation.
 */
public final class ClaimRegistry {
    public static final long MAX_TTL_MILLIS = 5 * 60_000L;

    /** A successful claim: who holds it, on what, and until when. */
    public record Grant(UUID taskId, UUID epoch, Set<String> keys, long token, long expiresAt) {
        public Grant {
            Objects.requireNonNull(taskId);
            Objects.requireNonNull(epoch);
            keys = Set.copyOf(keys);
            if (keys.isEmpty()) throw new IllegalArgumentException("A grant covers at least one key");
        }
    }

    private record Claim(UUID taskId, UUID epoch, long token, long expiresAt) { }

    private final Map<String, Claim> claims = new HashMap<>();
    private long nextToken = 1;

    /** Acquire every key or none. Keys this task already holds are re-leased under the new token. */
    public Optional<Grant> acquire(UUID taskId, UUID epoch, Collection<String> keys, long now, long ttlMillis) {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(epoch);
        requireTtl(ttlMillis);
        Set<String> wanted = canonical(keys);
        for (String key : wanted) {
            Claim current = claims.get(key);
            if (current != null && !expired(current, now) && !current.taskId().equals(taskId)) return Optional.empty();
        }
        long token = nextToken++;
        long expiresAt = now + ttlMillis;
        for (String key : wanted) claims.put(key, new Claim(taskId, epoch, token, expiresAt));
        return Optional.of(new Grant(taskId, epoch, wanted, token, expiresAt));
    }

    /** Renew before an action outlives its lease; an expired or superseded grant cannot be renewed. */
    public Optional<Grant> renew(Grant grant, long now, long ttlMillis) {
        Objects.requireNonNull(grant);
        requireTtl(ttlMillis);
        if (!holdsAll(grant, now)) return Optional.empty();
        long expiresAt = now + ttlMillis;
        for (String key : grant.keys()) claims.put(key, new Claim(grant.taskId(), grant.epoch(), grant.token(), expiresAt));
        return Optional.of(new Grant(grant.taskId(), grant.epoch(), grant.keys(), grant.token(), expiresAt));
    }

    /** Releases only the keys still held under this grant's token; returns how many were released. */
    public int release(Grant grant) {
        Objects.requireNonNull(grant);
        int released = 0;
        for (String key : grant.keys()) {
            Claim current = claims.get(key);
            if (current != null && current.token() == grant.token()) {
                claims.remove(key);
                released++;
            }
        }
        return released;
    }

    /** Releases a specific key if still held by this task; returns whether it was released. */
    public boolean releaseKey(UUID taskId, String key) {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(key);
        Claim current = claims.get(key);
        if (current != null && current.taskId().equals(taskId)) {
            claims.remove(key);
            return true;
        }
        return false;
    }

    /** Stop, fault or removal: a task's claims end immediately regardless of expiry. */
    public int releaseTask(UUID taskId) {
        Objects.requireNonNull(taskId);
        int before = claims.size();
        claims.values().removeIf(claim -> claim.taskId().equals(taskId));
        return before - claims.size();
    }

    /** Restart rebuilds current demand only: every claim from another session epoch is dropped. */
    public int invalidateOtherEpochs(UUID currentEpoch) {
        Objects.requireNonNull(currentEpoch);
        int before = claims.size();
        claims.values().removeIf(claim -> !claim.epoch().equals(currentEpoch));
        return before - claims.size();
    }

    /** Read-only validity check for the mutation boundary. */
    public boolean holdsAll(Grant grant, long now) {
        Objects.requireNonNull(grant);
        for (String key : grant.keys()) {
            Claim current = claims.get(key);
            if (current == null || current.token() != grant.token() || expired(current, now)) return false;
        }
        return true;
    }

    /** Which job currently holds a claim on this thing, if any. */
    public Optional<UUID> holderOf(String key, long now) {
        Claim current = claims.get(Objects.requireNonNull(key));
        return current == null || expired(current, now) ? Optional.empty() : Optional.of(current.taskId());
    }

    public int size() { return claims.size(); }

    private static boolean expired(Claim claim, long now) { return now >= claim.expiresAt(); }

    /** Cleans up and sorts the claim keys so the same thing always has the same key. */
    private static Set<String> canonical(Collection<String> keys) {
        Objects.requireNonNull(keys);
        if (keys.isEmpty()) throw new IllegalArgumentException("Acquire at least one key");
        TreeSet<String> ordered = new TreeSet<>();
        for (String key : keys) {
            if (key == null || key.isBlank() || key.length() > 256) throw new IllegalArgumentException("Invalid claim key");
            ordered.add(key);
        }
        return ordered;
    }

    private static void requireTtl(long ttlMillis) {
        if (ttlMillis <= 0 || ttlMillis > MAX_TTL_MILLIS) throw new IllegalArgumentException("Claim TTL must be 1 ms to five minutes");
    }
}
