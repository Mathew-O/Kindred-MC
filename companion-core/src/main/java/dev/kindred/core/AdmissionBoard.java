package dev.kindred.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: the seating chart for active AI players. Only so many may be working at once, each owner
 * may run only so many, and the chunks they keep loaded are capped, so the server never gets overloaded.
 *
 * Technical: Shared activity-seat and chunk-capacity limits. Legacy callers use creator ownership;
 * independent AI players use their own identity as the capacity principal.
 */
public final class AdmissionBoard {
    public static final int DEFAULT_MAX_ACTIVE = 2;
    public static final int DEFAULT_MAX_PER_OWNER = 1;
    public static final int DEFAULT_MAX_CHUNKS_EACH = 9;
    public static final int DEFAULT_MAX_CHUNKS_GLOBAL = 18;

    // Retain legacy constant aliases
    public static final int MAX_ACTIVE = DEFAULT_MAX_ACTIVE;
    public static final int MAX_PER_OWNER = DEFAULT_MAX_PER_OWNER;
    public static final int MAX_CHUNKS_EACH = DEFAULT_MAX_CHUNKS_EACH;
    public static final int MAX_CHUNKS_GLOBAL = DEFAULT_MAX_CHUNKS_GLOBAL;

    public enum Decision { ADMIT, WAITING, REJECTED }

    private final int maxActive;
    private final int maxPerOwner;
    private final int maxChunksEach;
    private final int maxChunksGlobal;
    private final Map<UUID, Seat> seats = new LinkedHashMap<>();

    public record Seat(UUID companionId, UUID ownerId, int chunks) { }

    /** A board with the default limits. */
    public AdmissionBoard() {
        this(DEFAULT_MAX_ACTIVE, DEFAULT_MAX_PER_OWNER, DEFAULT_MAX_CHUNKS_EACH, DEFAULT_MAX_CHUNKS_GLOBAL);
    }

    /** A board with custom limits: players active at once, per owner, chunks each and chunks in total. */
    public AdmissionBoard(int maxActive, int maxPerOwner, int maxChunksEach, int maxChunksGlobal) {
        if (maxActive < 1 || maxPerOwner < 1 || maxChunksEach < 1 || maxChunksGlobal < 1) {
            throw new IllegalArgumentException("Admission limits must be positive");
        }
        this.maxActive = maxActive;
        this.maxPerOwner = maxPerOwner;
        this.maxChunksEach = maxChunksEach;
        this.maxChunksGlobal = maxChunksGlobal;
    }

    /** Configured capacity only; this does not assert hardware or performance qualification. */
    public static AdmissionBoard serverTarget(int concurrentCompanions) {
        return new AdmissionBoard(concurrentCompanions, Math.max(1, concurrentCompanions / 2),
                DEFAULT_MAX_CHUNKS_EACH, concurrentCompanions * DEFAULT_MAX_CHUNKS_EACH);
    }

    /**
     * Asks for a seat. Admitted if every limit allows it, waiting if a limit is full right now, rejected if
     * the request could never fit.
     */
    public Decision request(UUID companionId, UUID ownerId, int chunks) {
        if (chunks < 1 || chunks > maxChunksEach) return Decision.REJECTED;
        Seat existing = seats.get(companionId);
        if (existing != null) return Decision.ADMIT;
        long owners = seats.values().stream().filter(seat -> seat.ownerId().equals(ownerId)).count();
        if (owners >= maxPerOwner) return Decision.WAITING;
        if (seats.size() >= maxActive) return Decision.WAITING;
        int used = seats.values().stream().mapToInt(Seat::chunks).sum();
        if (used + chunks > maxChunksGlobal) return Decision.WAITING;
        seats.put(companionId, new Seat(companionId, ownerId, chunks));
        return Decision.ADMIT;
    }

    public void release(UUID companionId) { seats.remove(companionId); }

    public int active() { return seats.size(); }
    public int maxActive() { return maxActive; }
    public int maxPerOwner() { return maxPerOwner; }
    public int maxChunksEach() { return maxChunksEach; }
    public int maxChunksGlobal() { return maxChunksGlobal; }
}
