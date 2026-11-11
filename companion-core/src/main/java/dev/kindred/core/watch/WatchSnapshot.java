package dev.kindred.core.watch;

import dev.kindred.core.eval.Json;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: what the watch page shows of each AI player at one moment: how it is, what it is doing and what it
 * weighed, what it said lately, what it has learned; and the moments worth showing. A copy made once a game tick,
 * which the page's server reads and nothing else.
 *
 * Technical: E-48. An immutable snapshot, taken on the server thread once a tick; the watch server's threads only read
 * it and write it out as JSON. Bounded: eight candidates, thirty lines, six lines of talk, ten notes a player; fifty
 * highlights. {@code takenOn} names the thread it was taken on.
 */
public record WatchSnapshot(long tick, String takenOn, List<Player> players, List<Highlight> highlights, String eval) {
    public static final int CANDIDATES = 8, LINES = 30, TALK = 6, NOTES = 10, TOLD = 10, HIGHLIGHTS = 50;

    /** Something it weighed doing, its priority and its score. */
    public record Candidate(String what, String priority, double score) {
        public Candidate {
            Objects.requireNonNull(what);
            Objects.requireNonNull(priority);
        }
    }

    /**
     * One player as the page shows it: its state, its goal and what it is doing, its candidates, its own recent lines,
     * what it said lately (the last of them its last words), its habits and notes, the model tokens it used today, and
     * what it was told lately on the Wire, as an onlooker may read it (F-34c: nothing narrower than public in words).
     */
    public record Player(String id, String name, boolean connected, float health, int food, String goal, String progress, String doing,
                         String status, String dimension, int x, int y, int z, List<Candidate> candidates, List<String> lines,
                         List<String> talk, List<String> habits, List<String> notes, long tokensToday, List<String> told) {
        public Player {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
            candidates = first(candidates, CANDIDATES);
            lines = last(lines, LINES);
            talk = last(talk, TALK);
            habits = List.copyOf(habits);
            notes = last(notes, NOTES);
            told = told == null ? List.of() : last(told, TOLD);
        }

        /** One without what it was told. */
        public Player(String id, String name, boolean connected, float health, int food, String goal, String progress, String doing,
                      String status, String dimension, int x, int y, int z, List<Candidate> candidates, List<String> lines,
                      List<String> talk, List<String> habits, List<String> notes, long tokensToday) {
            this(id, name, connected, health, food, goal, progress, doing, status, dimension, x, y, z, candidates, lines, talk, habits, notes, tokensToday, List.of());
        }
    }

    /** A moment worth showing: a death, a stage reached, a rescue, a lesson. */
    public record Highlight(long tick, String player, String kind, String text) {
        public Highlight {
            Objects.requireNonNull(player);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(text);
        }
    }

    public WatchSnapshot {
        Objects.requireNonNull(takenOn);
        players = List.copyOf(players);
        highlights = last(highlights, HIGHLIGHTS);
    }

    /** {@code /api/players}: the tick, the thread it was taken on, and every player. */
    public String playersJson() {
        var out = new StringBuilder().append("{\"tick\": ").append(tick).append(", \"takenOn\": ").append(Json.string(takenOn)).append(", \"players\": [");
        for (int i = 0; i < players.size(); i++) out.append(i == 0 ? "" : ", ").append(json(players.get(i)));
        return out.append("]}").toString();
    }

    /** {@code /api/players/<id>}: one player, by its id or its name; none when it has neither. */
    public Optional<String> playerJson(String idOrName) {
        return players.stream().filter(p -> p.id().equals(idOrName) || p.name().equalsIgnoreCase(idOrName)).findFirst().map(WatchSnapshot::json);
    }

    /** {@code /api/events}: the highlights, oldest first. */
    public String eventsJson() {
        var out = new StringBuilder().append("{\"tick\": ").append(tick).append(", \"highlights\": [");
        for (int i = 0; i < highlights.size(); i++) {
            var h = highlights.get(i);
            out.append(i == 0 ? "" : ", ").append("{\"tick\": ").append(h.tick()).append(", \"player\": ").append(Json.string(h.player()))
                    .append(", \"kind\": ").append(Json.string(h.kind())).append(", \"text\": ").append(Json.string(h.text())).append('}');
        }
        return out.append("]}").toString();
    }

    /** {@code /api/eval}: the evaluation's result so far, as it writes it, or null with none running. */
    public String evalJson() {
        return eval == null ? "{\"eval\": null}" : "{\"eval\": " + eval + "}";
    }

    private static String json(Player p) {
        var out = new StringBuilder().append('{')
                .append("\"id\": ").append(Json.string(p.id()))
                .append(", \"name\": ").append(Json.string(p.name()))
                .append(", \"connected\": ").append(p.connected())
                .append(", \"health\": ").append(Math.round(p.health() * 10) / 10.0)
                .append(", \"food\": ").append(p.food())
                .append(", \"goal\": ").append(Json.string(p.goal()))
                .append(", \"progress\": ").append(Json.string(p.progress()))
                .append(", \"doing\": ").append(Json.string(p.doing()))
                .append(", \"status\": ").append(Json.string(p.status()))
                .append(", \"dimension\": ").append(Json.string(p.dimension()))
                .append(", \"x\": ").append(p.x()).append(", \"y\": ").append(p.y()).append(", \"z\": ").append(p.z())
                .append(", \"candidates\": [");
        for (int i = 0; i < p.candidates().size(); i++) {
            var c = p.candidates().get(i);
            out.append(i == 0 ? "" : ", ").append("{\"what\": ").append(Json.string(c.what())).append(", \"priority\": ").append(Json.string(c.priority()))
                    .append(", \"score\": ").append(Math.round(c.score() * 10) / 10.0).append('}');
        }
        out.append("], \"lines\": ").append(strings(p.lines()))
                .append(", \"talk\": ").append(strings(p.talk()))
                .append(", \"lastWords\": ").append(Json.string(p.talk().isEmpty() ? "" : p.talk().getLast()))
                .append(", \"habits\": ").append(strings(p.habits()))
                .append(", \"notes\": ").append(strings(p.notes()))
                .append(", \"tokensToday\": ").append(p.tokensToday())
                .append(", \"told\": ").append(strings(p.told()))
                .append('}');
        return out.toString();
    }

    private static String strings(List<String> values) {
        var out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) out.append(i == 0 ? "" : ", ").append(Json.string(values.get(i)));
        return out.append(']').toString();
    }

    private static <T> List<T> first(List<T> values, int most) {
        return List.copyOf(values.subList(0, Math.min(most, values.size())));
    }

    private static <T> List<T> last(List<T> values, int most) {
        return List.copyOf(values.subList(Math.max(0, values.size() - most), values.size()));
    }
}
