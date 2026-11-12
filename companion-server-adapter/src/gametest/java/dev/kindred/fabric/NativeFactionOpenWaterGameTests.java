package dev.kindred.fabric;

import dev.kindred.core.Personality;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest of a faction walk into open sea (MOVEMENT, F-35j). An AI player in a faction world stands
 * on a small island two above a sea, and its goal's work goes on there; the places its faction play chooses are out on
 * the water. Where it has seen that water round where a walk would end, no walk is asked: the place is set aside at once.
 * Where a wall keeps the water out of its sight, its walks are asked as before, and only what it finds sets them aside.
 *
 * Technical: on Factions seed 303 (diag-factions303) the players' land is an island, and the legs refused for no way
 * (494 against 62 done) were to claims and scouts east of it, open sea from the surface at 62 down for more than 85
 * blocks. A faction walk's end over water is at the player's own height, in the air over the sea, and the walk is refused
 * ("No walkable route"), here as there. The test uses only the player's own perception ({@code WorldKnowledge}: still
 * water open to the air, in its line of sight); the walled case shows that a sea it has not seen is not known as one.
 * Staged as {@code NativeFactionBackOffGameTests} stages its factions, three chunks each: Red's land a column, Blue's
 * the column beside it east, Ada's power too low to claim more, so that its choices are scouts out over the water.
 */
public final class NativeFactionOpenWaterGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;
    /** How far the sea runs from the island's middle each way. */
    private static final int SEA = 36;
    /**
     * The most faction walks asked in its three thousand ticks once it has looked round, set from the measured counts
     * (F-35j's evidence): with no set-aside 9, 6 and 8, every one refused; with it 0, 0 and 2, those two asked in the first
     * second of its faction play, before it had seen all the water round where they would end.
     */
    private static final int BOUND = 3;

    /** It has seen the sea where its faction walks would end: each such place set aside, and few walks asked. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionOpenWaterSeen", tickLimit = 3700)
    public void aFactionWalkThatWouldEndInOpenWaterItSawIsSetAsideUnasked(TestContext context) {
        run(context, false);
    }

    /** A wall round its island hides the sea: nothing is set aside as water before its faction walks are asked, as before. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionOpenWaterHidden", tickLimit = 3700)
    public void aFactionWalkIntoWaterItHasNotSeenIsAskedAsBefore(TestContext context) {
        run(context, true);
    }

    /**
     * Its factions founded before it has looked round, the places its faction play chooses out on the water (F-35j1): a
     * place set aside as water it saw is followed by the next choice at the decision interval, not on the same tick. On
     * Factions seed 110 (main 0476ee5, RUNNER 2's F-35j pair) Echo had faction walks to ten places refused in one second:
     * each refused place, seen as water a second later, was set aside and another chosen at once, no wait ever served.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionOpenWaterPaced", tickLimit = 3700)
    public void theNextChoiceAfterAPlaceSetAsideAsWaterComesAtTheDecisionInterval(TestContext context) {
        paced(context, false);
    }

    /** The control (F-35j1): a causeway of land east through the sea it saw, to Blue's land: its faction walk still gets there. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionOpenWaterCauseway", tickLimit = 3700)
    public void aCausewayThroughTheSeaItSawIsStillWalked(TestContext context) {
        paced(context, true);
    }

    private static void paced(TestContext context, boolean causeway) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3600);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        long[] start = {-1};
        BlockPos[] isle = new BlockPos[1];
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    isle[0] = staged(context, f, false, causeway);
                    // The factions at once, before it has looked round: its first walks are asked before it saw the water.
                    factions(context, f, ben, isle[0]);
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    if (causeway) {
                        var at = Territory.Chunk.at(W, player.getBlockX(), player.getBlockZ());
                        var east = Territory.Chunk.at(W, isle[0].getX(), isle[0].getZ()).offset(1, 0);
                        context.assertTrue(at.x() == east.x(), "Along the causeway into Blue's land east: at " + player.getBlockPos().toShortString()
                                + "; move " + f.runtime.factionMoveForTest(f.id) + "; ended " + f.runtime.factionWalksEndedForTest(f.id));
                        return;
                    }
                    context.assertTrue(server.getTicks() - start[0] >= 3000, "Three thousand ticks on");
                })
                .createAndAdd(() -> {
                    if (causeway) return;
                    var ended = f.runtime.factionWalksEndedForTest(f.id);
                    var decided = f.runtime.factionDecisionsForTest(f.id);
                    KindredMod.LOGGER.info("Paced fixture: walks ended {}; choices at {}", ended, decided);
                    for (int i = 1; i < decided.size(); i++) {
                        context.assertTrue(decided.get(i) - decided.get(i - 1) >= FactionsPlayer.DECIDE_TICKS,
                                "Its next choice " + (decided.get(i) - decided.get(i - 1)) + " ticks after the last, before the decision interval: " + decided);
                    }
                })
                .completeIfSuccessful();
    }

    private static void run(TestContext context, boolean walled) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3600);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        long[] start = {-1};
        int[] early = {0};
        BlockPos[] isle = new BlockPos[1];
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    isle[0] = staged(context, f, walled, false);
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    // Its goal's work on the island until it has looked all round it once; then the factions, and their
                    // places out on the water.
                    context.assertTrue(f.runtime.sweepsForTest(f.id) >= 1, "Looked all round: " + f.runtime.sweepsForTest(f.id)
                            + " sweeps in " + (server.getTicks() - start[0]) + " ticks");
                    factions(context, f, ben, isle[0]);
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    int[] counts = f.runtime.factionOverWaterForTest(f.id);
                    if (walled) {
                        // Until its first faction walk has ended, nothing set aside as water: a way it makes through the
                        // wall may show it the sea after that, and then it is seen.
                        if (counts[0] > 0 && counts[1] == 0) early[0] = counts[0];
                        context.assertTrue(counts[1] >= 1 || early[0] > 0, "Its first faction walk asked and ended: " + counts[1]);
                        return;
                    }
                    context.assertTrue(server.getTicks() - start[0] >= 3000, "Three thousand ticks on");
                })
                .createAndAdd(() -> {
                    int[] counts = f.runtime.factionOverWaterForTest(f.id);
                    KindredMod.LOGGER.info("Open-water fixture ({}): {} places set aside as water it saw, {} faction walks ended, {} ticks",
                            walled ? "walled" : "open", counts[0], counts[1], server.getTicks() - start[0]);
                    if (walled) {
                        context.assertTrue(early[0] == 0, "Water it had not seen set aside before any walk was asked: " + early[0]);
                    } else {
                        context.assertTrue(counts[0] >= 1, "A place out on the water it saw set aside: " + counts[0]);
                        context.assertTrue(counts[1] <= BOUND, "Few faction walks asked into the sea it saw: " + counts[1] + " ended; move "
                                + f.runtime.factionMoveForTest(f.id));
                    }
                })
                .completeIfSuccessful();
    }

    /**
     * An island nine across, with two trees, its top two above a sea three deep running {@value #SEA} blocks each way (as
     * seed 303's land at Y 64 over a sea at 62), thirty up; walled,
     * a ring of stone four high round its edge, so that no line from its eyes reaches the water.
     */
    private static BlockPos island(TestContext context, BlockPos o, boolean walled, boolean causeway) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-SEA - 1, -7, -SEA - 1), o.add(SEA + 1, 8, SEA + 1));
        for (int x = -SEA; x <= SEA; x++) for (int z = -SEA; z <= SEA; z++) {
            boolean land = Math.abs(x) <= 4 && Math.abs(z) <= 4 || causeway && x > 4 && Math.abs(z) <= 1;
            world.setBlockState(o.add(x, -6, z), Blocks.STONE.getDefaultState());
            for (int y = -5; y <= -1; y++) world.setBlockState(o.add(x, y, z), (land ? Blocks.STONE : y <= -3 ? Blocks.WATER : Blocks.AIR).getDefaultState());
            for (int y = 0; y <= 7; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
            if (walled && land && (Math.abs(x) == 4 || Math.abs(z) == 4)) {
                for (int y = 0; y <= 3; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
            }
        }
        for (BlockPos trunk : List.of(o.add(2, 0, 2), o.add(-2, 0, -2))) {
            for (int y = 0; y < 4; y++) world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) world.setBlockState(trunk.add(dx, 4, dz), Blocks.OAK_LEAVES.getDefaultState());
        }
        return o;
    }

    /** The sea, the island and the player on it, playing: the island's middle. */
    private static BlockPos staged(TestContext context, NativePaidSkillsGameTests.Fixture f, boolean walled, boolean causeway) {
        var world = context.getWorld();
        var player = f.player();
        // The island's middle four blocks into its chunk from the west, so the chunk east begins eight past its edge.
        BlockPos up = player.getBlockPos().up(30);
        BlockPos o = new BlockPos((up.getX() & ~15) + 4, up.getY(), (up.getZ() & ~15) + 8);
        island(context, o, walled, causeway);
        world.setTimeOfDay(1000);
        player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
        player.setVelocity(Vec3d.ZERO);
        player.getInventory().clear();
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        context.assertTrue(f.runtime.socialSetting(f.source, f.id, null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
        context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
        // The goal a faction world gives it, set now so that it works and looks round before the factions are founded.
        context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
        return o;
    }

    /** The two factions and their land, as {@code NativeFactionBackOffGameTests} has them: every place east, on the water. */
    private static void factions(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] ben, BlockPos o) {
        var server = context.getWorld().getServer();
        var player = f.player();
        var here = Territory.Chunk.at(W, o.getX(), o.getZ());
        f.runtime.factionsWorldForTest(WIDE);
        ben[0] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
        f.runtime.factionsTickForTest();
        f.runtime.factions().clockForTest(-50 * 3_600_000L);
        context.assertTrue(f.runtime.faction(f.source, player.getUuid(), "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
        context.assertTrue(f.runtime.faction(f.source, ben[0].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
        f.runtime.factions().clockForTest(0);
        var ledgers = f.runtime.projected();
        String red = ledgers.factions().named("Red").orElseThrow().id().toString();
        String blue = ledgers.factions().named("Blue").orElseThrow().id().toString();
        for (int dz = -1; dz <= 1; dz++) {
            f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, player.getUuid(), red, here.offset(0, dz).key(), Map.of()));
            f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, here.offset(1, dz).key(), Map.of()));
        }
        f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
        // Ada's power too low to claim more: its choice is the scout of Blue's border, out over the water fifteen east.
        f.runtime.record(PowerRules.set(player.getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
    }
}
