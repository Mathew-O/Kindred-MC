package dev.kindred.fabric;

import dev.kindred.core.outing.Outing;
import dev.kindred.core.outing.OutingChoice;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In plain terms: an AI player with nothing to do and someone about, that has seen somewhere worth seeing (a village, a
 * lake), asks if anyone wants to come; "I'm in" from a person, or another idle AI player's own yes, and off it goes, walks
 * there, says so, stays a minute and walks back. A fight, nightfall, a stop or too long ends it and it heads home. It
 * remembers who was really there with it, and that is a good memory with them (E-24a); saying no costs nothing.
 *
 * Technical: E-27a's adapter, on the server thread, a turn a second. The plan is {@link Outing}; an AI invitee answers by
 * {@link OutingChoice}, its own rule. Nothing here moves a body: {@link #leg} says where each player on an outing means to
 * be, and its decision loop takes that up as a candidate of its own ({@code native_goto} there or back, a wait while there),
 * ranked with its reflexes and through the one dispatch with every check a task has; nothing grants anyone anything. At most
 * one outing per host in {@value #EVERY_TICKS} ticks; a trip that cannot get home is let go {@value #HOME_TICKS} ticks after it
 * ended.
 */
final class Outings {
    /** At most one invitation per host in this long: half an hour of game time. */
    static final long EVERY_TICKS = 36_000;
    /** A person near this long, the host idle, before it asks. */
    static final long NEAR_TICKS = 400;
    /** A destination at least this far and at most that far. */
    static final int NEAREST = 10, FARTHEST = 96;
    /** Close enough to have got there, or back. */
    static final double THERE = 4;
    /** Ended this long and still not home: the trip is let go, wherever it is (five game minutes). */
    static final long HOME_TICKS = 6_000;

    /** Where a player on an outing means to be next: a walk to {@code to}, or a stay where it is ({@code to} null). */
    record Leg(BlockPos to, String label) {
        static final Leg STAY = new Leg(null, "Look around");

        boolean stay() { return to == null; }
    }

    /** The runtime's side. */
    interface Source {
        List<CompanionSocials.Participant> players();

        /** Somewhere worth seeing it has itself seen, between {@value #NEAREST} and {@value #FARTHEST} blocks off, or empty. */
        Optional<Outing.Destination> destination(UUID id);

        /** Nothing in hand and no goal. */
        boolean idle(UUID id);

        /** Fighting, fleeing, falling, or in a match: no outing, and one under way ends. */
        boolean busy(UUID id);

        /** Whether an AI player has a goal of its own. */
        boolean hasGoal(UUID id);

        /** Something that happened between it and someone, for its ledger and friendship. */
        void remember(UUID id, UUID other, String name, String what, boolean together, String key);

        long tick();
    }

    private static final class Trip {
        final Outing outing;
        final BlockPos home;
        final Set<UUID> invited = new HashSet<>();
        /** Where each AI player that came stood when it set off, to walk back to. */
        final Map<UUID, BlockPos> aiHomes = new HashMap<>();
        long endedAt = Long.MIN_VALUE;

        Trip(Outing outing, BlockPos home) {
            this.outing = outing;
            this.home = home;
        }
    }

    private static final Pattern IN = Pattern.compile("(?:i'?m in|count me in|i'?ll come|sure,? let'?s go|let'?s go|yes,? i'?ll come)[.!]*");

    private final Source source;
    private final CompanionSocials socials;
    /** The social lane's own clock (milliseconds), for when a line is said. */
    private final java.util.function.LongSupplier clock;
    private final Map<UUID, Trip> trips = new HashMap<>();
    private final Map<UUID, Long> lastAsked = new HashMap<>();
    private final Map<UUID, Long> nearSince = new HashMap<>();
    private final java.util.ArrayDeque<String> log = new java.util.ArrayDeque<>();
    private long stay = Outing.STAY_TICKS, near = NEAR_TICKS;

    Outings(Source source, CompanionSocials socials, java.util.function.LongSupplier clock) {
        this.source = source;
        this.socials = socials;
        this.clock = clock;
    }

    /** What has happened on outings, newest last (sixty at most), for fixtures. */
    List<String> logForFixtures() { return List.copyOf(log); }

    /** The outing a host is on, for fixtures. */
    Optional<Outing> outingForFixtures(UUID host) { return Optional.ofNullable(trips.get(host)).map(t -> t.outing); }

    /** Staged, for fixtures: the half hour since its last invitation over. */
    void readyForFixtures(UUID host) { lastAsked.remove(host); }

    /** Staged, for fixtures that cannot wait a minute there and twenty seconds before asking. */
    void shorterForFixtures(long stayTicks, long nearTicks) {
        stay = stayTicks;
        near = nearTicks;
    }

    void tick() {
        long now = source.tick();
        for (var player : source.players()) {
            if (!player.active() || player.body() == null) continue;
            Trip trip = trips.get(player.id());
            if (trip != null) {
                step(player, trip, now);
                if (trip.outing.over()) {
                    if (trip.endedAt == Long.MIN_VALUE) trip.endedAt = now;
                    if (everyoneHome(player, trip) || now - trip.endedAt > HOME_TICKS) trips.remove(player.id());
                }
                continue;
            }
            maybeInvite(player, now);
        }
    }

    private void maybeInvite(CompanionSocials.Participant host, long now) {
        if (!host.profile().ambientEnabled() || host.profile().personality().sociability() < OutingChoice.SOCIABLE) return;
        if (!source.idle(host.id()) || source.busy(host.id()) || isNight(host)) return;
        Long last = lastAsked.get(host.id());
        if (last != null && now - last < EVERY_TICKS) return;
        var hearers = people(host);
        if (hearers.isEmpty()) {
            nearSince.remove(host.id());
            return;
        }
        long since = nearSince.computeIfAbsent(host.id(), ignored -> now);
        if (now - since < near) return;
        var destination = source.destination(host.id()).orElse(null);
        if (destination == null) return;
        lastAsked.put(host.id(), now);
        var trip = new Trip(new Outing(host.id(), host.name(), destination, now), host.body().getBlockPos());
        trips.put(host.id(), trip);
        for (var person : hearers) {
            trip.invited.add(person.getUuid());
            socials.unasked(host, person, "I found " + destination.words() + " not far off. Anyone want to come and see it? Say \"I'm in\".", clock.getAsLong());
        }
        // Another idle AI player in sight answers by its own rule, as a record, never as chat.
        for (var peer : source.players()) {
            if (peer.id().equals(host.id()) || peer.body() == null || !SocialPerception.visible(host.body(), peer.body())) continue;
            var answer = OutingChoice.answer(source.hasGoal(peer.id()), !source.idle(peer.id()), source.busy(peer.id()), false, peer.profile().personality());
            trip.invited.add(peer.id());
            if (answer.yes()) trip.outing.accept(peer.id(), now);
            note(host.name() + " asked " + peer.name() + ": " + (answer.yes() ? "yes" : "no (" + answer.why() + ")"));
        }
        note(host.name() + " invited " + hearers.size() + " to " + destination.words());
    }

    /** A person's "I'm in" to an outing they were asked on: the reply, or empty for anything else. */
    Optional<String> heard(ServerPlayerEntity speaker, CompanionSocials.Participant host, String message) {
        Trip trip = trips.get(host.id());
        if (trip == null || trip.outing.state() != Outing.State.INVITED || !trip.invited.contains(speaker.getUuid())) return Optional.empty();
        String m = dev.kindred.core.social.Claims.plain(message);
        if (!IN.matcher(m).matches()) return Optional.empty();
        if (!trip.outing.accept(speaker.getUuid(), source.tick())) return Optional.empty();
        note(speaker.getName().getString() + " is in");
        return Optional.of("Great, follow me.");
    }

    private void step(CompanionSocials.Participant host, Trip trip, long now) {
        Outing o = trip.outing;
        // A fight, nightfall or too long: cut short, and home (one already heading home just goes on).
        if (!o.over() && o.state() != Outing.State.RETURNING && (source.busy(host.id()) || isNight(host) || o.overdue(now))) {
            String why = source.busy(host.id()) ? "trouble" : isNight(host) ? "nightfall" : "too long";
            boolean setOff = o.state() != Outing.State.INVITED;
            o.cancel(why, now);
            say(host, trip, setOff ? "Let's head back." : "Never mind, another time.");
            note(host.name() + "'s outing to " + o.destination().words() + " cut short: " + why);
            for (UUID who : o.said()) source.remember(host.id(), who, null, "outing to " + o.destination().words() + " cut short", false, null);
        }
        switch (o.state()) {
            case INVITED -> {
                boolean allAnswered = !o.said().isEmpty() && o.said().containsAll(trip.invited);
                if (allAnswered ? o.setOffNow(now) : o.setOff(now)) {
                    if (o.over()) {
                        note(host.name() + "'s outing dropped: " + o.why());
                        return;
                    }
                    say(host, trip, "Off we go.");
                    note(host.name() + " set off for " + o.destination().words() + " with " + o.said().size());
                    // An AI player that said yes comes along on its own walk, as itself, and back to where it set off from.
                    for (var peer : source.players()) {
                        if (!o.said().contains(peer.id()) || peer.body() == null) continue;
                        trip.aiHomes.put(peer.id(), peer.body().getBlockPos());
                    }
                }
            }
            case GOING -> {
                if (near(host, destination(o))) {
                    var with = new HashSet<UUID>();
                    for (var person : world(host).getPlayers()) {
                        if (person == host.body() || person.squaredDistanceTo(host.body()) > Outing.TOGETHER * Outing.TOGETHER) continue;
                        UUID ai = socials.aiPlayerOf(person);
                        with.add(ai != null ? ai : person.getUuid());
                    }
                    o.arrived(with, now);
                    say(host, trip, "Here we are: " + o.destination().words() + ".");
                    note(host.name() + " got to " + o.destination().words() + "; there with it: " + o.there().size() + " of " + o.said().size());
                }
            }
            case THERE -> {
                if (o.timeToGoBack(now, stay)) {
                    o.goingBack(now);
                    say(host, trip, "Right, heading back.");
                }
            }
            case RETURNING -> {
                if (atHome(host, trip)) {
                    o.back(now);
                    note(host.name() + " is back from " + o.destination().words());
                    String key = "outing:" + host.id() + ":" + o.destination().words() + ":" + now / 1_200;
                    for (UUID who : o.there()) {
                        source.remember(host.id(), who, null, "went to " + o.destination().words() + " together", true, key);
                        // An AI player that came remembers it too, from its side.
                        if (trip.aiHomes.containsKey(who)) source.remember(who, host.id(), host.name(), "went to " + o.destination().words() + " together", true, key);
                    }
                }
            }
            case DONE, CANCELLED -> { }
        }
    }

    /**
     * Where the player {@code id}, standing at {@code at}, means to be next on an outing it asked people on or said yes
     * to: the walk there, a stay once there, the walk back; empty when it is on none, or is where it means to be.
     */
    Optional<Leg> leg(UUID id, BlockPos at) {
        Trip trip = trips.get(id);
        if (trip != null) return leg(trip.outing, at, trip.home, "Walk to " + trip.outing.destination().words(), "Walk back home");
        for (Trip other : trips.values()) {
            BlockPos home = other.aiHomes.get(id);
            if (home != null) return leg(other.outing, at, home, "Walk to " + other.outing.destination().words() + " with " + other.outing.hostName(),
                    "Walk back from " + other.outing.destination().words());
        }
        return Optional.empty();
    }

    private static Optional<Leg> leg(Outing o, BlockPos at, BlockPos home, String there, String back) {
        BlockPos to = destination(o);
        return switch (o.state()) {
            case INVITED -> Optional.empty();
            case GOING -> Optional.of(near(at, to) ? Leg.STAY : new Leg(to, there));
            case THERE -> Optional.of(Leg.STAY);
            case RETURNING, DONE, CANCELLED -> near(at, home) ? Optional.empty() : Optional.of(new Leg(home, back));
        };
    }

    private void say(CompanionSocials.Participant host, Trip trip, String text) {
        for (var person : people(host)) socials.unasked(host, person, text, clock.getAsLong());
    }

    /** The people who can hear it now: never an AI player. */
    private List<ServerPlayerEntity> people(CompanionSocials.Participant host) {
        return world(host).getPlayers().stream()
                .filter(p -> socials.aiPlayerOf(p) == null && socials.canTalk(p, host)).toList();
    }

    private static BlockPos destination(Outing o) {
        var d = o.destination();
        return new BlockPos(d.x(), d.y(), d.z());
    }

    private static boolean near(CompanionSocials.Participant host, BlockPos to) {
        return near(host.body().getBlockPos(), to);
    }

    private static boolean near(BlockPos at, BlockPos to) {
        return at.getSquaredDistance(to) <= THERE * THERE;
    }

    private static boolean atHome(CompanionSocials.Participant host, Trip trip) {
        return near(host, trip.home);
    }

    /** The host home, and each AI player that came back where it set off from (or gone from the game). */
    private boolean everyoneHome(CompanionSocials.Participant host, Trip trip) {
        if (!atHome(host, trip)) return false;
        for (var peer : source.players()) {
            BlockPos home = trip.aiHomes.get(peer.id());
            if (home != null && peer.active() && peer.body() != null && !near(peer.body().getBlockPos(), home)) return false;
        }
        return true;
    }

    private static boolean isNight(CompanionSocials.Participant host) {
        var world = world(host);
        return world.getDimension().hasSkyLight() && world.isNight();
    }

    private static net.minecraft.server.world.ServerWorld world(CompanionSocials.Participant host) {
        return (net.minecraft.server.world.ServerWorld) host.body().getWorld();
    }

    private void note(String line) {
        KindredMod.LOGGER.info("Outing: {}", line);
        log.addLast(line);
        while (log.size() > 60) log.removeFirst();
    }
}
