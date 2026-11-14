package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.SafeZones;
import dev.kindred.core.factions.Territory;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * In plain terms: a player that spawns in a faction world's hub, where it may cut no tree, walks out of it to wood. It
 * does not walk to the hub's trees it sees (it would only give each up there, then walk on to trees far over the
 * skyline), and a home in the hub is no home to explore round: out past the explore radius from it, with no tree in
 * view, the goal waited for good. On Factions seeds-10 (`f009244`) every one of the 71 Wood-stage waits ("No executable
 * local task") came 222 to 559 blocks from a spawn in or by the hub, and four players never made a wooden tool (M-06i1).
 *
 * Technical: staged, not a live run: a safe zone recorded over the fixture's own chunks (the hub's stand-in) with a
 * trunk inside it and one outside, the player standing inside. {@link PortalRunCampaign#treeInViewForTest} is the tree
 * its look walks toward; {@link PortalRunCampaign#exploreForTest} its explore step for logs with a given home. A home
 * 250 blocks off in a recorded zone chunk: explored from where it stands; the control, the same distance off on wild
 * land, keeps the radius from home and finds no heading, as before. With no zone recorded (the first check) the near
 * tree is the one it walks toward: the territory guard's rule alone changes anything, and it allows everything outside
 * a faction world.
 */
public final class NativeFactionHubWalkOutGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionHubWalkOut", tickLimit = 800)
    public void outOfTheHubItWalksToTreesItMayCutAndExploresFromWhereItStands(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        var server = context.getWorld().getServer();
        BlockPos o = NativeBaseGameTests.origin(context);
        // Its look sees trees past its sweep (32 blocks) out to 96: the zone's trunk 44 blocks off, the other 80 off another
        // way, its sight line clear of the first's crown.
        BlockPos stand = o.add(-30, 7, -30);
        BlockPos inside = o.add(14, 7, -30), outside = o.add(40, 7, 10);
        BlockPos farInZone = o.add(-280, 7, -30), farWild = o.add(-30, 7, -280);
        var zone = new TreeSet<String>();
        var campaign = new PortalRunCampaign();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rover"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, new BlockPos[0]);
                    field(context, o);
                    trunk(context, inside);
                    trunk(context, outside);
                    context.getWorld().setTimeOfDay(1000);
                    // Before the faction world: the anchor is home by construction, the territory guard allowing everything.
                    var coop = new PortalRunCampaign();
                    coop.exploreForTest(f.player(), farInZone);
                    context.assertTrue(coop.exploreAnchorForTest(f.player()).equals(farInZone), "No faction world: the anchor is home, "
                            + coop.exploreAnchorForTest(f.player()).toShortString());
                    f.runtime.factionsWorldForTest(WIDE);
                    for (int x = -40; x <= 20; x += 4) for (int z = -40; z <= 20; z += 4) {
                        var at = o.add(x, 0, z);
                        zone.add(SafeZones.chunkAt(W, at.getX(), at.getZ()));
                    }
                    zone.add(SafeZones.chunkAt(W, farInZone.getX(), farInZone.getZ()));
                    context.assertTrue(zone.contains(SafeZones.chunkAt(W, inside.getX(), inside.getZ()))
                            && !zone.contains(SafeZones.chunkAt(W, outside.getX(), outside.getZ()))
                            && !zone.contains(SafeZones.chunkAt(W, farWild.getX(), farWild.getZ())), "One trunk in the zone, one out; one far home in it, one out");
                    f.player().refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing in what will be the zone"))
                .createAndAdd(() -> {
                    // The control: a faction world with no zone yet: the near trunk is the one it walks toward.
                    var seen = campaign.treeInViewForTest(body(f));
                    context.assertTrue(seen.isPresent() && column(seen.get(), inside), "With no zone, the near trunk: " + seen + " (inside " + inside.toShortString() + ", outside " + outside.toShortString()
                            + ", standing " + f.player().getBlockPos().toShortString() + ", origin " + o.toShortString() + ")");
                    f.runtime.record(SafeZones.zone("fixture", zone, null, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "The zone written"))
                .createAndAdd(() -> {
                    var seen = campaign.treeInViewForTest(body(f));
                    context.assertTrue(seen.isPresent() && column(seen.get(), outside),
                            "The zone's trunk not walked toward, the one outside is: " + seen);
                    Optional<PortalRunCampaign.Task> fromZone = campaign.exploreForTest(f.player(), farInZone);
                    context.assertTrue(fromZone.isPresent() && fromZone.get().label().startsWith("Explore "),
                            "A home 250 blocks off in a safe zone: explored from where it stands: " + fromZone.map(PortalRunCampaign.Task::label));
                    Optional<PortalRunCampaign.Task> fromWild = campaign.exploreForTest(f.player(), farWild);
                    context.assertTrue(fromWild.isEmpty(), "The control, a home 250 blocks off on wild land: its radius kept, no heading: "
                            + fromWild.map(PortalRunCampaign.Task::label));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Live: its own goal begun in the zone, the zone's tree beside it and the only other tree some 34 blocks out past
     * open ground. It makes a wooden tool (within 4,800 ticks), and its explore anchor is no longer its home in the zone but workable ground.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionHubWalkOutLive", tickLimit = 4800)
    public void itsGoalBegunInTheZoneItWalksOutMakesAWoodenToolAndAnchorsOnWorkableGround(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 4700);
        var server = context.getWorld().getServer();
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos inside = o.add(20, 7, 16), outside = o.add(40, 7, 40);
        BlockPos stand = o.add(16, 7, 16);
        var zone = new TreeSet<String>();
        var tools = java.util.Set.of(net.minecraft.item.Items.WOODEN_PICKAXE, net.minecraft.item.Items.WOODEN_AXE,
                net.minecraft.item.Items.WOODEN_SWORD, net.minecraft.item.Items.WOODEN_SHOVEL, net.minecraft.item.Items.WOODEN_HOE);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Strider"))
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
                    f.runtime.record(SafeZones.zone("fixture", zone, null, server.getTicks(), System.currentTimeMillis()));
                    var player = f.player();
                    player.getInventory().clear();
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "The zone written"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    boolean holds = false;
                    for (int slot = 0; slot < inventory.size() && !holds; slot++) holds = tools.contains(inventory.getStack(slot).getItem());
                    context.assertTrue(holds, "A wooden tool earned: " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var campaign = f.runtime.campaignForTest(f.id);
                    var player = f.player();
                    BlockPos home = campaign.homeForTest(), anchor = campaign.exploreAnchorForTest(player);
                    context.assertTrue(home != null && zone.contains(SafeZones.chunkAt(W, home.getX(), home.getZ())), "Its home in the zone: " + home);
                    context.assertTrue(!anchor.equals(home) && TerritoryGuard.mayChange(player, anchor),
                            "Its explore anchor moved to ground it may work: " + anchor.toShortString() + " (home " + home.toShortString() + ")");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The fixture's field as grass over its whole kept area, open to the sky, so that its look reaches 90 blocks across it. */
    private static void field(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (int x = -40; x <= 72; x++) for (int z = -40; z <= 72; z++) {
            for (int y = 4; y <= 5; y++) world.setBlockState(o.add(x, y, z), net.minecraft.block.Blocks.DIRT.getDefaultState());
            world.setBlockState(o.add(x, 6, z), net.minecraft.block.Blocks.GRASS_BLOCK.getDefaultState());
            for (int y = 7; y <= 24; y++) world.setBlockState(o.add(x, y, z), net.minecraft.block.Blocks.AIR.getDefaultState());
        }
    }

    /** A trunk six logs high with its crown, as the base fixtures' trees. */
    private static void trunk(TestContext context, BlockPos at) {
        var world = context.getWorld();
        var leaves = net.minecraft.block.Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
        for (int y = 0; y < 6; y++) world.setBlockState(at.up(y), net.minecraft.block.Blocks.OAK_LOG.getDefaultState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) world.setBlockState(at.add(dx, 5, dz), leaves);
        world.setBlockState(at.up(6), leaves);
    }

    /** The log its look found is one of this trunk's: the same column (it is a log under the crown, not the foot). */
    private static boolean column(BlockPos log, BlockPos trunk) {
        return log.getX() == trunk.getX() && log.getZ() == trunk.getZ();
    }

    private static NativePlayerBody body(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.nativeBodyForTest(f.id);
    }
}
