package dev.kindred.core.record;

import dev.kindred.core.match.MatchRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: every match as it last stood (who was in it, how it ended and who won), worked out from the World
 * Record, so a restart forgets no result.
 *
 * Technical: The match ledger (F-31). Each change of a match is a {@code match} event carrying the whole match as it
 * then stood ({@code subject} its id); the last event for a match is the match. {@link MatchRegistry#restore} puts the
 * ledger back after a restart.
 */
public final class MatchLedger implements Ledger<Map<UUID, MatchRegistry.Stored>> {
    public static final String MATCH = "match";
    public static final MatchLedger INSTANCE = new MatchLedger();

    @Override public String name() { return "matches"; }

    @Override public Set<String> kinds() { return Set.of(MATCH); }

    @Override public Map<UUID, MatchRegistry.Stored> empty() { return Map.of(); }

    @Override
    public Map<UUID, MatchRegistry.Stored> apply(Map<UUID, MatchRegistry.Stored> state, WorldEvent event) {
        MatchRegistry.Stored stored;
        try {
            stored = decode(event.data());
        } catch (RuntimeException malformed) {
            return state;
        }
        var next = new LinkedHashMap<>(state);
        next.remove(stored.match().id()); // Last changed last, as the registry orders them.
        next.put(stored.match().id(), stored);
        return Collections.unmodifiableMap(next);
    }

    /** The event that records {@code stored} as it now stands. */
    public static WorldEvent event(long tick, long realTimeMs, String world, MatchRegistry.Stored stored) {
        var match = stored.match();
        return WorldEvent.recorded(tick, realTimeMs, world, MATCH, match.winner(), match.id().toString(), "", encode(stored));
    }

    public static Map<String, String> encode(MatchRegistry.Stored stored) {
        var m = stored.match();
        var data = new LinkedHashMap<String, String>();
        data.put("id", m.id().toString());
        data.put("mode", m.mode().name());
        data.put("state", m.state().name());
        data.put("invited", ids(m.invited()));
        data.put("runners", ids(m.runners()));
        data.put("joined", ids(m.joined()));
        data.put("out", ids(m.out()));
        data.put("countdownEnds", String.valueOf(m.countdownEnds()));
        data.put("endsAt", String.valueOf(m.endsAt()));
        data.put("winner", m.winner() == null ? "" : m.winner().toString());
        data.put("winners", ids(m.winners()));
        data.put("result", m.result());
        data.put("limit", String.valueOf(stored.limit()));
        data.put("headStart", String.valueOf(stored.headStart()));
        return data;
    }

    public static MatchRegistry.Stored decode(Map<String, String> data) {
        String winner = data.get("winner");
        var match = new MatchRegistry.Match(UUID.fromString(data.get("id")), MatchRegistry.Mode.valueOf(data.get("mode")),
                list(data.get("invited")), new LinkedHashSet<>(list(data.get("runners"))), new LinkedHashSet<>(list(data.get("joined"))),
                new LinkedHashSet<>(list(data.get("out"))), MatchRegistry.State.valueOf(data.get("state")),
                Long.parseLong(data.get("countdownEnds")), Long.parseLong(data.get("endsAt")),
                winner == null || winner.isEmpty() ? null : UUID.fromString(winner), new LinkedHashSet<>(list(data.get("winners"))),
                data.getOrDefault("result", ""));
        return new MatchRegistry.Stored(match, Integer.parseInt(data.get("limit")), Integer.parseInt(data.get("headStart")));
    }

    private static String ids(java.util.Collection<UUID> ids) {
        var out = new StringBuilder();
        for (UUID id : ids) {
            if (!out.isEmpty()) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    private static List<UUID> list(String text) {
        var out = new ArrayList<UUID>();
        if (text == null || text.isEmpty()) return out;
        for (String part : text.split(",")) out.add(UUID.fromString(part));
        return out;
    }
}
