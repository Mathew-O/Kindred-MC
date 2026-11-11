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
 * In plain terms: a GameTest of a faction walk out of a hollow (MOVEMENT, the narrow #120 item): an AI player in a faction
 * world stands in a hollow walled two blocks high all round, and the place its faction play sends it to is beyond the
 * wall. A walk alone finds no way out; it makes one, as a player does, digging a step or putting a block of its own, and
 * gets there, rather than asking for the walk again and again and setting the place aside.
 *
 * Technical: RUNNER 2's trace of Factions seed 109 (`factions-trace109`): spawn there is a basin a walk cannot leave
 * (1,209 cells flood-filled offline with the walk's rules, rises of 2 to 13 all round); 253 of the 359 faction legs refused
 * "No walkable route" stopped at exactly 1,184 nodes, the basin exhausted. Staged: the ground, and a wall and floor of bedrock under open sky; the two
 * factions and their land as the no-way fixture sets them ({@code NativeFactionPlayGameTests}), so that the player's
 * choice is the scout of the border chunk east; a stone pickaxe and a stack of cobblestone in hand.
 */
public final class NativeFactionBasinGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    /**
     * Walled two high in bedrock, the floor too, the place it goes beyond the wall: a way made out with blocks of its own,
     * and the place reached. Staged so that only its own blocks get it over, which 109's earth and stone do not ask.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionBasin", tickLimit = 3000)
    public void aFactionWalkOutOfAHollowWalledTwoHighMakesItsWayOutAndGetsThere(TestContext context) {
        hollow(context, Blocks.BEDROCK, 32);
    }

    /**
     * Walled two high in stone, as 109's rises are earth and stone, a pickaxe and no blocks of its own: a way dug out, a
     * step cut into the wall, and the place reached.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionBasinStone", tickLimit = 3000)
    public void aFactionWalkOutOfAHollowWalledTwoHighInStoneDigsItsWayOutAndGetsThere(TestContext context) {
        hollow(context, Blocks.STONE, 0);
    }

    private static void hollow(TestContext context, net.minecraft.block.Block wall, int cobblestone) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        String[] keys = new String[2];
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos feet = player.getBlockPos();
                    var here = Territory.Chunk.at(W, feet.getX(), feet.getZ());
                    keys[0] = here.key();
                    keys[1] = here.offset(1, 0).key();
                    // Ground from just west of it to the far side of the chunk east, put back as the test ends.
                    int east = (here.x() + 2) * 16 + 4;
                    BlockPos low = new BlockPos(feet.getX() - 4, feet.getY() - 1, feet.getZ() - 4), high = new BlockPos(east, feet.getY() + 3, feet.getZ() + 4);
                    FixtureGround.keep(context, low, high);
                    for (int x = low.getX(); x <= high.getX(); x++) for (int z = low.getZ(); z <= high.getZ(); z++) {
                        world.setBlockState(new BlockPos(x, feet.getY() - 1, z), Blocks.STONE.getDefaultState());
                        for (int y = feet.getY(); y <= feet.getY() + 3; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // The hollow, under open sky: a ring two high, two blocks out from its feet all round, of the wall's block,
                    // and a floor of bedrock in it, so that it is not dug down out of (in a first staging with no sky its
                    // survival goal's climb for the surface dug through the stone, 2 of 3 before any change).
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        world.setBlockState(feet.add(dx, -1, dz), Blocks.BEDROCK.getDefaultState());
                        if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
                        for (int dy = 0; dy <= 1; dy++) world.setBlockState(feet.add(dx, dy, dz), wall.getDefaultState());
                    }
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    if (cobblestone > 0) player.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, cobblestone));
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
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, player.getUuid(), red, keys[0], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, keys[1], Map.of()));
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, here.offset(2, 0).key(), Map.of()));
                    f.runtime.record(PowerRules.set(ben[0].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var at = Territory.Chunk.at(W, player.getBlockX(), player.getBlockZ()).key();
                    context.assertTrue(at.equals(keys[1]), "Out of the hollow and in the border chunk east: at "
                            + player.getBlockPos().toShortString() + " in " + at + "; refused " + f.runtime.factionRefusalsForTest(f.id, keys[1])
                            + " times; move " + f.runtime.factionMoveForTest(f.id) + "; " + f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id).work());
                })
                .completeIfSuccessful();
    }
}
