package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.Personality;
import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * In plain terms: a player standing in an enemy's exposed border chunk it cannot capture there and then does not scout the
 * chunk it already stands in. That scout was arrived at at once, weighed again at once and chosen again, every tick: 583
 * times in 13 seconds in a faction shelter fixture on main 99063e9 (MODES's trace, M-06i1b).
 *
 * Technical: staged, not a live run. Its faction (Red) claims the chunk west of it; Blue holds only the chunk it stands
 * in, on power 0, so that chunk is exposed and on Red's border, and Blue has no other land to scout. The server's starting coins are 50
 * against a claim's 100, so the capture there is refused for its cost. No goal, so only faction play moves it. Asserted:
 * after 1,500 ticks there, no more faction decisions in its last 300 ticks than its decision interval (600 ticks) allows, and
 * its move never a scout of the chunk it stands in.
 */
public final class NativeFactionScoutHereGameTests implements FabricGameTest {
    private static final FactionSettings POOR = FactionSettings.parse(Map.of("border", "60000000", "startCoins", "50"));
    private static final String W = Territory.OVERWORLD;
    /** Watched past two decision intervals: its first decision there can come before the land it stands in is written. */
    private static final int WATCH = 1500;
    /** The span counted at the end: the hook keeps its last 64 decisions, so the count is of the latest ticks. */
    private static final int COUNTED = 300;

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "factionScoutHere", tickLimit = 2600)
    public void inAnEnemyBorderChunkItCannotTakeItDoesNotScoutWhereItStands(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2500);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[2];
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        String[] here = new String[1];
        long[] from = {-1};
        int[] before = {0};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    context.assertTrue(f.runtime.socialSetting(f.source, ada[0], null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, ada[0], dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    f.runtime.factionsWorldForTest(POOR);
                    ben[0] = MockPlayers.survival(context, "Ben", new net.minecraft.util.math.BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, ada[1], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, ben[0].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
                    f.runtime.factions().clockForTest(0);
                })
                .createAndAdd(() -> {
                    var body = server.getPlayerManager().getPlayer(ada[1]);
                    var chunk = Territory.Chunk.at(W, body.getBlockX(), body.getBlockZ());
                    here[0] = chunk.key();
                    var ledgers = f.runtime.projected();
                    String red = ledgers.factions().named("Red").orElseThrow().id().toString();
                    String blue = ledgers.factions().named("Blue").orElseThrow().id().toString();
                    // Red the chunk west; Blue only the one it stands in, on power 0: exposed, on Red's border, and no other
                    // land of Blue's to scout (a second chunk, east, was walked to and refused off this test's ground).
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, ada[1], red, chunk.offset(-1, 0).key(), Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, chunk.key(), Map.of()));
                    f.runtime.record(PowerRules.set(ben[0].getUuid(), 0, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The land written");
                    // Its first decisions there, once it has seen where it stands; then the watch begins.
                    context.assertTrue(!f.runtime.factionDecisionsForTest(ada[0]).isEmpty(), "Deciding");
                    from[0] = server.getTicks();
                    before[0] = f.runtime.factionDecisionsForTest(ada[0]).size();
                })
                .createAndAdd(() -> {
                    var move = f.runtime.factionMoveForTest(ada[0]);
                    context.assertTrue(move == null || move.kind() != FactionPlay.Kind.SCOUT || !move.target().equals(here[0]),
                            "Never a scout of the chunk it stands in: " + move);
                    context.assertTrue(server.getTicks() - from[0] >= WATCH, "Watched " + WATCH + " ticks");
                })
                .createAndAdd(() -> {
                    long now = server.getTicks();
                    long n = f.runtime.factionDecisionsForTest(ada[0]).stream().filter(t -> t > now - COUNTED && t <= now).count();
                    context.assertTrue(n <= COUNTED / FactionsPlayer.DECIDE_TICKS + 1, "Faction decisions in its last " + COUNTED + " ticks: " + n
                            + " (its interval allows " + (COUNTED / FactionsPlayer.DECIDE_TICKS + 1) + "); last " + f.runtime.factionDecisionsForTest(ada[0]));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
