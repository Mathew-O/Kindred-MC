package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: GameTests of the auction house: Ana lists three diamonds and Ben's bid wins at the close, Ana paid
 * less the 3% fee and Ben handed the diamonds; a listing and its bid survive a restart and close after it; and a buy
 * order Ben posted is filled at once by a listing Ana makes later.
 *
 * Technical: M-06h's native fixtures, through the auction house's own counter; the commands are thin wrappers on it. The
 * fixture's runtime has no AI body to tick it, so the house's once-a-second step is run by the test, and its clock moved
 * past the listing's close.
 */
public final class NativeAuctionGameTests implements FabricGameTest {
    private static final long DAY = 24L * 3_600_000;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionClose", tickLimit = 600)
    public void aBidWinsAtTheCloseAndTheItemIsDelivered(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        List<String> told = new ArrayList<>();
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.projected().coins().of(p[0].getUuid()) == 50_000 && f.runtime.projected().coins().of(p[1].getUuid()) == 50_000, "500 each");
                    p[0].getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
                    p[0].getInventory().selectedSlot = 0;
                    f.runtime.auctions().list(p[0], 5_000, 0, 24, "minecraft:overworld", told::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1 && told.getFirst().startsWith("Listed"), "Listed: " + told);
                    context.assertTrue(p[0].getInventory().count(Items.DIAMOND) == 0, "The diamonds in escrow");
                    context.assertTrue(f.runtime.projected().coins().of(p[0].getUuid()) == 47_500, "25 coins' fee");
                    String id = f.runtime.auctions().book().listings().keySet().iterator().next();
                    context.assertTrue(f.runtime.auctions().bid(p[1], id, 6_000, "minecraft:overworld", told::add) == null, "Ben bids 60");
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 2 && f.runtime.projected().coins().of(p[1].getUuid()) == 44_000, "Ben's 60 held: " + told);
                    f.runtime.auctions().clockForTest(DAY + 60_000);
                    f.runtime.auctions().tick();
                })
                .createAndAdd(() -> f.runtime.auctions().tick())
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.auctions().book().listings().isEmpty(), "Closed");
                    context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 3, "Ben handed the three diamonds: " + p[1].getInventory().count(Items.DIAMOND));
                    context.assertTrue(f.runtime.projected().coins().of(p[0].getUuid()) == 47_500 + 6_000 - 180, "Ana paid 60 less 3%");
                    context.assertTrue(f.runtime.auctions().book().owed().isEmpty() && f.runtime.recordWritten(), "Nothing still owed");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionRestart", tickLimit = 900)
    public void aRestartMidAuctionLosesNothing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 880);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        List<String> told = new ArrayList<>();
        String[] id = new String[1];
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    p[0].getInventory().setStack(0, new ItemStack(Items.EMERALD, 8));
                    p[0].getInventory().selectedSlot = 0;
                    f.runtime.auctions().list(p[0], 1_000, 0, 24, "minecraft:overworld", told::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1, "Listed: " + told);
                    id[0] = f.runtime.auctions().book().listings().keySet().iterator().next();
                    context.assertTrue(f.runtime.auctions().bid(p[1], id[0], 2_000, "minecraft:overworld", told::add) == null, "Ben bids 20");
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 2 && f.runtime.recordWritten(), "Bid written");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    var listing = f.runtime.auctions().book().listings().get(id[0]);
                    context.assertTrue(listing != null && p[1].getUuid().equals(listing.bidder()) && listing.bid() == 2_000, "The listing and its bid after the restart: " + listing);
                    context.assertTrue(f.runtime.projected().coins().of(p[1].getUuid()) == 48_000, "Ben's 20 still held");
                    f.runtime.auctions().clockForTest(DAY + 60_000);
                    f.runtime.auctions().tick();
                })
                .createAndAdd(() -> f.runtime.auctions().tick())
                .createAndAdd(() -> {
                    context.assertTrue(p[1].getInventory().count(Items.EMERALD) == 8, "The emeralds delivered after the restart");
                    context.assertTrue(f.runtime.projected().coins().of(p[0].getUuid()) == 47_500 + 2_000 - 60, "Ana paid");
                    f.runtime.stop();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionOrder", tickLimit = 600)
    public void aBuyOrderIsFilledByALaterListing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        List<String> told = new ArrayList<>();
        cleanUp(context, p);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> context.assertTrue(f.runtime.auctions().order(p[1], "minecraft:iron_ingot", 16, 500, "minecraft:overworld", told::add) == null, "Ben orders 16 iron at 5"))
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1 && f.runtime.projected().coins().of(p[1].getUuid()) == 42_000, "80 held: " + told);
                    p[0].getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 16));
                    p[0].getInventory().selectedSlot = 0;
                    f.runtime.auctions().list(p[0], 5_000, 0, 24, "minecraft:overworld", told::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 2 && told.get(1).startsWith("Sold at once to a buy order for 80.00"), "Filled at once: " + told);
                    f.runtime.auctions().tick();
                })
                .createAndAdd(() -> {
                    context.assertTrue(p[1].getInventory().count(Items.IRON_INGOT) == 16, "Ben handed the sixteen ingots");
                    context.assertTrue(f.runtime.projected().coins().of(p[0].getUuid()) == 47_500 + 8_000 - 240, "Ana paid the order's 80 less 3%");
                    context.assertTrue(f.runtime.auctions().book().orders().isEmpty(), "The order filled");
                    context.assertTrue(f.runtime.projected().market().states().containsKey("minecraft:iron_ingot"), "A counted trade signalled the market");
                })
                .completeIfSuccessful();
    }

    /**
     * The runtime stopped mid-handover: Ben's win closed, its delivery begun (the {@code auction.delivering} write asked
     * for) and the runtime stopped in the same tick. The write's callback comes back after the stop and is dropped:
     * nothing is handed over and no write is tried after the stop ("Stop revokes synchronously and invalidates late
     * callbacks"). Begun and not delivered, the delivery is the Record's to report at the next start. In #137's CI run
     * 42826937829 an auction callback wrote {@code auction.delivered} after its runtime stopped, refused by the closing store.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionStopMidHandover", tickLimit = 900)
    public void aStopMidHandoverHandsNothingOverAndWritesNothingAfterIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 880);
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        List<String> told = new ArrayList<>();
        var server = context.getWorld().getServer();
        boolean[] stopped = new boolean[1];
        int[] waited = new int[2];
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<List<dev.kindred.core.record.WorldEvent>>[] audit = new java.util.concurrent.CompletableFuture[1];
        cleanUp(context, p);
        // The stop right after the delivery's begin is asked for (by the runtime's own step or the test's), before its
        // write comes back.
        f.runtime.auctions().afterBeginForTest(() -> {
            if (stopped[0]) return;
            stopped[0] = true;
            f.runtime.stop();
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    p[0].getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
                    p[0].getInventory().selectedSlot = 0;
                    f.runtime.auctions().list(p[0], 5_000, 0, 24, "minecraft:overworld", told::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1 && told.getFirst().startsWith("Listed"), "Listed: " + told);
                    String id = f.runtime.auctions().book().listings().keySet().iterator().next();
                    context.assertTrue(f.runtime.auctions().bid(p[1], id, 6_000, "minecraft:overworld", told::add) == null, "Ben bids 60");
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 2, "Ben's bid written: " + told);
                    f.runtime.auctions().clockForTest(DAY + 60_000);
                    f.runtime.auctions().tick(); // The close; the delivery follows at a later step of the house.
                })
                .createAndAdd(() -> context.assertTrue(stopped[0], "Stopped as the delivery was begun"))
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "The late callback's time to come back"))
                .createAndAdd(() -> {
                    int[] late = f.runtime.lateAfterStopForTest();
                    context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 0, "Nothing handed over after the stop: " + p[1].getInventory().count(Items.DIAMOND));
                    context.assertTrue(late[0] == 0, "No write tried after the stop: " + late[0]);
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                })
                // Conservation across a restart: the diamonds in exactly one place. Begun and not delivered, the delivery is
                // the Record's, reported for an operator, and never begun again: not with Ben, not back with Ana, not owed.
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    audit[0] = f.runtime.worldEventsForTest(java.util.Set.of(dev.kindred.core.market.Auctions.DELIVERING, dev.kindred.core.market.Auctions.DELIVERED, "market.uncertain"));
                })
                .createAndAdd(() -> context.assertTrue(++waited[1] >= 60, "The restarted house's steps"))
                .createAndAdd(() -> {
                    context.assertTrue(audit[0].isDone(), "The Record read");
                    var events = audit[0].join();
                    long begun = events.stream().filter(e -> e.kind().equals(dev.kindred.core.market.Auctions.DELIVERING)).count();
                    long delivered = events.stream().filter(e -> e.kind().equals(dev.kindred.core.market.Auctions.DELIVERED)).count();
                    context.assertTrue(begun == 1 && delivered == 0, "Begun once, never delivered: " + events);
                    context.assertTrue(f.runtime.auctions().book().owed().isEmpty(), "Not owed again: " + f.runtime.auctions().book().owed());
                    context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 0 && p[0].getInventory().count(Items.DIAMOND) == 0,
                            "In one place only, the begun delivery: Ben " + p[1].getInventory().count(Items.DIAMOND) + ", Ana " + p[0].getInventory().count(Items.DIAMOND));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A crash after the hand-over: the delivery's begin written and the diamonds in Ben's file, the receipt not yet
     * written. Staged by writing the begin and giving Ben the stack, then stopping. After the restart the delivery is not
     * handed over again (it is owed no longer once begun): Ben holds the three diamonds once, and the Record keeps it
     * begun and not delivered for an operator. Before {@code auction.delivering} was folded, the restart handed it over
     * a second time.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "auctionCrashAfterHandover", tickLimit = 900)
    public void aCrashAfterTheHandoverIsNeverHandedOverTwice(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 880);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        List<String> told = new ArrayList<>();
        int[] waited = new int[1];
        cleanUp(context, p);
        // The house's own deliveries held off until the staged crash: its step finds the delivery begun and stops.
        boolean[] staged = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> start(context, f, p))
                .createAndAdd(() -> {
                    p[0].getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
                    p[0].getInventory().selectedSlot = 0;
                    f.runtime.auctions().list(p[0], 5_000, 0, 24, "minecraft:overworld", told::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1, "Listed: " + told);
                    String id = f.runtime.auctions().book().listings().keySet().iterator().next();
                    context.assertTrue(f.runtime.auctions().bid(p[1], id, 6_000, "minecraft:overworld", told::add) == null, "Ben bids 60");
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 2, "Ben's bid written: " + told);
                    // The crash staged at the delivery's begin: the stack handed and Ben's file as it would be, the receipt never.
                    f.runtime.auctions().afterBeginForTest(() -> {
                        if (staged[0]) return;
                        staged[0] = true;
                        p[1].getInventory().offerOrDrop(new ItemStack(Items.DIAMOND, 3));
                        f.runtime.stop();
                    });
                    f.runtime.auctions().clockForTest(DAY + 60_000);
                    f.runtime.auctions().tick();
                })
                .createAndAdd(() -> context.assertTrue(staged[0] && f.runtime.shutdownComplete(), "Crashed after the hand-over"))
                .createAndAdd(() -> context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 3, "Ben holds them once: " + p[1].getInventory().count(Items.DIAMOND)))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS))
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 60, "The restarted house's steps"))
                .createAndAdd(() -> {
                    f.runtime.auctions().tick();
                    context.assertTrue(p[1].getInventory().count(Items.DIAMOND) == 3, "Never handed over twice: Ben holds " + p[1].getInventory().count(Items.DIAMOND));
                    context.assertTrue(f.runtime.auctions().book().owed().isEmpty(), "Not owed again: " + f.runtime.auctions().book().owed());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
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
