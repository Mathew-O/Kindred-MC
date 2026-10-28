package dev.kindred.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: the rule that makes a player glance up at someone new who comes near while it works, then
 * go back to what it was doing. It only proposes a short pause; it never cancels or changes the job, and it
 * keeps the glances rare.
 *
 * Technical: Notice-and-return: a bounded glance at a newly noticed person during an interruptible activity,
 * after which the body resumes the same checkpoint. This proposes a pause; it never cancels, retargets or
 * replans the activity, and it yields to a skill that owns the gaze channel.
 */
public final class AttentionPolicy {
    public static final long MIN_GLANCE_MILLIS = 800;
    public static final long MAX_GLANCE_MILLIS = 2_500;
    public static final long PER_CONTACT_COOLDOWN_MILLIS = 120_000;
    public static final long GLOBAL_COOLDOWN_MILLIS = 20_000;
    public static final long BUDGET_WINDOW_MILLIS = 60_000;
    public static final long BUDGET_MILLIS = 6_000;

    /** Phases a skill reports; only the first five may be interrupted for a moment. */
    public enum Phase {
        IDLE, WAITING, BROWSING_CONTAINER, SORTING_INVENTORY, WALKING_UNCOMMITTED,
        BREAKING, TRANSFERRING, PLACING, AIRBORNE, CANCELLING;

        /** Whether a skill in this phase may pause for a moment to look at someone. */
        public boolean interruptible() {
            return this == IDLE || this == WAITING || this == BROWSING_CONTAINER
                    || this == SORTING_INVENTORY || this == WALKING_UNCOMMITTED;
        }
    }

    /** A zero duration is a head-free acknowledgement because a skill owns the gaze. */
    public record Glance(UUID subject, long durationMillis, boolean remark) { }

    private record Spent(long at, long durationMillis) { }

    /** A newcomer who cannot be noticed yet waits for the next checkpoint, but not longer than this. */
    static final long PENDING_LIMIT_MILLIS = 30_000;

    private final Set<UUID> known = new HashSet<>();
    private final Map<UUID, Long> pendingSince = new HashMap<>();
    private final Map<UUID, Long> lastGlance = new LinkedHashMap<>();
    private final Deque<Spent> recent = new ArrayDeque<>();
    private long nextGlobal = Long.MIN_VALUE;

    /** Looks at who is nearby now and decides whether to glance at a newcomer, and for how long. */
    public Optional<Glance> observe(Set<UUID> contacts, Phase phase, boolean bodyLeased, boolean gazeOwnedBySkill,
                                    Personality personality, Set<Mannerisms.Quirk> activeQuirks, long now) {
        Objects.requireNonNull(contacts, "contacts");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(personality, "personality");
        Objects.requireNonNull(activeQuirks, "activeQuirks");
        if (!bodyLeased) { clear(); return Optional.empty(); }
        known.retainAll(contacts);
        pendingSince.keySet().retainAll(contacts);
        List<UUID> newcomers = new ArrayList<>();
        for (UUID contact : contacts) {
            if (known.contains(contact)) continue;
            long since = pendingSince.computeIfAbsent(contact, ignored -> now);
            if (now - since > PENDING_LIMIT_MILLIS) { settle(contact); continue; } // Too late to look up naturally.
            newcomers.add(contact);
        }
        if (newcomers.isEmpty() || !phase.interruptible() || now < nextGlobal) return Optional.empty();
        recent.removeIf(spent -> now - spent.at() >= BUDGET_WINDOW_MILLIS);
        long used = recent.stream().mapToLong(Spent::durationMillis).sum();
        long duration = gazeOwnedBySkill ? 0 : duration(personality, activeQuirks);
        if (used + duration > BUDGET_MILLIS) return Optional.empty();
        for (UUID contact : newcomers) {
            settle(contact);
            Long last = lastGlance.get(contact);
            if (last != null && now - last < PER_CONTACT_COOLDOWN_MILLIS) continue;
            remember(contact, now);
            nextGlobal = now + GLOBAL_COOLDOWN_MILLIS;
            if (duration > 0) recent.addLast(new Spent(now, duration));
            return Optional.of(new Glance(contact, duration, personality.sociability() > 0));
        }
        return Optional.empty();
    }

    /** Stop, sleep or lease loss: nothing is in progress, so nothing can be paused. */
    public void clear() {
        known.clear();
        pendingSince.clear();
    }

    /** Counts a person as noticed, so the player does not glance at them again. */
    private void settle(UUID contact) {
        known.add(contact);
        pendingSince.remove(contact);
    }

    /** Longer with curiosity and the curious-glance quirk, shorter with caution; always inside the bounds. */
    static long duration(Personality personality, Set<Mannerisms.Quirk> activeQuirks) {
        double base = MIN_GLANCE_MILLIS + (MAX_GLANCE_MILLIS - MIN_GLANCE_MILLIS) * personality.curiosity() / 100.0;
        if (activeQuirks.contains(Mannerisms.Quirk.CURIOUSGLANCE)) base += 400;
        base *= 1 - 0.4 * personality.caution() / 100.0;
        return Math.max(MIN_GLANCE_MILLIS, Math.min(MAX_GLANCE_MILLIS, Math.round(base)));
    }

    /** Notes when the player last glanced at someone. */
    private void remember(UUID contact, long now) {
        lastGlance.remove(contact);
        lastGlance.put(contact, now);
        while (lastGlance.size() > 64) lastGlance.remove(lastGlance.keySet().iterator().next());
    }
}
