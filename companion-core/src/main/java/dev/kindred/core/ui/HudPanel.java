package dev.kindred.core.ui;

import dev.kindred.core.record.EventData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * In plain terms: the panel on the right of the screen, made of sections, one under another: Factions adds its own
 * (the player's faction, the standings, the notices, the market), and a mod's integration adds its own the same way,
 * without a fixed layout to change. The H key cycles full, compact (each section's first lines) and hidden.
 *
 * Technical: The HUD's sections (the owner's mod-support decision 7, M-06l), pure. A section is an id, an order and its
 * lines, the first {@code compact} of them shown in compact mode too. {@link #layout} stacks the sections by order;
 * {@link #encode} and {@link #decode} are the payload's text, read back exactly, so the client draws what the server laid
 * out for that viewer alone and needs to know no section's state.
 */
public final class HudPanel {
    private HudPanel() {}

    /** Full, compact (each section's compact lines) or hidden: the H key's cycle. */
    public enum Mode { FULL, COMPACT, HIDDEN;
        public Mode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    /** What a line is drawn in: an owner's own colour (a faction's dye), the plain text, a dim one, or a state. */
    public enum Role { FACTION, TEXT, DIM, AMBER, RED, GREEN }

    /** One line; {@code colour} is a dye's name for {@link Role#FACTION}, empty otherwise. */
    public record Line(String text, Role role, String colour) {
        public Line {
            text = clean(text);
            colour = clean(colour);
            java.util.Objects.requireNonNull(role, "role");
        }
    }

    /**
     * One section: {@code id} names it and its owner ({@code factions.own}, a mod's {@code <mod>.<name>}), {@code order}
     * places it (lower first), {@code compact} how many of its first lines compact mode shows.
     */
    public record Section(String id, int order, int compact, List<Line> lines) {
        public Section {
            if (id == null || !id.matches("[a-z][a-z0-9_.]{0,63}")) throw new IllegalArgumentException("Invalid section id " + id);
            lines = List.copyOf(lines);
            if (compact < 0 || compact > lines.size()) throw new IllegalArgumentException("compact outside the section's lines: " + compact);
        }
    }

    /** The panel's lines for {@code mode}: the sections by order (then id), all their lines or their compact ones; none hidden. */
    public static List<Line> layout(List<Section> sections, Mode mode) {
        var out = new ArrayList<Line>();
        if (mode == Mode.HIDDEN) return out;
        var sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparingInt(Section::order).thenComparing(Section::id));
        for (var s : sorted) out.addAll(mode == Mode.COMPACT ? s.lines().subList(0, s.compact()) : s.lines());
        return out;
    }

    // ----- The payload's text: one line, read back exactly -----

    private static final char FIELD = '\u001d', LINE = '\u001f';

    public static String encode(List<Section> sections) {
        var d = new LinkedHashMap<String, String>();
        d.put("n", String.valueOf(sections.size()));
        for (int k = 0; k < sections.size(); k++) {
            var s = sections.get(k);
            d.put("i" + k, s.id());
            d.put("o" + k, String.valueOf(s.order()));
            d.put("c" + k, String.valueOf(s.compact()));
            var lines = new ArrayList<String>();
            for (var l : s.lines()) lines.add(l.role().name() + FIELD + l.colour() + FIELD + l.text());
            d.put("l" + k, String.join(String.valueOf(LINE), lines));
        }
        return EventData.encode(d);
    }

    public static List<Section> decode(String text) {
        var d = EventData.decode(text);
        int n = Integer.parseInt(d.get("n"));
        var out = new ArrayList<Section>(n);
        for (int k = 0; k < n; k++) {
            var lines = new ArrayList<Line>();
            String all = d.get("l" + k);
            if (!all.isEmpty()) for (String l : all.split(String.valueOf(LINE), -1)) {
                String[] x = l.split(String.valueOf(FIELD), 3);
                lines.add(new Line(x[2], Role.valueOf(x[0]), x[1]));
            }
            out.add(new Section(d.get("i" + k), Integer.parseInt(d.get("o" + k)), Integer.parseInt(d.get("c" + k)), lines));
        }
        return List.copyOf(out);
    }

    /** A line's text without the payload's separators, which no shown text needs. */
    private static String clean(String text) {
        return text == null ? "" : text.replace(FIELD, ' ').replace(LINE, ' ');
    }
}
