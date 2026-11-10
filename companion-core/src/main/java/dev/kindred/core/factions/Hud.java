package dev.kindred.core.factions;

import dev.kindred.core.record.EventData;
import dev.kindred.core.ui.HudPanel;
import dev.kindred.core.ui.HudPanel.Line;
import dev.kindred.core.ui.HudPanel.Mode;
import dev.kindred.core.ui.HudPanel.Role;
import dev.kindred.core.ui.HudPanel.Section;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In plain terms: what a player's Factions panel shows, built only from what that player may know: their own faction
 * (name, colour, members online), their own power and coins, the land they stand in, the standings every player can
 * see, their faction's last notices and the market's movers; and how the panel lays it out, full or compact.
 *
 * Technical: The HUD's state, codec and layout (M-06l), pure. {@link #build} is the visibility rule: a faction's members
 * online and its notices go only to its members; everyone's power, claims and member counts are public (the rulebook's
 * notifications); coins and power are the viewer's own. {@link #layout} turns a state into lines with a colour role each,
 * the same lines the client draws and the rendering test checks.
 */
public final class Hud {
    private Hud() {}

    /** Factions' sections' ids and places on the panel (mod-support decision 7): an integration's go before, between or after. */
    public static final String OWN = "factions.own", STANDINGS = "factions.standings", NOTICES = "factions.notices", MARKET = "factions.market";
    public static final int OWN_ORDER = 100, STANDINGS_ORDER = 200, NOTICES_ORDER = 300, MARKET_ORDER = 400;

    /** The viewer's own faction as its members see it. */
    public record Own(String tag, String name, String colour, int online, int members, boolean leader, int power, int claims, int capacity, int exposed,
                      long newShieldMinutes, long raidShieldMinutes) {}

    /** One row of the standings: public numbers only. */
    public record Row(String tag, String name, String colour, int power, int claims, int members, int trend, boolean mine) {}

    /** What the panel shows. */
    public record State(Optional<Own> own, int myPower, int maxPower, double progress, long coins, String lastChange, String here, Role hereRole,
                        List<Row> standings, List<String> notices, List<String> movers, String hand) {
        public State {
            standings = List.copyOf(standings);
            notices = List.copyOf(notices);
            movers = List.copyOf(movers);
        }
    }

    /**
     * {@code viewer}'s state. {@code online} the players online; {@code here} the land under the viewer, as its label and
     * role; {@code notices} the viewer's faction's recent notices (empty if none or not a member); {@code trends} each
     * faction's power change since the last hour (+1, 0, -1).
     */
    public static State build(UUID viewer, FactionBook factions, PowerBook powers, Map<String, String> claims, Capture.Book captures, long coins,
                              FactionSettings settings, java.util.Set<UUID> online, String lastChange, String here, Role hereRole,
                              List<String> notices, Map<UUID, Integer> trends, List<String> movers, String hand, long nowMs) {
        var mine = factions.of(viewer);
        Optional<Own> own = mine.map(f -> {
            var exposure = Capture.exposure(factions, powers, claims, f.id(), settings);
            long newShield = Math.max(0, Capture.newShieldUntil(factions, f, settings) - nowMs) / 60_000;
            long raidShield = Math.max(0, Capture.raidShieldUntil(captures, f.id(), settings) - nowMs) / 60_000;
            int on = (int) f.members().keySet().stream().filter(online::contains).count();
            return new Own(f.tag(), f.name(), f.colour().id(), on, f.members().size(), f.leader().equals(viewer), exposure.power(), exposure.claims(),
                    PowerRules.capacity(exposure.power(), settings), exposure.exposed(), newShield, raidShield);
        });
        var power = powers.of(viewer, settings);
        var rows = new ArrayList<Row>();
        for (var f : factions.factions().values()) {
            var exposure = Capture.exposure(factions, powers, claims, f.id(), settings);
            rows.add(new Row(f.tag(), f.name(), f.colour().id(), exposure.power(), exposure.claims(), f.members().size(), trends.getOrDefault(f.id(), 0),
                    mine.map(m -> m.id().equals(f.id())).orElse(false)));
        }
        rows.sort(java.util.Comparator.comparingInt((Row r) -> -r.power()).thenComparing(Row::name));
        var top = new ArrayList<>(rows.subList(0, Math.min(5, rows.size())));
        rows.stream().filter(Row::mine).findFirst().filter(r -> !top.contains(r)).ifPresent(top::add);
        return new State(own, power.power(), settings.maxPower(), power.power() >= settings.maxPower() ? 0 : (double) power.progressSeconds() / settings.regenSeconds(),
                coins, lastChange, here, hereRole, top, mine.isPresent() ? notices.subList(Math.max(0, notices.size() - 3), notices.size()) : List.of(),
                movers.subList(0, Math.min(5, movers.size())), hand);
    }

    /** Factions' lines for {@code mode}: its sections laid out alone (nothing when hidden; faction, power, coins and here when compact). */
    public static List<Line> layout(State s, Mode mode) { return HudPanel.layout(sections(s), mode); }

    /**
     * Factions' sections of the panel for state {@code s}: the viewer's own (faction, power, coins, the land here; all of it
     * shown compact too), then the standings, the faction's notices and the market, each only when it has lines.
     */
    public static List<Section> sections(State s) {
        var sections = new ArrayList<Section>();
        var out = new ArrayList<Line>();
        if (s.own().isPresent()) {
            var o = s.own().get();
            out.add(new Line("[" + o.tag() + "] " + o.name() + (o.leader() ? " ★" : ""), Role.FACTION, o.colour()));
            out.add(new Line(o.online() + " of " + o.members() + " online", Role.DIM, ""));
        } else {
            out.add(new Line("No faction", Role.DIM, ""));
        }
        out.add(new Line("Power " + s.myPower() + " / " + s.maxPower() + (s.progress() > 0 ? "  (" + (int) Math.round(s.progress() * 100) + "% to the next)" : ""),
                Role.TEXT, ""));
        s.own().ifPresent(o -> {
            Role state = o.exposed() > 0 ? Role.RED : o.claims() + 2 >= o.power() && o.claims() > 0 ? Role.AMBER : Role.GREEN;
            out.add(new Line("power " + o.power() + " · claims " + o.claims() + " / " + o.capacity() + (o.exposed() > 0 ? " · " + o.exposed() + " exposed" : ""), state, ""));
            if (o.newShieldMinutes() > 0) out.add(new Line("New-faction shield " + clock(o.newShieldMinutes()), Role.GREEN, ""));
            if (o.raidShieldMinutes() > 0) out.add(new Line("Raid shield " + clock(o.raidShieldMinutes()), Role.GREEN, ""));
        });
        out.add(new Line("Coins " + Coins.show(s.coins()) + (s.lastChange().isEmpty() ? "" : "  " + s.lastChange()), Role.TEXT, ""));
        out.add(new Line(s.here(), s.hereRole(), ""));
        sections.add(new Section(OWN, OWN_ORDER, out.size(), out));
        if (!s.standings().isEmpty()) {
            var rows = new ArrayList<Line>();
            rows.add(new Line("Standings", Role.DIM, ""));
            for (var r : s.standings()) {
                String arrow = r.trend() > 0 ? " ▲" : r.trend() < 0 ? " ▼" : "";
                rows.add(new Line((r.mine() ? "» " : "") + "[" + r.tag() + "] " + r.power() + " · " + r.claims() + " · " + r.members() + arrow, Role.FACTION, r.colour()));
            }
            sections.add(new Section(STANDINGS, STANDINGS_ORDER, 0, rows));
        }
        if (!s.notices().isEmpty()) {
            var notices = new ArrayList<Line>();
            notices.add(new Line("Notices", Role.DIM, ""));
            for (String n : s.notices()) notices.add(new Line(n, n.contains("captured") || n.contains("vulnerable") ? Role.AMBER : Role.TEXT, ""));
            sections.add(new Section(NOTICES, NOTICES_ORDER, 0, notices));
        }
        if (!s.movers().isEmpty() || !s.hand().isEmpty()) {
            var market = new ArrayList<Line>();
            market.add(new Line("Market", Role.DIM, ""));
            for (String m : s.movers()) market.add(new Line(m, Role.TEXT, ""));
            if (!s.hand().isEmpty()) market.add(new Line(s.hand(), Role.TEXT, ""));
            sections.add(new Section(MARKET, MARKET_ORDER, 0, market));
        }
        return List.copyOf(sections);
    }

    private static String clock(long minutes) { return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m"; }

    // ----- The payload's text: one line, read back exactly -----

    public static String encode(State s) {
        var d = new LinkedHashMap<String, String>();
        s.own().ifPresent(o -> d.put("own", String.join("|", o.tag(), o.name(), o.colour(), String.valueOf(o.online()), String.valueOf(o.members()), String.valueOf(o.leader()),
                String.valueOf(o.power()), String.valueOf(o.claims()), String.valueOf(o.capacity()), String.valueOf(o.exposed()), String.valueOf(o.newShieldMinutes()),
                String.valueOf(o.raidShieldMinutes()))));
        d.put("power", s.myPower() + "|" + s.maxPower() + "|" + s.progress());
        d.put("coins", String.valueOf(s.coins()));
        d.put("change", s.lastChange());
        d.put("here", s.hereRole().name() + "|" + s.here());
        var rows = new ArrayList<String>();
        for (var r : s.standings()) rows.add(String.join("~", r.tag(), r.name(), r.colour(), String.valueOf(r.power()), String.valueOf(r.claims()), String.valueOf(r.members()),
                String.valueOf(r.trend()), String.valueOf(r.mine())));
        d.put("rows", String.join("|", rows));
        d.put("notices", String.join("\u001f", s.notices()));
        d.put("movers", String.join("\u001f", s.movers()));
        d.put("hand", s.hand());
        return EventData.encode(d);
    }

    public static State decode(String text) {
        var d = EventData.decode(text);
        Optional<Own> own = Optional.ofNullable(d.get("own")).map(v -> {
            String[] o = v.split("\\|", -1);
            return new Own(o[0], o[1], o[2], Integer.parseInt(o[3]), Integer.parseInt(o[4]), Boolean.parseBoolean(o[5]), Integer.parseInt(o[6]),
                    Integer.parseInt(o[7]), Integer.parseInt(o[8]), Integer.parseInt(o[9]), Long.parseLong(o[10]), Long.parseLong(o[11]));
        });
        String[] p = d.get("power").split("\\|");
        String[] h = d.get("here").split("\\|", 2);
        var rows = new ArrayList<Row>();
        if (!d.get("rows").isEmpty()) for (String r : d.get("rows").split("\\|")) {
            String[] x = r.split("~", -1);
            rows.add(new Row(x[0], x[1], x[2], Integer.parseInt(x[3]), Integer.parseInt(x[4]), Integer.parseInt(x[5]), Integer.parseInt(x[6]), Boolean.parseBoolean(x[7])));
        }
        return new State(own, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Double.parseDouble(p[2]), Long.parseLong(d.get("coins")), d.get("change"), h[1],
                Role.valueOf(h[0]), rows, split(d.get("notices")), split(d.get("movers")), d.get("hand"));
    }

    private static List<String> split(String text) { return text.isEmpty() ? List.of() : List.of(text.split("\u001f")); }

    /** The label and role of the land at {@code chunk} for {@code viewer}. */
    public static Map.Entry<String, Role> here(UUID viewer, FactionBook factions, PowerBook powers, Map<String, String> claims, java.util.Set<String> safe,
                                               FactionSettings settings, String chunk) {
        if (safe.contains(chunk)) return Map.entry("Safe zone", Role.GREEN);
        String owner = claims.get(chunk);
        if (owner == null) return Map.entry("Wilderness", Role.DIM);
        var mine = factions.of(viewer);
        UUID id = UUID.fromString(owner);
        if (mine.isPresent() && mine.get().id().equals(id)) return Map.entry("Your land", Role.GREEN);
        var f = factions.get(id).orElse(null);
        if (f == null) return Map.entry("Wilderness", Role.DIM);
        var relation = mine.map(m -> factions.relation(m.id(), id)).orElse(Faction.Relation.ENEMY);
        boolean exposed = Capture.exposure(factions, powers, claims, id, settings).overextended();
        String label = f.name() + " (" + relation.name().toLowerCase(java.util.Locale.ROOT) + ")" + (exposed ? ", exposed, capturable" : "");
        return Map.entry(label, exposed ? Role.RED : relation == Faction.Relation.ENEMY ? Role.AMBER : Role.TEXT);
    }
}
