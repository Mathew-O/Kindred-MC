package dev.kindred.core.factions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: each player's power (0 to 10), how far it is through the hour that earns the next point, when each
 * killer last cost each victim power, and when each account was first seen.
 *
 * Technical: The state of {@link PowerLedger} (M-06c). A player not yet in it has the start power and no progress.
 * {@code lastCounted} is keyed {@code killer>victim} with the real time of the loss it counted, for the anti-farming
 * cooldown; {@code firstSeen} is each account's first sight on this server, for the new-account rule.
 */
public record PowerBook(Map<UUID, Power> powers, Map<String, Long> lastCounted, Map<UUID, Long> firstSeen) {
    public static final PowerBook EMPTY = new PowerBook(Map.of(), Map.of(), Map.of());

    /** A player's power and its progress, in online seconds, toward the next point. */
    public record Power(int power, int progressSeconds) {
        public Power {
            if (power < 0 || progressSeconds < 0) throw new IllegalArgumentException("Power and progress are never negative");
        }
    }

    public PowerBook {
        powers = Collections.unmodifiableMap(new LinkedHashMap<>(powers));
        lastCounted = Collections.unmodifiableMap(new LinkedHashMap<>(lastCounted));
        firstSeen = Collections.unmodifiableMap(new LinkedHashMap<>(firstSeen));
    }

    public Power of(UUID player, FactionSettings settings) {
        return powers.getOrDefault(player, new Power(settings.startPower(), 0));
    }

    public static String pair(UUID killer, UUID victim) { return killer + ">" + victim; }

    PowerBook with(UUID player, Power power) {
        var next = new LinkedHashMap<>(powers);
        next.put(player, power);
        return new PowerBook(next, lastCounted, firstSeen);
    }

    PowerBook counted(String pair, long atMs) {
        var next = new LinkedHashMap<>(lastCounted);
        next.put(pair, atMs);
        return new PowerBook(powers, next, firstSeen);
    }

    PowerBook seen(UUID player, long atMs) {
        if (firstSeen.containsKey(player)) return this;
        var next = new LinkedHashMap<>(firstSeen);
        next.put(player, atMs);
        return new PowerBook(powers, lastCounted, next);
    }
}
