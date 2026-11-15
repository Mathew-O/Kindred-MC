package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a founder down a tunnel, its first claim out past the spawn exclusion, asks no faction walk from there:
 * a walk's leg ends 48 blocks on at its own height, inside the rock, where no route ends. Once it is up at the surface
 * a walk toward the claim is asked. On Factions seed 20261030 (M-06i2c) 919 faction legs were refused for no route in
 * the two arms of M-06i2a's seed comparison, and 15 of them ended where it could stand; with bases on, Kindred's refused
 * legs began below Y 50 215 times of 235, from its branch mine, and it held no land.
 *
 * Technical: staged 200 blocks from the world's spawn, inside a 250-block spawn exclusion, in a lit tunnel, sealed, four
 * blocks up a hill of stone 29 high that runs on 60 blocks past it toward its claim: 24 blocks of stone over it. Its staged
 * inventory: 16 bread. No goal of its own. Asserted: its faction's choice is a claim outside the exclusion; in 1,200
 * ticks in the tunnel no faction walk is refused; then, set at the surface above, a faction walk is asked within 600.
 */
public final class NativeFactionUndergroundGameTests implements FabricGameTest {
    private static final FactionSettings PACK = FactionSettings.parse(Map.of("border", "60000000", "spawnExclusion", "250"));
    /** In the tunnel this long, as a founder in its branch mine is between its goal's steps. */
    private static final int BELOW = 1200;
    /** At the surface, a faction walk asked within this. */
    private static final int ASKED = 600;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionUnderground", tickLimit = 3000)
    public void downATunnelItAsksNoFactionWalkIntoTheRockAndAtTheSurfaceItDoes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        var world = context.getWorld();
        var server = world.getServer();
        world.getChunk(200 >> 4, 8 >> 4);
        int ground = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 200, 8);
        int floor = ground + 4;
        BlockPos stand = new BlockPos(200, floor, 8);
        long[] start = {-1};
        int[] walks = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, new BlockPos(176, ground - 1, 0), new BlockPos(262, ground + 32, 16));
                    // A hill of stone on the flat ground, 29 high, running on 60 blocks east, toward its claim: the tunnel inside it.
                    for (int x = 178; x <= 260; x++) for (int z = 2; z <= 14; z++) for (int y = ground; y <= ground + 28; y++) {
                        world.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    // The tunnel, two high, running north and south, sealed at its ends, lit so that nothing spawns in it.
                    for (int z = 4; z <= 12; z++) for (int y = floor; y <= floor + 1; y++) world.setBlockState(new BlockPos(200, y, z), Blocks.AIR.getDefaultState());
                    for (int z = 4; z <= 12; z += 4) world.setBlockState(new BlockPos(200, floor, z), Blocks.TORCH.getDefaultState());
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.getInventory().clear();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 16));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    context.assertTrue(FactionsPlayer.deepBelow(player), "Deep below the surface");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
                    f.runtime.factionsWorldForTest(PACK);
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, player.getUuid(), "create", List.of("Red", "RED")).accepted(), "Mason founds Red");
                    f.runtime.factions().clockForTest(0);
                    var choice = f.runtime.factionChooseForTest(f.id);
                    context.assertTrue(choice != null && choice.kind() == FactionPlay.Kind.CLAIM, "A first claim chosen: " + choice);
                    context.assertTrue(Territory.Chunk.of(choice.target()).nearestToSpawn() >= PACK.spawnExclusion(),
                            "Its first claim outside the spawn exclusion: " + choice);
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    context.assertTrue(server.getTicks() - start[0] >= BELOW, BELOW + " ticks in the tunnel");
                    int[] blocked = f.runtime.factionBlockedForTest(f.id);
                    if (walks[0] == -1) KindredMod.LOGGER.info("Underground fixture: {} faction walks refused, {} ended, in {} ticks below; move {}, at {}",
                            blocked[0], f.runtime.factionOverWaterForTest(f.id)[1], BELOW, f.runtime.factionMoveForTest(f.id), f.player().getBlockPos().toShortString());
                    walks[0] = -2;
                    context.assertTrue(blocked[0] == 0, "No faction walk refused into the rock: " + blocked[0] + " refused, at "
                            + f.player().getBlockPos().toShortString());
                    // Up at the surface above, where it may walk on.
                    var player = f.player();
                    int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 200, 8);
                    player.refreshPositionAndAngles(200.5, top, 8.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    walks[0] = f.runtime.factionOverWaterForTest(f.id)[1];
                    start[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    // Asked: under way now, or ended (done or refused) since it was set at the surface.
                    int[] blocked = f.runtime.factionBlockedForTest(f.id);
                    boolean asked = f.runtime.factionWalkingForTest(f.id) || f.runtime.factionOverWaterForTest(f.id)[1] > walks[0];
                    context.assertTrue(server.getTicks() - start[0] <= ASKED || asked, "At the surface, a faction walk asked within " + ASKED
                            + " ticks: none; move " + f.runtime.factionMoveForTest(f.id) + ", " + blocked[0] + " refused, at " + f.player().getBlockPos().toShortString());
                    context.assertTrue(asked, "A faction walk asked at the surface");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
