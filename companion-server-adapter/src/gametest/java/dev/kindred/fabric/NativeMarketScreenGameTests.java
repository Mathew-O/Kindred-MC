package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.market.Market;
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
 * In plain terms: GameTests of the market's chest screen (M-06g1).
 * - Ben browses what the market sells and buys one iron ingot for exactly what it asks.
 * - He sells a stack of his for exactly what the market quotes it at.
 * - A price that moved between his first click and his confirming one is refused, and nothing moves, until he clicks
 *   again.
 * - A purchase still settles, told in chat, when he closes the screen right after his confirming click.
 * - No other click in the screen (shift, number keys, drops, picking up his own stack) moves an item anywhere.
 *
 * Technical: {@link MarketScreenHandler} opened by bare {@code /shop}'s runtime call for a mock player. Clicks are the
 * server's own {@code onSlotClick}, as a client's click packet arrives. Every trade goes through {@link MarketDesk}.
 */
public final class NativeMarketScreenGameTests implements FabricGameTest {
    private static final String IRON = "minecraft:iron_ingot";

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketScreenBuy", tickLimit = 600)
    public void aBrowsedItemIsBoughtForExactlyWhatTheMarketAsks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        long[] ask = {0};
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000, "500 coins to start");
                    context.assertTrue(f.runtime.openMarketScreen(p[1].getCommandSource()) == 1, "Ben's /shop opens");
                })
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    context.assertTrue(screen.listed().size() == MarketScreenHandler.PAGE, "A full first page: " + screen.listed().size());
                    context.assertTrue(!screen.listed().contains("minecraft:diamond"), "Diamonds, never sold, not listed");
                    int at = ironOnItsPage(context, screen, p[1]);
                    screen.onSlotClick(at, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(screen.view() == MarketScreenHandler.View.CONFIRM_BUY, "Asks to confirm");
                    ask[0] = quote(f, 1);
                    screen.onSlotClick(MarketScreenHandler.ONE, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue("Buying...".equals(screen.note()), "Sent: " + screen.note());
                })
                .createAndAdd(() -> {
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 1, "One ingot handed over");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000 - ask[0], "Paid exactly the ask " + ask[0]);
                    var screen = screen(context, p[1]);
                    context.assertTrue(screen.view() == MarketScreenHandler.View.BROWSE && screen.note().startsWith("Bought 1 "), "Told: " + screen.note());
                    context.assertTrue(screen.getCursorStack().isEmpty(), "Nothing on the cursor");
                    p[1].closeHandledScreen();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketScreenSell", tickLimit = 600)
    public void aStackIsSoldForExactlyWhatTheMarketQuotes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        long[] pay = {0};
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    p[1].getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 16));
                    context.assertTrue(f.runtime.openMarketScreen(p[1].getCommandSource()) == 1, "Ben's /shop opens");
                })
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    screen.onSlotClick(MarketScreenHandler.SELL, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(screen.view() == MarketScreenHandler.View.SELL, "His stacks");
                    context.assertTrue(screen.getSlot(0).getStack().isOf(Items.IRON_INGOT) && screen.getSlot(0).getStack().getCount() == 16, "His iron shown");
                    screen.onSlotClick(0, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(screen.view() == MarketScreenHandler.View.CONFIRM_SELL, "Asks to confirm");
                    pay[0] = f.runtime.market().estimate(p[1], List.of(0)).cents();
                    context.assertTrue(pay[0] > 0, "A price for it");
                    screen.onSlotClick(MarketScreenHandler.SELL_IT, 0, SlotActionType.PICKUP, p[1]);
                })
                .createAndAdd(() -> {
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 0, "The stack gone");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000 + pay[0], "Paid exactly the quote " + pay[0]);
                    context.assertTrue(screen(context, p[1]).note().startsWith("Sold for "), "Told: " + screen(context, p[1]).note());
                    p[1].closeHandledScreen();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketScreenStale", tickLimit = 800)
    public void aPriceThatMovedIsRefusedUntilClickedAgain(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 780);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        long[] first = {0}, moved = {0};
        String[] told = {null};
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> context.assertTrue(f.runtime.openMarketScreen(p[1].getCommandSource()) == 1, "Ben's /shop opens"))
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    screen.onSlotClick(ironOnItsPage(context, screen, p[1]), 0, SlotActionType.PICKUP, p[1]);
                    first[0] = quote(f, 64);
                    // Ana sells iron meanwhile: the market's price for iron falls.
                    p[0].getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 64));
                    f.runtime.market().sell(p[0], List.of(0), "minecraft:overworld", line -> told[0] = line);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told[0] != null && told[0].startsWith("Sold for "), "Ana's sale settled: " + told[0]);
                    moved[0] = quote(f, 64);
                    context.assertTrue(moved[0] != first[0], "The price moved: " + first[0] + " to " + moved[0]);
                    var screen = screen(context, p[1]);
                    screen.onSlotClick(MarketScreenHandler.SIXTY_FOUR, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue(screen.note().startsWith("The price moved"), "Refused: " + screen.note());
                    context.assertTrue(screen.view() == MarketScreenHandler.View.CONFIRM_BUY, "Still asking");
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 0, "Nothing handed over");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000, "Nothing paid");
                    screen.onSlotClick(MarketScreenHandler.SIXTY_FOUR, 0, SlotActionType.PICKUP, p[1]);
                    context.assertTrue("Buying...".equals(screen.note()), "Bought at the new price on the second click: " + screen.note());
                })
                .createAndAdd(() -> {
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 64, "Sixty-four handed over");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000 - moved[0], "Paid the new price " + moved[0]);
                    p[1].closeHandledScreen();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketScreenClosed", tickLimit = 600)
    public void aPurchaseSettlesWhenTheScreenIsClosedRightAfterTheClick(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        long[] ask = {0};
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> context.assertTrue(f.runtime.openMarketScreen(p[1].getCommandSource()) == 1, "Ben's /shop opens"))
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    screen.onSlotClick(ironOnItsPage(context, screen, p[1]), 0, SlotActionType.PICKUP, p[1]);
                    ask[0] = quote(f, 16);
                    screen.onSlotClick(MarketScreenHandler.SIXTEEN, 0, SlotActionType.PICKUP, p[1]);
                    p[1].closeHandledScreen();
                    context.assertTrue(p[1].currentScreenHandler == p[1].playerScreenHandler, "Closed at once");
                })
                .createAndAdd(() -> {
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 16, "Sixteen handed over all the same");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000 - ask[0], "Paid exactly the ask once");
                    context.assertTrue(!f.runtime.market().holds().held(p[1].getUuid()), "His hold released");
                    context.assertTrue(p[1].currentScreenHandler == p[1].playerScreenHandler, "No screen reopened");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketScreenNoMove", tickLimit = 600)
    public void noClickInTheScreenMovesAnItem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    p[1].getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 5));
                    context.assertTrue(f.runtime.openMarketScreen(p[1].getCommandSource()) == 1, "Ben's /shop opens");
                })
                .createAndAdd(() -> {
                    var screen = screen(context, p[1]);
                    int hotbar = 54 + 27;
                    for (var view : new int[] {MarketScreenHandler.BROWSE, MarketScreenHandler.SELL}) {
                        screen.onSlotClick(view, 0, SlotActionType.PICKUP, p[1]);
                        screen.onSlotClick(0, 0, SlotActionType.QUICK_MOVE, p[1]);
                        screen.onSlotClick(0, 0, SlotActionType.SWAP, p[1]);
                        screen.onSlotClick(0, 1, SlotActionType.THROW, p[1]);
                        screen.onSlotClick(0, 2, SlotActionType.CLONE, p[1]);
                        screen.onSlotClick(0, 0, SlotActionType.PICKUP_ALL, p[1]);
                        screen.onSlotClick(hotbar, 0, SlotActionType.QUICK_MOVE, p[1]);
                        screen.onSlotClick(hotbar, 0, SlotActionType.PICKUP, p[1]);
                        context.assertTrue(screen.quickMove(p[1], 0).isEmpty() && screen.quickMove(p[1], hotbar).isEmpty(), "Shift-click moves nothing");
                        context.assertTrue(screen.getCursorStack().isEmpty(), "Nothing on the cursor");
                    }
                    context.assertTrue(screen.view() == MarketScreenHandler.View.SELL, "Nothing chosen but by a click on it");
                    context.assertTrue(p[1].getInventory().getStack(0).isOf(Items.IRON_INGOT) && p[1].getInventory().getStack(0).getCount() == 5, "His iron where it was");
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 5, "None copied out");
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 50_000, "No coins moved");
                    p[1].closeHandledScreen();
                })
                .createAndAdd(() -> {
                    var near = context.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(context.getAbsolutePos(BlockPos.ORIGIN)).expand(16), e -> true);
                    context.assertTrue(near.isEmpty(), "Nothing dropped: " + near);
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 5, "His inventory as before");
                })
                .completeIfSuccessful();
    }

    /** Pages on to the one that lists iron, by the screen's own next button; iron's slot there. */
    private static int ironOnItsPage(TestContext context, MarketScreenHandler screen, ServerPlayerEntity player) {
        for (int turns = 0; turns < 40 && !screen.listed().contains(IRON); turns++) {
            screen.onSlotClick(MarketScreenHandler.NEXT, 0, SlotActionType.PICKUP, player);
        }
        int at = screen.listed().indexOf(IRON);
        context.assertTrue(at >= 0, "Iron is on sale, on some page");
        context.assertTrue(screen.getSlot(at).getStack().isOf(Items.IRON_INGOT), "Shown as iron");
        return at;
    }

    /** What {@code count} iron ingots cost now, as the market quotes them. */
    private static long quote(NativePaidSkillsGameTests.Fixture f, int count) {
        var desk = f.runtime.market();
        return Market.quoteBuy(desk.pricing(), desk.book(), IRON, count, System.currentTimeMillis()).cents();
    }

    private static MarketScreenHandler screen(TestContext context, ServerPlayerEntity player) {
        context.assertTrue(player.currentScreenHandler instanceof MarketScreenHandler, "The market screen open: " + player.currentScreenHandler);
        return (MarketScreenHandler) player.currentScreenHandler;
    }

    private static void start(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] p) {
        f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
        p[0] = MockPlayers.survival(context, "Ana", new BlockPos(2, 2, 2));
        p[1] = MockPlayers.survival(context, "Ben", new BlockPos(4, 2, 2));
        f.runtime.factionsTickForTest();
    }

    private static void cleanUp(TestContext context, ServerPlayerEntity[] p) {
        var server = context.getWorld().getServer();
        FixtureGround.whenDone(context, () -> { for (var x : p) if (x != null && server.getPlayerManager().getPlayer(x.getUuid()) != null) server.getPlayerManager().remove(x); });
    }
}
