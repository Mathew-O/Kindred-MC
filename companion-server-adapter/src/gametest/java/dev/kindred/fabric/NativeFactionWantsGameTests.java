package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.guide.Need;
import dev.kindred.core.guide.NeedsAssessment;
import dev.kindred.core.guide.Objective;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.survival.StrongholdRunPlanner;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * In plain terms: in a faction world an AI player buys what its run lacks (M-06i). Its plans say what it is short of
 * (the run's provisions: four flint for a flint and steel, say), and with coins it earned to spare it buys them from the
 * market at the market's price, by its own choice, through the same purchase a person's command makes. It buys only
 * the count it lacks, and once bought, not again. With nothing lacking it buys nothing, and with coins below what it
 * keeps back for land it buys nothing either. Before, its view of the faction world held no wants, so it only ever sold.
 *
 * Technical: {@link PlayerRuntime#factionView} takes its wants from the campaign's provisions
 * ({@code NeedsAssessment} chunks), each need that one item satisfies, quoted by the market for its whole count; the
 * most it would pay is that quote. The provisions are staged ({@link PlayerRuntime#provisionsForTest}), as its goal's
 * assessment would leave them; Bram is a member, not an officer, so no claim or invitation comes first. His coins
 * are the starting coins every account gets and what the market's own sale of his spare cobblestone paid him, by his
 * own choice; nothing is handed out.
 */
public final class NativeFactionWantsGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    /** Fifty starting coins: below what a player keeps back for its next claim (the claim's cost, 100, and more if cautious). */
    private static final FactionSettings POOR = FactionSettings.parse(Map.of("border", "60000000", "startCoins", "50"));
    private static final StrongholdRunPlanner.Provisions FLINT = new StrongholdRunPlanner.Provisions(
            List.of(new NeedsAssessment.Chunk(Need.FLINT, NeedsAssessment.Kind.GATHER, 4, Objective.NETHER_PORTAL, "a flint and steel")),
            Map.of(), Set.of(), Map.of());

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionWants", tickLimit = 1600)
    public void anAiPlayerBuysWhatItsRunLacksAndNothingWhenItLacksNothing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        UUID[] ids = new UUID[2], bodies = new UUID[2];
        long[] coins = new long[1];
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
                    for (UUID id : ids) context.assertTrue(f.runtime.startGoal(f.source, id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    f.runtime.factionsTickForTest(); // Each account's starting coins, as for anyone first seen in a faction world.
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                })
                .createAndAdd(() -> {
                    var red = f.runtime.factions().book().of(bodies[0]).orElseThrow();
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "invite", List.of("Bram")).accepted(), "Ada invites Bram");
                    context.assertTrue(f.runtime.factionActForTest(ids[1], FactionPlay.Kind.JOIN, red.id().toString()).accepted(), "Bram joins Red");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.factions().book().of(bodies[1]).isPresent(), "Bram in Red");
                    coins[0] = f.runtime.projected().coins().of(bodies[1]);
                    // Staged: what he mined, four stacks of cobblestone; he keeps 64 and sells the rest by his own choice.
                    var inv = player(f, bodies[1]).getInventory();
                    for (int slot = 0; slot < 4; slot++) inv.setStack(slot, new net.minecraft.item.ItemStack(Items.COBBLESTONE, 64));
                    var sell = f.runtime.factionChooseForTest(ids[1]);
                    context.assertTrue(sell != null && sell.kind() == FactionPlay.Kind.SELL, "Bram chooses to sell his spare: " + sell);
                })
                .createAndAdd(() -> {
                    context.assertTrue(player(f, bodies[1]).getInventory().count(Items.COBBLESTONE) == 64, "Sold, 64 kept");
                    context.assertTrue(f.runtime.projected().coins().of(bodies[1]) > coins[0], "The sale paid him: " + f.runtime.projected().coins().of(bodies[1]));
                })
                // A trade being saved holds every faction choice of his: the sale's, before he is asked again.
                .createAndAdd(() -> context.assertTrue(!f.runtime.market().holds().held(bodies[1]), "The sale saved"))
                .createAndAdd(() -> {
                    coins[0] = f.runtime.projected().coins().of(bodies[1]);
                    // The control: nothing lacking, nothing bought.
                    var none = f.runtime.factionChooseForTest(ids[1]);
                    context.assertTrue(none == null || none.kind() != FactionPlay.Kind.BUY, "Lacking nothing, no purchase: " + none);
                    f.runtime.provisionsForTest(ids[1], FLINT);
                    var choice = f.runtime.factionChooseForTest(ids[1]);
                    context.assertTrue(choice != null && choice.kind() == FactionPlay.Kind.BUY && choice.target().equals("minecraft:flint") && choice.amount() == 4,
                            "Short of four flint, Bram chooses to buy them: " + choice);
                })
                .createAndAdd(() -> {
                    // His own turn buys them, through the market's own purchase.
                    var inv = player(f, bodies[1]).getInventory();
                    long now = f.runtime.projected().coins().of(bodies[1]);
                    context.assertTrue(inv.count(Items.FLINT) == 4, "Bram holds the four flint he bought: " + inv.count(Items.FLINT));
                    context.assertTrue(now < coins[0], "And paid for them: " + now + " of " + coins[0]);
                })
                // The purchase saved, so the choice below is his own, not held.
                .createAndAdd(() -> context.assertTrue(!f.runtime.market().holds().held(bodies[1]), "The purchase saved"))
                .createAndAdd(() -> {
                    // Bought, not bought again: the four it holds are the count it lacked, though its goal has not looked again.
                    var again = f.runtime.factionChooseForTest(ids[1]);
                    context.assertTrue(again == null || again.kind() != FactionPlay.Kind.BUY, "Not bought twice: " + again);
                    record[0] = f.runtime.worldEventsForTest(Set.of(MarketLedger.BUY));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var events = record[0].join();
                    context.assertTrue(events.stream().anyMatch(e -> e.kind().equals(MarketLedger.BUY) && bodies[1].equals(e.actor())), "Bram's purchase is the Record's: " + events);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Short of four flint with fifty coins, below what he keeps back for land: Bram buys nothing. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionWantsPoor", tickLimit = 1200)
    public void anAiPlayerShortOfCoinsBuysNothingItLacks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        UUID[] ids = new UUID[2], bodies = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(POOR);
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
                    for (UUID id : ids) context.assertTrue(f.runtime.startGoal(f.source, id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    f.runtime.factionsTickForTest();
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                })
                .createAndAdd(() -> {
                    var red = f.runtime.factions().book().of(bodies[0]).orElseThrow();
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "invite", List.of("Bram")).accepted(), "Ada invites Bram");
                    context.assertTrue(f.runtime.factionActForTest(ids[1], FactionPlay.Kind.JOIN, red.id().toString()).accepted(), "Bram joins Red");
                })
                .createAndAdd(() -> {
                    long coins = f.runtime.projected().coins().of(bodies[1]);
                    context.assertTrue(coins > 0 && coins < 100 * dev.kindred.core.factions.Coins.CENT, "Fifty coins, below the reserve: " + coins);
                    f.runtime.provisionsForTest(ids[1], FLINT);
                    var choice = f.runtime.factionChooseForTest(ids[1]);
                    context.assertTrue(choice == null || choice.kind() != FactionPlay.Kind.BUY, "Short of coins, no purchase: " + choice);
                })
                .createAndAdd(() -> {
                    context.assertTrue(player(f, bodies[1]).getInventory().count(Items.FLINT) == 0, "No flint bought");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
