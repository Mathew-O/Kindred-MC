package dev.kindred.fabric;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.market.Auctions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: GameTests of the auction house's chest screen: Ben clicks Ana's listing and confirms, and his bid is
 * exactly the one Cal makes with {@code /ah bid} on her other listing; shift-clicks, number keys, drops and pick-ups in
 * the screen move no item anywhere; and Ben, with a trade still being saved, is told so and bids nothing.
 *
 * Technical: M-06h's screen ({@link AuctionScreenHandler}) opened by bare {@code /ah}'s runtime call for a mock
 * player; clicks are the server's own {@code onSlotClick}, as a client's click packet arrives.
 */
public final class NativeAuctionScreenGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionScreenBid", tickLimit = 600)
    public void aConfirmedClickBidsExactlyWhatTheCommandBids(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[3];
        List<String> told = new ArrayList<>();
        String[] id = new String[2];
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> listDiamonds(f, p[0], told))
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1, "First listed: " + told);
                    listDiamonds(f, p[0], told);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 2 && f.runtime.auctions().book().listings().size() == 2, "Both listed: " + told);
                    context.assertTrue(f.runtime.openAuctionScreen(p[1].getCommandSource()) == 1, "Ben's /ah opens");
                })
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    var listings = f.runtime.auctions().book().listings().values().stream()
                            .sorted(java.util.Comparator.comparingLong(dev.kindred.core.market.AuctionBook.Listing::closesAt)
                                    .thenComparing(dev.kindred.core.market.AuctionBook.Listing::id)).toList();
                    id[0] = listings.get(0).id();
                    id[1] = listings.get(1).id();
                    context.assertTrue(screen.getSlot(0).getStack().isOf(Items.DIAMOND) && screen.getSlot(0).getStack().getCount() == 3, "The first listing shown");
                    screen.onSlotClick(0, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(screen.view() == AuctionScreenHandler.View.CONFIRM, "Asks to confirm");
                    screen.onSlotClick(AuctionScreenHandler.BID, 0, SlotActionType.PICKUP, p[1]);
                    long least = Auctions.least(f.runtime.auctions().book().listings().get(id[1]));
                    context.assertTrue(f.runtime.auctionRequest(p[2].getCommandSource(), p[2].getUuid(), "bid", List.of(id[1], Coins.show(least))) == 1, "Cal's /ah bid");
                })
                .createAndAdd(() -> {
                    var book = f.runtime.auctions().book().listings();
                    var a = book.get(id[0]);
                    var b = book.get(id[1]);
                    context.assertTrue(p[1].getUuid().equals(a.bidder()) && p[2].getUuid().equals(b.bidder()), "Ben's and Cal's bids: " + a + " " + b);
                    context.assertTrue(a.bid() == b.bid() && a.bid() == 5_000, "The same bid, the start: " + a.bid() + " " + b.bid());
                    var coins = f.runtime.projected().coins();
                    context.assertTrue(coins.of(p[1].getUuid()) == coins.of(p[2].getUuid()) && coins.of(p[1].getUuid()) == 45_000, "The same coins held");
                    context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 0 && p[1].currentScreenHandler.getCursorStack().isEmpty(), "Nothing handed over before the close");
                    context.assertTrue(f.runtime.recordWritten(), "Written");
                    p[1].closeHandledScreen();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionScreenNoMove", tickLimit = 600)
    public void noClickInTheScreenMovesAnItem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[3];
        List<String> told = new ArrayList<>();
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> listDiamonds(f, p[0], told))
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1, "Listed: " + told);
                    p[1].getInventory().setStack(0, new ItemStack(Items.DIRT, 5));
                    context.assertTrue(f.runtime.openAuctionScreen(p[1].getCommandSource()) == 1, "Ben's /ah opens");
                })
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    int hotbar = 54 + 27;
                    screen.onSlotClick(0, 0, SlotActionType.QUICK_MOVE, p[1]);
                    screen.onSlotClick(0, 0, SlotActionType.SWAP, p[1]);
                    screen.onSlotClick(0, 1, SlotActionType.THROW, p[1]);
                    screen.onSlotClick(0, 2, SlotActionType.CLONE, p[1]);
                    screen.onSlotClick(0, 0, SlotActionType.PICKUP_ALL, p[1]);
                    screen.onSlotClick(hotbar, 0, SlotActionType.QUICK_MOVE, p[1]);
                    screen.onSlotClick(hotbar, 0, SlotActionType.PICKUP, p[1]);
                    screen.onSlotClick(1, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(screen.quickMove(p[1], 0).isEmpty() && screen.quickMove(p[1], hotbar).isEmpty(), "Shift-click moves nothing");
                    context.assertTrue(screen.getCursorStack().isEmpty(), "Nothing on the cursor");
                    context.assertTrue(screen.view() == AuctionScreenHandler.View.ALL, "No listing chosen by anything but a click on it");
                    context.assertTrue(screen.getSlot(0).getStack().isOf(Items.DIAMOND) && screen.getSlot(0).getStack().getCount() == 3, "The listing still shown");
                    context.assertTrue(p[1].getInventory().getStack(0).isOf(Items.DIRT) && p[1].getInventory().getStack(0).getCount() == 5, "Ben's dirt where it was");
                    context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 0 && p[0].getInventory().count(Items.DIAMOND) == 0, "No diamond copied out");
                    context.assertTrue(f.runtime.auctions().book().listings().size() == 1, "The listing still in escrow");
                    p[1].closeHandledScreen();
                })
                .createAndAdd(() -> {
                    var near = context.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(context.getAbsolutePos(BlockPos.ORIGIN)).expand(16), e -> true);
                    context.assertTrue(near.isEmpty(), "Nothing dropped: " + near);
                    context.assertTrue(p[1].getInventory().count(Items.DIRT) == 5 && p[1].getInventory().count(Items.DIAMOND) == 0, "Ben's inventory as before");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionScreenHeld", tickLimit = 600)
    public void aHeldPlayerIsToldAndBidsNothing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[3];
        List<String> told = new ArrayList<>();
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> listDiamonds(f, p[0], told))
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1, "Listed: " + told);
                    context.assertTrue(f.runtime.openAuctionScreen(p[1].getCommandSource()) == 1, "Ben's /ah opens");
                })
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    screen.onSlotClick(0, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(f.runtime.market().holds().hold(p[1].getUuid()), "Ben held, as while a sale is saved");
                    screen.onSlotClick(AuctionScreenHandler.BID, 0, SlotActionType.PICKUP, p[1]);
                    f.runtime.market().holds().release(p[1].getUuid());
                    context.assertTrue(MarketDesk.HELD.equals(screen.note()), "Told: " + screen.note());
                    var listing = f.runtime.auctions().book().listings().values().iterator().next();
                    context.assertTrue(listing.bidder() == null, "No bid made");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000, "None of Ben's coins held");
                    p[1].closeHandledScreen();
                })
                .completeIfSuccessful();
    }

    private static AuctionScreenHandler screen(TestContext context, ServerPlayerEntity player) {
        context.assertTrue(player.currentScreenHandler instanceof AuctionScreenHandler, "The auction screen open: " + player.currentScreenHandler);
        return (AuctionScreenHandler) player.currentScreenHandler;
    }

    private static void listDiamonds(NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity seller, List<String> told) {
        seller.getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
        seller.getInventory().selectedSlot = 0;
        f.runtime.auctions().list(seller, 5_000, 0, 24, "minecraft:overworld", told::add);
    }

    private static void start(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] p) {
        f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
        p[0] = MockPlayers.survival(context, "Ana", new BlockPos(2, 2, 2));
        p[1] = MockPlayers.survival(context, "Ben", new BlockPos(4, 2, 2));
        p[2] = MockPlayers.survival(context, "Cal", new BlockPos(6, 2, 2));
        f.runtime.factionsTickForTest();
    }

    private static void cleanUp(TestContext context, ServerPlayerEntity[] p) {
        var server = context.getWorld().getServer();
        FixtureGround.whenDone(context, () -> { for (var x : p) if (x != null && server.getPlayerManager().getPlayer(x.getUuid()) != null) server.getPlayerManager().remove(x); });
    }
}
