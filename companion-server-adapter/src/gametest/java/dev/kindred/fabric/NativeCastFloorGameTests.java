package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** A frame's next block is poured from its floor, not from under it, and a cast with no walk back up digs its own way (F-25c4dm1). */
public final class NativeCastFloorGameTests implements FabricGameTest {
    /**
     * Under its frame's floor with its lava refilled, no walk back up: it digs its own way back to the frame and casts
     * the next block from the floor. In E-40's iteration 26 (`e40a26r-f8b7ffe`) on 707 Sol, back from a refill, found
     * "Cast: step closer" had no walkable route to the stands seven above, its own stair cut by its refill's dig, and its
     * goal climbed away ("no way on from here - Climb to find another way") to Y=66 with its lava in hand and its frame
     * half cast; Kindred, three under its frame's floor, was refused "No reachable face to pour against" from there.
     * Staged: a block of stone, the frame along north and south facing east, its bottom corners cobblestone and three of
     * its ten obsidian cast as if it had cast them, the row in front of it open five high and lit, and the row behind it
     * open two high; the frame taken as the player's own. The player at the foot of a shaft one wide and three deep under
     * one of the row behind's stands, the next cell to cast within reach of its eyes by distance and no face of it in
     * sight, with the cast route's kit, its lava refilled; daylight held. Passes once that cell is obsidian, cast with its
     * feet on the frame's floor, within 1,200 ticks; fails at once if it gives the site up or climbs more than two above
     * the floor.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "castFloor", tickLimit = 1600)
    public void underItsFramesFloorWithNoWalkBackUpItDigsBackAndCastsFromTheFloor(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(-8, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        BlockPos shaft = o.add(-8, -3, -1);
        BlockPos next = site.at(3, 1);
        int[] watched = new int[1];
        boolean[] fromBelow = new boolean[1];
        // The frame's obsidian, and its feet's height over the floor, a tick ago: a cell is judged by where its feet were
        // when it turned to obsidian, not by the plan's label, which still reads "Cast obsidian" while the walk before it
        // ends (on main 0ab12ea2 merged in, a step closer still under way took its feet one under the floor for four ticks
        // with that label up, and the cell was then poured from the floor).
        int[] castBefore = {-1};
        int[] upBefore = {0};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caster"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-16, -6, -8), o.add(4, 12, 8));
                    FixtureGround.simulated(context, o.add(-16, -6, -8), o.add(4, -6, 8));
                    for (int x = -16; x <= 4; x++) for (int z = -8; z <= 8; z++) for (int y = -6; y <= 12; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 10 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // The row in front of the frame, open five high, the frame's opening and top row, and the row behind
                    // the front, its stands, open two high.
                    for (int across = 0; across <= 3; across++) {
                        for (int up = 0; up <= 4; up++) world.setBlockState(site.front(across, up), Blocks.AIR.getDefaultState());
                        for (int up = 0; up <= 1; up++) world.setBlockState(site.front(across, up).offset(Direction.EAST), Blocks.AIR.getDefaultState());
                    }
                    for (int across = 0; across <= 3; across++) for (int up = 1; up <= 4; up++) {
                        world.setBlockState(site.at(across, up), Blocks.AIR.getDefaultState());
                    }
                    // Three of the ten cast, the bottom corners set.
                    world.setBlockState(site.at(0, 0), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(site.at(3, 0), Blocks.COBBLESTONE.getDefaultState());
                    for (int[] cast : new int[][]{{1, 0}, {2, 0}, {0, 1}}) {
                        world.setBlockState(site.at(cast[0], cast[1]), Blocks.OBSIDIAN.getDefaultState());
                    }
                    // The shaft it is at the foot of, one wide and three deep, under one of the row behind's stands: no
                    // walk leads out of it.
                    for (int y = -3; y <= -1; y++) world.setBlockState(shaft.withY(o.getY() + y), Blocks.AIR.getDefaultState());
                    world.setBlockState(site.front(1, 5), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(site.front(2, 5), Blocks.GLOWSTONE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(shaft.getX() + 0.5, shaft.getY(), shaft.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // The cast route's kit, its lava refilled for the next cell and a bucket of water.
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(8, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(9, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(10, new ItemStack(Items.FLINT));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(shaft), "At the shaft's foot: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    int up = player.getBlockY() - site.origin().getY();
                    if (watched[0]++ % 50 == 0 || progress.contains("Cast obsidian")) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    int cast = 0;
                    for (int across = 0; across <= 3; across++) for (int high = 0; high <= 4; high++) {
                        if (world.getBlockState(site.at(across, high)).isOf(Blocks.OBSIDIAN)) cast++;
                    }
                    if (castBefore[0] >= 0 && cast > castBefore[0]) {
                        seen.append(watched[0]).append(": obsidian ").append(cast).append(", feet ").append(upBefore[0]).append(" then ").append(up).append(" over the floor; ");
                        if (up < 0 || upBefore[0] < 0) fromBelow[0] = true;
                    }
                    castBefore[0] = cast;
                    upBefore[0] = up;
                    context.assertTrue(!progress.contains("Climb to find another way") && up <= 2,
                            "It climbed away from its frame, " + up + " over the floor: " + seen + " now " + progress);
                    context.assertTrue(f.runtime.campaignForTest(f.id).portalSiteForTest() != null, "It gave the frame site up: " + seen + " now " + progress);
                    context.assertTrue(watched[0] <= 1200, "The next cell not cast after 1,200 ticks: " + seen + " now " + progress);
                    context.assertTrue(world.getBlockState(next).isOf(Blocks.OBSIDIAN), "Not cast yet");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!fromBelow[0], "It poured from under the floor: " + seen);
                    KindredMod.LOGGER.info("Cast floor: the next cell cast after {} ticks; {}", watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: an AI player casting its portal frame from lava and water, whose next cell holds water that does not
     * drain, does not wait on it for ever: after a while it says why and fills the water in with a block of its own, digs it
     * out and casts the cell.
     *
     * Technical: F-22c128, from E-40 iteration 30 (`e40a30r-63b97ac`, RUNNER 2's {@code claude/corpus-data-e40a30-consoles-1123}):
     * on 707 Echo, its frame begun at about −36, −51, 26 with one block cast, back with its lava refilled, "Cast: let the
     * water drain" for five and a half minutes, six stuck spells, while "try elsewhere" took it as far as 11, −5, 18. The wait
     * asks only that the cell and the one over it hold no fluid, and has no bound. STAGED: as above (a block of stone, the
     * frame along north and south facing east, its bottom corners and three of its ten obsidian set, the rows in front and
     * behind open, lit; the frame taken as the player's own), the player on the frame's floor at its stand with the cast
     * route's kit and its lava refilled; a water source in the next cell to cast; daylight held. Passes once that cell is
     * obsidian within 1,200 ticks; fails at once if it gives the site up.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "castDrain", tickLimit = 1600)
    public void aFrameCellsWaterThatDoesNotDrainIsFilledInAndTheCellCast(TestContext context) {
        standingWater(context, 0);
    }

    /**
     * In plain terms: as above with its blocks spent as it waits and none to be had close by: it does not wait on the water
     * for ever either.
     *
     * Technical: F-22c128, the lead's gap: at the bound with nothing to fill the water with, the wait was left without bound.
     * STAGED: as above; as the wait begins its cobblestone is taken from it and the stone of the staged block turned to
     * bedrock. A GUARD, not a before-and-after: it passes on main too, as the plan gives the cast only with the frame's
     * blocks in hand and goes for more first. Passes at 1,200
     * ticks if it has waited on the water no more than 300 of them, or at once if the cell is cast or the site given up;
     * fails once it has waited on the water more than 300 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "castDrainBare", tickLimit = 1600)
    public void aFrameCellsWaterThatDoesNotDrainWithNoBlockToBeHadIsNotWaitedOnForEver(TestContext context) {
        standingWater(context, 2);
    }

    /** {@code spent} 0: its blocks kept; 2: its cobblestone taken as the wait begins and the stone round it turned to bedrock. */
    /**
     * In plain terms: a frame half cast, two of its bottom cells still to cast, in the dark under rock with torches in hand:
     * it lights its way, but never with a torch in a cell its cast will clear, and casts the next cell.
     *
     * Technical: F-22c127a, from E-40 iteration 31 (`20261030`): Kindred lit a torch at 24, 63, 88 ("Iron: light the way -
     * Place a torch at 24, 63, 88"), its cast cleared it ("Cast: clear 24, 63, 88"), it picked the torch up and lit it again,
     * 181 laps in 27 seconds, 263 on that seed. STAGED as the drain case's ground, but dark: no glowstone, the frame roofed in
     * rock and east of its front row, as CI's was, its corners and two cells cast, the bottom middle two left, the front row's
     * cell north of the player solid, the player on the front row beside the next cell to cast, with torches. Fails at once if
     * a torch stands in any cell of the cast's clear-set (the frame's cells and the cells over them, the mould behind them and
     * the rows in front of the frame, as {@code PortalRunCampaign.frameCells}, {@code clearPortalSite} and the cast's clearing
     * name them). Passes once the next
     * bottom cell is obsidian, within 1,200 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "castDarkTorch", tickLimit = 1600)
    public void inTheDarkAtAHalfCastFrameItLightsNoCellItsCastWillClear(TestContext context) {
        darkTorch(context, false);
    }

    /**
     * In plain terms: as the last, but standing in the frame's opening, rock on its open side: every spot its light tries is
     * one the cast will clear; it lights a cell clear of the cast within reach instead, never one in its way, and is not left
     * in the dark.
     *
     * Technical: F-22c127a, the lead's case. STAGED as the last, the bottom middle two cast, the player in the frame's opening
     * at its second cell up, the front row beside it rock: of the four cells round it and the one up behind it, each open one
     * is a frame cell or the mould behind, in the clear-set. On the row behind the front row, one cell over stone is open and
     * clear of it, within reach. Fails at once if a torch stands in any cell of the clear-set; passes once a torch
     * stands outside it within 300 ticks.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "castDarkTorchCorner", tickLimit = 700)
    public void inTheDarkWithEverySpotItTriesInTheCastsWayItLightsOneClearOfIt(TestContext context) {
        darkTorch(context, true);
    }

    private void darkTorch(TestContext context, boolean corner) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(-8, 0, 0);
        // As CI's: the frame east of its front row, running north; the player on the front row beside the next cell to cast.
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.EAST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.EAST, stand);
        BlockPos next = site.at(1, 0);
        var clearSet = new java.util.LinkedHashSet<BlockPos>();
        for (int across = 0; across <= 3; across++) {
            for (int up = 0; up <= 5; up++) {
                clearSet.add(site.at(across, up));
                clearSet.add(site.at(across, up).offset(site.toward()));
            }
            for (int up = 0; up <= 5; up++) clearSet.add(site.front(across, up));
        }
        int[] watched = new int[1];
        StringBuilder seen = new StringBuilder();
        String[] last = {""}, lit = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Lighter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-16, -6, -8), o.add(4, 12, 8));
                    FixtureGround.simulated(context, o.add(-16, -6, -8), o.add(4, -6, 8));
                    for (int x = -16; x <= 4; x++) for (int z = -8; z <= 8; z++) for (int y = -6; y <= 12; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 10 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    for (int across = 0; across <= 3; across++) {
                        for (int up = 0; up <= 4; up++) world.setBlockState(site.front(across, up), Blocks.AIR.getDefaultState());
                        for (int up = 0; up <= 1; up++) world.setBlockState(site.front(across, up).offset(Direction.WEST), Blocks.AIR.getDefaultState());
                    }
                    for (int across = 0; across <= 3; across++) for (int up = 0; up <= 4; up++) {
                        world.setBlockState(site.at(across, up), Blocks.AIR.getDefaultState());
                    }
                    // Staged: its corners and the first left side cell cast; the bottom middle two left, in the dark.
                    world.setBlockState(site.at(0, 0), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(site.at(3, 0), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(site.at(0, 1), Blocks.OBSIDIAN.getDefaultState());
                    world.setBlockState(site.at(3, 1), Blocks.OBSIDIAN.getDefaultState());
                    // Staged as CI's ground was at 23, 63, 88: the front row's cell to the north of where it stands solid, so
                    // the first open cell its light looks at, north, east, south and west in turn, is the frame's, east.
                    world.setBlockState(site.front(2, 0), Blocks.STONE.getDefaultState());
                    if (corner) {
                        // Staged: the bottom middle two cast, the front row beside the opening's second cell rock.
                        world.setBlockState(site.at(1, 0), Blocks.OBSIDIAN.getDefaultState());
                        world.setBlockState(site.at(2, 0), Blocks.OBSIDIAN.getDefaultState());
                        world.setBlockState(site.front(1, 1), Blocks.STONE.getDefaultState());
                        // The one open cell clear of the cast within its reach: on the row behind the front row, over stone.
                        world.setBlockState(site.front(2, 0).offset(site.toward().getOpposite()), Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    BlockPos front = corner ? site.at(1, 1) : site.front(1, 0);
                    player.refreshPositionAndAngles(front.getX() + 0.5, front.getY(), front.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(8, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(9, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(10, new ItemStack(Items.FLINT));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (!progress.equals(last[0])) {
                        if (seen.length() < 2500) seen.append(watched[0]).append(": ").append(progress.replaceAll("^.* - ", "")).append("; ");
                        last[0] = progress;
                    }
                    watched[0]++;
                    // The first torch seen in the clear-set fails it for good: a step's exception is only retried.
                    for (BlockPos cell : clearSet) {
                        var state = world.getBlockState(cell);
                        if (lit[0] == null && (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH))) {
                            lit[0] = "A torch put in a cell its cast will clear, at " + cell.toShortString()
                                    + " (" + (cell.equals(next) ? "the next cell to cast" : "of its clear-set") + "), tick " + watched[0];
                            KindredMod.LOGGER.info("Cast dark torch: {}", lit[0]);
                        }
                    }
                    if (lit[0] != null) throw new net.minecraft.test.GameTestException(lit[0] + ": " + seen + " now " + progress);
                    if (corner) {
                        BlockPos outside = null;
                        for (BlockPos cell : BlockPos.iterate(site.at(1, 1).add(-3, -1, -3), site.at(1, 1).add(3, 2, 3))) {
                            var state = world.getBlockState(cell);
                            if ((state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)) && !clearSet.contains(cell)) outside = cell.toImmutable();
                        }
                        context.assertTrue(watched[0] <= 300 || outside != null, "No torch lit clear of the cast in 300 ticks, every spot it tries in its way: " + seen + " now " + progress);
                        context.assertTrue(outside != null, "Not lit yet");
                        KindredMod.LOGGER.info("Cast dark torch (corner): a torch lit clear of the cast at {}, tick {}", outside.toShortString(), watched[0]);
                        return;
                    }
                    context.assertTrue(watched[0] <= 1200, "The next cell not cast after 1,200 ticks: " + seen + " now " + progress);
                    context.assertTrue(world.getBlockState(next).isOf(Blocks.OBSIDIAN), "Not cast yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cast dark torch{}: after {} ticks, no torch in its clear-set; {}", corner ? " (corner)" : "", watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    private void standingWater(TestContext context, int spent) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        BlockPos stand = o.add(-8, 0, 0);
        var site = new PortalRunCampaign.PortalSite(stand.offset(Direction.WEST, 2).offset(Direction.SOUTH, 1), Direction.NORTH, Direction.WEST, stand);
        BlockPos next = site.at(3, 1);
        int[] watched = new int[1], waits = new int[1], waited = new int[1];
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        boolean[] taken = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Drainer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-16, -6, -8), o.add(4, 12, 8));
                    FixtureGround.simulated(context, o.add(-16, -6, -8), o.add(4, -6, 8));
                    for (int x = -16; x <= 4; x++) for (int z = -8; z <= 8; z++) for (int y = -6; y <= 12; y++) {
                        world.setBlockState(o.add(x, y, z), (y <= 10 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    for (int across = 0; across <= 3; across++) {
                        for (int up = 0; up <= 4; up++) world.setBlockState(site.front(across, up), Blocks.AIR.getDefaultState());
                        for (int up = 0; up <= 1; up++) world.setBlockState(site.front(across, up).offset(Direction.EAST), Blocks.AIR.getDefaultState());
                    }
                    for (int across = 0; across <= 3; across++) for (int up = 1; up <= 4; up++) {
                        world.setBlockState(site.at(across, up), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(site.at(0, 0), Blocks.COBBLESTONE.getDefaultState());
                    world.setBlockState(site.at(3, 0), Blocks.COBBLESTONE.getDefaultState());
                    for (int[] cast : new int[][]{{1, 0}, {2, 0}, {0, 1}}) {
                        world.setBlockState(site.at(cast[0], cast[1]), Blocks.OBSIDIAN.getDefaultState());
                    }
                    world.setBlockState(site.front(1, 5), Blocks.GLOWSTONE.getDefaultState());
                    world.setBlockState(site.front(2, 5), Blocks.GLOWSTONE.getDefaultState());
                    // Staged: water that does not drain, a source in the next cell to cast.
                    world.setBlockState(next, Blocks.WATER.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(7, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(8, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(9, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(10, new ItemStack(Items.FLINT));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    f.runtime.campaignForTest(f.id).portalSiteForTest(site);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    if (spent > 0 && !taken[0] && progress.contains("let the water drain")) {
                        // Staged: its blocks spent as the wait begins, and the stone round it gone too.
                        taken[0] = true;
                        var inventory = player.getInventory();
                        for (int s = 0; s < inventory.size(); s++) if (inventory.getStack(s).isOf(Items.COBBLESTONE)) inventory.setStack(s, ItemStack.EMPTY);
                        if (spent == 2) for (int x = -16; x <= 4; x++) for (int z = -8; z <= 8; z++) for (int y = -6; y <= 10; y++) {
                            if (world.getBlockState(o.add(x, y, z)).isOf(Blocks.STONE)) world.setBlockState(o.add(x, y, z), Blocks.BEDROCK.getDefaultState());
                        }
                    }
                    if (!progress.equals(last[0])) {
                        if (progress.contains("let the water drain")) waits[0]++;
                        if (seen.length() < 2500) seen.append(watched[0]).append(": ").append(progress.replaceAll("^.* - ", "")).append("; ");
                        last[0] = progress;
                    }
                    watched[0]++;
                    if (progress.contains("let the water drain")) waited[0]++;
                    context.assertTrue(waited[0] <= 300, "Waited on water that does not drain " + waited[0] + " ticks: " + seen + " now " + progress);
                    boolean givenUp = f.runtime.campaignForTest(f.id).portalSiteForTest() == null;
                    context.assertTrue(spent == 2 || !givenUp, "It gave the frame site up: " + seen + " now " + progress);
                    boolean cast = world.getBlockState(next).isOf(Blocks.OBSIDIAN);
                    if (spent == 2 && (cast || givenUp || watched[0] >= 1200)) return;
                    context.assertTrue(watched[0] <= 1200, "The cell with water that does not drain not cast after 1,200 ticks: " + seen + " now " + progress);
                    context.assertTrue(cast, "Not cast yet");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Cast drain (spent {}): {} after {} ticks, {} waiting; {}", spent,
                            context.getWorld().getBlockState(next).isOf(Blocks.OBSIDIAN) ? "the cell cast"
                                    : f.runtime.campaignForTest(f.id).portalSiteForTest() == null ? "the site given up" : "neither", watched[0], waited[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
