package dev.kindred.core.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Matches and who may attack whom (M-00a, D7). A match is proposed with the players invited to it and runs only once
 * every one of them has joined: a human joins only by its own choice, and an AI player is entered by an operator.
 * While a match runs, and only then, its joined participants may attack one another. Players outside it are never
 * attacked. A match ends on a fall, a forfeit, its time running out or a stop, and its leave to attack ends with it at
 * once. One match at a time a player. Owner-thread only.
 */
public final class MatchRegistry {
    public enum Mode { DUEL }

    public enum State { LOBBY, COUNTDOWN, RUNNING, ENDED }

    /** A match as it stands: who was invited, who has joined, where it is in its life, and how it ended. */
    public record Match(UUID id, Mode mode, List<UUID> invited, Set<UUID> joined, State state, long countdownEnds,
                        long endsAt, UUID winner, String result) {
        public Match {
            invited = List.copyOf(invited);
            joined = Set.copyOf(joined);
        }

        public boolean participant(UUID player) { return joined.contains(player); }

        Match with(Set<UUID> joined) { return new Match(id, mode, invited, joined, state, countdownEnds, endsAt, winner, result); }
        Match counting(long until) { return new Match(id, mode, invited, joined, State.COUNTDOWN, until, endsAt, winner, result); }
        Match running(long until) { return new Match(id, mode, invited, joined, State.RUNNING, countdownEnds, until, winner, result); }
        Match ended(UUID winner, String result) { return new Match(id, mode, invited, joined, State.ENDED, countdownEnds, endsAt, winner, result); }
    }

    /** A request's outcome: the match as it now is, or why it was refused. */
    public record Result(Optional<Match> match, String reason) {
        static Result ok(Match match, String reason) { return new Result(Optional.of(match), reason); }
        static Result refused(String reason) { return new Result(Optional.empty(), reason); }
        public boolean accepted() { return match.isPresent(); }
    }

    /** Ticks from a start to the fight: three seconds. */
    public static final int COUNTDOWN_TICKS = 60;
    /** A duel's time: five minutes, then a draw. */
    public static final int DUEL_TICKS = 6000;
    /** Ended matches remembered for their results. */
    static final int ENDED_KEPT = 16;

    private final Map<UUID, Match> matches = new LinkedHashMap<>();

    /** A duel proposed between two players, none of them joined yet. */
    public Result propose(Mode mode, List<UUID> invited) {
        Objects.requireNonNull(mode);
        if (invited.size() != 2 || invited.get(0).equals(invited.get(1))) return Result.refused("A duel is between two different players");
        for (UUID player : invited) {
            if (active(player).isPresent()) return Result.refused("A player invited is already in a match");
        }
        var match = new Match(UUID.randomUUID(), mode, invited, Set.of(), State.LOBBY, -1, -1, null, "");
        matches.put(match.id(), match);
        prune();
        return Result.ok(match, "Proposed; each player must join");
    }

    /** {@code player} joins a match it was invited to, before it starts. */
    public Result join(UUID matchId, UUID player) {
        Match match = matches.get(matchId);
        if (match == null) return Result.refused("No such match");
        if (match.state() != State.LOBBY) return Result.refused("The match has already started or ended");
        if (!match.invited().contains(player)) return Result.refused("Not invited to this match");
        var joined = new LinkedHashSet<>(match.joined());
        joined.add(player);
        match = match.with(joined);
        matches.put(matchId, match);
        return Result.ok(match, "Joined");
    }

    /** Starts the countdown once every invited player has joined. */
    public Result start(UUID matchId, long tick) {
        Match match = matches.get(matchId);
        if (match == null) return Result.refused("No such match");
        if (match.state() != State.LOBBY) return Result.refused("The match has already started or ended");
        if (!match.joined().containsAll(match.invited())) return Result.refused("Not every invited player has joined");
        match = match.counting(tick + COUNTDOWN_TICKS);
        matches.put(matchId, match);
        return Result.ok(match, "Counting down");
    }

    /** The clock: a countdown that is over starts the fight; a fight whose time is up is a draw. The matches that changed. */
    public List<Match> tick(long tick) {
        var changed = new ArrayList<Match>();
        for (var entry : matches.entrySet()) {
            Match match = entry.getValue();
            if (match.state() == State.COUNTDOWN && tick >= match.countdownEnds()) {
                match = match.running(tick + DUEL_TICKS);
            } else if (match.state() == State.RUNNING && tick >= match.endsAt()) {
                match = match.ended(null, "Time ran out: a draw");
            } else {
                continue;
            }
            entry.setValue(match);
            changed.add(match);
        }
        return changed;
    }

    /** {@code player} fell: in a running duel, the other wins. */
    public Optional<Match> fallen(UUID player) {
        return running(player).map(match -> end(match, other(match, player), "fell"));
    }

    /** {@code player} leaves: before the start it simply leaves; once started, it forfeits. */
    public Result leave(UUID matchId, UUID player) {
        Match match = matches.get(matchId);
        if (match == null || !match.joined().contains(player)) return Result.refused("Not in this match");
        if (match.state() == State.LOBBY) {
            var joined = new LinkedHashSet<>(match.joined());
            joined.remove(player);
            match = match.with(joined);
            matches.put(matchId, match);
            return Result.ok(match, "Left");
        }
        if (match.state() == State.ENDED) return Result.refused("The match has ended");
        return Result.ok(end(match, other(match, player), "forfeited"), "Forfeited");
    }

    /**
     * {@code player} left the game. Before the start the match cannot be fought, so it ends with no winner; once started,
     * leaving is a forfeit.
     */
    public Optional<Match> departed(UUID player) {
        Match match = active(player).orElse(null);
        if (match == null) return Optional.empty();
        if (match.state() == State.LOBBY) return Optional.of(end(match, null, "A player left before the start"));
        return Optional.of(end(match, other(match, player), "left the game"));
    }

    /** Stopped by an operator: no winner, and the leave to attack ends now. */
    public Result stop(UUID matchId) {
        Match match = matches.get(matchId);
        if (match == null) return Result.refused("No such match");
        if (match.state() == State.ENDED) return Result.refused("The match has ended");
        match = match.ended(null, "Stopped");
        matches.put(matchId, match);
        return Result.ok(match, "Stopped");
    }

    /** Every match stopped, as when the server stops. */
    public void stopAll() {
        for (var entry : matches.entrySet()) {
            if (entry.getValue().state() != State.ENDED) entry.setValue(entry.getValue().ended(null, "Stopped"));
        }
    }

    /** Only joined participants of the same running match may attack one another. */
    public boolean mayAttack(UUID attacker, UUID target) {
        if (attacker == null || target == null || attacker.equals(target)) return false;
        return running(attacker).filter(match -> match.participant(target)).isPresent();
    }

    /** The running match {@code player} is in. */
    public Optional<Match> running(UUID player) {
        return matches.values().stream().filter(m -> m.state() == State.RUNNING && m.participant(player)).findFirst();
    }

    /** A match {@code player} is invited to or in that has not ended. */
    public Optional<Match> active(UUID player) {
        return matches.values().stream().filter(m -> m.state() != State.ENDED && m.invited().contains(player)).findFirst();
    }

    public Optional<Match> get(UUID matchId) { return Optional.ofNullable(matches.get(matchId)); }

    /** A match by its id, or by the start of it (at least four characters) when only one match starts so. */
    public Optional<Match> find(String raw) {
        if (raw == null) return Optional.empty();
        String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.length() < 4) return Optional.empty();
        var found = matches.values().stream().filter(m -> m.id().toString().startsWith(text)).limit(2).toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /** The id as players type it: its first eight characters. */
    public static String shortId(UUID id) { return id.toString().substring(0, 8); }

    public List<Match> list() { return List.copyOf(matches.values()); }

    /** In a duel, the one who is not {@code player}. */
    public static UUID other(Match match, UUID player) {
        for (UUID invited : match.invited()) if (!invited.equals(player)) return invited;
        return null;
    }

    private Match end(Match match, UUID winner, String how) {
        Match ended = match.ended(winner, how);
        matches.put(match.id(), ended);
        return ended;
    }

    private void prune() {
        long ended = matches.values().stream().filter(m -> m.state() == State.ENDED).count();
        var it = matches.values().iterator();
        while (ended > ENDED_KEPT && it.hasNext()) {
            if (it.next().state() == State.ENDED) {
                it.remove();
                ended--;
            }
        }
    }
}
