package dev.kindred.core.tactics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In plain terms: a play-by-play record of one fight: what the player saw, what it chose to do and what
 * really happened, tick by tick, so its choices can be checked afterwards against what it knew at the time. A
 * long fight keeps only its most recent stretch.
 *
 * Technical: One fight's record (E-41a): tick by tick, what the player observed, what it chose and what was
 * confirmed to happen, so that each decision can be read back afterwards from what the player had to go on
 * (the owner's design, ARCH-06). The player's own state is as it was; others are as the player could know
 * them, their health a belief. Events are only what the game confirmed (a hit seen to land, a hit taken),
 * never what was merely attempted.
 * <p>
 * Bounded: at most {@link #MAX_FRAMES} frames, the oldest dropped first and counted, and at most {@link #MAX_EVENTS}
 * events a frame. It is written out as JSON lines (a header, then a frame a line) by the adapter, off the server thread.
 */
public final class EncounterRecorder {
    public static final int MAX_FRAMES = 1200, MAX_EVENTS = 16;
    public static final int SCHEMA = 1;

    /** The player as it was. */
    public record Self(double x, double y, double z, double health, float charge, boolean onGround, boolean blocking, String held) { }

    /** Another creature as the player could know it: its health a belief, with the belief's confidence. */
    public record Seen(String id, String type, double distance, boolean visible, double believedHealth, double confidence, boolean afterMe) { }

    /** One tick: the player, what it saw, what it chose to do, and what the game confirmed. */
    public record Frame(long tick, Self self, List<Seen> seen, String decision, List<String> events) {
        public Frame {
            seen = List.copyOf(seen);
            events = List.copyOf(events.size() > MAX_EVENTS ? events.subList(0, MAX_EVENTS) : events);
        }
    }

    private final String encounterId, playerId;
    private final long startTick;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private int dropped;
    private String outcome = "";

    /** A new, empty record of one player's fight, starting at a given moment. */
    public EncounterRecorder(String encounterId, String playerId, long startTick) {
        this.encounterId = encounterId;
        this.playerId = playerId;
        this.startTick = startTick;
    }

    /** Adds one tick to the record, dropping the oldest once it is full. */
    public void record(Frame frame) {
        if (frames.size() == MAX_FRAMES) {
            frames.removeFirst();
            dropped++;
        }
        frames.addLast(frame);
    }

    /** How the fight ended, as the fight itself put it. */
    public void end(String outcome) { this.outcome = outcome == null ? "" : outcome; }

    public List<Frame> frames() { return List.copyOf(frames); }

    public int dropped() { return dropped; }

    public String encounterId() { return encounterId; }

    /** The record as JSON lines: a header naming the ruleset, the player and how it ended, then one frame a line. */
    public List<String> jsonLines() {
        var lines = new ArrayList<String>(frames.size() + 1);
        lines.add("{\"schema\":" + SCHEMA + ",\"ruleset\":" + quote(CombatRuleset.ID) + ",\"encounter\":" + quote(encounterId)
                + ",\"player\":" + quote(playerId) + ",\"start\":" + startTick + ",\"frames\":" + frames.size()
                + ",\"dropped\":" + dropped + ",\"outcome\":" + quote(outcome) + "}");
        for (Frame frame : frames) lines.add(json(frame));
        return lines;
    }

    /** One tick written out as a line of text. */
    static String json(Frame frame) {
        var out = new StringBuilder(256);
        Self self = frame.self();
        out.append("{\"tick\":").append(frame.tick())
                .append(",\"self\":{\"x\":").append(number(self.x())).append(",\"y\":").append(number(self.y())).append(",\"z\":").append(number(self.z()))
                .append(",\"health\":").append(number(self.health())).append(",\"charge\":").append(number(self.charge()))
                .append(",\"onGround\":").append(self.onGround()).append(",\"blocking\":").append(self.blocking())
                .append(",\"held\":").append(quote(self.held())).append("},\"seen\":[");
        for (int i = 0; i < frame.seen().size(); i++) {
            Seen seen = frame.seen().get(i);
            if (i > 0) out.append(',');
            out.append("{\"id\":").append(quote(seen.id())).append(",\"type\":").append(quote(seen.type()))
                    .append(",\"distance\":").append(number(seen.distance())).append(",\"visible\":").append(seen.visible())
                    .append(",\"believedHealth\":").append(number(seen.believedHealth())).append(",\"confidence\":").append(number(seen.confidence()))
                    .append(",\"afterMe\":").append(seen.afterMe()).append('}');
        }
        out.append("],\"decision\":").append(quote(frame.decision())).append(",\"events\":[");
        for (int i = 0; i < frame.events().size(); i++) {
            if (i > 0) out.append(',');
            out.append(quote(frame.events().get(i)));
        }
        return out.append("]}").toString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "null";
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Text written safely inside quotes for the record. */
    static String quote(String text) {
        if (text == null) return "null";
        var out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
