package dev.kindred.core.tactics;

import dev.kindred.core.tactics.Melee.Move;
import dev.kindred.core.tactics.Melee.Sight;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Learned melee execution (E-41e, Milestone 4): the choice of move learned offline from demonstrations, the same sight
 * in and the same moves out as {@link Melee}, versioned and with a way back. Demonstrations are samples of what a
 * player saw and the move it made ({@link Sample}); {@link #train} fits a softmax over the moves on fixed features of
 * the sight (deterministic: the same samples in the same order give the same model), {@link Model#version()} names a
 * model by its weights, and {@link #write}/{@link #read} keep it as text. A policy runs a model with the hand-set
 * melee's commitment, among the moves the player can make at all; with no model, or one trained for another ruleset,
 * it is the hand-set melee (the way back). Pure; nothing is learned while playing.
 */
public final class LearnedMelee implements MeleePolicy {
    /** The ruleset a model must have been trained for (E-41a). */
    public static final String RULESET = "1.21.1";
    static final int SCHEMA = 1;

    /** What a player saw and the move it made. */
    public record Sample(Sight sight, Move move) {
        public Sample {
            Objects.requireNonNull(sight);
            Objects.requireNonNull(move);
        }
    }

    /** The header of a demonstrations file: a sample a line, the sight's fields and then the move. */
    public static final String SAMPLE_HEADER = "distance,charge,health,food,shield,opponentReady,opponentBlocking,opponentHealth,visible,blocks,spams,axe,move";

    /** {@code sample} as a line of a demonstrations file. */
    public static String line(Sample sample) {
        var s = sample.sight();
        return String.format(Locale.ROOT, "%.3f,%.3f,%.2f,%d,%d,%d,%d,%.2f,%d,%.3f,%.3f,%d,%s", s.distance(), s.charge(), s.health(),
                s.food() ? 1 : 0, s.shield() ? 1 : 0, s.opponentReady() ? 1 : 0, s.opponentBlocking() ? 1 : 0, s.opponentHealth(),
                s.visible() ? 1 : 0, s.blocks(), s.spams(), s.axe() ? 1 : 0, sample.move().name());
    }

    /** A line of a demonstrations file read back; refused (an exception) if it is not one. */
    public static Sample sample(String line) {
        String[] p = line.trim().split(",");
        if (p.length != 13) throw new IllegalArgumentException("Not a demonstration: " + line);
        return new Sample(new Sight(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]), "1".equals(p[3]), "1".equals(p[4]),
                "1".equals(p[5]), "1".equals(p[6]), Double.parseDouble(p[7]), "1".equals(p[8]), Double.parseDouble(p[9]), Double.parseDouble(p[10]),
                "1".equals(p[11])), Move.valueOf(p[12]));
    }

    /** The features a model sees of a sight: fixed, in this order. */
    public static final List<String> FEATURES = List.of("bias", "distance", "charge", "health", "food", "shield", "opponentReady",
            "opponentBlocking", "opponentHealth", "visible", "blocks", "spams", "axe", "inReach", "full", "fullInReach", "losingLow",
            "hurtAndFar", "threatNear", "outOfReach");

    static double[] features(Sight s) {
        boolean inReach = s.distance() <= Melee.REACH && s.visible();
        boolean full = s.charge() >= Melee.FULL;
        boolean threat = s.opponentReady();
        return new double[]{
                1,
                Math.min(4, s.distance() / Melee.REACH),
                s.charge(),
                s.health() / 20,
                s.food() ? 1 : 0,
                s.shield() ? 1 : 0,
                s.opponentReady() ? 1 : 0,
                s.opponentBlocking() ? 1 : 0,
                s.opponentHealth() / 20,
                s.visible() ? 1 : 0,
                s.blocks(),
                s.spams(),
                s.axe() ? 1 : 0,
                inReach ? 1 : 0,
                full ? 1 : 0,
                inReach && full ? 1 : 0,
                s.health() <= Melee.LOW && s.opponentHealth() > s.health() ? 1 : 0,
                s.health() < Melee.HURT && s.distance() > Melee.REACH + 3 ? 1 : 0,
                threat && s.shield() && s.distance() <= Melee.REACH + 1 ? 1 : 0,
                s.distance() > Melee.REACH || !s.visible() ? 1 : 0,
        };
    }

    /**
     * The moves a player can make at all from {@code s}, whatever it would choose: a strike only in reach and in sight,
     * the shield only with one, eating only with food; the rest always.
     */
    public static List<Move> open(Sight s) {
        var moves = new ArrayList<Move>();
        for (Move move : Move.values()) {
            boolean can = switch (move) {
                case STRIKE -> s.distance() <= Melee.REACH && s.visible();
                case BLOCK -> s.shield();
                case EAT -> s.food();
                default -> true;
            };
            if (can) moves.add(move);
        }
        return moves;
    }

    /** A trained model: a row of weights a move, over {@link #FEATURES}; what it was trained on, and how it scored. */
    public record Model(String ruleset, int trainedOn, double heldOut, double[][] weights) {
        public Model {
            Objects.requireNonNull(ruleset);
            if (weights.length != Move.values().length) throw new IllegalArgumentException("A row of weights for each move");
            for (double[] row : weights) {
                if (row.length != FEATURES.size()) throw new IllegalArgumentException("A weight for each feature");
                for (double w : row) if (!Double.isFinite(w)) throw new IllegalArgumentException("Weights must be finite");
            }
        }

        /** The model's name, from its weights and ruleset: the same weights, the same name; any change, another. */
        public String version() {
            long h = 1125899906842597L;
            h = 31 * h + ruleset.hashCode();
            for (double[] row : weights) for (double w : row) h = 31 * h + Long.hashCode(Double.doubleToLongBits(round(w)));
            return "melee-learned-" + String.format(Locale.ROOT, "%016x", h).substring(0, 10);
        }

        /** The scores of each open move for {@code s}. */
        public Map<Move, Double> scores(Sight s) {
            double[] x = features(s);
            var u = new EnumMap<Move, Double>(Move.class);
            for (Move move : open(s)) {
                double z = 0;
                double[] row = weights[move.ordinal()];
                for (int i = 0; i < x.length; i++) z += row[i] * x[i];
                u.put(move, z);
            }
            return u;
        }

        /** The move it would make from {@code s}, without commitment. */
        public Move choose(Sight s) {
            return scores(s).entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        }
    }

    /** Weights kept to six places, so that a model written and read back is the same model. */
    static double round(double w) {
        return Math.round(w * 1e6) / 1e6;
    }

    /**
     * A model fitted to {@code samples}: a softmax over the moves open at each sample, by gradient descent for
     * {@code epochs} passes at {@code rate} with weight decay {@code l2}, the last fifth of the samples held out to
     * score it (the share of them whose move it makes). Deterministic.
     */
    public static Model train(List<Sample> samples, int epochs, double rate, double l2) {
        if (samples.size() < 10) throw new IllegalArgumentException("Too few demonstrations to learn from");
        int moves = Move.values().length, n = FEATURES.size();
        int split = samples.size() - Math.max(1, samples.size() / 5);
        double[][] w = new double[moves][n];
        double[][] xs = new double[samples.size()][];
        for (int i = 0; i < samples.size(); i++) xs[i] = features(samples.get(i).sight());
        for (int epoch = 0; epoch < epochs; epoch++) {
            for (int i = 0; i < split; i++) {
                var sample = samples.get(i);
                double[] x = xs[i];
                var allowed = open(sample.sight());
                if (!allowed.contains(sample.move())) continue;
                double[] z = new double[moves];
                double max = Double.NEGATIVE_INFINITY;
                for (Move m : allowed) {
                    double v = 0;
                    for (int k = 0; k < n; k++) v += w[m.ordinal()][k] * x[k];
                    z[m.ordinal()] = v;
                    max = Math.max(max, v);
                }
                double sum = 0;
                for (Move m : allowed) sum += Math.exp(z[m.ordinal()] - max);
                for (Move m : allowed) {
                    double p = Math.exp(z[m.ordinal()] - max) / sum;
                    double g = p - (m == sample.move() ? 1 : 0);
                    for (int k = 0; k < n; k++) w[m.ordinal()][k] -= rate * (g * x[k] + l2 * w[m.ordinal()][k]);
                }
            }
        }
        for (double[] row : w) for (int k = 0; k < n; k++) row[k] = round(row[k]);
        var model = new Model(RULESET, split, 0, w);
        int agree = 0, held = 0;
        for (int i = split; i < samples.size(); i++) {
            var sample = samples.get(i);
            if (!open(sample.sight()).contains(sample.move())) continue;
            held++;
            if (model.choose(sample.sight()) == sample.move()) agree++;
        }
        return new Model(RULESET, split, held == 0 ? 0 : (double) agree / held, w);
    }

    /** {@code model} as text: a header of key=value lines, then a row of weights a move. */
    public static String write(Model model) {
        var out = new StringBuilder();
        out.append("schema=").append(SCHEMA).append('\n');
        out.append("version=").append(model.version()).append('\n');
        out.append("ruleset=").append(model.ruleset()).append('\n');
        out.append("trainedOn=").append(model.trainedOn()).append('\n');
        out.append("heldOut=").append(String.format(Locale.ROOT, "%.4f", model.heldOut())).append('\n');
        out.append("features=").append(String.join(",", FEATURES)).append('\n');
        for (Move move : Move.values()) {
            out.append(move.name()).append('=');
            double[] row = model.weights()[move.ordinal()];
            for (int k = 0; k < row.length; k++) {
                if (k > 0) out.append(',');
                out.append(String.format(Locale.ROOT, "%.6f", row[k]));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * A model read back from {@link #write}'s text; refused (an exception) when the schema or features differ, or its
     * weights do not give the version it was written under (a changed or damaged model).
     */
    public static Model read(String text) {
        var fields = new java.util.HashMap<String, String>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) fields.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        if (!String.valueOf(SCHEMA).equals(fields.get("schema"))) throw new IllegalArgumentException("Another schema");
        if (!String.join(",", FEATURES).equals(fields.get("features"))) throw new IllegalArgumentException("Other features");
        double[][] w = new double[Move.values().length][];
        for (Move move : Move.values()) {
            String row = fields.get(move.name());
            if (row == null) throw new IllegalArgumentException("No weights for " + move);
            String[] parts = row.split(",");
            if (parts.length != FEATURES.size()) throw new IllegalArgumentException("Weights for " + move + " do not match the features");
            w[move.ordinal()] = new double[parts.length];
            for (int k = 0; k < parts.length; k++) w[move.ordinal()][k] = Double.parseDouble(parts[k]);
        }
        var model = new Model(Objects.requireNonNull(fields.get("ruleset"), "No ruleset"), Integer.parseInt(fields.getOrDefault("trainedOn", "0")),
                Double.parseDouble(fields.getOrDefault("heldOut", "0")), w);
        if (!model.version().equals(fields.get("version"))) throw new IllegalArgumentException("The weights do not give the version it was written under");
        return model;
    }

    private final Model model;
    private final Melee fallback = new Melee();
    private Move current;
    private long since;

    /** A policy running {@code model}, or the hand-set melee when it is null or trained for another ruleset (the way back). */
    public LearnedMelee(Model model) {
        this.model = model != null && RULESET.equals(model.ruleset()) ? model : null;
    }

    /** Whether a learned model is in use (false: the hand-set melee). */
    public boolean learned() { return model != null; }

    /** The model's version, or "hand-set". */
    public String version() { return model == null ? "hand-set" : model.version(); }

    /** The move to make at {@code tick}: the model's, kept a while once made, as the hand-set melee keeps its own. */
    @Override
    public Melee.Choice next(Sight s, long tick) {
        if (model == null) return fallback.next(s, tick);
        var u = model.scores(s);
        Move best = u.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        Move move = best;
        if (current != null && current != best && best != Move.STRIKE && best != Move.RETREAT && u.containsKey(current)
                && tick - since < Melee.COMMIT) {
            move = current;
        }
        if (move != current) {
            current = move;
            since = tick;
        }
        return new Melee.Choice(move, "learned (" + version() + "): " + move.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }
}
