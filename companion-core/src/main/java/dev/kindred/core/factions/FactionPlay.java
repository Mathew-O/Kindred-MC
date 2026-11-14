package dev.kindred.core.factions;

import dev.kindred.core.Personality;
import dev.kindred.core.market.AuctionBook;
import dev.kindred.core.market.Auctions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: how an AI player plays a faction world. With no faction it joins one that invited it or founds its own;
 * in one, it claims land as its faction's power allows, sells what it has spare and buys or bids for what it lacks while
 * keeping coins back for the next claim, goes home when its land is being taken, and only then raids: it scouts and
 * captures an enemy's exposed border chunk it has seen with its own eyes, never one it was not there to see.
 *
 * Technical: The pure decision of M-06i. {@link #decide} reads one immutable {@link View} (the ledgers' public facts,
 * the player's own sightings, quotes and wants, its personality's bounded weights) and returns its choices in order,
 * the first to do now: membership, then claims, then earning and spending, then defence, then raids; a capture of its
 * own land under way puts going home before all of it, and no raid is chosen while defence is; a combative player
 * ({@value #RAID_FIRST} or more) with an exposed enemy border in sight puts the raid before its own expansion. Every claim and
 * capture is tried against the same rules the commands use ({@link Territory#claim}, {@link Capture#capture}) with the
 * view's facts, so a choice the rules would refuse is never offered; the adapter checks again at dispatch. Personality
 * nudges thresholds only; it never permits anything the rules refuse.
 */
public final class FactionPlay {
    private FactionPlay() { }

    /** One game day, in milliseconds of real time (20 minutes). */
    public static final long GAME_DAY_MS = 20 * 60_000L;
    /** A loss this recent means a capture is under way. */
    public static final long UNDER_ATTACK_MS = 10 * 60_000L;
    /** A sighting older than this is checked again before it is acted on. */
    public static final long STALE_SIGHTING_MS = 60 * 60_000L;
    /** Combativeness at which a seen exposed border is taken before free land is claimed. */
    public static final int RAID_FIRST = 60;
    /** How many chunks round its site a faction's first land may be, when the site itself is refused. */
    public static final int SITE_REACH = 3;

    static final String UNDER_ATTACK = "Home: its land is being taken";

    public enum Kind { FOUND, JOIN, INVITE, CLAIM, SELL, BUY, BID, DEFEND, SCOUT, CAPTURE }

    /**
     * The least a kind's spare must be worth at the market's quote before it is sold: a coin. A spare worth less waits
     * until there is more of it. Its {@code target} names the kinds sold, comma-separated.
     */
    public static final long MIN_LOT = Coins.CENT;

    /** One thing to do: {@code target} a faction id, chunk key, item id or listing id; {@code amount} a count or coins (hundredths). */
    public record Choice(Kind kind, String target, long amount, String reason) {
        public Choice { Objects.requireNonNull(kind); target = target == null ? "" : target; Objects.requireNonNull(reason); }
    }

    /** An enemy chunk the player saw itself, and when: the only way it knows where another faction's land lies. */
    public record Sighting(String chunk, String faction, long seenAtMs) {}

    /** Spare stock the market quoted: {@code cents} what selling all of it pays. */
    public record Spare(String item, int count, long cents) {}

    /** Something its plans lack: {@code most} the most it would pay for all of it (hundredths). */
    public record Want(String item, int count, long most) {}

    /**
     * What the player knows. {@code site} the chunk of the base site it chose (M-01c), if any; {@code standing} the
     * chunk it stands in; {@code healthy} whether it is fit to fight (health and food); {@code asks} the market's ask for
     * each wanted item, for its whole count, where it sells it; {@code nearby} the players it saw near it lately;
     * {@code setAside} the places it could find no way to lately (chunk keys, or a home), not chosen again for a while;
     * {@code siteAlso} the second chunk its first base's ground covers, when no one chunk could hold it, claimed next.
     */
    public record View(UUID self, Personality personality, FactionBook factions, PowerBook powers, Map<String, String> claims, Set<String> safe,
                       Capture.Book captures, long coins, FactionSettings settings, long nowMs, Optional<Territory.Chunk> site,
                       Optional<Territory.Chunk> standing, boolean healthy, List<Spare> spare, List<Want> wants, Map<String, Long> asks,
                       AuctionBook auctions, List<Sighting> seen, List<UUID> nearby, Set<String> setAside, Optional<Territory.Chunk> siteAlso) {
        /** A view with its base's ground in one chunk. */
        public View(UUID self, Personality personality, FactionBook factions, PowerBook powers, Map<String, String> claims, Set<String> safe,
                    Capture.Book captures, long coins, FactionSettings settings, long nowMs, Optional<Territory.Chunk> site,
                    Optional<Territory.Chunk> standing, boolean healthy, List<Spare> spare, List<Want> wants, Map<String, Long> asks,
                    AuctionBook auctions, List<Sighting> seen, List<UUID> nearby, Set<String> setAside) {
            this(self, personality, factions, powers, claims, safe, captures, coins, settings, nowMs, site, standing, healthy, spare, wants, asks,
                    auctions, seen, nearby, setAside, Optional.empty());
        }

        public View {
            Objects.requireNonNull(siteAlso);
            Objects.requireNonNull(self);
            Objects.requireNonNull(personality);
            claims = Map.copyOf(claims);
            safe = Set.copyOf(safe);
            spare = List.copyOf(spare);
            wants = List.copyOf(wants);
            asks = Map.copyOf(asks);
            seen = List.copyOf(seen);
            nearby = List.copyOf(nearby);
            setAside = Set.copyOf(setAside);
        }
    }

    /** The player's choices, the first to do now; empty when there is nothing for the faction world to ask of it. */
    public static List<Choice> decide(View view) {
        var out = new ArrayList<Choice>();
        var mine = view.factions().of(view.self()).orElse(null);
        if (mine == null) {
            membership(view).ifPresent(out::add);
            return out;
        }
        var at = new Factions.At(0, view.nowMs(), Territory.OVERWORLD);
        var defend = defend(view, mine);
        // A capture under way: home before anything else, and nothing else meanwhile.
        if (defend.isPresent() && defend.get().reason().equals(UNDER_ATTACK)) return List.of(defend.get());
        boolean officer = mine.members().get(view.self()).atLeast(Faction.Rank.OFFICER);
        long reserve = reserve(view);
        // A raid only while nothing at home calls it back, and only by an officer fit to fight with a will to.
        List<Choice> raids = officer && defend.isEmpty() && view.healthy() && view.personality().combativeness() >= 40
                ? raid(view, mine, at) : List.of();
        // A combative player with an exposed border in sight takes it before it spreads into free land; others grow first.
        boolean raidFirst = !raids.isEmpty() && view.personality().combativeness() >= RAID_FIRST;
        if (raidFirst) out.addAll(raids);
        // Its people first: a player it met who is in no faction is asked before land is bought.
        if (officer && view.personality().sociability() >= 40) invite(view, mine).ifPresent(out::add);
        if (officer) claim(view, mine, at).ifPresent(out::add);
        // A kind is sold only once its spare makes a lot worth selling: a sale for a fraction of a coin is churn (factions2c:
        // about 290 a seed, each a World Record event and a turn between its goal's steps, for under a coin each).
        var lots = view.spare().stream().filter(sp -> sp.cents() >= MIN_LOT).toList();
        if (!lots.isEmpty()) {
            long cents = lots.stream().mapToLong(Spare::cents).sum();
            String kinds = String.join(",", lots.stream().map(Spare::item).toList());
            out.add(new Choice(Kind.SELL, kinds, cents, "Sell what is spare for " + Coins.show(cents)));
        }
        long spendable = Math.max(0, view.coins() - reserve);
        for (var want : view.wants()) {
            Long ask = view.asks().get(want.item());
            var bid = bid(view, want, spendable);
            if (bid.isPresent() && (ask == null || bid.get().amount() < ask)) {
                out.add(bid.get());
                spendable -= bid.get().amount();
            } else if (ask != null && ask <= want.most() && ask <= spendable) {
                out.add(new Choice(Kind.BUY, want.item(), want.count(), "Buy " + want.count() + " " + want.item() + " for " + Coins.show(ask)));
                spendable -= ask;
            }
        }
        defend.ifPresent(out::add);
        if (!raidFirst) out.addAll(raids);
        return out;
    }

    /**
     * No faction yet: join the invitation of the faction with the fewest members, where it is wanted most (a sociable
     * player takes one at once), or found its own
     * (a player of initiative does at once; any other after a game day of waiting for an invitation).
     */
    static Optional<Choice> membership(View view) {
        var invited = view.factions().factions().values().stream()
                .filter(f -> view.factions().invited(f.id(), view.self()) && f.members().size() < view.settings().maxMembers())
                .sorted(Comparator.comparingInt((Faction f) -> f.members().size()).thenComparing(Faction::name))
                .toList();
        var p = view.personality();
        if (!invited.isEmpty() && p.sociability() >= 35) {
            var f = invited.getFirst();
            return Optional.of(new Choice(Kind.JOIN, f.id().toString(), 0, "Join " + f.name() + ", who invited it"));
        }
        long seen = view.powers().firstSeen().getOrDefault(view.self(), view.nowMs());
        boolean waited = view.nowMs() - seen >= GAME_DAY_MS;
        if (p.initiative() >= 50 || waited || !invited.isEmpty()) {
            return Optional.of(new Choice(Kind.FOUND, "", 0, invited.isEmpty() ? "Found a faction of its own" : "Found its own rather than join"));
        }
        return Optional.empty();
    }

    /**
     * An officer's invitation to a player it saw near it lately who is in no faction and not yet asked, while its faction
     * has room: the first by id, one at a time.
     */
    static Optional<Choice> invite(View view, Faction mine) {
        if (mine.members().size() >= view.settings().maxMembers()) return Optional.empty();
        return view.nearby().stream()
                .filter(p -> !p.equals(view.self()) && view.factions().of(p).isEmpty() && !view.factions().invited(mine.id(), p))
                .sorted(Comparator.comparing(UUID::toString))
                .findFirst()
                .map(p -> new Choice(Kind.INVITE, p.toString(), 0, "Invite a player it met into " + mine.name()));
    }

    /** Coins kept back: a claim's cost, more for a cautious player (up to three claims' worth). */
    static long reserve(View view) {
        long claim = view.settings().claimCost() * Coins.CENT;
        return claim + claim * view.personality().caution() / 50;
    }

    /**
     * The next claim: the base site when the faction holds nothing, otherwise the free chunk touching its land nearest its
     * first claim, while the rules allow one (capacity, separation, the border, spawn, the cost).
     */
    static Optional<Choice> claim(View view, Faction mine, Factions.At at) {
        String id = mine.id().toString();
        var held = view.claims().entrySet().stream().filter(e -> e.getValue().equals(id)).map(e -> Territory.Chunk.of(e.getKey())).toList();
        var candidates = new ArrayList<Territory.Chunk>();
        if (held.isEmpty()) view.site().ifPresent(site -> {
            // Its site, or the nearest chunk round it the rules allow (another faction settled beside it first).
            var first = beyondSpawn(site, view.settings());
            for (int r = 0; r <= SITE_REACH; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) candidates.add(first.offset(dx, dz));
            }
        });
        else {
            var centre = held.getFirst();
            // Its first base's ground across two chunks: the other one first, so the base stands wholly on its land.
            view.siteAlso().filter(also -> view.site().map(site -> held.contains(site)).orElse(false) && !view.claims().containsKey(also.key()))
                    .ifPresent(candidates::add);
            for (var chunk : held) for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                var next = chunk.offset(d[0], d[1]);
                if (!view.claims().containsKey(next.key()) && !candidates.contains(next)) candidates.add(next);
            }
            int first = candidates.size() > 0 && view.siteAlso().map(candidates.getFirst()::equals).orElse(false) ? 1 : 0;
            candidates.subList(first, candidates.size()).sort(Comparator.comparingLong((Territory.Chunk c) -> (long) (c.x() - centre.x()) * (c.x() - centre.x()) + (long) (c.z() - centre.z()) * (c.z() - centre.z()))
                    .thenComparingInt(Territory.Chunk::x).thenComparingInt(Territory.Chunk::z));
        }
        // Only once the claim's cost is there without touching the coins kept for the one after, beyond the first.
        long needed = held.isEmpty() ? view.settings().claimCost() * Coins.CENT : reserve(view);
        if (view.coins() < needed) return Optional.empty();
        for (var chunk : candidates) {
            if (view.setAside().contains(chunk.key())) continue;
            var decision = Territory.claim(view.factions(), view.powers(), view.claims(), view.safe(), view.coins(), view.settings(), view.self(), chunk, at);
            if (decision.accepted()) return Optional.of(new Choice(Kind.CLAIM, chunk.key(), view.settings().claimCost() * Coins.CENT,
                    held.isEmpty() ? "Claim the base site" : "Claim land beside its own"));
        }
        return Optional.empty();
    }

    /**
     * Where a faction's first land can be: its site, or, when the site is within the spawn exclusion, the chunk just
     * beyond the exclusion on the same bearing from spawn (east from spawn itself), so a faction founded at spawn walks
     * out to settle rather than never claiming.
     */
    static Territory.Chunk beyondSpawn(Territory.Chunk site, FactionSettings settings) {
        if (site.nearestToSpawn() >= settings.spawnExclusion()) return site;
        double cx = site.x() * 16 + 8, cz = site.z() * 16 + 8;
        double length = Math.hypot(cx, cz);
        double dx = length < 1 ? 1 : cx / length, dz = length < 1 ? 0 : cz / length;
        double reach = settings.spawnExclusion() + 24;
        var out = Territory.Chunk.at(site.dimension(), (int) Math.floor(dx * reach), (int) Math.floor(dz * reach));
        while (out.nearestToSpawn() < settings.spawnExclusion()) out = out.offset((int) Math.signum(Math.round(dx * 10)), (int) Math.signum(Math.round(dz * 10)));
        return out;
    }

    /** A bid on an open listing of what it wants, at the least the house takes, within what it would pay and can spare. */
    static Optional<Choice> bid(View view, Want want, long spendable) {
        if (view.auctions() == null) return Optional.empty();
        return view.auctions().listings().values().stream()
                .filter(l -> l.key().equals(want.item()) && !l.seller().equals(view.self()) && !view.self().equals(l.bidder()) && l.closesAt() > view.nowMs())
                .map(l -> Map.entry(l, Auctions.least(l)))
                .filter(e -> e.getValue() <= want.most() && e.getValue() <= spendable)
                .min(Comparator.comparingLong((Map.Entry<AuctionBook.Listing, Long> e) -> e.getValue()).thenComparing(e -> e.getKey().id()))
                .map(e -> new Choice(Kind.BID, e.getKey().id(), e.getValue(), "Bid " + Coins.show(e.getValue()) + " for " + want.item()));
    }

    /**
     * Home when its land is being taken (a loss within ten minutes), or, for a cautious player, while its faction is
     * exposed; not when it already stands on its own land.
     */
    static Optional<Choice> defend(View view, Faction mine) {
        String id = mine.id().toString();
        if (view.standing().map(c -> id.equals(view.claims().get(c.key()))).orElse(false)) return Optional.empty();
        Long lost = view.captures().losses().get(id);
        boolean attacked = lost != null && view.nowMs() - lost < UNDER_ATTACK_MS;
        boolean exposed = Capture.exposure(view.factions(), view.powers(), view.claims(), mine.id(), view.settings()).overextended();
        if (!attacked && !(exposed && view.personality().caution() >= 50)) return Optional.empty();
        String home = mine.home() == null || mine.home().isEmpty() ? "" : mine.home();
        if (!home.isEmpty() && view.setAside().contains(home)) return Optional.empty();
        return Optional.of(new Choice(Kind.DEFEND, home, 0, attacked ? UNDER_ATTACK : "Home: its faction is exposed"));
    }

    /**
     * A raid by the rule, from what it saw: where it stands in an enemy chunk the rules let it capture now, capture it;
     * otherwise go to the nearest chunk it saw of an enemy now exposed and unshielded, on that enemy's border, to look
     * again and take it there. Nothing is chosen from land it never saw.
     */
    static List<Choice> raid(View view, Faction mine, Factions.At at) {
        var out = new ArrayList<Choice>();
        if (view.standing().isPresent()) {
            var here = view.standing().get();
            var decision = Capture.capture(view.factions(), view.powers(), view.claims(), view.safe(), view.captures(), view.coins(), view.settings(),
                    view.self(), here, at);
            boolean sawIt = view.seen().stream().anyMatch(s -> s.chunk().equals(here.key()));
            if (decision.accepted() && sawIt && view.coins() >= reserve(view)) {
                out.add(new Choice(Kind.CAPTURE, here.key(), view.settings().claimCost() * Coins.CENT, "Capture this exposed border chunk"));
                return out;
            }
        }
        // Never the chunk it stands in (M-06i1b): there, the capture above was refused, and a scout there is arrived at once and
        // weighed again at once, the same scout chosen every tick (583 times in 13 s on main 99063e9, MODES's trace).
        String here = view.standing().map(Territory.Chunk::key).orElse(null);
        var targets = view.seen().stream()
                .filter(s -> s.faction().equals(view.claims().get(s.chunk())) && !view.setAside().contains(s.chunk()))
                .filter(s -> !s.chunk().equals(here))
                .filter(s -> capturable(view, mine, s))
                .sorted(Comparator.comparingDouble((Sighting s) -> distance(view, s)).thenComparing(Sighting::chunk))
                .toList();
        if (!targets.isEmpty()) {
            var s = targets.getFirst();
            boolean stale = view.nowMs() - s.seenAtMs() > STALE_SIGHTING_MS;
            out.add(new Choice(Kind.SCOUT, s.chunk(), 0, stale ? "Look again at an exposed enemy's border it saw" : "Go to an exposed enemy's border it saw"));
        }
        return out;
    }

    private static boolean capturable(View view, Faction mine, Sighting s) {
        UUID owner;
        try { owner = UUID.fromString(s.faction()); } catch (IllegalArgumentException bad) { return false; }
        if (owner.equals(mine.id()) || view.safe().contains(s.chunk())) return false;
        var enemy = view.factions().get(owner).orElse(null);
        if (enemy == null || view.factions().relation(mine.id(), owner) != Faction.Relation.ENEMY) return false;
        if (!Capture.exposure(view.factions(), view.powers(), view.claims(), owner, view.settings()).overextended()) return false;
        if (view.nowMs() < Capture.newShieldUntil(view.factions(), enemy, view.settings())) return false;
        if (view.nowMs() < Capture.raidShieldUntil(view.captures(), owner, view.settings())) return false;
        return Capture.onTheBorder(view.claims(), view.safe(), s.faction(), mine.id().toString(), Territory.Chunk.of(s.chunk()));
    }

    private static double distance(View view, Sighting s) {
        var c = Territory.Chunk.of(s.chunk());
        var from = view.standing().orElse(view.site().orElse(new Territory.Chunk(Territory.OVERWORLD, 0, 0)));
        return Math.hypot(c.x() - from.x(), c.z() - from.z());
    }
}
