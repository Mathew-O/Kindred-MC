package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** The Nether-portal goal driving real native skills through the runtime: no grants, no repositioning. */
public final class NativePortalCampaignGameTests implements FabricGameTest {

    /**
     * A husk comes after the player while it works on the goal: the work stops and the player fights it to the end
     * in one recorded fight, chasing and striking with its own sword.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalStopsToFightAMobThatIsAfterThePlayer(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.HuskEntity[] husk = new net.minecraft.entity.mob.HuskEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Duelist");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    husk[0] = context.spawnMob(net.minecraft.entity.EntityType.HUSK, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    husk[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertFalse(husk[0].isAlive(), "The husk was fought and killed"))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "The player survived");
                    var sword = f.player().getInventory().getStack(0);
                    context.assertTrue(sword.isOf(Items.IRON_SWORD) && sword.getDamage() > 0, "Its own sword did the work: " + sword);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The last stage of the goal: holding ten obsidian, four cobblestone and its own flint and steel, the player
     * chooses a site, places the 4×5 frame block by block with cobblestone corners, and lights it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalBuildsAndLightsThePortalFrameFromOwnedBlocks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Gatewright");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.OBSIDIAN, 10));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 4));
                    inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(4, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(5, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.IRON_SWORD)); // Earlier stages are done; nothing else to craft.
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.OBSIDIAN) == 0 && ItemMoves.count(inventory, Items.COBBLESTONE) == 0,
                            "All ten obsidian and four corners were placed from the inventory");
                })
                .createAndAdd(() -> {
                    BlockPos feet = f.player().getBlockPos();
                    long portalBlocks = BlockPos.streamOutwards(feet, 6, 6, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.NETHER_PORTAL)).count();
                    context.assertTrue(portalBlocks == 6, "A lit 2x3 portal: " + portalBlocks);
                    int wear = -1;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.FLINT_AND_STEEL)) wear = f.player().getInventory().getStack(s).getDamage();
                    }
                    context.assertTrue(wear == 1, "Lit with its own flint and steel, one use of wear: " + wear);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A lava source recessed below its shore, the usual cave case. With the goal set and only a diamond pickaxe and
     * a water bucket for the job, the player pours onto the shore so the water runs over the lava, mines the
     * obsidian it hardens while the water guards the hole, and takes its water back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos lava = context.getAbsolutePos(new BlockPos(5, 1, 3));
        BlockPos shore = context.getAbsolutePos(new BlockPos(4, 2, 3));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Walls keep the poured water inside this test's area.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(5, 0, 3, Blocks.STONE);
                    context.setBlockState(5, 1, 3, Blocks.LAVA);
                    roof(context);
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the shore"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(2, new ItemStack(Items.IRON_INGOT)); // Flint and steel's iron is already in hand.
                    inventory.setStack(3, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getBlockState(lava).isOf(Blocks.OBSIDIAN)
                        || ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) > 0, "The poured water hardened the lava"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.OBSIDIAN) == 1, "The obsidian was mined and picked up");
                    context.assertTrue(ItemMoves.count(inventory, Items.WATER_BUCKET) == 1 && ItemMoves.count(inventory, Items.BUCKET) == 0,
                            "The water was taken back into the bucket");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertFalse(world.getFluidState(lava).isIn(FluidTags.LAVA), "No lava left at the source");
                    context.assertFalse(world.getFluidState(shore).isStill() && world.getFluidState(shore).isIn(FluidTags.WATER),
                            "No water source left on the shore");
                    int pickDamage = -1;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.DIAMOND_PICKAXE)) pickDamage = f.player().getInventory().getStack(s).getDamage();
                    }
                    context.assertTrue(pickDamage >= 1, "Vanilla wear from mining: " + pickDamage);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A husk out of reach beyond a lava moat: the goal starts a fight with it, but the chase stops at the edge
     * instead of walking into the lava, and the fight ends as unsafe. The husk is too far for a strike across.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 700)
    public void goalFightStopsAtLavaRatherThanChasingIntoIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.HuskEntity[] husk = new net.minecraft.entity.mob.HuskEntity[1];
        boolean[] burned = new boolean[1];
        double[] closest = {Double.MAX_VALUE};
        Runnable watch = () -> {
            var player = f.player();
            if (player == null || husk[0] == null) return;
            burned[0] |= player.isInLava() || player.isOnFire() || !player.isAlive();
            closest[0] = Math.min(closest[0], Math.hypot(player.getX() - husk[0].getX(), player.getZ() - husk[0].getZ()));
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    // A moat two blocks wide around the far corner, where the husk stands.
                    for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) {
                        if (x < 4 && z < 4 || x == 6 && z == 6) continue;
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.LAVA);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Wary");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    husk[0] = context.spawnMob(net.minecraft.entity.EntityType.HUSK, new net.minecraft.util.math.Vec3d(6.5, 2, 6.5));
                    husk[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Fight husk"), "The goal took up the fight");
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertFalse(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Fight husk"), "The fight ended");
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                    context.assertFalse(burned[0], "The player never stepped into the lava");
                    // Standing anywhere on the island keeps at least 3.6 blocks to the husk; a step onto the lava would not.
                    context.assertTrue(closest[0] >= 3.6, "It stopped at the edge: " + closest[0]);
                    context.assertTrue(husk[0].isAlive(), "The husk stayed out of reach");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A skeleton shooting across a lava moat it cannot be reached over: the fight stops at the edge, and the
     * player then takes cover behind its own cobblestone, building up from the floor until the skeleton has no
     * line of fire, instead of going on with its work under fire.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalTakesCoverFromASkeletonItCannotReach(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.SkeletonEntity[] skeleton = new net.minecraft.entity.mob.SkeletonEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) {
                        if (x < 4 && z < 4 || x == 6 && z == 6) continue;
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.LAVA);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Ducker");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    skeleton[0] = context.spawnMob(net.minecraft.entity.EntityType.SKELETON, new net.minecraft.util.math.Vec3d(6.5, 2, 6.5));
                    skeleton[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Take cover from skeleton"),
                        "Took cover once the fight could not reach it"))
                .createAndAdd(() -> context.assertFalse(f.player().canSee(skeleton[0]), "No line of fire left"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && !player.isInLava(), "Alive and out of the lava");
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.COBBLESTONE) < 16, "The cover is its own cobblestone");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Night in a pit open to the sky with an empty inventory, as after a respawn: the goal digs one staircase step
     * into the hill around it, whichever way it faces, and once under cover seals both openings with the dirt the
     * dig gave it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightShelter", tickLimit = 900)
    public void goalDigsInAndSealsTheShelterAtNight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Runnable night = () -> context.getWorld().setTimeOfDay(14000);
        BlockPos[] pit = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = -1; y <= 1; y++) context.setBlockState(x, y, z, Blocks.DIRT);
                    night.run();
                    f.runtime.createNative(f.source, "Burrow");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // The hill rises around wherever the player stood up: a pit three deep, open to the sky. Absolute
                    // positions throughout, since a rotated test's relative positions do not round-trip.
                    pit[0] = f.player().getBlockPos().toImmutable();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = 2; y <= 4; y++) {
                        BlockPos cell = context.getAbsolutePos(new BlockPos(x, y, z));
                        if (cell.getX() == pit[0].getX() && cell.getZ() == pit[0].getZ()) continue;
                        context.getWorld().setBlockState(cell, Blocks.DIRT.getDefaultState());
                    }
                    night.run();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    night.run();
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    context.assertTrue(feet.getY() == pit[0].getY() - 1 && feet.getManhattanDistance(pit[0].down()) == 1, "One step into the hill: " + feet);
                    context.assertTrue(world.getBlockState(pit[0]).isOf(Blocks.DIRT) && world.getBlockState(feet.up(2)).isOf(Blocks.DIRT),
                            "Both openings were sealed");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    var around = new java.util.ArrayList<>(java.util.List.of(feet.down(), feet.up(2)));
                    for (Direction side : Direction.Type.HORIZONTAL) {
                        around.add(feet.offset(side));
                        around.add(feet.up().offset(side));
                    }
                    for (BlockPos cell : around) {
                        context.assertTrue(world.getBlockState(cell).isSolidBlock(world, cell), "Enclosed: " + cell);
                    }
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() >= 20, "Unhurt");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A dark L-shaped cave passage with iron ore out of sight around the corner. At the iron stage the goal lights
     * the dark with a torch crafted from its own coal and a spare stick, walks to the part of the cave it can see,
     * spots the ore from the corner, and mines it, rather than digging a tunnel from where it stood.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalExploresACaveAndMinesIronItCouldNotSeeAtFirst(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] ore = new BlockPos[1];
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute layout around wherever the player stood up. The first leg runs toward the near edge
                    // of a 4-block knowledge cell, so the corner lies in a cave cell the player has not walked yet.
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    Direction alongX = (p.getX() & 3) >= 2 ? Direction.EAST : Direction.WEST;
                    Direction alongZ = (p.getZ() & 3) >= 2 ? Direction.SOUTH : Direction.NORTH;
                    for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 3; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    // Sealed inside the stone block, so no daylight reaches it.
                    var passage = new java.util.ArrayList<BlockPos>();
                    for (int i = 0; i <= 2; i++) passage.add(p.offset(alongX, i));
                    for (int i = 1; i <= 2; i++) passage.add(p.offset(alongX, 2).offset(alongZ, i));
                    for (BlockPos cell : passage) {
                        world.setBlockState(cell, Blocks.AIR.getDefaultState());
                        world.setBlockState(cell.up(), Blocks.AIR.getDefaultState());
                    }
                    ore[0] = p.offset(alongX, 2).offset(alongZ, 3);
                    world.setBlockState(ore[0], Blocks.IRON_ORE.getDefaultState());
                })
                // A cave is old ground, not freshly built: let the light engine darken the passage first.
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 6));
                    inventory.setStack(5, new ItemStack(Items.COAL, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.RAW_IRON) >= 1,
                        "Found and mined the iron around the corner"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertFalse(world.getBlockState(ore[0]).isOf(Blocks.IRON_ORE), "The ore is gone");
                    long torches = BlockPos.streamOutwards(ore[0], 6, 3, 6).filter(pos -> world.getBlockState(pos).isOf(Blocks.TORCH)).count();
                    context.assertTrue(torches >= 1, "It lit the dark passage with its own torch");
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.COAL) == 3 && ItemMoves.count(inventory, Items.STICK) == 5,
                            "One coal and one spare stick became torches: coal " + ItemMoves.count(inventory, Items.COAL)
                                    + ", sticks " + ItemMoves.count(inventory, Items.STICK));
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Fuel and ore matched: with fourteen raw iron and one coal (eight items' worth), the goal loads eight and keeps
     * six. Then, as if a load had been left in a cold furnace, the six are put in the furnace with no fuel: the
     * goal does not wait by it, and when it next holds coal it goes back and adds it as fuel. Obsidian walls keep
     * its search for coal inside the plot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 6000)
    public void goalRefuelsAFurnaceThatWentColdWithIronStillInIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] furnace = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.OBSIDIAN);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Stoker");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 4));
                    inventory.setStack(5, new ItemStack(Items.RAW_IRON, 14));
                    inventory.setStack(6, new ItemStack(Items.IRON_INGOT));
                    inventory.setStack(7, new ItemStack(Items.COAL));
                    inventory.setStack(8, new ItemStack(Items.FURNACE));
                    inventory.setStack(9, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    furnace[0] = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 3, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.FURNACE)).findFirst().map(BlockPos::toImmutable).orElse(null);
                    context.assertTrue(furnace[0] != null, "It placed its own furnace");
                })
                .createAndAdd(() -> {
                    var block = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) context.getWorld().getBlockEntity(furnace[0]);
                    var inventory = f.player().getInventory();
                    context.assertTrue(block.getStack(0).getCount() + block.getStack(2).getCount() + ItemMoves.count(inventory, Items.IRON_INGOT) == 9
                                    && ItemMoves.count(inventory, Items.RAW_IRON) == 6 && ItemMoves.count(inventory, Items.COAL) == 0,
                            "One coal took eight raw iron and the other six stayed in the inventory: furnace " + block.getStack(0)
                                    + ", raw iron held " + ItemMoves.count(inventory, Items.RAW_IRON));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var block = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) world.getBlockEntity(furnace[0]);
                    boolean lit = world.getBlockState(furnace[0]).get(net.minecraft.block.AbstractFurnaceBlock.LIT);
                    context.assertTrue(block.getStack(0).isEmpty() && block.getStack(2).isEmpty() && !lit, "The eight are smelted and taken");
                    // A load left in a cold furnace: its own six raw iron moved into the input, and the coal it would find next.
                    var inventory = f.player().getInventory();
                    for (int slot = 0; slot < 36; slot++) {
                        if (inventory.getStack(slot).isOf(Items.RAW_IRON)) block.setStack(0, inventory.removeStack(slot));
                    }
                    inventory.insertStack(new ItemStack(Items.COAL));
                })
                .createAndAdd(() -> {
                    var block = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) context.getWorld().getBlockEntity(furnace[0]);
                    context.assertTrue(block.getStack(0).isEmpty() && ItemMoves.count(f.player().getInventory(), Items.RAW_IRON) == 0,
                            "It went back, added the coal, and the rest was smelted: input " + block.getStack(0));
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.COAL) == 0, "Its coal became the fuel");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The night pit again, but with water under the whole floor, as over an aquifer: every staircase step would
     * end in the water, so the goal turns away from each without digging anything, and no hole is opened into it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightShelter", tickLimit = 900)
    public void goalDoesNotDigAStaircaseIntoWaterBelow(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Runnable night = () -> context.getWorld().setTimeOfDay(14000);
        BlockPos[] pit = new BlockPos[1];
        int[] waited = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        context.setBlockState(x, -1, z, Blocks.STONE);
                        context.setBlockState(x, 0, z, Blocks.WATER);
                        context.setBlockState(x, 1, z, Blocks.DIRT);
                    }
                    night.run();
                    f.runtime.createNative(f.source, "Wader");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    pit[0] = f.player().getBlockPos().toImmutable();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = 2; y <= 4; y++) {
                        BlockPos cell = context.getAbsolutePos(new BlockPos(x, y, z));
                        if (cell.getX() == pit[0].getX() && cell.getZ() == pit[0].getZ()) continue;
                        context.getWorld().setBlockState(cell, Blocks.DIRT.getDefaultState());
                    }
                    night.run();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    night.run();
                    context.assertTrue(++waited[0] >= 200, "Giving the goal time to try every way down");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (Direction side : Direction.Type.HORIZONTAL) {
                        BlockPos step = pit[0].down().offset(side);
                        context.assertTrue(world.getBlockState(step).isOf(Blocks.DIRT), "No step dug toward the water: " + step);
                    }
                    context.assertTrue(f.player().isAlive() && !f.player().isTouchingWater(), "Dry and alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The progress watchdog: sealed in an obsidian room it cannot dig with a stone pickaxe, the goal looks around
     * the room and then has nothing feasible. After {@link PortalRunCampaign#STUCK_TICKS} of game time with no
     * progress it gives up on the spot and tries to leave for somewhere else, rather than waiting forever.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 9000)
    public void goalLeavesForElsewhereAfterMakingNoProgress(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] gaveUp = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        context.setBlockState(x, 1, z, Blocks.OBSIDIAN);
                        context.setBlockState(x, 5, z, Blocks.OBSIDIAN);
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.OBSIDIAN);
                    }
                    f.runtime.createNative(f.source, "Restless");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 4));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    gaveUp[0] |= progress.contains("no progress here for a while") || progress.contains("tried elsewhere");
                    context.assertTrue(gaveUp[0], "The watchdog gave up on the spot and tried somewhere else");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Stranded on a one-block pillar five blocks above the floor, with every way off a fall the navigator would not
     * take: the goal jumps down, taking vanilla fall damage it can afford, rather than wait for ever.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 3000)
    public void goalJumpsDownFromALedgeWhenNothingElseIsFeasible(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] top = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Leaper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Everything but the column under the player sinks to the lowest floor: a pillar it stands on.
                    var world = context.getWorld();
                    top[0] = f.player().getBlockPos().toImmutable();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        BlockPos column = context.getAbsolutePos(new BlockPos(x, 0, z));
                        boolean pillar = column.getX() == top[0].getX() && column.getZ() == top[0].getZ();
                        for (int y = -3; y <= 1; y++) {
                            world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), pillar ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, -4, z)), Blocks.STONE.getDefaultState());
                    }
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 4));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getBlockY() <= top[0].getY() - 4 && f.player().isOnGround(),
                        "Jumped down to the floor: " + f.player().getBlockPos()))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 10, "Took only damage it could afford: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A roof over the fixture: the goal treats it as underground, so a night set by another test changes nothing here. */
    private static void roof(TestContext context) {
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) context.setBlockState(x, 7, z, Blocks.STONE);
    }
}
