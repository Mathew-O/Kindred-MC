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
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: GameTests of an AI player playing a faction world (M-06i): on its own, with nobody telling it, it
 * founds a faction and claims the land it stands on; and, a fierce player beside an enemy's exposed land that it can
 * see, it walks into that land and captures it by the rule.
 *
 * Technical: M-06i's native fixtures. Each runs one native body through the runtime's own decision step
 * ({@code factionCandidates}); the second drives the decision at the start ({@code factionChooseForTest}) so the
 * sighting and the choice are asserted, then leaves the walk and the capture to the body. The border setting is at its
 * widest, since test positions lie millions of blocks from spawn.
 */
public final class NativeFactionPlayGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionPlayFound", tickLimit = 2400)
    public void anAiPlayerFoundsAFactionAndClaimsWhereItStandsOnItsOwn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        UUID[] ada = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    // Its operator sets it playing; nothing more is asked of it.
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.factionsTickForTest();
                })
                .createAndAdd(() -> {
                    var faction = f.runtime.projected().factions().of(ada[1]);
                    context.assertTrue(faction.isPresent() && faction.get().name().equals("Ada_Hold"), "Ada founded Ada_Hold herself: " + faction);
                })
                .createAndAdd(() -> {
                    var ledgers = f.runtime.projected();
                    String red = ledgers.factions().of(ada[1]).orElseThrow().id().toString();
                    long held = ledgers.claims().values().stream().filter(red::equals).count();
                    context.assertTrue(held >= 1, "Ada claimed land herself");
                    context.assertTrue(ledgers.coins().of(ada[1]) <= 400 * dev.kindred.core.factions.Coins.CENT, "Paid for it: " + ledgers.coins().of(ada[1]));
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionPlayRaid", tickLimit = 2400)
    public void aFiercePlayerScoutsTheExposedBorderItSawAndCapturesItThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[2];
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        String[] keys = new String[3];
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Open ground east of the start, across the chunks Ada will walk: a floor, and air above it.
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 44; x++) for (int z = -4; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), net.minecraft.block.Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(),
                            "Ada established");
                    ada[0] = statuses.getFirst().id();
                    ada[1] = statuses.getFirst().nativeId();
                    context.assertTrue(f.runtime.socialSetting(f.source, ada[0], null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, ada[0], dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    // Both founded more than 48 hours ago, past the new-faction shield.
                    f.runtime.factionsWorldForTest(WIDE);
                    ben[0] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, ada[1], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, ben[0].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
                    f.runtime.factions().clockForTest(0);
                })
                .createAndAdd(() -> {
                    var body = server.getPlayerManager().getPlayer(ada[1]);
                    var here = Territory.Chunk.at(W, body.getBlockX(), body.getBlockZ());
                    keys[0] = here.key();
                    keys[1] = here.offset(1, 0).key();
                    keys[2] = here.offset(2, 0).key();
                    var ledgers = f.runtime.projected();
                    String red = ledgers.factions().named("Red").orElseThrow().id().toString();
                    String blue = ledgers.factions().named("Blue").orElseThrow().id().toString();
                    // Red holds the chunk Ada stands in; Blue the two east of it, on power 1: one exposed.
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, ada[1], red, keys[0], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, keys[1], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, keys[2], Map.of()));
                    f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The land written");
                    var move = f.runtime.factionChooseForTest(ada[0]);
                    context.assertTrue(move != null && move.kind() == FactionPlay.Kind.SCOUT && move.target().equals(keys[1]),
                            "Ada goes to the exposed border she sees, not the land beyond it: " + move);
                })
                .createAndAdd(() -> {
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    context.assertTrue(red.equals(f.runtime.projected().claims().get(keys[1])), "Ada walked in and captured it: "
                            + f.runtime.projected().claims().get(keys[1]));
                    context.assertTrue(!red.equals(f.runtime.projected().claims().get(keys[2])), "The chunk beyond is not taken in the same breath");
                })
                .completeIfSuccessful();
    }
}
