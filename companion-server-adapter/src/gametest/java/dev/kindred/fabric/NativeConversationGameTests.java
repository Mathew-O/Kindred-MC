package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.social.Conversation;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.social.PeerUpdate;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In plain terms: a GameTest: in a real test world, AI players talking among themselves. Echo and Sol, meeting again
 * after ten minutes apart, say two or three lines, heard by the person beside them and not by one forty blocks off, and
 * never sent to either of them as chat; with nobody near, they each note the meeting and say nothing; three of them over
 * five minutes say at most twenty lines in all; and a fight stops a talk.
 *
 * Technical: E-51. The first two run the whole runtime with two native players; "ten minutes apart" is staged through the
 * lane's fixture hook, since a test cannot wait ten minutes. The other two drive {@link PeerTalk} with its own clock and a
 * stand-in for the runtime's side, on the older mob-style body, as {@link SocialGameTests} drives the social lane, with
 * mock players logged in to hear.
 */
public final class NativeConversationGameTests implements FabricGameTest {
    /** Echo and Sol, made by their owner beside them; the lane's lines heard by everyone, by id. */
    private static final class Pair {
        final NativeVoiceGameTests.Talk t;
        UUID echo, sol;
        ServerPlayerEntity echoBody, solBody;
        boolean solMade;

        Pair(TestContext context) {
            t = new NativeVoiceGameTests.Talk(context, false);
        }

        void makeSol(TestContext context) {
            if (!solMade) {
                t.f.runtime.createNative(t.owner.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(1.5, 2, 3.5))), "Sol");
                solMade = true;
            }
        }

        void established(TestContext context) {
            var statuses = t.f.runtime.nativeStatuses(Actor.console());
            context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.connected() && s.phase() == StoredNativePlayer.Phase.ESTABLISHED),
                    "Both established: " + statuses);
            for (var s : statuses) {
                var body = context.getWorld().getServer().getPlayerManager().getPlayer(s.nativeId());
                if (s.name().equals("Echo")) { echo = s.id(); echoBody = body; }
                if (s.name().equals("Sol")) { sol = s.id(); solBody = body; }
            }
            context.assertTrue(echoBody != null && solBody != null, "Both found");
            context.assertTrue(t.f.runtime.peersForFixtures(echo) != null && t.f.runtime.peersForFixtures(sol) != null, "Both ledgers read");
        }

        void hear() {
            t.f.runtime.heardForFixtures((player, text) -> t.heard.add(new NativeVoiceGameTests.Heard(player.getUuid(), text.getString(), NativeVoiceGameTests.Talk.now())));
        }

        PeerTalk talk() { return t.f.runtime.peerTalkForFixtures(); }
    }

    /**
     * Staged: ten minutes apart. Echo and Sol, side by side with their owner beside them and another person forty blocks
     * off, talk: two or three lines, every one heard by the owner, none by the one far off, none sent to Echo or Sol.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peerTalk", tickLimit = 1600)
    public void twoPlayersMeetingAfterTenMinutesApartTalkHeardBesideThemNotFarOff(TestContext context) {
        var p = new Pair(context);
        var f = p.t.f;
        ServerPlayerEntity[] far = {null};
        boolean[] staged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> p.t.createOwned(context))
                .createAndAdd(() -> p.makeSol(context))
                .createAndAdd(() -> p.established(context))
                .createAndAdd(() -> {
                    p.hear();
                    far[0] = p.t.player(context, new Vec3d(43.5, 2, 3.5), 270);
                    NativeVoiceGameTests.Talk.waitFor(1_200); // A turn of the lane: they have been seen together.
                })
                .createAndAdd(() -> {
                    if (!staged[0]) {
                        p.talk().apartForFixtures(p.echo, p.sol, Conversation.APART_MILLIS + 60_000); // Staged: ten minutes apart.
                        staged[0] = true;
                    }
                    context.assertTrue(p.talk().linesSaid() >= 2 && p.talk().talksUnderWay() == 0, "A talk said: " + p.talk().saidForFixtures());
                })
                .createAndAdd(() -> {
                    var said = p.talk().saidForFixtures();
                    context.assertTrue(said.size() >= 2 && said.size() <= 3, "Two or three lines: " + said);
                    for (String line : said) {
                        context.assertTrue(p.t.heardBy(p.t.owner).stream().anyMatch(h -> h.text().equals("[Kindred] " + line)), "Heard beside them: " + line + " in " + p.t.heardBy(p.t.owner));
                    }
                    context.assertTrue(p.t.heardBy(far[0]).isEmpty(), "Nothing forty blocks off: " + p.t.heardBy(far[0]));
                    context.assertTrue(p.t.heard.stream().noneMatch(h -> h.to().equals(p.echoBody.getUuid()) || h.to().equals(p.solBody.getUuid())),
                            "No line ever sent to an AI player: " + p.t.heard);
                    p.t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Staged: ten minutes apart, with their owner gone forty blocks off. Each notes the meeting in its ledger; nothing is said. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peerTalk", tickLimit = 1600)
    public void withNobodyNearTheLedgersUpdateAndNothingIsSaid(TestContext context) {
        var p = new Pair(context);
        var f = p.t.f;
        boolean[] staged = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> p.t.createOwned(context))
                .createAndAdd(() -> p.makeSol(context))
                .createAndAdd(() -> p.established(context))
                .createAndAdd(() -> {
                    p.hear();
                    Vec3d off = context.getAbsolute(new Vec3d(43.5, 2, 3.5));
                    p.t.owner.refreshPositionAndAngles(off.x, off.y, off.z, 0, 0);
                    NativeVoiceGameTests.Talk.waitFor(1_200);
                })
                .createAndAdd(() -> {
                    if (!staged[0]) {
                        p.talk().apartForFixtures(p.echo, p.sol, Conversation.APART_MILLIS + 60_000);
                        staged[0] = true;
                    }
                    var echoKnows = f.runtime.peersForFixtures(p.echo).episodes(p.sol);
                    var solKnows = f.runtime.peersForFixtures(p.sol).episodes(p.echo);
                    context.assertTrue(echoKnows.stream().anyMatch(e -> e.text().startsWith("talked: ")) && solKnows.stream().anyMatch(e -> e.text().startsWith("talked: ")),
                            "Both noted the meeting: " + echoKnows + " / " + solKnows);
                })
                .createAndAdd(() -> {
                    NativeVoiceGameTests.Talk.waitFor(2_000);
                    context.assertTrue(p.talk().linesSaid() == 0 && p.talk().talksUnderWay() == 0, "Nothing said: " + p.talk().saidForFixtures());
                    context.assertTrue(p.t.heard.stream().noneMatch(h -> h.text().startsWith("[Kindred] Echo: ") && !h.text().contains("test-mock-player")
                            || h.text().startsWith("[Kindred] Sol: ") && !h.text().contains("test-mock-player")), "No talk heard: " + p.t.heard);
                    p.t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A stand-in for the runtime's side: the players, their ledgers and updates, and who is busy. */
    private static final class Stand implements PeerTalk.Source {
        final List<CompanionSocials.Participant> players = new ArrayList<>();
        final Map<UUID, PeerLedger> ledgers = new HashMap<>();
        final Map<UUID, PeerUpdate> updates = new HashMap<>();
        final Set<UUID> busy = new HashSet<>();
        final AtomicLong now;

        Stand(AtomicLong now) { this.now = now; }

        @Override public List<CompanionSocials.Participant> players() { return players; }
        @Override public Optional<PeerLedger> ledger(UUID id) { return Optional.ofNullable(ledgers.get(id)); }
        @Override public Optional<PeerUpdate> update(UUID id) { return Optional.ofNullable(updates.get(id)); }
        @Override public boolean busy(UUID id) { return busy.contains(id); }
        @Override public Expression sounding(CompanionSocials.Participant player) { return Expression.PLAIN; }
        @Override public ListenerPreferences preferences(UUID person) { return ListenerPreferences.defaults(person); }
        @Override public long tick() { return now.get() / 50; }
    }

    /** The lane on its own clock, with {@code names} players side by side at the plot's middle and a person beside them. */
    private static final class Lane {
        final AtomicLong now = new AtomicLong();
        final Stand stand = new Stand(now);
        final List<String> heard = new ArrayList<>();
        final CompanionSocials socials = new CompanionSocials(id -> null, now::get, (to, text) -> heard.add(to.getUuid() + " <- " + text.getString()));
        final PeerTalk talk = new PeerTalk(stand, socials, now::get, new Conversation.Chatter(Conversation.Chatter.DEFAULT_PER_MINUTE));
        final List<ServerPlayerEntity> people = new ArrayList<>();

        Lane(TestContext context, String... names) {
            for (int i = 0; i < names.length; i++) {
                var body = KindredMod.COMPANION.create(context.getWorld());
                if (body == null) throw new IllegalStateException("Missing companion factory");
                body.bind(body.getUuid(), UUID.randomUUID());
                Vec3d at = context.getAbsolute(new Vec3d(2.5 + i, 2, 3.5));
                body.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
                var participant = new CompanionSocials.Participant(body.companionId(), body.ownerId(), names[i], body,
                        SocialProfile.defaults(body.companionId()).withPublic(true), true);
                stand.players.add(participant);
                stand.ledgers.put(participant.id(), new PeerLedger(participant.id()));
                stand.updates.put(participant.id(), new PeerUpdate(participant.id(), names[i], "working on a nether portal: Wood " + i, List.of(i + " iron ingot"), List.of(), null));
            }
        }

        ServerPlayerEntity person(TestContext context) {
            var person = MockPlayers.creative(context);
            Vec3d at = context.getAbsolute(new Vec3d(3.5, 2, 5.5));
            person.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
            people.add(person);
            return person;
        }

        /** Every pair staged ten minutes apart, so that each may talk whenever the budget allows. */
        void apartAll() {
            for (var a : stand.players) for (var b : stand.players) if (a != b) talk.apartForFixtures(a.id(), b.id(), Conversation.APART_MILLIS + 1);
        }

        void step(long millis) {
            talk.tick();
            for (var player : stand.players) socials.deliverDue(player.id(), now.get() + 60_000);
            now.addAndGet(millis);
        }

        long heardBy(ServerPlayerEntity person) {
            return heard.stream().filter(h -> h.startsWith(person.getUuid() + " <- ")).count();
        }

        void cleanup(TestContext context) {
            var manager = context.getWorld().getServer().getPlayerManager();
            for (var person : people) manager.remove(person);
        }
    }

    /** Three players, every pair always due a talk: over five minutes at most twenty lines in all, four a minute, heard by the person beside them. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peerTalk")
    public void threePlayersOverFiveMinutesSayAtMostTwentyLines(TestContext context) {
        var l = new Lane(context, "Kindred", "Sol", "Echo");
        var jo = l.person(context);
        while (l.now.get() < 300_000) {
            l.apartAll();
            l.step(1_000);
        }
        KindredMod.LOGGER.info("Three players over five minutes: {} lines between them, {} heard by the person beside them", l.talk.linesSaid(), l.heardBy(jo));
        context.assertTrue(l.talk.linesSaid() > 0 && l.talk.linesSaid() <= 20, "At most twenty: " + l.talk.linesSaid() + " " + l.talk.saidForFixtures());
        context.assertTrue(l.heardBy(jo) == l.talk.linesSaid(), "Each heard once by the person beside them: " + l.heardBy(jo));
        l.cleanup(context);
        context.complete();
    }

    /** A talk begun; then one of them is in a fight: no more of it is said, and it is not taken up again. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peerTalk")
    public void aFightStopsAConversation(TestContext context) {
        var l = new Lane(context, "Sol", "Echo");
        var jo = l.person(context);
        l.apartAll();
        l.step(0);
        context.assertTrue(l.talk.linesSaid() == 1 && l.talk.talksUnderWay() == 1, "The first line said, the talk under way: " + l.talk.saidForFixtures());
        l.stand.busy.add(l.stand.players.get(1).id()); // Staged: Echo is in a fight.
        for (int i = 0; i < 30; i++) l.step(1_000);
        context.assertTrue(l.talk.linesSaid() == 1 && l.talk.talksUnderWay() == 0, "Stopped at one line: " + l.talk.saidForFixtures());
        l.stand.busy.clear();
        for (int i = 0; i < 30; i++) l.step(1_000);
        context.assertTrue(l.talk.linesSaid() == 1, "Not taken up again: " + l.talk.saidForFixtures());
        context.assertTrue(l.heardBy(jo) == 1, "Heard: " + l.heard);
        l.cleanup(context);
        context.complete();
    }
}
