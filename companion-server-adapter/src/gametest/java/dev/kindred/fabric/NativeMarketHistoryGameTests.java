package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.market.MarketLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: {@code /market history <item>} shows an item's last trades however many the World Record holds. It read
 * the first 10,000 trades from the Record's start and showed that item's last 10 of those, so past 10,000 trades it showed
 * old ones as the latest (B's audit of limited Record reads); a month on the owner's server at 8 players passes that.
 *
 * Technical: 10,020 sales of cobblestone are written to the Record, one coin each, then a last one of 7 for 42.42 coins.
 * Asserted: the history's last line is that last sale, and it shows 10 lines.
 */
public final class NativeMarketHistoryGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String ITEM = "minecraft:cobblestone";
    /** More than the 10,000 the history read. */
    private static final int TRADES = 10_020;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "marketHistory", tickLimit = 2400)
    public void theHistoryShowsTheLastTradesPastTenThousand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        var said = new ArrayList<String>();
        long[] asked = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    UUID seller = UUID.nameUUIDFromBytes("market-history-seller".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    long now = System.currentTimeMillis();
                    var batch = new ArrayList<WorldEvent>();
                    for (int i = 0; i < TRADES; i++) {
                        batch.add(WorldEvent.recorded(0, now, Territory.OVERWORLD, MarketLedger.SALE, seller, ITEM, "", Map.of("count", "1", "cents", "100")));
                        if (batch.size() == 1000) { f.runtime.record(List.copyOf(batch)); batch.clear(); }
                    }
                    batch.add(WorldEvent.recorded(0, now, Territory.OVERWORLD, MarketLedger.SALE, seller, ITEM, "", Map.of("count", "7", "cents", "4242")));
                    f.runtime.record(List.copyOf(batch));
                })
                // The writes in: the last sale read back.
                .createAndAdd(() -> {
                    if (asked[0] < 0) {
                        asked[0] = server.getTicks();
                        f.runtime.marketRequest(source(server, said), null, "history", List.of(ITEM));
                    }
                    context.assertTrue(!said.isEmpty(), "The history told");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Market history fixture: {} lines, the last {}", said.size(), said.isEmpty() ? null : said.getLast());
                    context.assertTrue(said.size() == 10, "Its last 10 trades: " + said.size() + " lines");
                    context.assertTrue(said.getLast().startsWith("sold 7 for 42.42 at "), "Its last line the last sale: " + said.getLast());
                    f.runtime.factionsWorldOffForTest();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ServerCommandSource source(net.minecraft.server.MinecraftServer server, List<String> said) {
        CommandOutput output = new CommandOutput() {
            @Override public void sendMessage(Text message) { said.add(message.getString()); }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        };
        return new ServerCommandSource(output, Vec3d.ZERO, Vec2f.ZERO, server.getOverworld(), 4, "history", Text.literal("history"), server, null);
    }
}
