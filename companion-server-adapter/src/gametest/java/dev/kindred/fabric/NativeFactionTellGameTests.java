package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.FactionLedger;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * An AI player's faction acts told to its faction on the Wire resting on the World Record (M-06i with F-34): what it
 * joined and what it claimed are written first, and the message cites exactly the events the Record gave them; and a
 * write-then-tell begun as the runtime stops tells nothing after the stop (the stop barrier, as for recordThen).
 */
public final class NativeFactionTellGameTests implements FabricGameTest {
    /** The border at its widest: the test structures stand far out (as in NativeFactionPlayGameTests). */
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionTell", tickLimit = 1600)
    public void aJoinAndAClaimAreToldCitingTheRecordsEvents(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        UUID[] ids = new UUID[2], bodies = new UUID[2];
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
                    context.assertTrue(f.runtime.factionActForTest(ids[0], FactionPlay.Kind.FOUND, "").accepted(), "Ada founds her faction");
                })
                .createAndAdd(() -> {
                    var faction = f.runtime.factions().book().of(bodies[0]).orElseThrow();
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "invite", List.of("Bram")).accepted(), "Ada invites Bram");
                    context.assertTrue(f.runtime.factionActForTest(ids[1], FactionPlay.Kind.JOIN, faction.id().toString()).accepted(), "Bram joins");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.factions().book().of(bodies[1]).isPresent(), "Bram in Ada's faction");
                    context.assertTrue(f.runtime.factionActForTest(ids[0], FactionPlay.Kind.CLAIM, "").accepted(), "Ada claims where she stands");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!f.runtime.wire().inbox(ids[1], "faction.claim").isEmpty(), "Bram told of the claim: " + f.runtime.wire().inbox(ids[1]));
                    context.assertTrue(!f.runtime.wire().inbox(ids[0], "faction.joined").isEmpty(), "Ada told of the join: " + f.runtime.wire().inbox(ids[0]));
                    record[0] = f.runtime.worldEventsForTest(Set.of(ClaimLedger.CLAIM, FactionLedger.JOIN, dev.kindred.core.factions.Coins.KIND));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var events = record[0].join();
                    var claim = events.stream().filter(e -> e.kind().equals(ClaimLedger.CLAIM)).map(WorldEvent::id).toList();
                    // The claim's decision: the claim and the coins it cost ("claim <chunk>"), written together (Territory.claim).
                    String claimed = events.stream().filter(e -> e.kind().equals(ClaimLedger.CLAIM)).map(WorldEvent::place).findFirst().orElse("");
                    var spent = events.stream().filter(e -> e.kind().equals(dev.kindred.core.factions.Coins.KIND) && bodies[0].equals(e.actor())
                            && ("claim " + claimed).equals(e.get("for", ""))).map(WorldEvent::id).toList();
                    var decision = new java.util.ArrayList<Long>(claim);
                    decision.addAll(spent);
                    java.util.Collections.sort(decision);
                    var join = events.stream().filter(e -> e.kind().equals(FactionLedger.JOIN) && bodies[1].equals(e.actor())).map(WorldEvent::id).toList();
                    var toldClaim = f.runtime.wire().inbox(ids[1], "faction.claim").getFirst().message();
                    var toldJoin = f.runtime.wire().inbox(ids[0], "faction.joined").getFirst().message();
                    context.assertTrue(claim.size() == 1 && spent.size() == 1 && toldClaim.eventIds().stream().sorted().toList().equals(decision),
                            "The claim told cites exactly the Record's claim and its coins: " + toldClaim.eventIds() + " " + decision);
                    context.assertTrue(join.size() == 1 && toldJoin.eventIds().equals(join), "The join told cites the Record's join: " + toldJoin.eventIds() + " " + join);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The stop barrier stops the tell, never the events: a claim written just before the stop keeps its Record event and
     * its tell is dropped; one made as the stop begins (after its barrier) is still written through the ordered writer,
     * which the stop waits on, and nothing is told of it. After a restart the Record holds both claims, so an audit never
     * finds a claim with no event; nothing was told of either.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionTellStop", tickLimit = 800)
    public void aClaimMadeAsTheRuntimeStopsKeepsItsEventAndIsNeverTold(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 700);
        var server = context.getWorld().getServer();
        boolean[] asked = new boolean[2];
        Object[] sent = new Object[2];
        int[][] late = new int[1][];
        String[] chunks = {ClaimLedger.chunk("minecraft:overworld", 77, 77), ClaimLedger.chunk("minecraft:overworld", 78, 77)};
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    late[0] = f.runtime.lateAfterStopForTest();
                    // Made as the stop begins: after its barrier, before anything it waits on.
                    f.runtime.onStopBeginForTest(() -> f.runtime.recordAndTell(List.of(claim(server, chunks[1])),
                            eventIds -> { asked[1] = true; return null; }, d -> sent[1] = d == null ? "none" : d));
                    // Written just before the stop; its tell would come back after it.
                    f.runtime.recordAndTell(List.of(claim(server, chunks[0])), eventIds -> { asked[0] = true; return null; }, d -> sent[0] = d == null ? "none" : d);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                    var now = f.runtime.lateAfterStopForTest();
                    context.assertTrue(!asked[0] && !asked[1], "Nothing told of either: " + asked[0] + ", " + asked[1]);
                    context.assertTrue(now[1] > late[0][1], "The tell of the claim before the stop dropped by it: " + java.util.Arrays.toString(now));
                    context.assertTrue(now[2] > late[0][2] && now[0] == late[0][0], "The claim as it stops: told nothing, its write not refused: " + java.util.Arrays.toString(now));
                    context.assertTrue("none".equals(sent[1]), "Its caller told nothing was sent: " + sent[1]);
                    f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer);
                    f.runtime.start();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.isReady(), "Restarted");
                    record[0] = f.runtime.worldEventsForTest(Set.of(ClaimLedger.CLAIM));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var claimed = record[0].join().stream().map(WorldEvent::place).toList();
                    context.assertTrue(claimed.contains(chunks[0]) && claimed.contains(chunks[1]), "Both claims are the Record's: " + claimed);
                    context.assertTrue("oak".equals(f.runtime.ledgers().claims().get(chunks[1])), "The claim made as it stopped holds: " + f.runtime.ledgers().claims());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static WorldEvent claim(net.minecraft.server.MinecraftServer server, String chunk) {
        return WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM, null, "oak", chunk, Map.of());
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
