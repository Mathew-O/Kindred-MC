package dev.kindred.fabric;

import dev.kindred.core.factions.Faction;
import dev.kindred.core.factions.FactionBook;
import dev.kindred.core.factions.FactionColour;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Factions;
import dev.kindred.core.factions.PowerBook;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.factions.Zone;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: Factions mode's part of the server: it asks the faction and power rules on each request, death and
 * online second, and writes what they accept to the World Record. People and AI players go through the same methods.
 *
 * Technical: The adapter's side of M-06c, owned by {@link PlayerRuntime} and on its thread. It holds no state of its own
 * beyond online time not yet written: the faction and power books are the runtime's {@link Ledgers}, rebuilt from the
 * Record. Online time is measured by the wall clock while a player is connected in a faction world, and written as a
 * {@code power.online} event every five minutes, when the player leaves and when the server stops, so a clean restart
 * keeps regeneration to the second and a crash loses at most five minutes of it.
 */
final class FactionsService {
    /** Online time is written at least this often, in seconds. */
    static final int ONLINE_FLUSH_SECONDS = 300;
    /** A gap longer than this between two measurements (a frozen server) counts only this much. */
    private static final long MAX_STEP_MS = 5_000;

    private final MinecraftServer server;
    private final Supplier<Ledgers> ledgers;
    private final Consumer<List<WorldEvent>> record;
    private FactionSettings settings;
    /** Per online player: when last measured, and the whole milliseconds measured but not yet written. */
    private final Map<UUID, long[]> online = new HashMap<>();
    /** Accounts whose first sight is written or being written. */
    private final java.util.Set<UUID> seenAsked = new java.util.HashSet<>();

    FactionsService(MinecraftServer server, FactionSettings settings, Supplier<Ledgers> ledgers, Consumer<List<WorldEvent>> record) {
        this.server = server;
        this.settings = settings;
        this.ledgers = ledgers;
        this.record = record;
    }

    FactionSettings settings() { return settings; }

    MinecraftServer server() { return server; }

    /** The chunks of the safe zones (M-06f), from the Record; none until the hub is set. */
    java.util.Set<String> safe() { return ledgers.get().safe().chunks(); }

    /** The last refusal each player was told, for fixtures. */
    private final Map<UUID, String> told = new HashMap<>();

    /** {@code player} told {@code line} on its action bar, and kept as its last refusal. */
    void tell(ServerPlayerEntity player, String line) {
        told.put(player.getUuid(), line);
        player.sendMessage(net.minecraft.text.Text.literal(line).formatted(net.minecraft.util.Formatting.RED), true);
    }

    String lastTold(UUID player) { return told.getOrDefault(player, ""); }

    /**
     * Why a player's hit on another player stands refused in a faction world, or null where it may land: a safe zone
     * first, then the faction rules (D7). A running match's own leave is the caller's.
     */
    String strikeRefusal(ServerPlayerEntity attacker, ServerPlayerEntity target) {
        if (zone(attacker) == Zone.SAFE || zone(target) == Zone.SAFE) return "No fighting in a safe zone";
        return mayAttack(attacker, target) ? null : "Only enemy factions' members fight here";
    }

    /**
     * The borders kept as the settings have them once the hub is set (M-06f): the overworld's {@code border} across round
     * (0, 0) with a 20-block warning, the Nether's an eighth of it (vanilla copies the overworld's size unscaled, which
     * would let the Nether reach eight times the overworld's land), the End's the same as the overworld's. Applied when
     * the hub is set and checked again once a second, so a restart or vanilla's own copying is put right.
     */
    void applyBorders() {
        if (!ledgers.get().safe().zones().containsKey(dev.kindred.core.factions.SafeZones.HUB)) return;
        set(server.getOverworld(), settings.border());
        var nether = server.getWorld(net.minecraft.world.World.NETHER);
        if (nether != null) set(nether, settings.border() / 8.0);
        var end = server.getWorld(net.minecraft.world.World.END);
        if (end != null) set(end, settings.border());
    }

    private static void set(net.minecraft.server.world.ServerWorld world, double size) {
        var border = world.getWorldBorder();
        if (border.getCenterX() != 0 || border.getCenterZ() != 0) border.setCenter(0, 0);
        if (border.getSize() != size) border.setSize(size);
        if (border.getWarningBlocks() != 20) border.setWarningBlocks(20);
    }

    /**
     * Whether a portal from {@code from} at {@code pos} would lead out of the playable area: the place it leads to, at
     * the destination's scale, outside that dimension's border.
     */
    static boolean leavesBorder(net.minecraft.server.world.ServerWorld from, BlockPos pos) {
        var server = from.getServer();
        boolean inNether = from.getRegistryKey() == net.minecraft.world.World.NETHER;
        var to = server.getWorld(inNether ? net.minecraft.world.World.OVERWORLD : net.minecraft.world.World.NETHER);
        if (to == null) return false;
        double scale = net.minecraft.world.dimension.DimensionType.getCoordinateScaleFactor(from.getDimension(), to.getDimension());
        return !to.getWorldBorder().contains(pos.getX() * scale, pos.getZ() * scale);
    }
    /** Whether {@code player} may break, place or use things in {@code chunk} (M-06d). */
    boolean mayChange(ServerPlayerEntity player, dev.kindred.core.factions.Territory.Chunk chunk) {
        return dev.kindred.core.factions.Territory.mayChange(book(), ledgers.get().claims(), safe(), player.getUuid(), player.hasPermissionLevel(2), chunk);
    }

    /** Whether something may pass from {@code from} into {@code to}. */
    boolean mayCross(dev.kindred.core.factions.Territory.Chunk from, dev.kindred.core.factions.Territory.Chunk to) {
        return dev.kindred.core.factions.Territory.mayCross(ledgers.get().claims(), safe(), from, to);
    }

    /** Whether {@code chunk} is neither claimed nor a safe zone. */
    boolean unprotected(dev.kindred.core.factions.Territory.Chunk chunk) {
        return dev.kindred.core.factions.Territory.access(ledgers.get().claims(), safe(), chunk) == dev.kindred.core.factions.Territory.Access.ANY;
    }

    void settings(FactionSettings next) { settings = next; }

    FactionBook book() { return ledgers.get().factions(); }

    /** The ledgers as the rules read them now. */
    dev.kindred.core.record.Ledgers ledgersNow() { return ledgers.get(); }

    /** Each faction notice also kept for its members' panels (M-06l). */
    private java.util.function.BiConsumer<UUID, String> panelNotices = (f, line) -> { };

    void panelNotices(java.util.function.BiConsumer<UUID, String> sink) { panelNotices = sink; }

    PowerBook powers() { return ledgers.get().power(); }

    Factions.At at(String world) { return new Factions.At(server.getTicks(), now(), world); }

    /** The clock the rules read: the wall clock, moved by a fixture only. */
    long now() { return System.currentTimeMillis() + clockOffset; }

    /** A fixture's clock moved, so shields and the hour's limit can pass within a test. */
    void clockForTest(long offsetMs) { clockOffset = offsetMs; }

    /** Each faction's exposed chunks as last seen, for the vulnerable and secure notices; null until first looked. */
    private Map<UUID, Integer> exposedSeen;
    private long clockOffset;

    /**
     * Once a second: a faction that has just overextended is announced vulnerable, one no longer overextended secure
     * (M-06e), to everyone online. The first look after a start only remembers.
     */
    void notices() {
        var seen = new HashMap<UUID, Integer>();
        var events = new ArrayList<WorldEvent>();
        for (var faction : book().factions().values()) {
            var exposure = dev.kindred.core.factions.Capture.exposure(book(), powers(), ledgers.get().claims(), faction.id(), settings);
            seen.put(faction.id(), exposure.exposed());
            if (exposedSeen == null) continue;
            int before = exposedSeen.getOrDefault(faction.id(), 0);
            if (before == 0 && exposure.exposed() > 0 || before > 0 && exposure.exposed() == 0) {
                boolean vulnerable = exposure.exposed() > 0;
                events.add(dev.kindred.core.factions.Capture.notice(faction.id(), vulnerable, exposure, at("")));
                broadcast(faction.name() + (vulnerable ? " is vulnerable: " + exposure.exposed() + " chunks exposed" : " is secure again"));
                panelNotices.accept(faction.id(), faction.name() + (vulnerable ? " is vulnerable" : " is secure again"));
            }
        }
        exposedSeen = seen;
        if (!events.isEmpty()) record.accept(events);
    }

    /** A line told to everyone online. */
    void broadcast(String line) {
        for (var player : server.getPlayerManager().getPlayerList()) player.sendMessage(net.minecraft.text.Text.literal(line).formatted(net.minecraft.util.Formatting.GOLD), false);
    }

    /** A line told to a faction's members online. */
    void tellFaction(UUID faction, String line) {
        panelNotices.accept(faction, line);
        var members = book().get(faction).map(f -> f.members().keySet()).orElse(java.util.Set.of());
        for (UUID member : members) {
            var player = server.getPlayerManager().getPlayer(member);
            if (player != null) player.sendMessage(net.minecraft.text.Text.literal(line).formatted(net.minecraft.util.Formatting.YELLOW), false);
        }
    }

    /** A decision's events written to the Record; its reason either way. */
    Factions.Decision apply(Factions.Decision decision) {
        if (decision.accepted()) record.accept(decision.events());
        return decision;
    }

    /** The zone at {@code pos} in {@code world}: claimed land, or the wilderness; safe zones are M-06f's. */
    Zone zone(String world, BlockPos pos) {
        String key = ClaimLedger.chunk(world, pos.getX() >> 4, pos.getZ() >> 4);
        if (safe().contains(key)) return Zone.SAFE;
        return ledgers.get().claims().containsKey(key) ? Zone.CLAIMED : Zone.WILDERNESS;
    }

    Zone zone(ServerPlayerEntity player) { return zone(player.getServerWorld().getRegistryKey().getValue().toString(), player.getBlockPos()); }

    /** Whether {@code attacker} may strike {@code target} by the faction rules. */
    boolean mayAttack(ServerPlayerEntity attacker, ServerPlayerEntity target) {
        return PowerRules.mayAttack(book(), attacker.getUuid(), target.getUuid(), zone(attacker), zone(target));
    }

    boolean mayAttack(UUID attacker, UUID target) {
        var a = server.getPlayerManager().getPlayer(attacker);
        var t = server.getPlayerManager().getPlayer(target);
        return a != null && t != null && mayAttack(a, t);
    }

    /** A player's death: the power it costs when it qualifies, written beside the death. Returns the reason. */
    String died(ServerPlayerEntity victim, UUID killer) {
        String world = victim.getServerWorld().getRegistryKey().getValue().toString();
        flushOnline(victim.getUuid());
        var qualification = PowerRules.qualifies(book(), powers(), settings, victim.getUuid(), killer, zone(victim), System.currentTimeMillis());
        if (!qualification.counts()) return qualification.reason();
        BlockPos at = victim.getBlockPos();
        record.accept(List.of(PowerRules.loss(powers(), victim.getUuid(), killer, settings, server.getTicks(), System.currentTimeMillis(), world,
                at.getX() + "," + at.getY() + "," + at.getZ())));
        return qualification.reason();
    }

    /** A player connected: its first sight written once, and its online time measured from now. */
    void connected(UUID player) {
        if (!powers().firstSeen().containsKey(player) && seenAsked.add(player)) {
            var events = new ArrayList<WorldEvent>();
            events.add(PowerRules.seen(player, server.getTicks(), System.currentTimeMillis()));
            // The starting coins, once an account: the same rule for people and AI players (M-06d).
            dev.kindred.core.factions.Coins.start(ledgers.get().coins(), player, settings, at("")).ifPresent(events::add);
            record.accept(events);
        }
        online.putIfAbsent(player, new long[] {System.currentTimeMillis(), 0});
    }

    /** A player left: its online time written. */
    void disconnected(UUID player) {
        measure(player, System.currentTimeMillis());
        flushOnline(player);
        online.remove(player);
    }

    /** Once a second: each online player's time measured, and written once it reaches five minutes. */
    void tick(Iterable<ServerPlayerEntity> players) {
        long now = System.currentTimeMillis();
        var present = new java.util.HashSet<UUID>();
        for (var player : players) {
            present.add(player.getUuid());
            connected(player.getUuid());
            measure(player.getUuid(), now);
            if (online.get(player.getUuid())[1] >= ONLINE_FLUSH_SECONDS * 1000L) flushOnline(player.getUuid());
        }
        for (UUID gone : new ArrayList<>(online.keySet())) if (!present.contains(gone)) disconnected(gone);
    }

    /** Every online player's time written, as at a stop. */
    void flushAll() {
        long now = System.currentTimeMillis();
        for (UUID player : new ArrayList<>(online.keySet())) {
            measure(player, now);
            flushOnline(player);
        }
    }

    /** Online time measured but not yet written, in whole seconds, for the status and fixtures. */
    int pendingSeconds(UUID player) {
        long[] slot = online.get(player);
        return slot == null ? 0 : (int) (slot[1] / 1000);
    }

    private void measure(UUID player, long now) {
        long[] slot = online.get(player);
        if (slot == null) return;
        slot[1] += Math.max(0, Math.min(MAX_STEP_MS, now - slot[0]));
        slot[0] = now;
    }

    private void flushOnline(UUID player) {
        long[] slot = online.get(player);
        if (slot == null) return;
        int seconds = (int) (slot[1] / 1000);
        if (seconds <= 0) return;
        slot[1] -= seconds * 1000L;
        record.accept(List.of(PowerRules.online(powers(), player, seconds, settings, server.getTicks(), System.currentTimeMillis())));
    }

    // ----- Requests: the same for a person and an AI player -----

    /** A request's outcome: accepted or not, and the lines to tell whoever asked. */
    record Reply(boolean accepted, List<String> lines) {
        static Reply of(Factions.Decision decision) { return new Reply(decision.accepted(), List.of(decision.reason())); }
        static Reply refused(String reason) { return new Reply(false, List.of(reason)); }
        static Reply told(List<String> lines) { return new Reply(true, lines); }
    }

    /**
     * {@code who}'s request {@code verb} with its arguments, standing at {@code pos} in {@code world}: create, invite,
     * uninvite, join, leave, kick, promote, demote, leader, ally, truce, enemy, sethome, home, colour, disband, info, list,
     * power. {@code players} resolves a name to a player's id. Nothing teleports: {@code home} tells where it is.
     */
    Reply request(UUID who, String verb, List<String> args, String world, BlockPos pos, java.util.function.Function<String, UUID> players,
                  java.util.function.Function<UUID, String> names) {
        var book = book();
        var at = at(world);
        String first = args.isEmpty() ? "" : args.getFirst();
        switch (verb) {
            case "create" -> {
                if (args.size() < 2) return Reply.refused("/faction create <name> <tag> [colour]");
                var colour = args.size() > 2 ? FactionColour.parse(args.get(2)) : java.util.Optional.of(FactionColour.WHITE);
                if (colour.isEmpty()) return Reply.refused("A colour is one of the sixteen dyes: " + dyes());
                return Reply.of(apply(Factions.create(book, who, args.get(0), args.get(1), colour.get(), UUID.randomUUID(), at)));
            }
            case "invite", "uninvite" -> {
                UUID player = players.apply(first);
                if (player == null) return Reply.refused("No player named " + first);
                return Reply.of(apply(Factions.invite(book, who, player, verb.equals("invite"), at)));
            }
            case "join" -> {
                var faction = book.named(first).orElse(null);
                if (faction == null) return Reply.refused("No faction named " + first);
                return Reply.of(apply(Factions.join(book, who, faction.id(), settings, at)));
            }
            case "leave" -> { return Reply.of(apply(Factions.leave(book, who, at))); }
            case "kick", "promote", "demote", "leader" -> {
                UUID player = players.apply(first);
                if (player == null) return Reply.refused("No player named " + first);
                if (verb.equals("kick")) return Reply.of(apply(Factions.kick(book, who, player, at)));
                var rank = switch (verb) {
                    case "promote" -> Faction.Rank.OFFICER;
                    case "leader" -> Faction.Rank.LEADER;
                    default -> Faction.Rank.MEMBER;
                };
                return Reply.of(apply(Factions.rank(book, who, player, rank, at)));
            }
            case "ally", "truce", "enemy" -> {
                var other = book.named(first).orElse(null);
                if (other == null) return Reply.refused("No faction named " + first);
                var relation = Faction.Relation.valueOf(verb.toUpperCase(java.util.Locale.ROOT));
                return Reply.of(apply(Factions.wish(book, who, other.id(), relation, at)));
            }
            case "sethome" -> { return Reply.of(apply(Factions.home(book, who, world + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ(), at))); }
            case "home" -> {
                var faction = book.of(who).orElse(null);
                if (faction == null) return Reply.refused("You are not in a faction");
                return Reply.told(List.of(faction.home().isEmpty() ? faction.name() + " has no home set" : faction.name() + "'s home: " + faction.home()));
            }
            case "colour", "color" -> {
                var colour = FactionColour.parse(first);
                if (colour.isEmpty()) return Reply.refused("A colour is one of the sixteen dyes: " + dyes());
                return Reply.of(apply(Factions.colour(book, who, colour.get(), at)));
            }
            case "disband" -> { return Reply.of(apply(Factions.disband(book, who, at))); }
            case "claim", "unclaim" -> {
                var chunk = dev.kindred.core.factions.Territory.Chunk.at(world, pos.getX(), pos.getZ());
                var decision = verb.equals("claim")
                        ? dev.kindred.core.factions.Territory.claim(book, powers(), ledgers.get().claims(), safe(), ledgers.get().coins().of(who), settings, who, chunk, at)
                        : dev.kindred.core.factions.Territory.unclaim(book, ledgers.get().claims(), who, chunk, at);
                if (decision.accepted()) record.accept(decision.events());
                return new Reply(decision.accepted(), List.of(decision.reason()));
            }
            case "capture" -> {
                var chunk = dev.kindred.core.factions.Territory.Chunk.at(world, pos.getX(), pos.getZ());
                String from = ledgers.get().claims().get(chunk.key());
                var decision = dev.kindred.core.factions.Capture.capture(book, powers(), ledgers.get().claims(), safe(), ledgers.get().captures(),
                        ledgers.get().coins().of(who), settings, who, chunk, at);
                if (decision.accepted()) {
                    record.accept(decision.events());
                    var attacker = book.of(who).orElseThrow();
                    var loser = book.get(UUID.fromString(from)).orElseThrow();
                    tellFaction(attacker.id(), attacker.name() + " captured " + chunk.key() + " from " + loser.name());
                    tellFaction(loser.id(), attacker.name() + " captured your chunk " + chunk.key());
                }
                return new Reply(decision.accepted(), List.of(decision.reason()));
            }
            case "coins" -> { return Reply.told(List.of("Coins: " + dev.kindred.core.factions.Coins.show(ledgers.get().coins().of(who)))); }
            case "map" -> { return Reply.told(map(who, world, pos)); }
            case "info" -> {
                var faction = first.isEmpty() ? book.of(who).orElse(null) : book.named(first).orElse(null);
                if (faction == null) return Reply.refused(first.isEmpty() ? "You are not in a faction" : "No faction named " + first);
                var lines = new ArrayList<String>();
                lines.add(factionLine(faction));
                var mine = book.of(who).orElse(null);
                if (mine != null && !mine.id().equals(faction.id())) lines.add("To you: " + book.relation(mine.id(), faction.id()).name().toLowerCase(java.util.Locale.ROOT));
                for (var member : faction.members().entrySet()) {
                    lines.add(" " + names.apply(member.getKey()) + " (" + member.getValue().name().toLowerCase(java.util.Locale.ROOT) + "), power "
                            + powers().of(member.getKey(), settings).power());
                }
                return Reply.told(lines);
            }
            case "list" -> {
                var lines = new ArrayList<String>();
                book.factions().values().stream()
                        .sorted(java.util.Comparator.comparingInt((Faction f) -> -PowerRules.factionPower(book, powers(), f.id(), settings)))
                        .forEach(f -> lines.add(factionLine(f)));
                if (lines.isEmpty()) lines.add("No factions yet");
                return Reply.told(lines);
            }
            case "power" -> {
                UUID player = first.isEmpty() ? who : players.apply(first);
                if (player == null) return Reply.refused("No player named " + first);
                return Reply.told(List.of(names.apply(player) + ": power " + powerLine(player)));
            }
            default -> { return Reply.refused("Unknown: /faction " + verb); }
        }
    }

    /** The land round {@code pos}, eleven chunks across: + yours, # another faction's, s a safe zone, - the wilderness, @ here. */
    List<String> map(UUID who, String world, BlockPos pos) {
        var mine = book().of(who).map(f -> f.id().toString()).orElse("");
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        var lines = new ArrayList<String>();
        for (int dz = -5; dz <= 5; dz++) {
            var row = new StringBuilder();
            for (int dx = -5; dx <= 5; dx++) {
                String key = ClaimLedger.chunk(world, cx + dx, cz + dz);
                String owner = ledgers.get().claims().get(key);
                row.append(dx == 0 && dz == 0 ? '@' : safe().contains(key) ? 's' : owner == null ? '-' : owner.equals(mine) ? '+' : '#');
            }
            lines.add(row.toString());
        }
        lines.add("@ here, + yours, # another faction's, s safe, - wilderness");
        return lines;
    }

    private static String dyes() {
        var out = new StringBuilder();
        for (var c : FactionColour.values()) out.append(out.isEmpty() ? "" : ", ").append(c.id());
        return out.toString();
    }

    // ----- Reading -----

    /** A player's power as {@code 7 / 10}, with its progress toward the next point. */
    String powerLine(UUID player) {
        var power = powers().of(player, settings);
        int pending = power.power() >= settings.maxPower() ? 0 : pendingSeconds(player);
        var shown = PowerRules.regenerate(power, pending, settings);
        return shown.power() + " / " + settings.maxPower() + (shown.power() < settings.maxPower()
                ? ", next point in " + (settings.regenSeconds() - shown.progressSeconds()) / 60 + " min online" : "");
    }

    /** A faction's power, claims and capacity, with how many chunks are exposed. */
    String factionLine(Faction faction) {
        int power = PowerRules.factionPower(book(), powers(), faction.id(), settings);
        int claims = claims(faction.id());
        int exposed = PowerRules.exposed(claims, power);
        return faction.name() + " [" + faction.tag() + "] " + faction.members().size() + "/" + settings.maxMembers() + " members, power " + power
                + ", claims " + claims + " / " + PowerRules.capacity(power, settings) + (exposed > 0 ? ", " + exposed + " exposed" : "");
    }

    int claims(UUID faction) {
        String id = faction.toString();
        return (int) ledgers.get().claims().values().stream().filter(id::equals).count();
    }

    /** The settings file's entries, the defaults written there first if it does not exist. Off the server thread. */
    static FactionSettings readSettings(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                var text = new StringBuilder("# Kindred Factions mode (M-06): the rulebook's defaults, the border at the owner's 8,000.\n");
                for (var entry : FactionSettings.DEFAULTS.properties().entrySet()) text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
                Files.writeString(file, text, StandardCharsets.UTF_8);
                return FactionSettings.DEFAULTS;
            }
            var properties = new java.util.Properties();
            try (var in = Files.newInputStream(file)) { properties.load(in); }
            var entries = new LinkedHashMap<String, String>();
            for (String key : properties.stringPropertyNames()) entries.put(key, properties.getProperty(key));
            return FactionSettings.parse(entries);
        } catch (Exception failure) {
            KindredMod.LOGGER.error("Factions settings could not be read from {}; the rulebook's defaults apply", file, failure);
            return FactionSettings.DEFAULTS;
        }
    }

    /** A colour by its dye name, or empty. */
    static java.util.Optional<FactionColour> colour(String raw) { return FactionColour.parse(raw); }
}
