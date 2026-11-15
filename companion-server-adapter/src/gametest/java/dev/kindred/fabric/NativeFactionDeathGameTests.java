package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.record.DeathLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: when a faction's member dies, its faction is told, and no one else: an AI player by its name and how
 * it died, a person only as "A member", never the killer or the place (F-34c1). The message rests on the death the World
 * Record holds, written first and told after. An AI player's death comes through its runtime's own body hook
 * (onNativeDeath), as in play; a person's through the mod's death hook to the server's own runtime, the third fixture.
 */
public final class NativeFactionDeathGameTests implements FabricGameTest {
    /** The border at its widest: the test structures stand far out (as in NativeFactionTellGameTests). */
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionDeath", tickLimit = 1800)
    public void aMembersDeathIsToldToItsFactionAloneAndAPersonIsNotNamed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1700);
        String[] names = {"Ada", "Bram", "Cora"};
        UUID[] ids = new UUID[3], bodies = new UUID[3];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        boolean[] dead = {false, false};
        List<String> cleoHeard = new ArrayList<>();
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 2, "Bram allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cora"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 3 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "All three established");
                    for (var status : statuses) {
                        int i = List.of(names).indexOf(player(f, status.nativeId()).getName().getString());
                        ids[i] = status.id();
                        bodies[i] = status.nativeId();
                    }
                    person[0] = MockPlayers.survival(context, "Cleo", new BlockPos(1, 2, 1));
                    f.runtime.heardForFixtures((who, text) -> { if (who == person[0]) cleoHeard.add(text.getString()); });
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "create", List.of("Red", "RED", "red")).accepted(), "Cleo founds Red");
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "invite", List.of("Ada")).accepted(), "Cleo invites Ada");
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "invite", List.of("Bram")).accepted(), "Cleo invites Bram");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "join", List.of("Red")).accepted(), "Ada joins Red");
                    context.assertTrue(f.runtime.faction(f.source, bodies[1], "join", List.of("Red")).accepted(), "Bram joins Red");
                    context.assertTrue(f.runtime.faction(f.source, bodies[2], "create", List.of("Blue", "BLU", "blue")).accepted(), "Cora founds Blue");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.factions().book().named("Red").orElseThrow().members().size() == 3, "Red: Cleo, Ada and Bram"))
                .createAndAdd(() -> {
                    var ada = player(f, bodies[0]);
                    if (!dead[0]) {
                        ada.damage(ada.getDamageSources().generic(), 1000f);
                        dead[0] = ada.isDead();
                    }
                    context.assertTrue(dead[0], "Ada died");
                })
                .createAndAdd(() -> {
                    var told = f.runtime.wire().inbox(ids[1], PlayerRuntime.MEMBER_DIED);
                    context.assertTrue(told.size() == 1, "Bram told of Ada's death: " + f.runtime.wire().inbox(ids[1]));
                    var m = told.getFirst().message();
                    context.assertTrue(m.fromServer() && "Ada".equals(m.facts().get("who").value()) && !m.facts().get("cause").value().isBlank()
                            && m.facts().keySet().equals(Set.of("who", "cause")), "Who and how, and nothing else: " + m.facts());
                    context.assertTrue(f.runtime.wire().inbox(ids[2], PlayerRuntime.MEMBER_DIED).isEmpty(), "Cora, of Blue, told nothing");
                    context.assertTrue(cleoHeard.stream().anyMatch(l -> l.contains("Ada died")), "Cleo, of Red, hears it: " + cleoHeard);
                })
                .createAndAdd(() -> {
                    if (!dead[1]) {
                        var source = person[0].getDamageSources().generic();
                        person[0].damage(source, 1000f);
                        dead[1] = person[0].isDead();
                        // The mod's death hook (KindredMod, AFTER_DEATH) reaches the server's own runtime, not a fixture's:
                        // its death handed to this one as that hook hands it there.
                        if (dead[1]) f.runtime.playerDied(person[0], source);
                    }
                    context.assertTrue(dead[1], "Cleo died");
                })
                .createAndAdd(() -> {
                    var told = f.runtime.wire().inbox(ids[1], PlayerRuntime.MEMBER_DIED);
                    context.assertTrue(told.size() == 2, "Bram told of Cleo's death too: " + told);
                    var m = told.getFirst().message(); // Newest first.
                    context.assertTrue("A member".equals(m.facts().get("who").value()), "A person not named: " + m.facts());
                    context.assertTrue(told.stream().noneMatch(t -> t.message().facts().values().stream().anyMatch(v -> v.value().contains("Cleo"))),
                            "Cleo's name in nothing told");
                    context.assertTrue(f.runtime.wire().inbox(ids[2], PlayerRuntime.MEMBER_DIED).isEmpty(), "Cora still told nothing");
                    record[0] = f.runtime.worldEventsForTest(Set.of(DeathLedger.DEATH));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var deaths = record[0].join();
                    var told = f.runtime.wire().inbox(ids[1], PlayerRuntime.MEMBER_DIED);
                    var ada = deaths.stream().filter(e -> bodies[0].equals(e.actor())).map(WorldEvent::id).toList();
                    var cleo = deaths.stream().filter(e -> person[0].getUuid().equals(e.actor())).map(WorldEvent::id).toList();
                    // Newest first: Cleo's, then Ada's.
                    context.assertTrue(ada.size() == 1 && told.getLast().message().eventIds().equals(ada), "Ada's told cites her death: " + told.getLast().message().eventIds() + " " + ada);
                    context.assertTrue(cleo.size() == 1 && told.getFirst().message().eventIds().equals(cleo), "Cleo's told cites hers: " + told.getFirst().message().eventIds() + " " + cleo);
                    f.runtime.stop();
                    server(f).getPlayerManager().remove(person[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A person on the death screen is still online, so is told as every online member is (M-06e: "both factions' members
     * online are told"): once vanilla has taken the dead body out of the world (20 ticks after death), until it respawns,
     * a member's death still reaches that person, at once, not on respawn (F-34c1a, from RUNNER 2's f34c3 run).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionDeathScreen", tickLimit = 1400)
    public void aPersonOnTheDeathScreenIsToldOfAMembersDeath(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1300);
        UUID[] bodies = new UUID[1];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        boolean[] dead = {false, false};
        int[] since = {0};
        List<String> cleoHeard = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Ada established");
                    bodies[0] = statuses.getFirst().nativeId();
                    person[0] = MockPlayers.survival(context, "Cleo", new BlockPos(1, 2, 1));
                    f.runtime.heardForFixtures((who, text) -> { if (who == person[0]) cleoHeard.add(text.getString()); });
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "create", List.of("Red", "RED", "red")).accepted(), "Cleo founds Red");
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "invite", List.of("Ada")).accepted(), "Cleo invites Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.faction(f.source, bodies[0], "join", List.of("Red")).accepted(), "Ada joins Red"))
                .createAndAdd(() -> {
                    if (!dead[1]) {
                        var source = person[0].getDamageSources().generic();
                        person[0].damage(source, 1000f);
                        dead[1] = person[0].isDead();
                        if (dead[1]) f.runtime.playerDied(person[0], source);
                    }
                    context.assertTrue(dead[1], "Cleo died");
                })
                .createAndAdd(() -> {
                    // The death screen: the body out of the world, the person still on the server, not respawned. A mock
                    // player's connection is not the server's to tick, so its ticks are run here as its connection would
                    // (as NativeNightsGameTests does): vanilla takes a dead body out of the world 20 ticks after its death.
                    if (!person[0].isRemoved()) person[0].playerTick();
                    context.assertTrue(person[0].isRemoved() && person[0].isDead(), "Cleo's body taken out of the world, not respawned");
                    context.assertTrue(server(f).getPlayerManager().getPlayer(person[0].getUuid()) == person[0], "Cleo still on the server");
                    since[0] = cleoHeard.size();
                })
                .createAndAdd(() -> {
                    var ada = player(f, bodies[0]);
                    if (!dead[0]) {
                        ada.damage(ada.getDamageSources().generic(), 1000f);
                        dead[0] = ada.isDead();
                    }
                    context.assertTrue(dead[0], "Ada died");
                })
                .createAndAdd(() -> {
                    var after = cleoHeard.subList(since[0], cleoHeard.size());
                    context.assertTrue(person[0].isRemoved() && person[0].isDead(), "Cleo still on the death screen");
                    context.assertTrue(after.stream().filter(l -> l.contains("Ada died")).count() == 1, "Cleo, on the death screen, hears Ada died once: " + after);
                    f.runtime.heardForFixtures(null);
                    f.runtime.stop();
                    server(f).getPlayerManager().remove(person[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Outside a faction world, or out of any faction, a death is only written: nobody is told of it. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionDeathNone", tickLimit = 1200)
    public void aDeathOutOfAnyFactionIsWrittenAndToldToNoOne(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        UUID[] ids = new UUID[2], bodies = new UUID[2];
        boolean[] dead = {false};
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
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
                    // Bram has a faction; Ada has none.
                    context.assertTrue(f.runtime.faction(f.source, bodies[1], "create", List.of("Blue", "BLU", "blue")).accepted(), "Bram founds Blue");
                })
                .createAndAdd(() -> {
                    var ada = player(f, bodies[0]);
                    if (!dead[0]) {
                        ada.damage(ada.getDamageSources().generic(), 1000f);
                        dead[0] = ada.isDead();
                    }
                    context.assertTrue(dead[0], "Ada died");
                    record[0] = f.runtime.worldEventsForTest(Set.of(DeathLedger.DEATH));
                })
                .createAndAdd(() -> {
                    context.assertTrue(record[0].isDone(), "The Record read");
                    context.assertTrue(record[0].join().stream().anyMatch(e -> bodies[0].equals(e.actor())), "Ada's death written");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.wire().inbox(ids[1], PlayerRuntime.MEMBER_DIED).isEmpty(), "Bram told nothing of a death out of his faction");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The production path for a person: a person's real death reaches the server's own runtime through the mod's death
     * hook (KindredMod, AFTER_DEATH), which writes it and tells the faction, here a faction-mate who is a person too.
     * Nothing is handed in. The server's runtime is left as found: the faction disbanded, its world no longer factions.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionDeathHook", tickLimit = 600)
    public void aPersonsDeathReachesTheServersRuntimeThroughTheDeathHookAndIsToldUnnamed(TestContext context) {
        var server = context.getWorld().getServer();
        var runtime = KindredMod.runtime();
        var source = server.getCommandSource();
        String name = "Hk" + Integer.toHexString(context.getWorld().getRandom().nextInt(0xffff));
        ServerPlayerEntity[] people = new ServerPlayerEntity[2];
        boolean[] dead = {false};
        List<String> heard = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(runtime != null && runtime.isReady(), "The server's runtime ready"))
                .createAndAdd(() -> {
                    runtime.factionsWorldForTest(WIDE);
                    people[0] = MockPlayers.survival(context, "Cleo", new BlockPos(1, 2, 1));
                    people[1] = MockPlayers.survival(context, "Dan", new BlockPos(3, 2, 1));
                    runtime.heardForFixtures((who, text) -> { if (who == people[1]) heard.add(text.getString()); });
                    context.assertTrue(runtime.faction(source, people[0].getUuid(), "create", List.of(name, "HK", "red")).accepted(), "Cleo founds " + name);
                    context.assertTrue(runtime.faction(source, people[0].getUuid(), "invite", List.of("Dan")).accepted(), "Cleo invites Dan");
                })
                .createAndAdd(() -> context.assertTrue(runtime.faction(source, people[1].getUuid(), "join", List.of(name)).accepted(), "Dan joins"))
                .createAndAdd(() -> context.assertTrue(runtime.factions().book().named(name).orElseThrow().members().size() == 2, "Cleo and Dan"))
                .createAndAdd(() -> {
                    if (!dead[0]) {
                        people[0].damage(people[0].getDamageSources().generic(), 1000f);
                        dead[0] = people[0].isDead();
                    }
                    context.assertTrue(dead[0], "Cleo died");
                })
                .createAndAdd(() -> {
                    context.assertTrue(heard.stream().anyMatch(l -> l.contains("A member died")), "Dan hears a member died: " + heard);
                    context.assertTrue(heard.stream().noneMatch(l -> l.contains("Cleo")), "Cleo not named: " + heard);
                })
                .createAndAdd(() -> {
                    runtime.heardForFixtures(null);
                    runtime.faction(source, people[1].getUuid(), "leave", List.of());
                    runtime.faction(source, people[0].getUuid(), "disband", List.of());
                    runtime.factionsWorldOffForTest();
                    server.getPlayerManager().remove(people[0]);
                    server.getPlayerManager().remove(people[1]);
                })
                .createAndAdd(() -> context.assertTrue(runtime.factions().book().named(name).isEmpty(), "Its faction gone, the server's runtime as found"))
                .completeIfSuccessful();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }

    private static net.minecraft.server.MinecraftServer server(NativePaidSkillsGameTests.Fixture f) {
        return f.source.getServer();
    }
}
