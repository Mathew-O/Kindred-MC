package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.Ledgers;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * In plain terms: a GameTest: two AI players fight a duel, one is killed by the other, a faction's chunk is claimed and
 * another captured, Ada sells iron to the market, the server's runtime is stopped and started again, and every ledger
 * (the duel and its winner, the death and its killer, who owns each chunk, the market's stock and Ada's coins) comes back
 * exactly as it was, from the World Record alone.
 *
 * Technical: F-31's restart fixture. The ledgers before the stop are the live ones, advanced as each event was written;
 * after the restart they are rebuilt by replaying the Record at startup; both are compared, and the Record read straight
 * from the database after the final stop agrees with them, as does the nightly check run once by hand.
 */
public final class NativeWorldRecordGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "worldRecord", tickLimit = 1600)
    public void everyLedgerIsRebuiltFromTheRecordAloneAfterARestart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] players = new UUID[2];
        UUID[] match = new UUID[1];
        Ledgers[] before = new Ledgers[1];
        boolean[] dead = {false};
        List<String> sold = new java.util.ArrayList<>();
        @SuppressWarnings("unchecked") CompletableFuture<Boolean>[] check = new CompletableFuture[1];
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] read = new CompletableFuture[1];
        String home = ClaimLedger.chunk("minecraft:overworld", 40, 40), taken = ClaimLedger.chunk("minecraft:overworld", 41, 40);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) players[player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1] = status.nativeId();
                })
                .createAndAdd(() -> {
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 1, "Duel proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running"))
                .createAndAdd(() -> {
                    // Bram killed by Ada, at once: the death, its killer and the duel's end are the game's own.
                    var ada = player(f, players[0]);
                    var bram = player(f, players[1]);
                    // Struck until dead (a body just joined is briefly invulnerable), and never again once it is.
                    if (!dead[0]) {
                        bram.damage(bram.getDamageSources().playerAttack(ada), 1000f);
                        dead[0] = bram.isDead();
                    }
                    context.assertTrue(dead[0], "Bram killed");
                })
                .createAndAdd(() -> {
                    // A sale: Ada sells sixteen iron ingots to the market (M-06g), its price and her coins ledgers.
                    f.runtime.factionsWorldForTest(dev.kindred.core.factions.FactionSettings.DEFAULTS);
                    // The faction world's first tick now, before the restart: both players first seen and given their
                    // starting coins, so the restarted runtime's own ticks have nothing new to record and the ledgers
                    // compared are the Record's alone, however soon after the restart a tick comes.
                    f.runtime.factionsTickForTest();
                    var ada = player(f, players[0]);
                    ada.getInventory().setStack(8, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_INGOT, 16));
                    f.runtime.market().sell(ada, List.of(8), "minecraft:overworld", sold::add);
                    f.runtime.record(WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM,
                            players[0], "red", home, Map.of()));
                    f.runtime.record(List.of(
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM, players[1], "blue", taken, Map.of()),
                            WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CAPTURE, players[0], "red", taken,
                                    Map.of("from", "blue"))));
                })
                .createAndAdd(() -> {
                    context.assertTrue(sold.size() == 1 && sold.getFirst().startsWith("Sold for "), "Ada's sale settled: " + sold);
                    context.assertTrue(f.runtime.recordWritten(), "Written");
                    var ledgers = f.runtime.ledgers();
                    context.assertTrue(ledgers.market().state("minecraft:iron_ingot").stock() == 16, "The market holds sixteen more ingots: " + ledgers.market().states());
                    context.assertTrue(ledgers.coins().of(players[0]) > 0, "Ada paid");
                    var duel = ledgers.matches().get(match[0]);
                    context.assertTrue(duel != null && duel.match().state() == MatchRegistry.State.ENDED && players[0].equals(duel.match().winner()),
                            "The duel ended, Ada the winner: " + duel);
                    context.assertTrue(ledgers.deaths().equals(Map.of(players[1], 1)), "Bram's one death: " + ledgers.deaths());
                    context.assertTrue(ledgers.claims().equals(Map.of(home, "red", taken, "red")), "Both chunks red's: " + ledgers.claims());
                    before[0] = ledgers;
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> {
                    var after = f.runtime.ledgers();
                    context.assertTrue(after.equals(before[0]), "Every ledger the same from the Record alone: " + after + " against " + before[0]);
                    var duel = f.runtime.matches().stream().filter(m -> m.id().equals(match[0])).findFirst();
                    context.assertTrue(duel.isPresent() && players[0].equals(duel.get().winner()) && "fell".equals(duel.get().result()),
                            "The duel's result put back in the matches: " + duel);
                    check[0] = f.runtime.checkLedgers();
                })
                .createAndAdd(() -> {
                    context.assertTrue(check[0].isDone() && Boolean.TRUE.equals(check[0].join()), "The nightly check agrees, the action ledger with it");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .createAndAdd(() -> read[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                        store.allWorldEvents(Ledgers.kinds()).thenCompose(events -> store.closeAsync().thenApply(ignored -> events))))
                .createAndAdd(() -> {
                    context.assertTrue(read[0].isDone() && !read[0].isCompletedExceptionally(), "Record read");
                    var events = read[0].join();
                    context.assertTrue(Ledgers.replay(events).equals(before[0]) && before[0].agrees(events), "The Record and the ledgers agree");
                    long deaths = events.stream().filter(e -> e.kind().equals("death")).count();
                    context.assertTrue(deaths == 1, "One death recorded, not two: " + deaths);
                    var death = events.stream().filter(e -> e.kind().equals("death")).findFirst().orElseThrow();
                    context.assertTrue(players[0].toString().equals(death.get("killer", "")) && players[1].equals(death.actor()),
                            "Bram's death, Ada the killer: " + death);
                    server.setPvpEnabled(pvp);
                })
                .completeIfSuccessful();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
