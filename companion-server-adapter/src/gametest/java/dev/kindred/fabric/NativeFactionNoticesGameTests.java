package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.Capture;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a GameTest of the Factions mode's notices on the Wire (M-06e with F-34). Bram's faction holds five
 * chunks in a plus with the power for one: the whole server is told it is vulnerable, citing the Record's notice. Ada,
 * an AI player, takes the border chunk she stands in: the capture is written once, and each side's faction is told once,
 * in today's words, citing exactly the capture's events. Once Bram's power is back the server is told it is secure.
 *
 * Technical: two AI players, each with a person in its faction, and a person in none. The notices are the server's own
 * messages (from nobody), sent through {@code PlayerRuntime.recordAndTell} by FactionsService's notifier, the one writer
 * of their events: the AI's act holds none of the capture's events, so neither is written twice nor told twice.
 */
public final class NativeFactionNoticesGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";
    /** The border at its widest: the test structures stand far out (as in NativeFactionPlayGameTests). */
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionNotices", tickLimit = 1800)
    public void vulnerableCaptureAndSecureAreToldOnTheWireCitingTheRecordAndNeverTwice(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1700);
        var server = context.getWorld().getServer();
        UUID[] ids = new UUID[2], bodies = new UUID[2];
        ServerPlayerEntity[] people = new ServerPlayerEntity[3]; // Cal in Ada's faction, Dee in Bram's, Eve in none.
        var heard = new java.util.concurrent.ConcurrentHashMap<UUID, List<String>>();
        String[] names = new String[2], taken = new String[1];
        int[] t = new int[2];
        int[] lookFrom = new int[1];
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        FixtureGround.whenDone(context, () -> {
            for (var x : people) if (x != null && server.getPlayerManager().getPlayer(x.getUuid()) != null) server.getPlayerManager().remove(x);
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    lookFrom[0] = server.getTicks();
                    f.runtime.heardForFixtures((person, text) -> heard.computeIfAbsent(person.getUuid(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(text.getString()));
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1;
                        ids[i] = status.id();
                        bodies[i] = status.nativeId();
                    }
                    var at = player(f, bodies[0]).getBlockPos();
                    people[0] = MockPlayers.survival(context, "Cal", context.getRelativePos(at));
                    people[1] = MockPlayers.survival(context, "Dee", context.getRelativePos(at));
                    people[2] = MockPlayers.survival(context, "Eve", context.getRelativePos(at));
                    // Founded 50 hours back, past the new-faction shield.
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.factionActForTest(ids[0], FactionPlay.Kind.FOUND, "").accepted(), "Ada founds her faction");
                    context.assertTrue(f.runtime.factionActForTest(ids[1], FactionPlay.Kind.FOUND, "").accepted(), "Bram founds his");
                })
                .createAndAdd(() -> {
                    var red = f.runtime.factions().book().of(bodies[0]).orElseThrow();
                    var blue = f.runtime.factions().book().of(bodies[1]).orElseThrow();
                    names[0] = red.name();
                    names[1] = blue.name();
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "invite", List.of("Cal")).accepted(), "Ada invites Cal");
                    context.assertTrue(f.runtime.faction(f.source, people[0].getUuid(), "join", List.of(red.name())).accepted(), "Cal joins Ada's");
                    context.assertTrue(f.runtime.faction(f.source, bodies[1], "invite", List.of("Dee")).accepted(), "Bram invites Dee");
                    context.assertTrue(f.runtime.faction(f.source, people[1].getUuid(), "join", List.of(blue.name())).accepted(), "Dee joins Bram's");
                    f.runtime.factions().clockForTest(0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The factions written");
                    // The first once-a-second look after a start only remembers: two looks made before the land is written.
                    context.assertTrue(server.getTicks() >= lookFrom[0] + 40, "A look made before Bram's land");
                    // Bram's plus round Ada: her chunk T on its west arm, the centre east of it; his faction's power one.
                    var ada = player(f, bodies[0]);
                    t[0] = ada.getBlockX() >> 4;
                    t[1] = ada.getBlockZ() >> 4;
                    taken[0] = ClaimLedger.chunk(W, t[0], t[1]);
                    String blue = f.runtime.factions().book().of(bodies[1]).orElseThrow().id().toString();
                    for (int[] c : new int[][] {{0, 0}, {1, 0}, {2, 0}, {1, 1}, {1, -1}}) {
                        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, ClaimLedger.chunk(W, t[0] + c[0], t[1] + c[1]), Map.of()));
                    }
                    f.runtime.record(PowerRules.set(bodies[1], 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                    f.runtime.record(PowerRules.set(people[1].getUuid(), 0, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    // The once-a-second look: Bram's faction overextended, the whole server told, citing the Record's notice.
                    var told = f.runtime.wire().inbox(ids[0], Capture.VULNERABLE);
                    var blueId = f.runtime.factions().book().of(bodies[1]).orElseThrow().id();
                    context.assertTrue(told.size() == 1, "Ada told once that Bram's faction is vulnerable: " + f.runtime.wire().inbox(ids[0]) + " "
                            + Capture.exposure(f.runtime.factions().book(), f.runtime.factions().powers(), f.runtime.projected().claims(), blueId, WIDE));
                    var m = told.getFirst().message();
                    context.assertTrue(m.fromServer() && m.scope().serverWide() && m.eventIds().size() == 1, "The server's, to everyone, citing one event: " + m);
                    String line = "[public] " + names[1] + " is vulnerable: ";
                    for (var person : people) {
                        var lines = lines(heard, person, line);
                        context.assertTrue(lines.size() == 1 && lines.getFirst().endsWith(" chunks exposed"), person.getName().getString() + " hears it once: " + heard.get(person.getUuid()));
                    }
                    // Ada stands in T and takes it: her own act through /faction capture's method.
                    var ada = player(f, bodies[0]);
                    ada.refreshPositionAndAngles(t[0] * 16 + 8, ada.getY(), t[1] * 16 + 8, 0, 0);
                    var reply = f.runtime.factionActForTest(ids[0], FactionPlay.Kind.CAPTURE, "");
                    context.assertTrue(reply.accepted(), "Ada captures T: " + reply);
                })
                .createAndAdd(() -> {
                    context.assertTrue(!f.runtime.wire().inbox(ids[1], "faction.capture").isEmpty(), "Bram told of the capture: " + f.runtime.wire().inbox(ids[1]));
                    record[0] = f.runtime.worldEventsForTest(Set.of(ClaimLedger.CAPTURE, dev.kindred.core.factions.Coins.KIND, Capture.VULNERABLE));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var events = record[0].join();
                    var captures = events.stream().filter(e -> e.kind().equals(ClaimLedger.CAPTURE) && taken[0].equals(e.place())).map(WorldEvent::id).toList();
                    var paid = events.stream().filter(e -> e.kind().equals(dev.kindred.core.factions.Coins.KIND) && bodies[0].equals(e.actor())
                            && ("capture " + taken[0]).equals(e.get("for", ""))).map(WorldEvent::id).toList();
                    context.assertTrue(captures.size() == 1 && paid.size() == 1, "The capture and its cost written once each: " + captures + " " + paid);
                    var decision = new ArrayList<Long>(captures);
                    decision.addAll(paid);
                    java.util.Collections.sort(decision);
                    var vulnerable = events.stream().filter(e -> e.kind().equals(Capture.VULNERABLE)).map(WorldEvent::id).toList();
                    context.assertTrue(f.runtime.wire().inbox(ids[0], Capture.VULNERABLE).getFirst().message().eventIds().equals(vulnerable),
                            "The vulnerable notice cites the Record's: " + vulnerable);
                    // Each side told once, by the server, citing exactly the capture's events: Ada's act told nothing of its own.
                    var toAda = f.runtime.wire().inbox(ids[0], "faction.capture");
                    var toBram = f.runtime.wire().inbox(ids[1], "faction.capture");
                    context.assertTrue(toAda.size() == 1 && toBram.size() == 1, "One capture message to each side: " + toAda.size() + ", " + toBram.size());
                    for (var told : List.of(toAda.getFirst().message(), toBram.getFirst().message())) {
                        context.assertTrue(told.fromServer() && told.eventIds().stream().sorted().toList().equals(decision),
                                "The server's, citing the capture and its cost: " + told.eventIds() + " " + decision);
                    }
                    context.assertTrue(toAda.getFirst().message().scope().key().equals(f.runtime.factions().book().of(bodies[0]).orElseThrow().id().toString())
                            && toBram.getFirst().message().scope().key().equals(f.runtime.factions().book().of(bodies[1]).orElseThrow().id().toString()),
                            "Each to its own faction");
                    // Only the capture's lines: the founding told earlier rests on its own events.
                    var cal = lines(heard, people[0], "[faction] " + names[0] + " captured");
                    var dee = lines(heard, people[1], "[faction] " + names[0] + " captured");
                    context.assertTrue(cal.equals(List.of("[faction] " + names[0] + " captured " + taken[0] + " from " + names[1])), "Cal hears today's line once: " + cal);
                    context.assertTrue(dee.equals(List.of("[faction] " + names[0] + " captured your chunk " + taken[0])), "Dee hears today's line once: " + dee);
                    context.assertTrue(lines(heard, people[2], "[faction] " + names[0] + " captured").isEmpty(), "Eve, in no faction, hears neither");
                    // Bram's power back: secure again.
                    f.runtime.record(PowerRules.set(bodies[1], 10, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    var told = f.runtime.wire().inbox(ids[0], Capture.SECURE);
                    context.assertTrue(told.size() == 1 && told.getFirst().message().fromServer() && told.getFirst().message().eventIds().size() == 1,
                            "Ada told once that Bram's faction is secure: " + f.runtime.wire().inbox(ids[0]));
                    for (var person : people) {
                        context.assertTrue(lines(heard, person, "[public] " + names[1] + " is secure again").size() == 1,
                                person.getName().getString() + " hears it once: " + heard.get(person.getUuid()));
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static List<String> lines(Map<UUID, List<String>> heard, ServerPlayerEntity person, String start) {
        return heard.getOrDefault(person.getUuid(), List.of()).stream().filter(l -> l.startsWith(start)).toList();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
