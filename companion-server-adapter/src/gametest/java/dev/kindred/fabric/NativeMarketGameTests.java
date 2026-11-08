package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.market.Pricing;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.SqliteCompanionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: GameTests of the market: a person sells the whole of their iron and cobblestone at once, the items and
 * the coins moving together and the price falling for the next seller; a person who leaves in the middle of a sale
 * keeps their items and their coins; and an item the market cannot value (a potion without a price, a full shulker box,
 * a command block) is refused with its reason and nothing moves.
 *
 * Technical: M-06g's native fixtures, through the market's own counter ({@link MarketDesk}); the commands are thin
 * wrappers on it. The Record is read back from the file after the stop for the sale's events.
 */
public final class NativeMarketGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketSellAll", tickLimit = 600)
    public void aSellAllSettlesAtomicallyAndMovesThePrice(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] cleo = new ServerPlayerEntity[1];
        long[] before = new long[3];
        List<String> replies = new ArrayList<>();
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] read = new CompletableFuture[1];
        FixtureGround.whenDone(context, () -> { if (cleo[0] != null && server.getPlayerManager().getPlayer(cleo[0].getUuid()) != null) server.getPlayerManager().remove(cleo[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    cleo[0] = MockPlayers.survival(context, "Cleo", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.projected().coins().of(cleo[0].getUuid()) == 50_000, "500 coins to start");
                    var inv = cleo[0].getInventory();
                    inv.setStack(0, new ItemStack(Items.IRON_INGOT, 64));
                    inv.setStack(1, new ItemStack(Items.COBBLESTONE, 64));
                    inv.setStack(2, new ItemStack(Items.COBBLESTONE, 64));
                    before[0] = Pricing.sellPrice(f.runtime.market().pricing().price(f.runtime.market().book(), "minecraft:iron_ingot", System.currentTimeMillis()).orElseThrow().mid());
                    var estimate = f.runtime.market().estimate(cleo[0], allSlots(cleo[0]));
                    before[1] = estimate.cents();
                    context.assertTrue(estimate.quotes().size() == 2 && estimate.refused().isEmpty(), "Two kinds quoted: " + estimate.quotes().keySet());
                    f.runtime.market().sell(cleo[0], allSlots(cleo[0]), "minecraft:overworld", replies::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(replies.size() == 1 && replies.getFirst().startsWith("Sold for "), "Settled: " + replies);
                    context.assertTrue(f.runtime.recordWritten(), "Written");
                    var inv = cleo[0].getInventory();
                    context.assertTrue(inv.count(Items.IRON_INGOT) == 0 && inv.count(Items.COBBLESTONE) == 0, "The items taken");
                    long coins = f.runtime.projected().coins().of(cleo[0].getUuid());
                    context.assertTrue(coins == 50_000 + before[1], "Paid exactly the estimate: " + coins + " against " + (50_000 + before[1]));
                    long after = Pricing.sellPrice(f.runtime.market().pricing().price(f.runtime.market().book(), "minecraft:iron_ingot", System.currentTimeMillis()).orElseThrow().mid());
                    context.assertTrue(after < before[0], "The next ingot pays less: " + after + " < " + before[0]);
                    before[2] = coins;
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> read[0] = SqliteCompanionStore.open(f.directory.resolve("companions.sqlite"), 128).thenCompose(store ->
                        store.allWorldEvents(Set.of(MarketDesk.INTENT, MarketDesk.CANCEL, MarketLedger.SALE, "coins")).thenCompose(events -> store.closeAsync().thenApply(i -> events))))
                .createAndAdd(() -> {
                    context.assertTrue(read[0].isDone() && !read[0].isCompletedExceptionally(), "Record read");
                    var kinds = read[0].join().stream().map(WorldEvent::kind).toList();
                    context.assertTrue(kinds.equals(List.of("coins", MarketDesk.INTENT, MarketLedger.SALE, "coins", MarketLedger.SALE, "coins")),
                            "The start, the intent, then each kind's sale with its coins: " + kinds);
                    var sales = read[0].join().stream().filter(e -> e.kind().equals(MarketLedger.SALE)).toList();
                    context.assertTrue(sales.size() == 2 && sales.stream().allMatch(e -> !e.get("intent", "").isEmpty()), "Two sales, each carrying its intent");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketDisconnect", tickLimit = 600)
    public void aDisconnectMidSaleLeavesBothSidesWhole(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] cleo = new ServerPlayerEntity[1];
        List<String> replies = new ArrayList<>();
        FixtureGround.whenDone(context, () -> { if (cleo[0] != null && server.getPlayerManager().getPlayer(cleo[0].getUuid()) != null) server.getPlayerManager().remove(cleo[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    cleo[0] = MockPlayers.survival(context, "Cleo", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.projected().coins().of(cleo[0].getUuid()) == 50_000, "500 coins to start");
                    cleo[0].getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 64));
                    cleo[0].getInventory().selectedSlot = 0;
                    f.runtime.market().sell(cleo[0], List.of(0), "minecraft:overworld", replies::add);
                    // Gone before the intent is written: the sale must not happen, and nothing may move.
                    server.getPlayerManager().remove(cleo[0]);
                })
                .createAndAdd(() -> {
                    context.assertTrue(replies.size() == 1 && replies.getFirst().startsWith("Sale cancelled (the seller left)"), "Cancelled: " + replies);
                    context.assertTrue(f.runtime.recordWritten(), "Written");
                    context.assertTrue(cleo[0].getInventory().count(Items.IRON_INGOT) == 64, "The 64 ingots still the seller's");
                    context.assertTrue(f.runtime.projected().coins().of(cleo[0].getUuid()) == 50_000, "No coins paid");
                    context.assertTrue(f.runtime.market().book().states().isEmpty(), "The market untouched");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketPending", tickLimit = 400)
    public void anItemTheMarketCannotValueIsRefusedWithItsReason(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] cleo = new ServerPlayerEntity[1];
        List<String> replies = new ArrayList<>();
        FixtureGround.whenDone(context, () -> { if (cleo[0] != null && server.getPlayerManager().getPlayer(cleo[0].getUuid()) != null) server.getPlayerManager().remove(cleo[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    cleo[0] = MockPlayers.survival(context, "Cleo", new BlockPos(2, 2, 2));
                    var potion = new ItemStack(Items.POTION);
                    potion.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(Potions.SWIFTNESS));
                    var shulker = new ItemStack(Items.SHULKER_BOX);
                    shulker.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(new ItemStack(Items.DIAMOND, 3))));
                    var inv = cleo[0].getInventory();
                    inv.setStack(0, potion);
                    inv.setStack(1, shulker);
                    inv.setStack(2, new ItemStack(Items.COMMAND_BLOCK));
                    var named = new ItemStack(Items.DIAMOND);
                    named.set(DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("Priceless"));
                    inv.setStack(3, named);
                    inv.setStack(4, new ItemStack(Items.DIAMOND));
                    var estimate = f.runtime.market().estimate(cleo[0], List.of(0, 1, 2, 3, 4));
                    context.assertTrue(estimate.refused().get(0).equals("Valuation pending: minecraft:potion#minecraft:swiftness has no price yet"), "The potion: " + estimate.refused().get(0));
                    context.assertTrue(estimate.refused().get(1).equals("A container with things in it: empty it first"), "The shulker: " + estimate.refused().get(1));
                    context.assertTrue(estimate.refused().get(2).equals("Never paid for: an admin-only item"), "The command block: " + estimate.refused().get(2));
                    context.assertTrue(estimate.quotes().keySet().equals(Set.of("minecraft:diamond")) && estimate.quotes().get("minecraft:diamond").count() == 2,
                            "A renamed diamond is a diamond: " + estimate.quotes().keySet());
                    f.runtime.market().sell(cleo[0], List.of(0), "minecraft:overworld", replies::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(replies.equals(List.of("Refused: Valuation pending: minecraft:potion#minecraft:swiftness has no price yet")), "Told why: " + replies);
                    context.assertTrue(cleo[0].getInventory().getStack(0).isOf(Items.POTION), "The potion kept");
                    context.assertTrue(f.runtime.projected().coins().of(cleo[0].getUuid()) == 0, "Nothing paid (no start coins asked for in this fixture)");
                })
                .completeIfSuccessful();
    }

    private static List<Integer> allSlots(ServerPlayerEntity player) {
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < player.getInventory().main.size(); i++) slots.add(i);
        return slots;
    }
}
