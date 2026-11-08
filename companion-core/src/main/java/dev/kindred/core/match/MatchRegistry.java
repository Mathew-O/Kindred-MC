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
 * In plain terms: keeps track of matches between players, such as duels, speedrun races, manhunts and build
 * battles: who was invited, who joined, the countdown, the clock and who won. It is also the one place that
 * says who may attack whom: only players on opposite sides of the same running match that allows it, never
 * anyone outside a match.
 *
 * Technical: Matches and who may attack whom (M-00a, D7). A match is proposed with the players invited to it
 * and runs only once every one of them has joined: a human joins only by its own choice, and an AI player is
 * entered by an operator. While a duel runs, and only then, its joined participants may attack one another;
 * no other mode lets players strike one another, and players outside a match are never attacked. A match ends
 * on its mode's terms (a fall in a duel, the first runner to finish a speedrun), a forfeit, its time running
 * out or a stop, and its leave to attack ends with it at once. One match at a time a player. Owner-thread
 * only.
 */
public final class MatchRegistry {
    public enum Mode {
        /** Two players fight until one falls: the only mode whose players may strike one another. */
        DUEL(2, 2, true, 6000),
        /** One to eight runners race to the dragon (M-03a): nobody strikes anybody, and a fall is only a setback. */
        SPEEDRUN(1, 8, false, 0),
        /**
         * Runners make for the dragon while hunters hunt them (M-02a): a runner and a hunter may strike one another, never
         * two on the same side. A runner's fall puts it out; a hunter's is only a setback.
         */
        MANHUNT(2, 8, true, 0),
        /**
         * A creative build battle on a theme (M-04a): each builder on its own plot for the build's time, then a vote, then
         * the results. Nobody strikes anybody.
         */
        BUILDER(2, 8, false, 6000);

        public final int minPlayers, maxPlayers;
        /** Whether its players may strike one another while it runs. */
        public final boolean pvp;
        /** Its time from the start of the fight, then a draw; zero for none. */
        public final int ticks;

        /** A mode with its player limits, whether players may strike one another, and its time. */
        Mode(int minPlayers, int maxPlayers, boolean pvp, int ticks) {
            this.minPlayers = minPlayers;
            this.maxPlayers = maxPlayers;
            this.pvp = pvp;
            this.ticks = ticks;
        }

        public String label() { return this == BUILDER ? "Build battle" : name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT); }
    }

    /** Where a match is in its life. VOTING is a build battle's: building is over and the builds are being judged. */
    public enum State { LOBBY, COUNTDOWN, RUNNING, VOTING, ENDED }

    /**
     * A match as it stands: who was invited and which of them run (every player in a speedrun, the runners of a
     * manhunt, nobody in a duel), who has joined, who has dropped out since its start (a runner who forfeited or fell,
     * where the others play on), where it is in its life, and how it ended: the player who won it, if one did, and
     * everyone on the winning side.
     */
    public record Match(UUID id, Mode mode, List<UUID> invited, Set<UUID> runners, Set<UUID> joined, Set<UUID> out, State state,
                        long countdownEnds, long endsAt, UUID winner, Set<UUID> winners, String result) {
        public Match {
            invited = List.copyOf(invited);
            runners = Set.copyOf(runners);
            joined = Set.copyOf(joined);
            out = Set.copyOf(out);
            winners = Set.copyOf(winners);
        }

        /** Joined, whether or not still in it. */
        public boolean participant(UUID player) { return joined.contains(player); }

        /** Joined and not dropped out. */
        public boolean playing(UUID player) { return joined.contains(player) && !out.contains(player); }

        /** A manhunt's hunter: in it, and not a runner. */
        public boolean hunter(UUID player) { return mode == Mode.MANHUNT && invited.contains(player) && !runners.contains(player); }

        /** On the winning side of a match that ended. */
        public boolean won(UUID player) { return state == State.ENDED && winners.contains(player); }

        Match with(Set<UUID> joined) { return new Match(id, mode, invited, runners, joined, out, state, countdownEnds, endsAt, winner, winners, result); }
        Match without(Set<UUID> out) { return new Match(id, mode, invited, runners, joined, out, state, countdownEnds, endsAt, winner, winners, result); }
        Match counting(long until) { return new Match(id, mode, invited, runners, joined, out, State.COUNTDOWN, until, endsAt, winner, winners, result); }
        Match running(long until) { return new Match(id, mode, invited, runners, joined, out, State.RUNNING, countdownEnds, until, winner, winners, result); }
        Match voting(long until) { return new Match(id, mode, invited, runners, joined, out, State.VOTING, countdownEnds, until, winner, winners, result); }
        /** The same match ended, with its winner, its winning side and how it ended. */
        Match ended(UUID winner, Set<UUID> winners, String result) {
            return new Match(id, mode, invited, runners, joined, out, State.ENDED, countdownEnds, endsAt, winner, winners, result);
        }
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
    /** A build battle's vote with two builders: thirty seconds. */
    public static final int VOTE_TICKS = 600;
    /** How long each build is shown for the vote, one after another in plot order (M-04): fifteen seconds. */
    public static final int SHOW_TICKS = 300;
    /** Ended matches remembered for their results. */
    static final int ENDED_KEPT = 16;

    private final Map<UUID, Match> matches = new LinkedHashMap<>();
    /** A match's own time where it was given one (a build battle's build time), instead of its mode's. */
    private final Map<UUID, Integer> limits = new LinkedHashMap<>();
    /** A manhunt's head start for its runners, in ticks from its start, where it was given one. */
    private final Map<UUID, Integer> headStarts = new LinkedHashMap<>();
    /** The clock's last tick, for the head start. */
    private long now;
    /** Matches changed since the last {@link #drainChanges}, in the order they changed, for the World Record (F-31). */
    private final LinkedHashSet<UUID> changed = new LinkedHashSet<>();

    /**
     * A match as the World Record keeps it (F-31): the match, its own time where it was given one (-1 for its mode's),
     * and a manhunt's head start (zero for none).
     */
    public record Stored(Match match, int limit, int headStart) {
        public Stored {
            java.util.Objects.requireNonNull(match);
            if (limit < -1 || headStart < 0) throw new IllegalArgumentException("Invalid stored match");
        }
    }

    private void put(UUID id, Match match) {
        matches.put(id, match);
        changed.add(id);
    }

    /** A match as the Record keeps it, if it is still remembered. */
    public Optional<Stored> stored(UUID matchId) {
        Match match = matches.get(matchId);
        return match == null ? Optional.empty() : Optional.of(new Stored(match, limits.getOrDefault(matchId, -1), headStarts.getOrDefault(matchId, 0)));
    }

    /** Every match changed since the last call, as the Record keeps it, in the order they changed; the list is then cleared. */
    public List<Stored> drainChanges() {
        var out = new ArrayList<Stored>();
        for (UUID id : changed) stored(id).ifPresent(out::add);
        changed.clear();
        return out;
    }

    /**
     * The matches the World Record kept, put back after a restart (F-31). Only into an empty registry. A match the restart
     * cut short is ended as stopped, as {@link #stopAll} would have ended it, so no leave to strike survives a restart;
     * that ending is itself a change for the Record. The most recent ended matches are kept as {@link #prune} keeps them.
     * How many were put back.
     */
    public int restore(java.util.Collection<Stored> kept) {
        if (!matches.isEmpty()) throw new IllegalStateException("Matches are restored only into an empty registry");
        for (Stored stored : kept) {
            Match match = stored.match();
            if (stored.limit() >= 0) limits.put(match.id(), stored.limit());
            if (stored.headStart() > 0) headStarts.put(match.id(), stored.headStart());
            if (match.state() != State.ENDED) put(match.id(), match.ended(null, Set.of(), "Stopped: the server restarted"));
            else matches.put(match.id(), match);
        }
        prune();
        return matches.size();
    }

    /** A match proposed between its players, none of them joined yet: two for a duel, one to eight runners for a speedrun. */
    public Result propose(Mode mode, List<UUID> invited) {
        if (mode == Mode.MANHUNT) return Result.refused("A manhunt names its runners");
        return propose(mode, invited, mode == Mode.SPEEDRUN ? Set.copyOf(invited) : Set.of());
    }

    /** A manhunt proposed: its runners, and the hunters after them, two to eight players in all. */
    public Result proposeManhunt(List<UUID> runners, List<UUID> hunters) {
        return proposeManhunt(runners, hunters, 0);
    }

    /**
     * A manhunt proposed with the runners' head start: {@code headStartTicks} from the start in which the hunters are held
     * and nobody may strike, zero to two minutes.
     */
    public Result proposeManhunt(List<UUID> runners, List<UUID> hunters, int headStartTicks) {
        if (headStartTicks < 0 || headStartTicks > 20 * 120) return Result.refused("A head start is from none to two minutes");
        var result = proposeHunt(runners, hunters);
        if (headStartTicks > 0) result.match().ifPresent(match -> headStarts.put(match.id(), headStartTicks));
        return result;
    }

    private Result proposeHunt(List<UUID> runners, List<UUID> hunters) {
        if (runners.isEmpty() || hunters.isEmpty()) return Result.refused("A manhunt has at least one runner and one hunter");
        var invited = new ArrayList<UUID>(runners);
        invited.addAll(hunters);
        return propose(Mode.MANHUNT, invited, Set.copyOf(runners));
    }

    /** A duel proposed between two players with its own time, from thirty seconds to half an hour, then a draw. */
    public Result proposeDuel(List<UUID> invited, int ticks) {
        if (ticks < 20 * 30 || ticks > 20 * 60 * 30) return Result.refused("A duel lasts from thirty seconds to half an hour");
        var result = propose(Mode.DUEL, invited);
        result.match().ifPresent(match -> limits.put(match.id(), ticks));
        return result;
    }

    /** A build battle proposed between its builders, with {@code buildTicks} to build in. */
    public Result proposeBuild(List<UUID> builders, int buildTicks) {
        if (buildTicks < 20 * 30 || buildTicks > 20 * 60 * 30) return Result.refused("A build takes from thirty seconds to half an hour");
        var result = propose(Mode.BUILDER, builders, Set.of());
        result.match().ifPresent(match -> limits.put(match.id(), buildTicks));
        return result;
    }

    private Result propose(Mode mode, List<UUID> invited, Set<UUID> runners) {
        Objects.requireNonNull(mode);
        if (new LinkedHashSet<>(invited).size() != invited.size()) return Result.refused("Each player is invited once");
        if (invited.size() < mode.minPlayers || invited.size() > mode.maxPlayers) {
            return Result.refused(mode == Mode.DUEL ? "A duel is between two different players"
                    : "A " + mode.label().toLowerCase(java.util.Locale.ROOT) + " has " + mode.minPlayers + " to " + mode.maxPlayers + " players");
        }
        for (UUID player : invited) {
            if (active(player).isPresent()) return Result.refused("A player invited is already in a match");
        }
        var match = new Match(UUID.randomUUID(), mode, invited, runners, Set.of(), Set.of(), State.LOBBY, -1, -1, null, Set.of(), "");
        put(match.id(), match);
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
        put(matchId, match);
        return Result.ok(match, "Joined");
    }

    /** Starts the countdown once every invited player has joined. */
    public Result start(UUID matchId, long tick) {
        Match match = matches.get(matchId);
        if (match == null) return Result.refused("No such match");
        if (match.state() != State.LOBBY) return Result.refused("The match has already started or ended");
        if (!match.joined().containsAll(match.invited())) return Result.refused("Not every invited player has joined");
        match = match.counting(tick + COUNTDOWN_TICKS);
        put(matchId, match);
        return Result.ok(match, "Counting down");
    }

    /** The clock: a countdown that is over starts the fight; a fight whose time is up is a draw. The matches that changed. */
    public List<Match> tick(long tick) {
        now = tick;
        var changed = new ArrayList<Match>();
        for (var entry : matches.entrySet()) {
            Match match = entry.getValue();
            int limit = limits.getOrDefault(match.id(), match.mode().ticks);
            if (match.state() == State.COUNTDOWN && tick >= match.countdownEnds()) {
                match = match.running(limit > 0 ? tick + limit : Long.MAX_VALUE);
            } else if (match.state() == State.RUNNING && limit > 0 && tick >= match.endsAt() && match.mode() == Mode.BUILDER) {
                // The build's time is up: on to the vote, which the players' votes decide (decide).
                match = match.voting(tick + voteTicks(match));
            } else if (match.state() == State.RUNNING && limit > 0 && tick >= match.endsAt()) {
                match = match.ended(null, Set.of(), "Time ran out: a draw");
            } else {
                continue;
            }
            entry.setValue(match);
            this.changed.add(match.id());
            changed.add(match);
        }
        return changed;
    }

    /**
     * {@code player} fell. In a running duel, the other wins. In a manhunt a runner who falls is out, and with no runner
     * left the hunters have won; a hunter only respawns. In a race a fall is only a setback.
     */
    public Optional<Match> fallen(UUID player) {
        var match = running(player).orElse(null);
        if (match == null) return Optional.empty();
        if (match.mode() == Mode.DUEL) return Optional.of(end(match, other(match, player), "fell"));
        if (match.mode() == Mode.MANHUNT && match.runners().contains(player)) return Optional.of(dropOut(match, player, "fell"));
        return Optional.empty();
    }

    /**
     * {@code player} finished: the first runner to free the End wins a speedrun, and in a manhunt its side has won.
     * Either way the match is over.
     */
    public Optional<Match> finished(UUID player) {
        return running(player).filter(match -> match.runners().contains(player)).map(match -> end(match, player, "finished"));
    }

    /** {@code player} leaves: before the start it simply leaves; once started, it forfeits. */
    public Result leave(UUID matchId, UUID player) {
        Match match = matches.get(matchId);
        if (match == null || !match.joined().contains(player)) return Result.refused("Not in this match");
        if (match.state() == State.LOBBY) {
            var joined = new LinkedHashSet<>(match.joined());
            joined.remove(player);
            match = match.with(joined);
            put(matchId, match);
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
     * nobody is left running does it end, with no winner. In a manhunt, once no runner is left the hunters have won,
     * and once no hunter is left the runners have.
     */
    private Match dropOut(Match match, UUID player, String how) {
        if (match.mode() == Mode.DUEL) return end(match, other(match, player), how);
        var out = new LinkedHashSet<>(match.out());
        out.add(player);
        Match left = match.without(out);
        var still = new LinkedHashSet<>(left.joined());
        still.removeAll(out);
        if (left.mode() == Mode.MANHUNT) {
            boolean runners = still.stream().anyMatch(left.runners()::contains), hunters = still.stream().anyMatch(p -> !left.runners().contains(p));
            if (!runners) return end(left, null, side(left, false), how.equals("fell") ? "hunted" : "Every runner " + how);
            if (!hunters) return end(left, null, side(left, true), "Every hunter " + how);
        } else if (still.isEmpty()) {
            return end(left, null, Set.of(), "Every runner " + how);
        }
        put(left.id(), left);
        return left;
    }

    /** A manhunt's runners, or its hunters, among the players who joined it. */
    private static Set<UUID> side(Match match, boolean runners) {
        var side = new LinkedHashSet<UUID>();
        for (UUID player : match.joined()) if (match.runners().contains(player) == runners) side.add(player);
        return side;
    }

    /**
     * A build battle's vote decided: {@code winners} the builders with the best builds (none, if nobody voted), as the
     * votes gave them. Only while it is voting.
     */
    public Result decide(UUID matchId, Set<UUID> winners, String result) {
        Match match = matches.get(matchId);
        if (match == null) return Result.refused("No such match");
        if (match.state() != State.VOTING) return Result.refused("Not being voted on");
        var kept = new LinkedHashSet<UUID>();
        for (UUID player : winners) if (match.participant(player)) kept.add(player);
        return Result.ok(end(match, kept.size() == 1 ? kept.iterator().next() : null, kept, result), result);
    }

    /** Stopped by an operator: no winner, and the leave to attack ends now. */
    public Result stop(UUID matchId) {
        Match match = matches.get(matchId);
        if (match == null) return Result.refused("No such match");
        if (match.state() == State.ENDED) return Result.refused("The match has ended");
        match = match.ended(null, Set.of(), "Stopped");
        put(matchId, match);
        return Result.ok(match, "Stopped");
    }

    /** Every match stopped, as when the server stops. */
    public void stopAll() {
        for (var entry : matches.entrySet()) {
            if (entry.getValue().state() != State.ENDED) {
                entry.setValue(entry.getValue().ended(null, Set.of(), "Stopped"));
                changed.add(entry.getKey());
            }
        }
    }

    /**
     * Only players still in the same running duel, or a runner and a hunter of the same manhunt past its runners' head
     * start, may attack one another.
     */
    public boolean mayAttack(UUID attacker, UUID target) {
        if (attacker == null || target == null || attacker.equals(target)) return false;
        return running(attacker).filter(match -> match.mode().pvp && match.playing(target) && !heldBack(match, now)
                && (match.mode() != Mode.MANHUNT || match.runners().contains(attacker) != match.runners().contains(target))).isPresent();
    }

    /**
     * Whether {@code owner} is {@code player}'s opponent in the running match {@code player} is still in (E-44): the
     * same sides as may attack one another, the other player of a duel or the other side of a manhunt, the owner having
     * joined it, whether or not still in it. Never in a match whose players are not opponents (a speedrun, a build
     * battle), and never outside one.
     */
    public boolean opponents(UUID player, UUID owner) {
        if (player == null || owner == null || player.equals(owner)) return false;
        return running(player).filter(match -> match.mode().pvp && match.participant(owner)
                && (match.mode() != Mode.MANHUNT || match.runners().contains(player) != match.runners().contains(owner))).isPresent();
    }

    /** A manhunt's head start in ticks, zero for none. */
    public int headStart(UUID matchId) { return headStarts.getOrDefault(matchId, 0); }

    /** Whether a running manhunt is still in its runners' head start at {@code tick}: its hunters held, nobody striking. */
    public boolean heldBack(Match match, long tick) {
        int head = headStarts.getOrDefault(match.id(), 0);
        return head > 0 && match.state() == State.RUNNING && tick < match.countdownEnds() + head;
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

    /** Ended with {@code winner} the one who won it: in a manhunt with the whole of its side, otherwise alone. */
    private Match end(Match match, UUID winner, String how) {
        Set<UUID> winners = winner == null ? Set.of() : match.mode() == Mode.MANHUNT ? side(match, match.runners().contains(winner)) : Set.of(winner);
        return end(match, winner, winners, how);
    }

    private Match end(Match match, UUID winner, Set<UUID> winners, String how) {
        Match ended = match.ended(winner, winners, how);
        put(match.id(), ended);
        return ended;
    }

    /** A build battle's whole vote: each build shown in turn for {@link #SHOW_TICKS}. */
    public static int voteTicks(Match match) { return Math.max(1, match.invited().size()) * SHOW_TICKS; }

    /**
     * The builder whose build is being voted on at {@code tick}, in plot order (the order the builders were invited), or
     * empty outside the vote.
     */
    public static Optional<UUID> showing(Match match, long tick) {
        if (match.state() != State.VOTING || match.invited().isEmpty()) return Optional.empty();
        long start = match.endsAt() - voteTicks(match);
        int index = (int) Math.max(0, Math.min(match.invited().size() - 1, (tick - start) / SHOW_TICKS));
        return Optional.of(match.invited().get(index));
    }

    /** A match's own time limit in ticks, or its mode's. */
    public int limit(UUID matchId) {
        Match match = matches.get(matchId);
        return match == null ? 0 : limits.getOrDefault(matchId, match.mode().ticks);
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
