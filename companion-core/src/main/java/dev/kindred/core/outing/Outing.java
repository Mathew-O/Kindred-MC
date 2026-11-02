package dev.kindred.core.outing;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: a short trip together: an AI player that has seen somewhere worth seeing (a village, a lake) asks those
 * near if they want to come; those who say "I'm in" are on it; it walks there, says so when it arrives, stays a minute,
 * and walks back. It never lasts more than ten game minutes; a stop, a fight, nightfall or anyone in danger ends it, and
 * it goes home. Who was actually there when it arrived is what it remembers, not who said they would come.
 *
 * Technical: E-27a, pure. The plan (host, destination as the atlas saw it, who said yes, who was there) and its state
 * machine, each step bounded in time; {@link #accept} is a person's own "I'm in" or an AI player's own yes by
 * {@link OutingChoice}; {@link #arrived} takes who the game saw within {@value #TOGETHER} blocks. Invitations never
 * unlock a skill or give anyone control: each participant walks on its own, as itself.
 */
public final class Outing {
    /** How far from the host a participant must be, at the destination, to have been there. */
    public static final int TOGETHER = 16;
    /** An invitation waits this long for a yes (game ticks): a minute. */
    public static final long INVITE_TICKS = 1_200;
    /** At most this long in all (game ticks): ten game minutes. */
    public static final long MOST_TICKS = 12_000;
    /** It stays this long once there: a minute. */
    public static final long STAY_TICKS = 1_200;

    public enum State { INVITED, GOING, THERE, RETURNING, DONE, CANCELLED }

    /** Where to go, as it was seen: what it is in words ("the lake") and where. */
    public record Destination(String words, String dimension, int x, int y, int z) {
        public Destination {
            Objects.requireNonNull(words);
            Objects.requireNonNull(dimension);
        }
    }

    private final UUID host;
    private final String hostName;
    private final Destination destination;
    private final long invitedAt;
    private final Set<UUID> said = new LinkedHashSet<>(), there = new LinkedHashSet<>();
    private State state = State.INVITED;
    private long stateAt;
    private String why = "";

    public Outing(UUID host, String hostName, Destination destination, long now) {
        this.host = Objects.requireNonNull(host);
        this.hostName = Objects.requireNonNull(hostName);
        this.destination = Objects.requireNonNull(destination);
        this.invitedAt = now;
        this.stateAt = now;
    }

    public UUID host() { return host; }
    public String hostName() { return hostName; }
    public Destination destination() { return destination; }
    public State state() { return state; }
    public Set<UUID> said() { return Set.copyOf(said); }
    public Set<UUID> there() { return Set.copyOf(there); }
    public String why() { return why; }

    /** Someone said they are coming (a person's own "I'm in", or an AI player's own yes): only while it is being asked. */
    public boolean accept(UUID who, long now) {
        if (state != State.INVITED || who.equals(host) || now - invitedAt > INVITE_TICKS) return false;
        return said.add(who);
    }

    /** The invitation's minute is over: off, if someone said yes; dropped, if nobody did. */
    public boolean setOff(long now) {
        if (state != State.INVITED || now - invitedAt < INVITE_TICKS && said.isEmpty()) return false;
        if (said.isEmpty()) return end(State.CANCELLED, "nobody wanted to come", now);
        state = State.GOING;
        stateAt = now;
        return true;
    }

    /** Off at once, every invitee having answered (the host need not wait the minute). */
    public boolean setOffNow(long now) {
        if (state != State.INVITED || said.isEmpty()) return false;
        state = State.GOING;
        stateAt = now;
        return true;
    }

    /** There: who the game saw with it now is who came. */
    public void arrived(Set<UUID> seenWithin, long now) {
        if (state != State.GOING) return;
        for (UUID who : seenWithin) if (said.contains(who)) there.add(who);
        state = State.THERE;
        stateAt = now;
    }

    /** Its minute there is over: time to walk back. */
    public boolean timeToGoBack(long now) {
        return timeToGoBack(now, STAY_TICKS);
    }

    /** As above, with a stay of {@code stay} ticks. */
    public boolean timeToGoBack(long now, long stay) {
        return state == State.THERE && now - stateAt >= stay;
    }

    public void goingBack(long now) {
        if (state == State.THERE || state == State.GOING) {
            state = State.RETURNING;
            stateAt = now;
        }
    }

    /** Back home: done. */
    public void back(long now) {
        if (state == State.RETURNING) end(State.DONE, "", now);
    }

    /** Whether it has gone on too long, all told. */
    public boolean overdue(long now) {
        return state != State.DONE && state != State.CANCELLED && now - invitedAt > MOST_TICKS;
    }

    /** Ended early: a stop, a fight, nightfall, someone in danger. Going home from wherever it is. */
    public boolean cancel(String reason, long now) {
        if (state == State.DONE || state == State.CANCELLED) return false;
        return end(State.CANCELLED, reason, now);
    }

    public boolean over() { return state == State.DONE || state == State.CANCELLED; }

    private boolean end(State to, String reason, long now) {
        state = to;
        stateAt = now;
        why = reason == null ? "" : reason;
        return true;
    }
}
