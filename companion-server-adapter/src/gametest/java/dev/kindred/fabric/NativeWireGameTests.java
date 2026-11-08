package dev.kindred.fabric;

import dev.kindred.core.match.GameModes;
import dev.kindred.core.wire.Delivery;
import dev.kindred.core.wire.Fact;
import dev.kindred.core.wire.HowKnown;
import dev.kindred.core.wire.Listener;
import dev.kindred.core.wire.Message;
import dev.kindred.core.wire.Scope;
import dev.kindred.core.wire.Visibility;
import dev.kindred.core.wire.Wording;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In plain terms: the Wire on a real server with logged-in players: a faction's announcement reaches a member and
 * never an outsider stood beside them; a manhunt hunter's side message never reaches a runner; a market shortage
 * said once to each person in earshot; and three AI players sending as fast as they can for five minutes stay within
 * the channel's and each person's lines a minute.
 *
 * Technical: F-34b's fixtures. People are logged-in survival mock players; AI players are logged-in mock players the
 * staged {@link KindredWire.Memberships} names as AI players, so the roster is built from the server's own player
 * list. Words are captured at the {@link KindredWire.Speaker}; AI receivers' knowledge at the {@link
 * KindredWire.Knowledge} sink and the inbox.
 */
public final class NativeWireGameTests implements FabricGameTest {
    /** A stand: who is an AI player, who belongs where, the mode, and what was said to whom. */
    private static final class Stand implements KindredWire.Memberships {
        final Map<UUID, UUID> ai = new HashMap<>();
        final Map<UUID, KindredWire.Belongs> belongs = new HashMap<>();
        final List<ServerPlayerEntity> logged = new ArrayList<>();
        final Map<UUID, List<String>> heard = new HashMap<>();
        final List<Delivery.Told> told = new ArrayList<>();
        final AtomicLong now = new AtomicLong();
        GameModes.WorldMode mode = GameModes.WorldMode.FACTIONS;
        final KindredWire wire;

        Stand(TestContext context, Set<String> announcements) {
            wire = new KindredWire(context.getWorld().getServer(), KindredWire.defaultWire(announcements), new Wording(), this, told::add,
                    (person, words) -> heard.computeIfAbsent(person.getUuid(), k -> new ArrayList<>()).add(words), now::get);
        }

        @Override public UUID aiPlayerOf(ServerPlayerEntity body) { return ai.get(body.getUuid()); }
        @Override public KindredWire.Belongs of(UUID id, ServerPlayerEntity body) { return belongs.getOrDefault(id, KindredWire.Belongs.NONE); }
        @Override public GameModes.WorldMode mode() { return mode; }

        ServerPlayerEntity person(TestContext context, String name, BlockPos pos, KindredWire.Belongs b) {
            var p = MockPlayers.survival(context, name, pos);
            logged.add(p);
            belongs.put(p.getUuid(), b);
            return p;
        }

        /** A logged-in body named as an AI player; its wire id is its own AI id. */
        UUID aiPlayer(TestContext context, String name, BlockPos pos, KindredWire.Belongs b) {
            var p = MockPlayers.survival(context, name, pos);
            logged.add(p);
            UUID id = UUID.randomUUID();
            ai.put(p.getUuid(), id);
            belongs.put(id, b);
            return id;
        }

        List<String> heardBy(ServerPlayerEntity p) { return heard.getOrDefault(p.getUuid(), List.of()); }

        void cleanup(TestContext context) {
            var manager = context.getWorld().getServer().getPlayerManager();
            for (var p : logged) manager.remove(p);
        }
    }

    private static KindredWire.Belongs faction(String f) { return new KindredWire.Belongs(Set.of(), f, "", ""); }

    private static KindredWire.Belongs side(String s) { return new KindredWire.Belongs(Set.of(), "", "", s); }

    private static Map<String, Fact> facts(Object... kv) {
        var out = new LinkedHashMap<String, Fact>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Fact) kv[i + 1]);
        return out;
    }

    /** A faction's announcement: its member (a person) hears it, its AI member is told it, the outsider beside them neither. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wire")
    public void aFactionAnnouncementReachesAMemberAndNotAnOutsider(TestContext context) {
        var s = new Stand(context, Set.of());
        UUID wren = s.aiPlayer(context, "Wren", new BlockPos(1, 2, 1), faction("oak"));
        UUID ash = s.aiPlayer(context, "Ash", new BlockPos(2, 2, 1), faction("oak"));
        UUID rook = s.aiPlayer(context, "Rook", new BlockPos(3, 2, 1), faction("birch"));
        var member = s.person(context, "Mat", new BlockPos(1, 2, 2), faction("oak"));
        var outsider = s.person(context, "Guest", new BlockPos(2, 2, 2), KindredWire.Belongs.NONE);
        var d = s.wire.send(Message.of("faction.capture", wren, Scope.faction("oak"),
                facts("chunk", Fact.of("12, -4", Visibility.PUBLIC), "from", Fact.of("birch", Visibility.PUBLIC)), List.of(501L)));
        context.assertTrue(d.sent(), "Sent: " + d.refused());
        context.assertTrue(s.heardBy(member).size() == 1 && s.heardBy(member).getFirst().startsWith("[faction] Wren: capture"), "The member hears it: " + s.heardBy(member));
        context.assertTrue(s.heardBy(outsider).isEmpty(), "The outsider hears nothing: " + s.heardBy(outsider));
        context.assertTrue(s.wire.inbox(ash).size() == 1 && s.wire.inbox(ash).getFirst().howKnown() == HowKnown.TOLD, "Ash is told it");
        context.assertTrue(s.wire.inbox(rook).isEmpty() && s.wire.inbox(wren).isEmpty(), "Neither the other faction nor the sender");
        context.assertTrue(s.told.stream().noneMatch(t -> t.receiver().equals(rook)), "Rook's knowledge untouched");
        context.assertTrue(s.wire.inbox(ash).getFirst().message().eventIds().equals(List.of(501L)), "Its event ids carried");
        s.cleanup(context);
        context.complete();
    }

    /** A hunter's side message, a runner beside the hunters: no runner hears or is told it, its facts never public. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wire")
    public void aHuntersSideMessageNeverReachesARunner(TestContext context) {
        var s = new Stand(context, Set.of());
        s.mode = GameModes.WorldMode.MANHUNT;
        String hunters = Listener.side("m1", "hunters"), runners = Listener.side("m1", "runners");
        UUID hawk = s.aiPlayer(context, "Hawk", new BlockPos(1, 2, 1), side(hunters));
        var mat = s.person(context, "Mat", new BlockPos(2, 2, 1), side(hunters));
        UUID fox = s.aiPlayer(context, "Fox", new BlockPos(1, 2, 2), side(runners));
        var ren = s.person(context, "Ren", new BlockPos(2, 2, 2), side(runners));
        for (int i = 0; i < 20; i++) {
            s.now.addAndGet(31_000);
            var pos = facts("runner_at", Fact.of((100 + i) + ", 70, -40", Visibility.SIDE));
            var d = s.wire.send(Message.of("side.sighting", hawk, Scope.side("m1", "hunters"), pos, List.of()));
            context.assertTrue(d.sent(), "Sent " + i + ": " + d.refused());
            var pub = s.wire.send(Message.of("side.sighting", hawk, s.wire.wire().earshotOf(new Listener.Place(
                    context.getWorld().getRegistryKey().getValue().toString(), 0, 0, 0)), pos, List.of()));
            context.assertTrue(pub.refused().orElse(null) == Delivery.Refusal.NARROWER_FACT, "Never public: " + pub.refused());
            var priv = s.wire.send(Message.of("private.sighting", hawk, Scope.toPlayer(ren.getUuid()), pos, List.of()));
            context.assertTrue(priv.receivers().isEmpty(), "Never privately to a runner");
        }
        context.assertTrue(s.heardBy(ren).isEmpty(), "Ren hears nothing: " + s.heardBy(ren));
        context.assertTrue(s.wire.inbox(fox).isEmpty(), "Fox is told nothing");
        context.assertTrue(s.heardBy(mat).size() >= 4, "Mat, a hunter, hears it: " + s.heardBy(mat).size());
        var asRunner = s.wire.send(Message.of("side.need", fox, Scope.side("m1", "hunters"), Map.of(), List.of()));
        context.assertTrue(asRunner.refused().orElse(null) == Delivery.Refusal.NOT_MEMBER, "A runner cannot post on the hunters' side");
        s.cleanup(context);
        context.complete();
    }

    /** A shortage announced near two people by two AI players: each person hears it once; one 40 blocks off not at all. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wire")
    public void aPublicShortageIsSaidOncePerPersonInEarshot(TestContext context) {
        var s = new Stand(context, Set.of());
        UUID wren = s.aiPlayer(context, "Wren", new BlockPos(1, 2, 1), faction("oak"));
        UUID rook = s.aiPlayer(context, "Rook", new BlockPos(3, 2, 1), faction("birch"));
        var a = s.person(context, "Mat", new BlockPos(1, 2, 3), KindredWire.Belongs.NONE);
        var b = s.person(context, "Ren", new BlockPos(4, 2, 3), faction("birch"));
        var far = s.person(context, "Far", new BlockPos(1, 2, 45), KindredWire.Belongs.NONE);
        var shortage = facts("item", Fact.of("iron ingot", Visibility.PUBLIC), "price", Fact.of("14", Visibility.PUBLIC));
        for (UUID from : List.of(wren, rook)) {
            var at = context.getAbsolutePos(new BlockPos(from == wren ? 1 : 3, 2, 1));
            var place = new Listener.Place(context.getWorld().getRegistryKey().getValue().toString(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            var d = s.wire.send(Message.of("market.shortage", from, s.wire.wire().earshotOf(place), shortage, List.of()));
            context.assertTrue(d.sent(), "Sent: " + d.refused());
            s.now.addAndGet(1_000);
        }
        context.assertTrue(s.heardBy(a).size() == 1 && s.heardBy(b).size() == 1, "Once each: " + s.heardBy(a) + " " + s.heardBy(b));
        context.assertTrue(s.heardBy(far).isEmpty(), "Not 40 blocks off: " + s.heardBy(far));
        s.cleanup(context);
        context.complete();
    }

    /** Three AI players sending on their faction a second each for five minutes: ten a minute go out, a person hears four. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wire")
    public void theBudgetHoldsUnderThreePlayersForFiveMinutes(TestContext context) {
        var s = new Stand(context, Set.of());
        var senders = List.of(s.aiPlayer(context, "A", new BlockPos(1, 2, 1), faction("oak")),
                s.aiPlayer(context, "B", new BlockPos(2, 2, 1), faction("oak")),
                s.aiPlayer(context, "C", new BlockPos(3, 2, 1), faction("oak")));
        var mat = s.person(context, "Mat", new BlockPos(2, 2, 3), faction("oak"));
        int[] sent = new int[5];
        int n = 0;
        while (s.now.get() < 300_000) {
            for (UUID from : senders) {
                var d = s.wire.send(Message.of("faction.need", from, Scope.faction("oak"), facts("n", Fact.of("" + (n++), Visibility.FACTION)), List.of()));
                if (d.sent()) sent[(int) (s.now.get() / 60_000)]++;
            }
            s.now.addAndGet(1_000);
        }
        for (int m = 0; m < 5; m++) context.assertTrue(sent[m] == 10, "Ten in minute " + m + ": " + sent[m]);
        context.assertTrue(s.heardBy(mat).size() == 20, "Four a minute to the person: " + s.heardBy(mat).size());
        KindredMod.LOGGER.info("Wire under three players for five minutes: {} sent, {} heard by the person", java.util.Arrays.stream(sent).sum(), s.heardBy(mat).size());
        s.cleanup(context);
        context.complete();
    }

    /** The runtime's memberships: AI players out of a match share the team; a manhunt splits by side; a speedrunner is a side of its own. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wire")
    public void theRuntimesMembershipsFollowTheMatch(TestContext context) {
        UUID hunter = UUID.randomUUID(), runner = UUID.randomUUID(), racer = UUID.randomUUID();
        var hunt = new dev.kindred.core.match.MatchRegistry.Match(UUID.randomUUID(), dev.kindred.core.match.MatchRegistry.Mode.MANHUNT,
                List.of(hunter, runner), Set.of(runner), Set.of(hunter, runner), Set.of(), dev.kindred.core.match.MatchRegistry.State.RUNNING,
                0, 0, null, Set.of(), "");
        var race = new dev.kindred.core.match.MatchRegistry.Match(UUID.randomUUID(), dev.kindred.core.match.MatchRegistry.Mode.SPEEDRUN,
                List.of(racer), Set.of(racer), Set.of(racer), Set.of(), dev.kindred.core.match.MatchRegistry.State.RUNNING, 0, 0, null, Set.of(), "");
        var free = KindredWire.belongs(true, java.util.Optional.empty(), UUID.randomUUID(), "oak");
        context.assertTrue(free.teams().equals(Set.of(KindredWire.TEAM)) && free.faction().equals("oak") && free.side().isEmpty(), "Out of a match: " + free);
        context.assertTrue(KindredWire.belongs(false, java.util.Optional.empty(), UUID.randomUUID(), null).teams().isEmpty(), "A person is on no AI team");
        var h = KindredWire.belongs(true, java.util.Optional.of(hunt), hunter, "");
        var r = KindredWire.belongs(true, java.util.Optional.of(hunt), runner, "");
        context.assertTrue(h.side().equals(hunt.id() + ":hunters") && r.side().equals(hunt.id() + ":runners") && h.teams().isEmpty(), "Sides: " + h + " " + r);
        context.assertTrue(KindredWire.belongs(true, java.util.Optional.of(race), racer, "").side().equals(race.id() + ":" + racer), "A racer its own side");
        context.complete();
    }

    /** The Factions mode's four announcements reach everyone on the server from the server alone; nothing else does. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wire")
    public void factionAnnouncementsReachTheWholeServerFromTheServerAlone(TestContext context) {
        var s = new Stand(context, KindredWire.FACTION_ANNOUNCEMENTS);
        UUID wren = s.aiPlayer(context, "Wren", new BlockPos(1, 2, 1), faction("oak"));
        var far = s.person(context, "Far", new BlockPos(1, 2, 60), KindredWire.Belongs.NONE);
        for (String kind : KindredWire.FACTION_ANNOUNCEMENTS) {
            s.now.addAndGet(1_000);
            var d = s.wire.send(Message.of(kind, null, Scope.server(), facts("faction", Fact.of("oak", Visibility.PUBLIC)), List.of(1L)));
            context.assertTrue(d.sent() && d.receivers().contains(far.getUuid()) && d.receivers().contains(wren), kind + " to everyone: " + d.refused());
        }
        var fromAPlayer = s.wire.send(Message.of("faction.capture", wren, Scope.server(), Map.of(), List.of()));
        context.assertTrue(fromAPlayer.refused().orElse(null) == Delivery.Refusal.NOT_ANNOUNCEMENT, "Only the server announces");
        var other = s.wire.send(Message.of("faction.gossip", null, Scope.server(), Map.of(), List.of()));
        context.assertTrue(other.refused().orElse(null) == Delivery.Refusal.NOT_ANNOUNCEMENT, "Only the four kinds");
        context.assertTrue(s.heardBy(far).size() == 4, "Said once each: " + s.heardBy(far));
        s.cleanup(context);
        context.complete();
    }
}
