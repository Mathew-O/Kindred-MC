package dev.kindred.core.chronicle;

import dev.kindred.core.wire.Visibility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In plain terms: the story of the world kept short, so months of play stay readable: each player's day, each
 * faction's and settlement's week, and the world's month, each a few lines that name the events they rest on. Each
 * tier is written only from the tier below it. Without a model it is a plain list of the day's events by kind, so
 * nothing depends on one. The exact facts (who died how often, who owns what, what sold for how much) stay in the
 * ledgers; a summary that contradicts them, or cites an event that does not exist, is refused.
 *
 * Technical: F-33's pure core. {@link Event} is the staged shape of F-31's {@code WorldEvent} (its id, tick, kind,
 * actor, subject and one line of text); the adapter maps one to the other once the Record is on main. A
 * {@link Summary}'s visibility follows its scope: a player's day is that player's own, a faction's week its members',
 * a settlement's its residents', the world's month public.
 */
public final class Chronicle {
    private Chronicle() { }

    /** A game day in ticks. */
    public static final long DAY = 24_000;

    /** The tiers, each written from the one below. */
    public enum Tier {
        PLAYER_DAY(DAY), FACTION_WEEK(7 * DAY), SETTLEMENT_WEEK(7 * DAY), WORLD_MONTH(30 * DAY);

        /** The span of one summary, in ticks. */
        public final long span;

        Tier(long span) { this.span = span; }

        /** The tier this one is written from; null for the first, written from events. */
        public Tier below() {
            return switch (this) {
                case PLAYER_DAY -> null;
                case FACTION_WEEK, SETTLEMENT_WEEK -> PLAYER_DAY;
                case WORLD_MONTH -> FACTION_WEEK;
            };
        }

        public Visibility visibility() {
            return switch (this) {
                case PLAYER_DAY -> Visibility.SELF;
                case FACTION_WEEK -> Visibility.FACTION;
                case SETTLEMENT_WEEK -> Visibility.SETTLEMENT;
                case WORLD_MONTH -> Visibility.PUBLIC;
            };
        }

        /** The first tick of the span holding {@code tick}. */
        public long start(long tick) { return Math.floorDiv(tick, span) * span; }
    }

    /** One recorded event, as the Chronicle reads it (staged for F-31's {@code WorldEvent}). */
    public record Event(long id, long tick, String kind, UUID actor, String subject, String text) {
        public Event {
            if (id <= 0) throw new IllegalArgumentException("An event the Record kept has a positive id");
            Objects.requireNonNull(kind);
            subject = subject == null ? "" : subject;
            text = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").strip();
        }
    }

    /**
     * One summary: its tier, its scope (the player's id, the faction's, the settlement's, or "world"), its span, its
     * words, the event ids it cites, and whether a model wrote it or it is the plain list.
     */
    public record Summary(Tier tier, String scope, long from, long until, String text, List<Long> cites, boolean model) {
        public static final int MAX_CHARS = 1_600;

        public Summary {
            Objects.requireNonNull(tier);
            Objects.requireNonNull(scope);
            text = text == null ? "" : text.strip();
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS - 1) + "…";
            cites = List.copyOf(cites);
            if (until <= from) throw new IllegalArgumentException("A summary spans some time");
        }

        public Visibility visibility() { return tier.visibility(); }
    }

    /** The kinds of the World Record a player's day is written from (F-31's), and the page's own kind. */
    public static final java.util.Set<String> DAY_KINDS = java.util.Set.of("death", "journal", "capture", "claim", "unclaim", "match");
    public static final String PAGE = "chronicle.page";

    /**
     * One of the World Record's events as the Chronicle reads it, or nothing for a kind it does not tell: a death by its
     * cause, a journal line by its type and detail, a claim, an unclaim or a capture by its chunk, a match by its result.
     */
    public static Optional<Event> fromRecord(dev.kindred.core.record.WorldEvent e) {
        if (e.id() <= 0 || !DAY_KINDS.contains(e.kind())) return Optional.empty();
        var d = e.data();
        String who = e.subject().isEmpty() ? "someone" : e.subject();
        String text = switch (e.kind()) {
            case "death" -> who + " died: " + d.getOrDefault("cause", "unknown").replace('.', ' ');
            case "journal" -> who + ": " + d.getOrDefault("detail", "");
            case "capture", "claim", "unclaim" -> e.kind() + " " + who + " " + String.join(" ", d.values());
            case "match" -> "match " + who + " " + d.getOrDefault("result", d.getOrDefault("state", ""));
            default -> who;
        };
        if (text.length() > 200) text = text.substring(0, 199) + "…";
        return Optional.of(new Event(e.id(), Math.max(0, e.tick()), e.kind(), e.actor(), e.subject(), text));
    }

    /** At most this many lines a day is told from; a plain page at most this many characters; a report quotes at most this much of a page. */
    public static final int MAX_LINES = 40, PLAIN_CHARS = 600, REPORT_CHARS = 360;

    /**
     * A day's events condensed to what is worth telling (measured: a player logs some 1,900 events a game day, most of
     * them journal lines): every death, claim, release, capture and match its own line, the first {@code MAX_LINES} of
     * them; each kind of journal line one line saying how many and its first and last, numbered by its first event. Every
     * line keeps a real event's id, so a page citing them cites the Record; in the order of those ids.
     */
    public static List<Event> condense(List<Event> events) {
        var notable = new ArrayList<Event>();
        var journals = new LinkedHashMap<String, List<Event>>();
        for (Event e : events) {
            if ("journal".equals(e.kind())) journals.computeIfAbsent(e.subject(), k -> new ArrayList<>()).add(e);
            else if (notable.size() < MAX_LINES) notable.add(e);
        }
        var groups = new ArrayList<>(journals.entrySet());
        groups.sort(Comparator.comparingInt((Map.Entry<String, List<Event>> g) -> -g.getValue().size()));
        var out = new ArrayList<>(notable);
        for (var g : groups) {
            if (out.size() >= MAX_LINES) break;
            var list = g.getValue();
            var first = list.getFirst();
            String text = list.size() == 1 ? first.text()
                    : g.getKey() + " " + list.size() + " times, first " + after(first.text()) + ", last " + after(list.getLast().text());
            if (text.length() > 200) text = text.substring(0, 199) + "…";
            out.add(new Event(first.id(), first.tick(), first.kind(), first.actor(), first.subject(), text));
        }
        out.sort(Comparator.comparingLong(Event::id));
        return List.copyOf(out);
    }

    /** A journal line's words after its type ("goal: built a table" → "built a table"). */
    private static String after(String text) {
        int colon = text.indexOf(": ");
        String rest = colon < 0 ? text : text.substring(colon + 2);
        return rest.length() > 70 ? rest.substring(0, 69) + "…" : rest;
    }

    /** The plain list of exactly these events (a batch read from the Record since the last page), for a span. */
    public static Summary plainOf(Tier tier, String scope, long from, long until, List<Event> events) {
        var shifted = new ArrayList<Event>();
        for (Event e : events) shifted.add(new Event(e.id(), from, e.kind(), e.actor(), e.subject(), e.text()));
        var page = plain(tier, scope, from, shifted);
        String text = page.text().length() <= PLAIN_CHARS ? page.text() : page.text().substring(0, PLAIN_CHARS - 1) + "…";
        return new Summary(tier, scope, from, Math.max(from + 1, until), text, page.cites(), false);
    }

    /** A page as a World Record event's data: its span, the last event it read, its words, its citations, and who wrote it. */
    public static java.util.Map<String, String> pageData(Summary page, long through) {
        var d = new LinkedHashMap<String, String>();
        d.put("tier", page.tier().name());
        d.put("from", String.valueOf(page.from()));
        d.put("until", String.valueOf(page.until()));
        d.put("through", String.valueOf(through));
        d.put("text", page.text());
        d.put("cites", page.cites().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        d.put("model", page.model() ? "1" : "0");
        return d;
    }

    /** A page read back from its World Record event, or nothing when it is not one. */
    public static Optional<Summary> fromPage(dev.kindred.core.record.WorldEvent e) {
        if (!PAGE.equals(e.kind())) return Optional.empty();
        try {
            var d = e.data();
            var cites = new ArrayList<Long>();
            for (String c : d.getOrDefault("cites", "").split(",")) if (!c.isBlank()) cites.add(Long.parseLong(c.strip()));
            return Optional.of(new Summary(Tier.valueOf(d.get("tier")), e.subject(), Long.parseLong(d.get("from")), Long.parseLong(d.get("until")),
                    d.getOrDefault("text", ""), cites, "1".equals(d.get("model"))));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** A model's page as a summary to check: empty when it wrote nothing. */
    public static Optional<Summary> fromModel(Tier tier, String scope, long from, Optional<String> text, List<Long> cites) {
        return text.filter(t -> !t.isBlank()).map(t -> new Summary(tier, scope, from, from + tier.span, t, cites, true));
    }

    /**
     * The plain list of a span's events by kind, the most frequent first: "3 deaths: a Zombie; a fall; lava [4, 9, 12].
     * 1 trade: 4 iron for bread [7]." At most three examples a kind, every event cited.
     */
    public static Summary plain(Tier tier, String scope, long from, List<Event> events) {
        var byKind = new TreeMap<String, List<Event>>();
        for (Event e : events) {
            if (e.tick() < from || e.tick() >= from + tier.span) continue;
            byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        }
        var kinds = new ArrayList<>(byKind.entrySet());
        kinds.sort(Comparator.comparingInt((Map.Entry<String, List<Event>> en) -> -en.getValue().size()).thenComparing(Map.Entry::getKey));
        var text = new StringBuilder();
        var cites = new ArrayList<Long>();
        for (var en : kinds) {
            var list = en.getValue();
            if (text.length() > 0) text.append(' ');
            text.append(list.size()).append(' ').append(plural(en.getKey(), list.size()));
            var examples = list.stream().map(Event::text).filter(t -> !t.isEmpty()).limit(3).toList();
            if (!examples.isEmpty()) text.append(": ").append(String.join("; ", examples));
            var ids = list.stream().map(Event::id).toList();
            cites.addAll(ids);
            text.append(' ').append(ids).append('.');
        }
        if (text.isEmpty()) text.append("Nothing recorded.");
        return new Summary(tier, scope, from, from + tier.span, text.toString(), cites, false);
    }

    /** The plain list a tier above writes from the summaries below it: each in turn, its citations carried up. */
    public static Summary plainFrom(Tier tier, String scope, long from, List<Summary> below) {
        var text = new StringBuilder();
        var cites = new ArrayList<Long>();
        for (Summary s : below) {
            if (s.tier() != tier.below() || s.from() < from || s.until() > from + tier.span) continue;
            if (text.length() > 0) text.append(' ');
            text.append("Day ").append(s.from() / DAY + 1).append(" (").append(s.scope()).append("): ").append(s.text());
            cites.addAll(s.cites());
        }
        if (text.isEmpty()) text.append("Nothing recorded.");
        return new Summary(tier, scope, from, from + tier.span, text.toString(), cites, false);
    }

    private static String plural(String kind, int n) {
        String word = kind.replace('_', ' ').replace('.', ' ');
        return n == 1 || word.endsWith("s") ? word : word + "s";
    }

    // ---- The checks -------------------------------------------------------------------------------------------

    /**
     * The exact facts a summary may not contradict, as the ledgers hold them for its span: deaths by name, owners by
     * thing, prices in coins by item. Names, things and items are matched without case.
     */
    public record Facts(Map<String, Integer> deaths, Map<String, String> owners, Map<String, Integer> prices) {
        public Facts {
            deaths = lower(deaths);
            owners = lower(owners);
            prices = lower(prices);
        }

        public static Facts none() { return new Facts(Map.of(), Map.of(), Map.of()); }

        private static <V> Map<String, V> lower(Map<String, V> map) {
            var out = new LinkedHashMap<String, V>();
            if (map != null) map.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT).strip(), v instanceof String s ? (V) s.toLowerCase(Locale.ROOT).strip() : v));
            return Map.copyOf(out);
        }
    }

    /** What is wrong with a summary: citations to events that do not exist, and statements that contradict a ledger. */
    public record Problems(List<Long> missing, List<String> contradictions) {
        public boolean none() { return missing.isEmpty() && contradictions.isEmpty(); }
    }

    private static final Pattern DEATHS = Pattern.compile("\\b([A-Z][\\w-]{1,15})\\b[^.;]{0,40}?\\b(no|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+deaths?\\b|\\b([A-Z][\\w-]{1,15})\\s+died\\s+(once|twice|(?:one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+times)", Pattern.CASE_INSENSITIVE);
    /**
     * Words that put events in an order of their own (the lines are already in order, and a model's "earlier" or "between
     * the deaths" was measured against the ticks), and words about the page itself rather than the world.
     */
    private static final Pattern ORDER = Pattern.compile("\\b(earlier|before|between|meanwhile|previously|prior to|beforehand)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("\\b(the|these|those) (lines?|page|entries|entry|records? given)\\b|\\bthe chronicle\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRICE = Pattern.compile("\\b([a-z][\\w ]{1,30}?)\\s+(?:sold\\s+)?(?:at|for)\\s+(\\d+)\\s+coins?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The checks a summary must pass before it is kept: every event it cites exists in {@code existing} (the
     * Record's ids for its span and scope), and no count of deaths, owner or price it states differs from the
     * ledgers' {@code facts}. A statement about someone or something the ledgers do not hold is not checked here. A
     * model's page also may not order events in words of its own nor speak of the page or its lines.
     */
    public static Problems check(Summary summary, java.util.Set<Long> existing, Facts facts) {
        var missing = new ArrayList<Long>();
        for (Long id : summary.cites()) if (!existing.contains(id)) missing.add(id);
        var wrong = new ArrayList<String>();
        String text = summary.text();
        var m = DEATHS.matcher(text);
        while (m.find()) {
            String who = (m.group(1) != null ? m.group(1) : m.group(3)).toLowerCase(Locale.ROOT);
            Integer known = facts.deaths().get(who);
            if (known == null) continue;
            int said = number(m.group(2) != null ? m.group(2) : m.group(4));
            if (said >= 0 && said != known) wrong.add(m.group().strip() + " (the ledger: " + known + ")");
        }
        for (var e : facts.owners().entrySet()) {
            var owns = Pattern.compile("\\b([A-Z][\\w-]{1,15})\\s+(?:owns|holds|took|captured)\\s+(?:the\\s+)?" + Pattern.quote(e.getKey()) + "(?![\\w])",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            while (owns.find()) {
                if (!owns.group(1).toLowerCase(Locale.ROOT).equals(e.getValue())) wrong.add(owns.group().strip() + " (the ledger: " + e.getValue() + ")");
            }
        }
        m = PRICE.matcher(text);
        while (m.find()) {
            String item = m.group(1).toLowerCase(Locale.ROOT).strip();
            for (var e : facts.prices().entrySet()) {
                if ((item.equals(e.getKey()) || item.endsWith(" " + e.getKey())) && Integer.parseInt(m.group(2)) != e.getValue()) {
                    wrong.add(m.group().strip() + " (the ledger: " + e.getValue() + ")");
                }
            }
        }
        if (summary.model()) {
            var order = ORDER.matcher(text);
            while (order.find()) wrong.add("\"" + order.group() + "\" orders events itself");
            var meta = META.matcher(text);
            while (meta.find()) wrong.add("\"" + meta.group() + "\" speaks of the page, not the world");
        }
        return new Problems(List.copyOf(missing), List.copyOf(wrong));
    }

    private static int number(String said) {
        String s = said.toLowerCase(Locale.ROOT).replace(" times", "").strip();
        return switch (s) {
            case "no" -> 0;
            case "one", "once" -> 1;
            case "two", "twice" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            default -> s.matches("\\d+") ? Integer.parseInt(s) : -1;
        };
    }

    /**
     * The summary to keep: the model's when it passes {@link #check}, else the plain list (which cites only real
     * events by construction), so a bad model answer never reaches anyone.
     */
    public static Summary keep(Optional<Summary> fromModel, Summary plain, java.util.Set<Long> existing, Facts facts) {
        return fromModel.filter(s -> check(s, existing, facts).none()).orElse(plain);
    }

    /**
     * The history a strategist's report carries (F-19's prompt, F-33's use): the player's last {@code days} day
     * summaries, oldest first, then only the events since the newest of them, at most {@code keep}; with no summary yet,
     * the events as before. Each event line is "at Ns: what", N in game seconds.
     */
    public static List<String> reportHistory(List<Summary> playerDays, List<String> events, long sinceTick, int days, int keep) {
        var out = new ArrayList<String>();
        var recent = playerDays.stream().filter(s -> s.tier() == Tier.PLAYER_DAY).sorted(Comparator.comparingLong(Summary::from)).toList();
        recent = recent.subList(Math.max(0, recent.size() - days), recent.size());
        if (recent.isEmpty()) return List.copyOf(events);
        for (Summary s : recent) {
            String text = s.text().length() <= REPORT_CHARS ? s.text() : s.text().substring(0, REPORT_CHARS - 1) + "…";
            out.add("Day " + (s.from() / DAY + 1) + ": " + text);
        }
        long after = recent.getLast().until();
        var later = new ArrayList<String>();
        for (String e : events) {
            var mm = Pattern.compile("^at (\\d+)s: ").matcher(e);
            if (!mm.find() || Long.parseLong(mm.group(1)) * 20 >= after) later.add(e);
        }
        out.addAll(later.subList(Math.max(0, later.size() - keep), later.size()));
        return List.copyOf(out);
    }
}
