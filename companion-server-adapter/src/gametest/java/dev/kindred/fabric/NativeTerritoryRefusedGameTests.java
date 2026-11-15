package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.SafeZones;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.TerritoryRefusals;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a refused attempt on a faction's land is written down for that faction, once a minute for each player
 * and chunk: a person's break, and an AI player's own break as a person's; an AI player asking whether it may (as its
 * plans do all the time) writes nothing, and nor does a refusal in a safe zone (F-34c2, Session A's conditions). A
 * griefer hammering the land writes no more than the limits allow, and the refusal path costs the server thread only a
 * lookup and a queued write.
 */
public final class NativeTerritoryRefusedGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";

    private static void claim(PlayerRuntime runtime, TestContext context, BlockPos local, String owner) {
        var at = context.getAbsolutePos(local);
        runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, owner,
                ClaimLedger.chunk(W, at.getX() >> 4, at.getZ() >> 4), Map.of()));
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryRefused", tickLimit = 1200)
    public void aRefusedBreakIsWrittenOnceAMinuteAndAPlanningCheckWritesNothing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        var server = context.getWorld().getServer();
        UUID[] bodies = new UUID[2];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        BlockPos target = new BlockPos(5, 2, 5);
        String[] red = new String[1], chunk = new String[1];
        long[] nanos = new long[2];
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        FixtureGround.whenDone(context, () -> { if (person[0] != null && server.getPlayerManager().getPlayer(person[0].getUuid()) != null) server.getPlayerManager().remove(person[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) bodies[player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1] = status.nativeId();
                    person[0] = MockPlayers.survival(context, "Cleo", new BlockPos(4, 2, 4));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, bodies[1], "create", List.of("Blue", "BLU")).accepted(), "Bram founds Blue");
                })
                .createAndAdd(() -> {
                    red[0] = f.runtime.factions().book().named("Red").orElseThrow().id().toString();
                    for (var corner : List.of(new BlockPos(0, 0, 0), new BlockPos(8, 0, 0), new BlockPos(0, 0, 8), new BlockPos(8, 0, 8))) claim(f.runtime, context, corner, red[0]);
                    context.setBlockState(target, Blocks.OAK_PLANKS);
                    var at = context.getAbsolutePos(target);
                    chunk[0] = ClaimLedger.chunk(W, at.getX() >> 4, at.getZ() >> 4);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    var at = context.getAbsolutePos(target);
                    var bram = player(f, bodies[1]);
                    // The planning check, as an AI player's gathering asks: refused, and nothing written.
                    for (int i = 0; i < 50; i++) context.assertFalse(TerritoryGuard.mayChange(bram, at), "Bram may not change Red's land");
                    // A person's real attempt: refused, written once; again in the same minute, refused and not written.
                    context.assertFalse(person[0].interactionManager.tryBreakBlock(at), "Cleo's break refused");
                    context.assertFalse(person[0].interactionManager.tryBreakBlock(at), "and again");
                    // An AI player's own real attempt: its body starting to dig, as its mining does (a bare break is its body's
                    // own gate's to refuse, which lets only its mining target go): refused, and written as a person's would be.
                    bram.interactionManager.processBlockBreakingAction(at, net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                            net.minecraft.util.math.Direction.UP, context.getWorld().getTopY(), 0);
                    // A griefer hammering five hundred times in a tick: the refusal path's cost on the server thread.
                    long start = System.nanoTime();
                    for (int i = 0; i < 500; i++) TerritoryGuard.refused(person[0], context.getWorld(), at, "break");
                    nanos[0] = System.nanoTime() - start;
                    nanos[1] = 500;
                    KindredMod.LOGGER.info("Refusal path: {} calls in {} us on the server thread ({} ns each), all within the minute, none written",
                            nanos[1], nanos[0] / 1_000, nanos[0] / nanos[1]);
                    context.expectBlock(Blocks.OAK_PLANKS, target);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Written");
                    record[0] = f.runtime.worldEventsForTest(Set.of(TerritoryRefusals.REFUSED));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var refusals = record[0].join();
                    context.assertTrue(refusals.size() == 2, "Two refusals written, Cleo's and Bram's: " + refusals);
                    var cleo = refusals.stream().filter(e -> person[0].getUuid().equals(e.actor())).toList();
                    var bram = refusals.stream().filter(e -> bodies[1].equals(e.actor())).toList();
                    context.assertTrue(cleo.size() == 1 && bram.size() == 1, "One each: " + refusals);
                    for (var e : refusals) {
                        context.assertTrue(red[0].equals(e.subject()) && chunk[0].equals(e.place()) && "break".equals(e.get("what", "")),
                                "Red's, its chunk, a break: " + e);
                    }
                    long[] counts = f.runtime.factions().refusalCounts();
                    context.assertTrue(counts[0] == 2 && counts[1] == 501 && counts[2] == 0, "Two written, 501 the same minute's: " + java.util.Arrays.toString(counts));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryRefusedSafe", tickLimit = 800)
    public void aRefusalInASafeZoneOrTheWildernessWritesNothing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        BlockPos target = new BlockPos(5, 2, 5);
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        FixtureGround.whenDone(context, () -> { if (person[0] != null && server.getPlayerManager().getPlayer(person[0].getUuid()) != null) server.getPlayerManager().remove(person[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    person[0] = MockPlayers.survival(context, "Cleo", new BlockPos(4, 2, 4));
                    var chunks = new TreeSet<String>();
                    for (var corner : List.of(new BlockPos(0, 0, 0), new BlockPos(8, 0, 0), new BlockPos(0, 0, 8), new BlockPos(8, 0, 8))) {
                        var at = context.getAbsolutePos(corner);
                        chunks.add(SafeZones.chunkAt(W, at.getX(), at.getZ()));
                    }
                    f.runtime.record(SafeZones.zone("fixture", chunks, null, server.getTicks(), System.currentTimeMillis()));
                    context.setBlockState(target, Blocks.OAK_PLANKS);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The zone written");
                    var at = context.getAbsolutePos(target);
                    context.assertFalse(person[0].interactionManager.tryBreakBlock(at), "Cleo's break refused in the safe zone");
                    // The wilderness just outside: the guard refuses nothing there, and nothing could be written of it.
                    TerritoryGuard.refused(person[0], context.getWorld(), at.add(64, 0, 64), "break");
                    context.expectBlock(Blocks.OAK_PLANKS, target);
                    record[0] = f.runtime.worldEventsForTest(Set.of(TerritoryRefusals.REFUSED));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    context.assertTrue(record[0].join().isEmpty(), "Nothing written: " + record[0].join());
                    context.assertTrue(f.runtime.factions().refusalCounts()[0] == 0, "None written by the limit's count");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }
}
