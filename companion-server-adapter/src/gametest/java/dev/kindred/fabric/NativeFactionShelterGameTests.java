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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest of a faction walk at night (MOVEMENT, F-35h2). An AI player in a faction world digs in for
 * the night and seals its shelter, and the place its faction play sends it to is outside. It stays in till daylight: no
 * faction walk is asked and no way made out through its own seal while it is dug in, as its portal walk does not (F-35c1).
 *
 * Technical: on Factions seed 110 (main 0476ee5, RUNNER 2's F-35j pair) Echo, sealed in for the night, had its faction
 * walk refused from the shelter, F-35h's way made out dug the seal ("Make a way toward 93, 63, -91: dig 60, 61, -57"),
 * the goal sealed it again ("Seal the shelter at 60, 61, -57"), round and round: 52 digs, 53 refusals. The check is the
 * campaign's own `dugInForTheNight`, the one F-35c1's portal walk uses. Staged as the basin fixture stages its factions
 * (`NativeFactionBasinGameTests`): Blue's land the chunk east and the next, Ben's power 1 so that it is exposed, Ada's 1
 * so that its choice is the scout of the border chunk east; deep ground to dig in, at nightfall; a goal (a Nether
 * portal), a stone pickaxe and bread.
 */
public final class NativeFactionShelterGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    /** Dug in for the night with a faction place outside: no faction walk asked and no way made while it is in. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionShelter", tickLimit = 3200)
    public void dugInForTheNightItsFactionWalkWaitsAndNoWayIsMadeOutThroughItsSeal(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3100);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        long[] start = {-1};
        int[] dugIn = {0}, madeWays = {0}, walks = {0};
        String[] seen = {""};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos spawn = player.getBlockPos();
                    // Its middle of its chunk's width, so that the chunk east is wholly outside, beyond its shelter.
                    BlockPos feet = new BlockPos((spawn.getX() & ~15) + 8, spawn.getY(), spawn.getZ());
                    var here = Territory.Chunk.at(W, feet.getX(), feet.getZ());
                    int east = (here.x() + 2) * 16 + 4;
                    BlockPos low = new BlockPos(feet.getX() - 8, feet.getY() - 6, feet.getZ() - 6), high = new BlockPos(east, feet.getY() + 4, feet.getZ() + 6);
                    FixtureGround.keep(context, low, high);
                    for (int x = low.getX(); x <= high.getX(); x++) for (int z = low.getZ(); z <= high.getZ(); z++) {
                        for (int y = feet.getY() - 6; y < feet.getY(); y++) world.setBlockState(new BlockPos(x, y, z), (y == feet.getY() - 1 ? Blocks.DIRT : Blocks.STONE).getDefaultState());
                        for (int y = feet.getY(); y <= feet.getY() + 4; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
                    player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.BREAD, 8));
                    player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 32));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setTimeOfDay(13_000);
                    context.assertTrue(f.runtime.socialSetting(f.source, f.id, null, false, Personality.Trait.COMBATIVENESS, 70) == 1, "Ada made fierce");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
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
                    f.runtime.record(PowerRules.set(player.getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                    start[0] = server.getTicks();
                    int[] endedBefore = {f.runtime.factionOverWaterForTest(f.id)[1]};
                    context.runAtEveryTick(() -> {
                        if (!f.runtime.dugInForTheNightForTest(f.id)) {
                            endedBefore[0] = f.runtime.factionOverWaterForTest(f.id)[1];
                            return;
                        }
                        dugIn[0]++;
                        String work = String.valueOf(f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id).work());
                        if (work.contains("Make a way toward")) { madeWays[0]++; seen[0] = work; }
                        int ended = f.runtime.factionOverWaterForTest(f.id)[1];
                        if (ended > endedBefore[0]) { walks[0] += ended - endedBefore[0]; seen[0] = work; }
                        endedBefore[0] = ended;
                    });
                })
                .createAndAdd(() -> context.assertTrue(server.getTicks() - start[0] >= 2800, "Two thousand eight hundred ticks of night on"))
                .createAndAdd(() -> {
                    String state = dugIn[0] + " ticks dug in; " + madeWays[0] + " ticks of a way made out, " + walks[0] + " faction walks ended while dug in; last "
                            + seen[0] + "; move " + f.runtime.factionMoveForTest(f.id);
                    KindredMod.LOGGER.info("Shelter fixture: {}", state);
                    context.assertTrue(dugIn[0] >= 200, "Dug in for the night for a while: " + state);
                    context.assertTrue(madeWays[0] == 0 && walks[0] == 0, "No faction walk and no way made out while dug in: " + state);
                })
                .completeIfSuccessful();
    }
}
