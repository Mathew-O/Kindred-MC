package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.Personality;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * An AI player's own trade and defence in a faction world (M-06i's remaining native fixtures): it sells what it has
 * spare by its own choice; it buys from the market and bids at the auction house through the same calls a person's
 * commands use (what it wants is M-06i's next item, so the buy and the bid are asked of it here); and it walks home
 * when its faction is exposed and it is cautious. Every coin and item is earned or the market's, nothing handed out
 * but the starting coins every account gets.
 */
public final class NativeFactionTradeGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    /** No claim within reach of the starting coins, so a founder's first choice is not land but its trade. */
    private static final FactionSettings NO_LAND = FactionSettings.parse(Map.of("border", "60000000", "claimCost", "1000000"));
    private static final String W = Territory.OVERWORLD;

    /** Ada sells her spare cobblestone by her own choice; Bram, with his starting coins, buys some of it from the market. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionTradeMarket", tickLimit = 2400)
    public void anAiPlayerSellsItsSpareAndAnotherBuysFromTheMarket(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        UUID[] ids = new UUID[2], bodies = new UUID[2];
        long[] coins = new long[2];
        @SuppressWarnings("unchecked") CompletableFuture<List<WorldEvent>>[] record = new CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(NO_LAND);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> both(context, f, ids, bodies))
                .createAndAdd(() -> {
                    for (UUID id : ids) context.assertTrue(f.runtime.startGoal(f.source, id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    f.runtime.factionsTickForTest(); // Each account's starting coins, as for anyone first seen in a faction world.
                    context.assertTrue(f.runtime.faction(f.source, bodies[0], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, bodies[1], "create", List.of("Blue", "BLU")).accepted(), "Bram founds Blue");
                    // Staged: what she mined, four stacks of cobblestone; she keeps 64 of each kind and the rest is spare.
                    var inv = player(f, bodies[0]).getInventory();
                    for (int slot = 0; slot < 4; slot++) inv.setStack(slot, new ItemStack(Items.COBBLESTONE, 64));
                })
                .createAndAdd(() -> {
                    coins[0] = f.runtime.projected().coins().of(bodies[0]);
                    coins[1] = f.runtime.projected().coins().of(bodies[1]);
                    context.assertTrue(coins[0] > 0 && coins[1] > 0, "Starting coins: " + coins[0] + ", " + coins[1]);
                    var choice = f.runtime.factionChooseForTest(ids[0]);
                    context.assertTrue(choice != null && choice.kind() == FactionPlay.Kind.SELL && choice.target().contains("minecraft:cobblestone"),
                            "Ada chooses to sell her spare: " + choice);
                })
                .createAndAdd(() -> {
                    // Her own turn sells it, through the market's own sale.
                    var inv = player(f, bodies[0]).getInventory();
                    context.assertTrue(inv.count(Items.COBBLESTONE) == 64, "She sold the three spare stacks and kept 64: " + inv.count(Items.COBBLESTONE));
                    context.assertTrue(f.runtime.projected().coins().of(bodies[0]) > coins[0], "Paid for them: " + f.runtime.projected().coins().of(bodies[0]));
                })
                .createAndAdd(() -> {
                    var reply = f.runtime.factionActForTest(ids[1], FactionPlay.Kind.BUY, "minecraft:cobblestone", 32);
                    context.assertTrue(reply.accepted(), "Bram buys 32 cobblestone: " + reply.line());
                })
                .createAndAdd(() -> {
                    var inv = player(f, bodies[1]).getInventory();
                    long now = f.runtime.projected().coins().of(bodies[1]);
                    context.assertTrue(inv.count(Items.COBBLESTONE) == 32, "Bram holds the 32 he bought: " + inv.count(Items.COBBLESTONE));
                    context.assertTrue(now < coins[1], "And paid for them: " + now + " of " + coins[1]);
                    record[0] = f.runtime.worldEventsForTest(Set.of(MarketLedger.SALE, MarketLedger.BUY));
                })
                .createAndAdd(() -> context.assertTrue(record[0].isDone(), "The Record read"))
                .createAndAdd(() -> {
                    var events = record[0].join();
                    context.assertTrue(events.stream().anyMatch(e -> e.kind().equals(MarketLedger.SALE) && bodies[0].equals(e.actor())), "Ada's sale is the Record's: " + events);
                    context.assertTrue(events.stream().anyMatch(e -> e.kind().equals(MarketLedger.BUY) && bodies[1].equals(e.actor())), "Bram's purchase is the Record's: " + events);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Ben, a person, lists three diamonds; Bram, an AI player, bids on them: his bid is the listing's, his coins held. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionTradeBid", tickLimit = 1600)
    public void anAiPlayerBidsAtTheAuctionHouse(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        UUID[] bram = new UUID[2];
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        long[] before = new long[1];
        String[] id = new String[1];
        List<String> told = new ArrayList<>();
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.createNative(f.source, "Bram");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Bram established");
                    bram[0] = statuses.getFirst().id();
                    bram[1] = statuses.getFirst().nativeId();
                    context.assertTrue(f.runtime.startGoal(f.source, bram[0], dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    ben[0] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.projected().coins().of(ben[0].getUuid()) > 0, "Ben's starting coins");
                    ben[0].getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
                    ben[0].getInventory().selectedSlot = 0;
                    f.runtime.auctions().list(ben[0], 5_000, 0, 24, W, told::add);
                })
                .createAndAdd(() -> {
                    context.assertTrue(told.size() == 1 && told.getFirst().startsWith("Listed"), "Listed: " + told);
                    id[0] = f.runtime.auctions().book().listings().keySet().iterator().next();
                    before[0] = f.runtime.projected().coins().of(bram[1]);
                    var reply = f.runtime.factionActForTest(bram[0], FactionPlay.Kind.BID, id[0], 6_000);
                    context.assertTrue(reply.accepted(), "Bram bids 60: " + reply.line());
                })
                .createAndAdd(() -> {
                    var listing = f.runtime.auctions().book().listings().get(id[0]);
                    context.assertTrue(listing != null && bram[1].equals(listing.bidder()), "Bram's is the listing's bid: " + listing);
                    context.assertTrue(f.runtime.projected().coins().of(bram[1]) == before[0] - 6_000, "His 60 held: " + f.runtime.projected().coins().of(bram[1]));
                    server.getPlayerManager().remove(ben[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Ada's faction holds two chunks on power 1, so it is exposed; she is cautious and stands off its land: she chooses
     * home and walks there herself.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "factionTradeDefend", tickLimit = 2400)
    public void aCautiousPlayerWalksHomeWhenItsFactionIsExposed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        UUID[] ada = new UUID[2];
        BlockPos[] home = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = 0; x <= 60; x++) for (int z = 0; z <= 14; z++) {
                        world.setBlockState(o.add(x, 1, z), net.minecraft.block.Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    context.assertTrue(f.runtime.socialSetting(f.source, ada[0], null, false, Personality.Trait.CAUTION, 70) == 1, "Ada made cautious");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, ada[0], dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    context.assertTrue(f.runtime.faction(f.source, ada[1], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                })
                .createAndAdd(() -> {
                    var body = player(f, ada[1]);
                    home[0] = body.getBlockPos();
                    context.assertTrue(f.runtime.faction(f.source, ada[1], "sethome", List.of()).accepted(), "Her home where she stands");
                    var here = Territory.Chunk.at(W, body.getBlockX(), body.getBlockZ());
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    // Two chunks on power 1: more land than power holds, so exposed.
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, ada[1], red, here.key(), Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, ada[1], red, here.offset(0, -1).key(), Map.of()));
                    f.runtime.record(PowerRules.set(ada[1], 1, null, FactionSettings.DEFAULTS, context.getWorld().getServer().getTicks(), System.currentTimeMillis()));
                    // Staged: she has walked off east, out of her land, onto open ground.
                    var off = context.getAbsolutePos(new BlockPos(50, 2, 7));
                    body.requestTeleport(off.getX() + 0.5, off.getY(), off.getZ() + 0.5);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The land written");
                    var body = player(f, ada[1]);
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    var standing = Territory.Chunk.at(W, body.getBlockX(), body.getBlockZ());
                    context.assertTrue(!red.equals(f.runtime.projected().claims().get(standing.key())), "Off her land: " + standing.key());
                    var choice = f.runtime.factionChooseForTest(ada[0]);
                    context.assertTrue(choice != null && choice.kind() == FactionPlay.Kind.DEFEND, "Ada chooses home: " + choice);
                })
                .createAndAdd(() -> {
                    var body = player(f, ada[1]);
                    context.assertTrue(body.getBlockPos().getSquaredDistance(home[0]) <= 16, "She walked home: " + body.getBlockPos() + " to " + home[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void both(TestContext context, NativePaidSkillsGameTests.Fixture f, UUID[] ids, UUID[] bodies) {
        var statuses = f.runtime.nativeStatuses(Actor.console());
        context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                "Both established");
        for (var status : statuses) {
            int i = player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1;
            ids[i] = status.id();
            bodies[i] = status.nativeId();
        }
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
