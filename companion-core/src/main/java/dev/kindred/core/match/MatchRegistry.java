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
 * While a duel runs, and only then, its joined participants may attack one another; no other mode lets players strike
 * one another, and players outside a match are never attacked. A match ends on its mode's terms (a fall in a duel, the
 * first runner to finish a speedrun), a forfeit, its time running out or a stop, and its leave to attack ends with it at
 * once. One match at a time a player. Owner-thread only.
 */
public final class MatchRegistry {
    public enum Mode {
        /** Two players fight until one falls: the only mode whose players may strike one another. */
        DUEL(2, 2, true, 6000),
        /** One to eight runners race to the dragon (M-03a): nobody strikes anybody, and a fall is only a setback. */
        SPEEDRUN(1, 8, false, 0);

        public final int minPlayers, maxPlayers;
        /** Whether its players may strike one another while it runs. */
        public final boolean pvp;
        /** Its time from the start of the fight, then a draw; zero for none. */
        public final int ticks;

        Mode(int minPlayers, int maxPlayers, boolean pvp, int ticks) {
            this.minPlayers = minPlayers;
            this.maxPlayers = maxPlayers;
            this.pvp = pvp;
            this.ticks = ticks;
        }

        public String label() { return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT); }
    }

    public enum State { LOBBY, COUNTDOWN, RUNNING, ENDED }

    /**
     * A match as it stands: who was invited, who has joined, who has dropped out since its start (a runner who forfeited
     * or left a race the others run on), where it is in its life, and how it ended.
     */
    public record Match(UUID id, Mode mode, List<UUID> invited, Set<UUID> joined, Set<UUID> out, State state, long countdownEnds,
                        long endsAt, UUID winner, String result) {
        public Match {
            invited = List.copyOf(invited);
            joined = Set.copyOf(joined);
            out = Set.copyOf(out);
        }

        /** Joined, whether or not still in it. */
        public boolean participant(UUID player) { return joined.contains(player); }

        /** Joined and not dropped out. */
        public boolean playing(UUID player) { return joined.contains(player) && !out.contains(player); }

        Match with(Set<UUID> joined) { return new Match(id, mode, invited, joined, out, state, countdownEnds, endsAt, winner, result); }
        Match without(Set<UUID> out) { return new Match(id, mode, invited, joined, out, state, countdownEnds, endsAt, winner, result); }
        Match counting(long until) { return new Match(id, mode, invited, joined, out, State.COUNTDOWN, until, endsAt, winner, result); }
        Match running(long until) { return new Match(id, mode, invited, joined, out, State.RUNNING, countdownEnds, until, winner, result); }
        Match ended(UUID winner, String result) { return new Match(id, mode, invited, joined, out, State.ENDED, countdownEnds, endsAt, winner, result); }
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
    public static final int DUEL_TICKS = Mode.DUEL.ticks;
    /** Ended matches remembered for their results. */
    static final int ENDED_KEPT = 16;

    private final Map<UUID, Match> matches = new LinkedHashMap<>();

    /** A match proposed between its players, none of them joined yet: two for a duel, one to eight runners for a speedrun. */
    public Result propose(Mode mode, List<UUID> invited) {
        Objects.requireNonNull(mode);
        if (new LinkedHashSet<>(invited).size() != invited.size()) return Result.refused("Each player is invited once");
        if (invited.size() < mode.minPlayers || invited.size() > mode.maxPlayers) {
            return Result.refused(mode == Mode.DUEL ? "A duel is between two different players"
                    : "A " + mode.label().toLowerCase(java.util.Locale.ROOT) + " has " + mode.minPlayers + " to " + mode.maxPlayers + " players");
        }
        for (UUID player : invited) {
            if (active(player).isPresent()) return Result.refused("A player invited is already in a match");
        }
        var match = new Match(UUID.randomUUID(), mode, invited, Set.of(), Set.of(), State.LOBBY, -1, -1, null, "");
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
                match = match.running(match.mode().ticks > 0 ? tick + match.mode().ticks : Long.MAX_VALUE);
            } else if (match.state() == State.RUNNING && match.mode().ticks > 0 && tick >= match.endsAt()) {
                match = match.ended(null, "Time ran out: a draw");
            } else {
                continue;
            }
            entry.setValue(match);
            changed.add(match);
        }
        return changed;
    }

    /** {@code player} fell: in a running duel, the other wins. In a race a fall is only a setback. */
    public Optional<Match> fallen(UUID player) {
        return running(player).filter(match -> match.mode() == Mode.DUEL).map(match -> end(match, other(match, player), "fell"));
    }

    /** {@code player} finished a running speedrun: the first to finish wins, and the race is over. */
    public Optional<Match> finished(UUID player) {
        return running(player).filter(match -> match.mode() == Mode.SPEEDRUN).map(match -> end(match, player, "finished"));
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
        if (!match.playing(player)) return Result.refused("Already out of this match");
        return Result.ok(dropOut(match, player, "forfeited"), "Forfeited");
    }

    /**
     * {@code player} left the game. Before the start the match cannot be fought, so it ends with no winner; once started,
     * leaving is a forfeit.
     */
    public Optional<Match> departed(UUID player) {
        Match match = active(player).orElse(null);
        if (match == null) return Optional.empty();
        if (match.state() == State.LOBBY) return Optional.of(end(match, null, "A player left before the start"));
        return Optional.of(dropOut(match, player, "left the game"));
    }

    /**
     * {@code player} drops out of a started match. In a duel the other wins. In a race the rest run on, and only when
     * nobody is left running does it end, with no winner.
     */
    private Match dropOut(Match match, UUID player, String how) {
        if (match.mode() == Mode.DUEL) return end(match, other(match, player), how);
        var out = new LinkedHashSet<>(match.out());
        out.add(player);
        match = match.without(out);
        if (out.containsAll(match.joined())) return end(match, null, "Every runner " + how);
        matches.put(match.id(), match);
        return match;
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

    /** Only players still in the same running duel may attack one another. */
    public boolean mayAttack(UUID attacker, UUID target) {
        if (attacker == null || target == null || attacker.equals(target)) return false;
        return running(attacker).filter(match -> match.mode().pvp && match.playing(target)).isPresent();
    }

    /** The running match {@code player} is still in. */
    public Optional<Match> running(UUID player) {
        return matches.values().stream().filter(m -> m.state() == State.RUNNING && m.playing(player)).findFirst();
    }

    /** A match {@code player} is invited to or still in that has not ended. */
    public Optional<Match> active(UUID player) {
        return matches.values().stream().filter(m -> m.state() != State.ENDED && m.invited().contains(player) && !m.out().contains(player)).findFirst();
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
