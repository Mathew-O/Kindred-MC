package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Territory;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a faction player builds its base. With its goal running (a faction world's standing goal is the End or
 * the dragon, never none) and stone tools in hand, its cabin is due, and it begins it on its own faction's land, not
 * after a goal that never ends; a faction walk asked while the cabin goes up waits for it and is kept: it never sets out
 * toward the walk's end, east of its land, while the cabin is being built. Before M-06i2 no
 * faction player's base was ever built: faction play and the goal's campaign both came before the base, and the base
 * waited for no goal (RUNNER 2's finding).
 *
 * Technical: staged, not a live run. Its faction, Red, holds the nine chunks round where it stands (recorded, as a
 * founder's claims would be). Its staged inventory, item by item: a stone pickaxe and a stone axe (what makes the cabin
 * due); the cabin's whole bill, 98 oak planks, its 12 oak logs, 42 oak stairs, 7 oak slabs, an oak door, a red bed, 2
 * chests, a torch, its 54 cobblestone and its furnace (the other 8); and, not from the bill, 2 crafting tables, 64 dirt to
 * scaffold with and 16 bread. The whole bill, so that its first blocks go down as soon as it begins: with half its logs
 * and none of its stone, the build's one trip for them first (as {@link NativeBaseGameTests} has it) took the first
 * placement past 2,400 ticks under CI's load (#323's run 43666381367), though the cabin was begun at once. Its goal is
 * a Nether portal. Stood a
 * moment before it plays, so that it has looked at the ground round it. The controls: the same founder with no land
 * begins no base; a co-op player with a goal begins none, as before.
 */
public final class NativeFactionBaseGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    /** No starting coins: a claim costs coins, so its faction can claim no land (the no-land control). */
    private static final FactionSettings PENNILESS = FactionSettings.parse(Map.of("border", "60000000", "startCoins", "0"));
    private static final String W = Territory.OVERWORLD;
    /** The begun cabin's first placement within this many ticks of its goal starting. */
    private static final int BEGIN = 2400;
    /** Stood still before it plays, looking round (as {@code NativeTradingHallGameTests.LOOK_ROUND}). */
    private static final int LOOK_ROUND = 100;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionBase", tickLimit = 5200)
    public void withItsGoalRunningAFounderBeginsItsCabinOnItsOwnLandAndAWalkWaitsForIt(TestContext context) {
        cabinAndWalk(context, 0, 0);
    }

    /**
     * Its land grown a chunk further east as its cabin goes up, and it on that chunk as the walk is asked: on its own land,
     * not set out toward the walk (M-06i2d). In CI (#391's run 43816972536, #405's run 43851384322) its faction claimed a
     * tenth chunk, one further east, as the cabin began, and the cabin's stand was on it; a check that took its land to be
     * the nine staged chunks read it as gone east. Staged: Red holds the chunk two east of the centre too, and it is set down
     * there as the walk is staged.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionBaseGrownEast", tickLimit = 5200)
    public void itsLandGrownEastItStandsOnItsOwnNewChunkAndAWalkStillWaits(TestContext context) {
        cabinAndWalk(context, 2, 2);
    }

    /**
     * The cabin begun and a faction walk east asked while it goes up; with {@code claimEast} above 0 its faction also holds
     * the chunk that many east of the centre, and with {@code standEast} above 0 it is set down that many chunks east of the
     * centre as the walk is asked.
     */
    private static void cabinAndWalk(TestContext context, int claimEast, int standEast) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5100);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos home = o.add(16, 7, 16);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        Territory.Chunk[] centre = new Territory.Chunk[1];
        String[] red = new String[1];
        long[] start = {-1}, stood = {-1}, begun = {-1};
        boolean[] leftLand = {false};
        String[] left = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    stage(context, f, home, trunks);
                    centre[0] = Territory.Chunk.at(W, home.getX(), home.getZ());
                    f.runtime.factionsWorldForTest(WIDE);
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, f.player().getUuid(), "create", List.of("Red", "RED")).accepted(), "Mason founds Red");
                    f.runtime.factions().clockForTest(0);
                    red[0] = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, f.player().getUuid(), red[0],
                                centre[0].offset(dx, dz).key(), Map.of()));
                    }
                    if (claimEast > 0) {
                        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, f.player().getUuid(), red[0],
                                centre[0].offset(claimEast, 0).key(), Map.of()));
                    }
                    stood[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.recordWritten(), "Its land written"))
                .createAndAdd(() -> context.assertTrue(context.getWorld().getServer().getTicks() - stood[0] >= LOOK_ROUND, "Stood looking round"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    long now = context.getWorld().getServer().getTicks();
                    context.assertTrue(now - start[0] <= BEGIN || begun[0] >= 0, "Its cabin begun within " + BEGIN + " ticks: " + f.runtime.currentBase(f.id)
                            + " / " + f.runtime.baseNote(f.id) + " / " + f.runtime.nativeLastResult(f.id));
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(base.isPresent() && base.get().stage().equals("cabin") && placed(context, base.get()) > 0,
                            "Its cabin's first blocks placed: " + base + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.baseNote(f.id));
                    begun[0] = now;
                    context.assertTrue(f.runtime.goalForTest(f.id) == dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL, "Its goal still its goal");
                    for (BlockPos at : placedAt(context, base.get())) {
                        context.assertTrue(red[0].equals(f.runtime.projected().claims().get(Territory.Chunk.at(W, at.getX(), at.getZ()).key())),
                                "Its cabin's blocks on Red's land: " + at.toShortString());
                    }
                    // A faction walk asked now, a scout four chunks east of its land: it waits while the cabin goes up.
                    f.runtime.factionMoveSetForTest(f.id, new FactionPlay.Choice(FactionPlay.Kind.SCOUT, centre[0].offset(5, 0).key(), 0,
                            "staged: a chunk east of its land"));
                    if (standEast > 0) {
                        var player = f.player();
                        player.refreshPositionAndAngles(centre[0].offset(standEast, 0).x() * 16 + 8.5, player.getY(), player.getZ(), 0, 0);
                        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    }
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    // Toward the walk's end: east of its land's centre, on a chunk its faction does not hold by its claims as recorded
                    // now (a build's own stand may be a step off its edge, any side but the walk's). Its land is what its faction holds,
                    // not the nine chunks staged: its faction play may claim more as it builds (M-06i2d).
                    var here = Territory.Chunk.at(W, player.getBlockX(), player.getBlockZ());
                    if (!leftLand[0] && here.x() > centre[0].x() && !red[0].equals(f.runtime.projected().claims().get(here.key()))) {
                        leftLand[0] = true;
                        left[0] = "at " + player.getBlockPos().toShortString() + ", " + (context.getWorld().getServer().getTicks() - begun[0]) + " ticks in, build "
                                + f.runtime.buildStatus(f.id).orElse("none") + ", move " + f.runtime.factionMoveForTest(f.id) + ", step " + f.runtime.goalStepForTest(f.id)
                                + ", last " + f.runtime.nativeLastResult(f.id);
                    }
                    long now = context.getWorld().getServer().getTicks();
                    boolean building = f.runtime.buildStatus(f.id).isPresent();
                    context.assertTrue(now - begun[0] >= 600 || !building, "Watched while it builds");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!leftLand[0], "It never set out east toward the walk while its cabin went up, the walk waiting: went " + left[0]);
                    var move = f.runtime.factionMoveForTest(f.id);
                    boolean building = f.runtime.buildStatus(f.id).isPresent();
                    context.assertTrue(!building || move != null && move.kind() == FactionPlay.Kind.SCOUT, "The walk kept while it builds: " + move);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The control: the same founder with no land of its faction's begins no base (a faction base is built on its own claims
     * only). The server's starting coins are none, so it claims nothing either: with coins, its faction play claims its
     * first base's ground (#238), and the base follows on it, as the first test's does.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionBaseNoLand", tickLimit = 3400)
    public void aFounderWithNoLandBeginsNoBase(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3300);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos home = o.add(16, 7, 16);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        long[] start = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    stage(context, f, home, trunks);
                    f.runtime.factionsWorldForTest(PENNILESS);
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, f.player().getUuid(), "create", List.of("Red", "RED")).accepted(), "Mason founds Red");
                    f.runtime.factions().clockForTest(0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    context.assertTrue(!f.runtime.projected().claims().containsValue(red), "Red holds no land");
                    context.assertTrue(f.runtime.currentBase(f.id).isEmpty(), "No base begun with no land: " + f.runtime.currentBase(f.id));
                    context.assertTrue(context.getWorld().getServer().getTicks() - start[0] >= BEGIN, "Watched " + BEGIN + " ticks");
                })
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The control: outside a faction world a player with a goal running begins no base, as before (M-01c). */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionBaseCoop", tickLimit = 3400)
    public void inCoOpAPlayerWithAGoalBeginsNoBase(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3300);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos home = o.add(16, 7, 16);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        long[] start = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    stage(context, f, home, trunks);
                    context.assertTrue(!f.runtime.factionsWorld(), "Not a faction world");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getWorld().getServer().getTicks();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.currentBase(f.id).isEmpty(), "No base begun with a goal running in co-op: " + f.runtime.currentBase(f.id));
                    context.assertTrue(context.getWorld().getServer().getTicks() - start[0] >= BEGIN, "Watched " + BEGIN + " ticks");
                })
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The ground, its staged inventory (listed in the class comment), by day, bases on, its base on the first plan. */
    private static void stage(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos home, BlockPos[] trunks) {
        NativeBaseGameTests.ground(context, trunks);
        context.getWorld().setTimeOfDay(1000);
        var player = f.player();
        var inventory = player.getInventory();
        inventory.clear();
        ItemStack[] held = {
                new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.OAK_PLANKS, 34), new ItemStack(Items.OAK_LOG, 12), new ItemStack(Items.OAK_STAIRS, 42),
                new ItemStack(Items.OAK_SLAB, 7), new ItemStack(Items.OAK_DOOR, 1), new ItemStack(Items.RED_BED, 1),
                new ItemStack(Items.CRAFTING_TABLE, 2), new ItemStack(Items.CHEST, 2), new ItemStack(Items.TORCH, 1),
                new ItemStack(Items.COBBLESTONE, 54), new ItemStack(Items.FURNACE, 1),
                new ItemStack(Items.DIRT, 64), new ItemStack(Items.BREAD, 16)};
        for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
        context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
        f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
    }

    /** How many of the cabin's own materials stand by its origin at and above its floor: what its build has placed so far. */
    private static int placed(TestContext context, dev.kindred.fabric.PlayerMemoryStore.Base base) {
        return placedAt(context, base).size();
    }

    /** Where the cabin's own materials stand within ten blocks of its origin, at and above its floor (the ground holds none). */
    private static List<BlockPos> placedAt(TestContext context, dev.kindred.fabric.PlayerMemoryStore.Base base) {
        var world = context.getWorld();
        var out = new java.util.ArrayList<BlockPos>();
        for (int x = base.x() - 10; x <= base.x() + 10; x++) for (int z = base.z() - 10; z <= base.z() + 10; z++) for (int y = base.y(); y <= base.y() + 6; y++) {
            var at = new BlockPos(x, y, z);
            var state = world.getBlockState(at);
            if (state.isOf(Blocks.OAK_PLANKS) || state.isOf(Blocks.OAK_STAIRS) || state.isOf(Blocks.OAK_SLAB)
                    || state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.OAK_DOOR)) out.add(at);
        }
        return out;
    }
}
