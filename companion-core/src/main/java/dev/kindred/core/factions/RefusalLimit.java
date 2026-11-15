package dev.kindred.core.factions;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: how often a refused attempt on a faction's land is written down: once a minute for each player and
 * chunk, and at most so many a game day for each faction whose land it is; anything past that is only counted.
 *
 * Technical: F-34c2 (Session A's conditions, 2026-11-15). In memory only, on the server thread; a restart begins it again.
 * The per-minute window is real time; the day is the game day the caller gives. The map of minutes is pruned as it is
 * used, so it holds at most the pairs seen in the last minute.
 */
public final class RefusalLimit {
    /** What the limit says of one attempt. */
    public enum Verdict { WRITE, SAME_MINUTE, FACTION_CAP }

    public static final long MINUTE_MS = 60_000;
    public static final int DEFAULT_DAILY_CAP = 100;

    private final int dailyCap;
    private final Map<String, Long> lastWritten = new HashMap<>();
    private final Map<String, Integer> writtenToday = new HashMap<>();
    private long day = Long.MIN_VALUE;
    private long lastPrune = Long.MIN_VALUE;
    private long sameMinute, overCap, written;

    public RefusalLimit(int dailyCap) {
        if (dailyCap < 1) throw new IllegalArgumentException("A cap of at least one");
        this.dailyCap = dailyCap;
    }

    /** One attempt by {@code actor} on {@code chunk}, land of {@code faction}, at {@code nowMs} on game day {@code gameDay}. */
    public Verdict admit(UUID actor, String chunk, String faction, long nowMs, long gameDay) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(chunk);
        Objects.requireNonNull(faction);
        if (gameDay != day) {
            day = gameDay;
            writtenToday.clear();
        }
        if (nowMs - lastPrune >= MINUTE_MS) prune(nowMs);
        String key = actor + "|" + chunk;
        Long last = lastWritten.get(key);
        if (last != null && nowMs - last < MINUTE_MS) {
            sameMinute++;
            return Verdict.SAME_MINUTE;
        }
        if (writtenToday.getOrDefault(faction, 0) >= dailyCap) {
            // Kept for the minute as if written, so the repeats that follow take the one-lookup path (Session A's review).
            lastWritten.put(key, nowMs);
            overCap++;
            return Verdict.FACTION_CAP;
        }
        lastWritten.put(key, nowMs);
        writtenToday.merge(faction, 1, Integer::sum);
        written++;
        return Verdict.WRITE;
    }

    /**
     * Whether {@code actor} on {@code chunk} was written within the minute before {@code nowMs}: asked before anything is
     * looked up, so a repeat costs one map lookup; a yes is counted as the same minute's, as {@link #admit} would.
     */
    public boolean sameMinute(UUID actor, String chunk, long nowMs) {
        Long last = lastWritten.get(actor + "|" + chunk);
        if (last == null || nowMs - last >= MINUTE_MS) return false;
        sameMinute++;
        return true;
    }

    private void prune(long nowMs) {
        lastPrune = nowMs;
        for (Iterator<Long> it = lastWritten.values().iterator(); it.hasNext(); ) if (nowMs - it.next() >= MINUTE_MS) it.remove();
    }

    /** Written, left as the same minute's, and over a faction's cap, since start. */
    public long[] counts() { return new long[] {written, sameMinute, overCap}; }

    /** The pairs held for the minute now: bounded by those seen in the last minute. */
    public int held() { return lastWritten.size(); }
}
