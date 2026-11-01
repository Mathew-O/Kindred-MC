package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * In plain terms: a player keeps a map of the world from its own play. Diamonds it saw and had no use for then are
 * gone back to when it wants them, rather than searched for again; and at night it walks round a place where it was
 * killed, when the way round is about as short.
 *
 * Technical: F-30. The diamonds are seen by the goal's own sweep ({@code WorldKnowledge}: in its line of sight,
 * exposed to air), which feeds the atlas; nothing is put in the atlas by the fixture there. The move away from them,
 * the loss of the diamond pickaxe that made them of no use, and the death the night walk shuns are staged.
 */
public final class NativeAtlasGameTests implements FabricGameTest {
    /** How far east of the diamonds the player is put: past the 40 blocks it gathers ore in on the surface. */
    private static final int AWAY = 66;

    /**
     * It sees two diamond ores beside it with a diamond pickaxe in hand, so it has no use for them, and its atlas
     * keeps them. Put 66 blocks off, past its reach for ore, its diamond pickaxe gone and two diamonds in hand, it wants
     * one more: it goes back to where it saw them and mines one, rather than dig down or branch mine for diamonds.
     * (With the atlas off, the same player looks round and digs down where it stands: its sweep still remembers the
     * ore, but nothing sends it farther than its reach.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "atlasDiamonds", tickLimit = 2400)
    public void diamondsItSawWhenItHadNoUseForThemAreGoneBackToWhenItWantsThem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] origin = new BlockPos[1];
        BlockPos[] ores = new BlockPos[2];
        List<String> steps = new ArrayList<>();
        Atlas.Sighting[] seen = new Atlas.Sighting[1];
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Prospector"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos o = origin[0] = f.player().getBlockPos().up(20).toImmutable();
                    chunks(world, o.add(-8, 0, -8), o.add(AWAY + 10, 0, 8), true);
                    // A walled causeway in the open, three blocks of stone thick, the sky over it.
                    for (int x = -6; x <= AWAY + 8; x++) for (int z = -5; z <= 5; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean edge = x == -6 || x == AWAY + 8 || z == -5 || z == 5;
                        for (int y = 0; y <= 4; y++) {
                            world.setBlockState(o.add(x, y, z), edge && y <= 2 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    ores[0] = o.add(3, 0, 4);
                    ores[1] = o.add(4, 0, 4);
                    for (BlockPos ore : ores) world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(origin[0]), "On the causeway"))
                .createAndAdd(() -> context.assertTrue(f.runtime.atlasForFixtures(f.id) != null, "Its atlas read"))
                .createAndAdd(() -> {
                    kit(f, true);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var sighting = f.runtime.atlasForFixtures(f.id).lastSeen(Atlas.Resource.DIAMOND);
                    context.assertTrue(sighting.isPresent(), "Its atlas has the diamonds it saw");
                    seen[0] = sighting.get();
                    BlockPos at = new BlockPos(seen[0].at().x(), seen[0].at().y(), seen[0].at().z());
                    context.assertTrue(at.equals(ores[0]) || at.equals(ores[1]), "Where they are: " + at);
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Stopped");
                })
                .createAndAdd(() -> {
                    // Far off now, its diamond pickaxe gone, two diamonds in hand: it wants one more.
                    var player = f.player();
                    player.refreshPositionAndAngles(origin[0].getX() + AWAY + 0.5, origin[0].getY(), origin[0].getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    kit(f, false);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().getX() == origin[0].getX() + AWAY,
                        "Put far off"))
                // A second: what the goal knows of the world is reckoned afresh once a second, and is from where it stands now.
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 30, "A moment"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal again"))
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (steps.isEmpty() || !steps.getLast().equals(progress)) steps.add(progress);
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.DIAMOND) >= 3 || ItemMoves.count(inventory, Items.DIAMOND_PICKAXE) >= 1,
                            "A third diamond: " + progress);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    KindredMod.LOGGER.info("Atlas diamonds: its steps {}", steps);
                    context.assertTrue(steps.stream().anyMatch(s -> s.contains(PortalRunCampaign.LEAD_LABEL + "diamond ore")),
                            "It went back to where it saw them: " + steps);
                    context.assertTrue(steps.stream().noneMatch(s -> s.contains("Dig down") || s.contains("Branch mine") || s.contains("Explore the cave")
                            || s.contains("Tunnel")), "Not a search for diamonds: " + steps);
                    context.assertTrue(!world.getBlockState(ores[0]).isOf(Blocks.DIAMOND_ORE) || !world.getBlockState(ores[1]).isOf(Blocks.DIAMOND_ORE),
                            "One of the diamonds it saw mined");
                    context.assertTrue(f.player().getBlockPos().getSquaredDistance(ores[0]) <= 8 * 8, "Back where it saw them: " + f.player().getBlockPos());
                    context.assertTrue(f.player().isAlive(), "Alive");
                    chunks(world, origin[0].add(-8, 0, -8), origin[0].add(AWAY + 10, 0, 8), false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Its kit at the diamond stage: iron tools, a water bucket, the chestplate and shield; with or without a diamond pickaxe. */
    private static void kit(NativePaidSkillsGameTests.Fixture f, boolean diamondPickaxe) {
        var player = f.player();
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
        inventory.setStack(2, new ItemStack(Items.BREAD, 16));
        inventory.setStack(3, new ItemStack(Items.WATER_BUCKET));
        inventory.setStack(4, new ItemStack(Items.TORCH, 16));
        inventory.setStack(5, new ItemStack(Items.COBBLESTONE, 32));
        inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
        inventory.setStack(7, new ItemStack(Items.DIAMOND, 2));
        inventory.setStack(9, new ItemStack(Items.STICK, 8));
        inventory.setStack(10, new ItemStack(Items.COAL, 8));
        inventory.setStack(11, new ItemStack(Items.CRAFTING_TABLE));
        if (diamondPickaxe) inventory.setStack(8, new ItemStack(Items.DIAMOND_PICKAXE));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    /**
     * At night, a walk across open stone whose straight way runs through the sixteen-block cell its atlas marks for a
     * death there that night: it goes round the cell, about seven blocks longer, and arrives. Its body cuts the corners
     * of the way round, a block into the cell at most; straight through, it would go eight in.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "atlasNight", tickLimit = 900)
    public void atNightAWalkGoesRoundTheCellWhereItWasKilled(TestContext context) {
        walkPastAMarkedCell(context, "Wary", 18000, false);
    }

    /** The control: the same walk by day goes straight through the cell, as it does when nothing is marked. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "atlasDay", tickLimit = 900)
    public void byDayTheSameWalkGoesStraightThroughIt(TestContext context) {
        walkPastAMarkedCell(context, "Daylit", 1000, true);
    }

    private static void walkPastAMarkedCell(TestContext context, String name, long timeOfDay, boolean through) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] cell = new BlockPos[1];
        BlockPos[] start = new BlockPos[1];
        BlockPos[] goal = new BlockPos[1];
        int[] deepest = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().up(20);
                    // The cell east of the one it stands in, the walk along the cell's middle from ten blocks short of
                    // its west side to ten past its east side.
                    BlockPos c = cell[0] = new BlockPos((Math.floorDiv(p.getX(), 16) + 1) * 16, p.getY(), Math.floorDiv(p.getZ(), 16) * 16);
                    context.assertTrue(Math.floorDiv(p.getY(), 16) == Math.floorDiv(p.getY() + 1, 16), "Its feet and head in the cell's layer");
                    start[0] = c.add(-10, 0, 8);
                    goal[0] = c.add(26, 0, 8);
                    chunks(world, c.add(-16, 0, -8), c.add(32, 0, 24), true);
                    for (int x = -14; x <= 30; x++) for (int z = -6; z <= 22; z++) {
                        world.setBlockState(c.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 3; y++) world.setBlockState(c.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(timeOfDay);
                    var player = f.player();
                    player.refreshPositionAndAngles(start[0].getX() + 0.5, start[0].getY(), start[0].getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(start[0]), "At the start"))
                .createAndAdd(() -> context.assertTrue(context.getWorld().isNight() != through, "Night or day as the fixture asks: "
                        + context.getWorld().getTimeOfDay()))
                .createAndAdd(() -> context.assertTrue(f.runtime.atlasForFixtures(f.id) != null, "Its atlas read"))
                .createAndAdd(() -> {
                    // Killed in the middle of the cell that night (staged).
                    ServerWorld world = context.getWorld();
                    f.runtime.atlasForFixtures(f.id).harmed(PortalRunCampaign.place(world, cell[0].add(8, 0, 8)), true, world.getTime(), Atlas.DEATH);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeGoto(f.source, f.id, goal[0], 0.8) == 1, "Walk requested");
                })
                .createAndAdd(() -> {
                    BlockPos at = f.player().getBlockPos();
                    if (at.getX() >= cell[0].getX() && at.getX() < cell[0].getX() + 16 && at.getZ() >= cell[0].getZ() && at.getZ() < cell[0].getZ() + 16) {
                        int dx = Math.min(at.getX() - cell[0].getX(), cell[0].getX() + 15 - at.getX());
                        int dz = Math.min(at.getZ() - cell[0].getZ(), cell[0].getZ() + 15 - at.getZ());
                        deepest[0] = Math.max(deepest[0], 1 + Math.min(dx, dz));
                    }
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_goto"),
                            "Arrives: " + activity + " at " + f.player().getPos());
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Atlas {}: the walk went {} blocks into the marked cell at the deepest", name, deepest[0]);
                    // The body's line cuts a corner of its route, a block into the cell at most; through it, eight.
                    context.assertTrue(through ? deepest[0] >= 8 : deepest[0] <= 1, (through ? "By day straight through the middle of the cell"
                            : "At night round the cell it was killed in") + ": " + deepest[0] + " blocks in at the deepest");
                    context.assertTrue(f.player().getBlockPos().getSquaredDistance(goal[0]) <= 4, "Arrived: " + f.player().getBlockPos());
                    chunks(context.getWorld(), cell[0].add(-16, 0, -8), cell[0].add(32, 0, 24), false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The test server ticks only a test's own chunks; the chunks a fixture's walk crosses are kept loaded while it runs. */
    private static void chunks(ServerWorld world, BlockPos min, BlockPos max, boolean forced) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) world.setChunkForced(cx, cz, forced);
        }
    }
}
