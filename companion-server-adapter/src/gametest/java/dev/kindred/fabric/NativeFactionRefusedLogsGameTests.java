package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.SafeZones;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.survival.WorldFacts;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.TreeSet;

/**
 * In plain terms: in a faction world, a tree on land the player may not work (a safe zone here) is not a tree in sight
 * for its plan, as a tree it failed to reach is not. Its gather already refused those logs ("may not work here"), but
 * its plan still counted the tree, so the plan chose the gather again and again and the gather found nothing: three of
 * seed 110's ten gather lines in LOOPS's recapture (M-06i). Set aside, the plan goes on to look for trees elsewhere.
 *
 * Technical: {@link PortalRunCampaign#factsForTest} is the plan's facts where the player stands. With only the zone's
 * tree known there is no tree in them; with the zone's and one outside it known, the nearest tree is the one outside;
 * the control, the zone's tree with no zone recorded, is a tree in sight. Outside a faction world nothing changes (the
 * territory guard allows everything there).
 */
public final class NativeFactionRefusedLogsGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionRefusedLogs", tickLimit = 800)
    public void aTreeOnLandItMayNotWorkIsNotATreeInSightForItsPlan(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        var server = context.getWorld().getServer();
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos inside = o.add(20, 7, 16), outside = o.add(40, 7, 40);
        BlockPos home = o.add(16, 7, 16);
        var zone = new TreeSet<String>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, new BlockPos[] {inside, outside});
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.factionsWorldForTest(WIDE);
                    for (int x = -12; x <= 24; x += 4) for (int z = -12; z <= 24; z += 4) {
                        var at = o.add(x, 0, z);
                        zone.add(SafeZones.chunkAt(W, at.getX(), at.getZ()));
                    }
                    context.assertTrue(zone.contains(SafeZones.chunkAt(W, inside.getX(), inside.getZ()))
                            && !zone.contains(SafeZones.chunkAt(W, outside.getX(), outside.getZ())), "One tree in the zone, one out");
                    var player = f.player();
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing at home"))
                .createAndAdd(() -> {
                    // The control: a faction world with no zone yet, the near tree on wild land: a tree in sight.
                    var facts = facts(f, inside);
                    context.assertTrue(facts.distance(WorldFacts.Source.TREE).equals(java.util.OptionalInt.of(distance(f, inside))),
                            "With no zone, the near tree is in sight: " + facts.nearest());
                    f.runtime.record(SafeZones.zone("fixture", zone, null, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "The zone written"))
                .createAndAdd(() -> {
                    var only = facts(f, inside);
                    context.assertTrue(!only.knows(WorldFacts.Source.TREE),
                            "Only the zone's tree known: no tree in sight, not " + only.nearest());
                    var both = facts(f, inside, outside);
                    context.assertTrue(both.distance(WorldFacts.Source.TREE).equals(java.util.OptionalInt.of(distance(f, outside))),
                            "Both known: the tree outside the zone, " + distance(f, outside) + " blocks, not " + both.nearest());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The plan's facts of a campaign that knows only these trunks' logs, where the player stands. */
    private static WorldFacts facts(NativePaidSkillsGameTests.Fixture f, BlockPos... trunks) {
        var campaign = new PortalRunCampaign();
        for (BlockPos trunk : trunks) for (int y = 0; y < 6; y++) campaign.knowledge().remember(WorldKnowledge.Kind.LOG, trunk.up(y));
        return campaign.factsForTest(f.player());
    }

    /** The plan's distance to a trunk: to its nearest log, rounded. */
    private static int distance(NativePaidSkillsGameTests.Fixture f, BlockPos trunk) {
        BlockPos feet = f.player().getBlockPos();
        double best = Double.MAX_VALUE;
        for (int y = 0; y < 6; y++) best = Math.min(best, trunk.up(y).getSquaredDistance(feet));
        return (int) Math.round(Math.sqrt(best));
    }
}
