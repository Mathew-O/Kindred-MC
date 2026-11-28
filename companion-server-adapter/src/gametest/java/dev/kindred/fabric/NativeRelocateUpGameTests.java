package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * When the watchdog moves a stuck player elsewhere while the player's own step was a climb up, the new place is up, not
 * along at the same depth (F-25c4do1).
 */
public final class NativeRelocateUpGameTests implements FabricGameTest {

    /**
     * In plain terms: an AI player hurt and out of food deep in rock, climbing toward the surface for something to eat, is
     * sent on by the watchdog upward, the way its own climb goes, rather than sideways along the depth it is trying to leave.
     *
     * Technical: F-25c4do1, E-40 iteration 30 batch 1 (`e40a30-63b97ac`), seed 505: Kindred, hurt and out of food at Y=-47,
     * had "Climb toward the surface for food" put off by one move elsewhere after another, 23 in all, each 24 along at its
     * own level (only every third, toward home, went up). STAGED: a native player in a pocket in solid stone twenty deep,
     * at health 6 and food 2 with nothing to eat, a wooden pickaxe, by day, on the Nether-portal goal; the watchdog's spell
     * shortened to 30 ticks, so it comes mid-climb. Passes once a move elsewhere is under way whose target is at least
     * twelve above the player's feet when it began; fails at once if one goes to the feet's own level.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "relocateUp", tickLimit = 1600)
    public void hurtAndHungryDeepInRockTheMoveElsewhereGoesUpWithItsOwnClimb(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] target = {null};
        int[] fromY = {Integer.MIN_VALUE};
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Deepdown"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos p = player.getBlockPos().toImmutable();
                    FixtureGround.keep(context, p.add(-6, -1, -6), p.add(6, 21, 6));
                    for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -1; y <= 20; y++) {
                        boolean pocket = x == 0 && z == 0 && (y == 0 || y == 1);
                        world.setBlockState(p.add(x, y, z), pocket ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 16));
                    player.setHealth(6.0f);
                    player.getHungerManager().setFoodLevel(2);
                    player.getHungerManager().setSaturationLevel(0f);
                    f.runtime.stuckTicksForFixtures(f.id, 30);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bots = f.runtime.panelBots(dev.kindred.core.Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (progress != null && !progress.equals(last[0])) {
                        last[0] = progress;
                        if (seen.length() < 2500) seen.append(progress.replaceAll("^.* - ", "")).append("; ");
                    }
                    f.player().setHealth(Math.min(f.player().getHealth(), 6.0f)); // Kept hurt, so the climb is for food.
                    var campaign = f.runtime.campaignForTest(f.id);
                    BlockPos to = campaign == null ? null : campaign.relocateTargetForTest();
                    if (to != null && target[0] == null) {
                        target[0] = to;
                        fromY[0] = f.player().getBlockY();
                        KindredMod.LOGGER.info("Relocate up: a move elsewhere toward {} from y {}; {}", to.toShortString(), fromY[0], seen);
                    }
                    context.assertTrue(target[0] != null, "No move elsewhere yet: " + seen);
                })
                .createAndAdd(() -> {
                    context.assertTrue(target[0].getY() >= fromY[0] + 12, "The move elsewhere went to " + target[0].toShortString()
                            + ", " + (target[0].getY() - fromY[0]) + " above its feet at y " + fromY[0] + ": " + seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: an AI player far out in the water with no shore in reach is not sent by the watchdog further along the
     * water it can see, but toward land.
     *
     * Technical: F-25c4do1, FACTIONS A's shipped-configuration runs on 101 (cache on 05416e24 and off 31fc6a46): at sea,
     * the first two moves elsewhere went 24 on along the sea's surface, into water it could see, and only the third, home,
     * ended spells of 12 and 31 minutes. STAGED: a rimmed pool three deep and nearly sixty across, the player on a stone
     * pillar at its middle with nothing to do there, and dry land only beyond its southern edge, twenty-six off (out of the
     * dry-ground search's sixteen); the pool's open water remembered as seen, as a look over it from the shore leaves it
     * (the fixture's subject is the choice of where to go, not the sweep); the watchdog's spell shortened to 60 ticks. The
     * first heading the move takes is east, over the water. Passes once a move elsewhere is under way whose target is not
     * water; fails at once if it is.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "relocateDry", tickLimit = 2000)
    public void farOutInTheWaterTheMoveElsewhereIsNotAlongTheWaterItSees(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] pool = {null};
        BlockPos[] target = {null};
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Afloat"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos p = player.getBlockPos().up().toImmutable(); // A layer up: its feet stand on the plot's floor.
                    pool[0] = p;
                    // The pool from the plot's corner where the player stands, inside the plot: the player at its middle, 27 in,
                    // and dry land from 26 south of it, out of the dry-ground search's sixteen.
                    FixtureGround.keep(context, p.add(-2, 0, -2), p.add(56, 8, 58));
                    // A rim one over the water round it, so the pool does not spill; a pillar of stone at its middle, its top two
                    // over the water, which the player stands on: from there it sees the water about it, as a swimmer's eyes at
                    // the surface do not.
                    for (int x = -2; x <= 56; x++) for (int z = -2; z <= 58; z++) {
                        boolean land = z >= 51, rim = x == -2 || x == 56 || z == -2 || z == 58, pillar = x == 27 && z == 25;
                        world.setBlockState(p.add(x, 0, z), Blocks.STONE.getDefaultState());
                        for (int y = 1; y <= 3; y++) world.setBlockState(p.add(x, y, z), land || rim || pillar ? Blocks.STONE.getDefaultState() : Blocks.WATER.getDefaultState());
                        world.setBlockState(p.add(x, 4, z), rim || pillar ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        world.setBlockState(p.add(x, 5, z), pillar ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        for (int y = 6; y <= 8; y++) world.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    player.refreshPositionAndAngles(p.getX() + 27.5, p.getY() + 6, p.getZ() + 25.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // Staged: the pool's open water remembered as seen, as a look over it from the shore leaves it (its own
                    // sweep, from eyes at the surface, reaches little of it in the time a fixture has), once it has looked round. Then
                    // the spell, short.
                    var campaign = f.runtime.campaignForTest(f.id);
                    context.assertTrue(campaign != null, "No goal campaign yet");
                    // After its first look round: that first sweep begins its sight afresh in the dimension, forgetting
                    // anything staged before it.
                    context.assertTrue(!campaign.knowledge().all(WorldKnowledge.Kind.WATER).isEmpty(), "Not looked round yet");
                    BlockPos corner = pool[0];
                    for (int x = -1; x <= 55; x++) for (int z = -1; z <= 57; z++) { // The whole pool, to its rim and shore.
                        BlockPos surface = corner.add(x, 3, z);
                        if (context.getWorld().getBlockState(surface).isOf(Blocks.WATER)) campaign.knowledge().remember(WorldKnowledge.Kind.WATER, surface);
                    }
                    BlockPos feet = f.player().getBlockPos();
                    context.assertTrue(campaign.knowledge().seenOpenWaterToward(feet, feet.east(24), FactionsPlayer.OPEN_WATER),
                            "The water east of it is not known as seen");
                    f.runtime.stuckTicksForFixtures(f.id, 60);
                })
                .createAndAdd(() -> {
                    var bots = f.runtime.panelBots(dev.kindred.core.Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (progress != null && !progress.equals(last[0])) {
                        last[0] = progress;
                        if (seen.length() < 2500) seen.append(progress.replaceAll("^.* - ", "")).append("; ");
                    }
                    var campaign = f.runtime.campaignForTest(f.id);
                    BlockPos to = campaign == null ? null : campaign.relocateTargetForTest();
                    if (to != null && target[0] == null) {
                        target[0] = to;
                        KindredMod.LOGGER.info("Relocate dry: a move elsewhere toward {} ({} from the pool's corner) from {}, its cell {}, its column seen as water {}, water columns known {}; {}",
                                to.toShortString(), to.subtract(pool[0]).toShortString(), f.player().getBlockPos().toShortString(),
                                context.getWorld().getBlockState(to).getBlock(), campaign.knowledge().seenWaterColumn(to),
                                campaign.knowledge().all(WorldKnowledge.Kind.WATER).size(), seen);
                    }
                    context.assertTrue(target[0] != null, "No move elsewhere yet: " + seen);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(world.getFluidState(target[0]).isEmpty(), "The move elsewhere went to " + target[0].toShortString()
                            + ", water: " + seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
