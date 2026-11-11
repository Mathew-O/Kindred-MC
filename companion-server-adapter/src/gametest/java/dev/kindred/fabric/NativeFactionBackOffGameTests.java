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
 * In plain terms: a GameTest of a faction walk refused while the player's own goal goes on (MOVEMENT): an AI player
 * in a faction world stands on a small island in the sky with trees on it, and its survival goal fells them, one task
 * after another; the place its faction play sends it to is off the island, where no walk goes. Each refusal of that
 * walk is counted and backs it off, as a refusal is when nothing else is running, so it is not asked again and again
 * between the goal's tasks.
 *
 * Technical: RUNNER 2's trace of Factions seed 109 on main a60f919 ({@code factions-trace109main}): Kindred, branch
 * mining for iron at Y 19, had its faction walk toward the surface refused 89 times into one chunk over the last
 * seventeen minutes, twice in one second, with no set-aside; the faction walks came between the goal's mining steps.
 * The back-off holds in the no-way fixture ({@code NativeFactionPlayGameTests}), where nothing else runs. Staged: the
 * island and its trees, the two factions and their land as the no-way fixture sets them, so that the player's choice
 * is the scout of the border chunk east.
 */
public final class NativeFactionBackOffGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    /**
     * Every faction walk that came back refused is counted by the back-off, and after three in a row, whatever the
     * places, it asks for none for a while: few of them in two thousand ticks (eight before, two places three times each
     * and again).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionBackOff", tickLimit = 3000)
    public void aFactionWalkRefusedBetweenItsGoalsTasksIsBackedOffEachTime(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        long[] start = {-1};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    staged(context, f, ben);
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    // Two thousand ticks of its goal's work on the island, its faction walk refused between.
                    context.assertTrue(server.getTicks() - start[0] >= 2000, "Two thousand ticks on");
                    int[] counts = f.runtime.factionBlockedForTest(f.id);
                    context.assertTrue(counts[0] >= 1, "Its faction walk asked and refused at least once: " + counts[0]);
                    context.assertTrue(counts[0] == counts[1], "Every refused faction walk counted by the back-off: " + counts[0]
                            + " refused, " + counts[1] + " counted; move " + f.runtime.factionMoveForTest(f.id));
                    context.assertTrue(counts[0] <= 4, "Backed off, not asked again and again: " + counts[0] + " refused in 2,000 ticks");
                })
                .completeIfSuccessful();
    }

    /**
     * Its faction walks waiting after a run of refusals, it is carried forty blocks off to another island: the wait ends
     * there, at once, and a faction walk is asked again, rather than sitting out its faction play for most of a day.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionBackOffMoved", tickLimit = 4000)
    public void itsFactionWalksWaitingItsWaitEndsOnceItIsFortyBlocksOff(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        BlockPos[] isle = new BlockPos[1];
        int[] before = {-1};
        long[] moved = {-1};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> isle[0] = staged(context, f, ben))
                .createAndAdd(() -> context.assertTrue(f.runtime.factionPausedForTest(f.id), "Its faction walks waiting after a run of refusals: "
                        + f.runtime.factionBlockedForTest(f.id)[0] + " refused"))
                .createAndAdd(() -> {
                    // Another island forty blocks west, as bare as the first: carried there, where it stands is new.
                    BlockPos o = isle[0].west(40);
                    island(context, o);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    before[0] = f.runtime.factionBlockedForTest(f.id)[0];
                    moved[0] = server.getTicks();
                    context.assertTrue(!f.runtime.factionPausedForTest(f.id), "Forty blocks off, its faction walks wait no more");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.factionBlockedForTest(f.id)[0] > before[0],
                        "A faction walk asked again from there: " + f.runtime.factionBlockedForTest(f.id)[0] + " refused, "
                                + before[0] + " before the move, " + (server.getTicks() - moved[0]) + " ticks on"))
                .completeIfSuccessful();
    }

    /** An island in the sky, nine across, with two trees on it: a drop of thirty all round. */
    private static void island(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-5, -2, -5), o.add(5, 8, 5));
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            world.setBlockState(o.add(x, -1, z), Blocks.STONE.getDefaultState());
            for (int y = 0; y <= 7; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        for (BlockPos trunk : List.of(o.add(3, 0, 3), o.add(-3, 0, -3))) {
            for (int y = 0; y < 4; y++) world.setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos leaf = trunk.add(dx, 4, dz);
                if (Math.abs(leaf.getX() - o.getX()) <= 4 && Math.abs(leaf.getZ() - o.getZ()) <= 4) {
                    world.setBlockState(leaf, Blocks.OAK_LEAVES.getDefaultState());
                }
            }
        }
    }

    /** The island, the player on it with its goal's work there, and the two factions: its island's origin. */
    private static BlockPos staged(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity[] ben) {
        var server = context.getWorld().getServer();
        var world = context.getWorld();
        var player = f.player();
        BlockPos o = player.getBlockPos().up(30).toImmutable();
        island(context, o);
        world.setTimeOfDay(1000);
        player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
        player.setVelocity(Vec3d.ZERO);
        player.getInventory().clear();
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        var here = Territory.Chunk.at(W, o.getX(), o.getZ());
        context.assertTrue(f.runtime.socialSetting(f.source, f.id, null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
        context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
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
        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, player.getUuid(), red, here.key(), Map.of()));
        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, here.offset(1, 0).key(), Map.of()));
        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, here.offset(2, 0).key(), Map.of()));
        f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
        return o;
    }
}
