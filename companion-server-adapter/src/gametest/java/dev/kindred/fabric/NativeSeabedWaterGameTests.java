package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.KelpBlock;
import net.minecraft.block.TallSeagrassBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Under the sea in its own tunnel, wanting water for the cast: the water it wants is over its head (F-35g). In E-40's
 * iteration 18 (`e40a18`) on 606 Kindred, at the iron stage with an empty bucket, stood at feet Y=56 in a tunnel two high
 * under one block of the seabed's dirt and four of the sea, and went "out from under the water: advance" to 196, 56, -126
 * and "to the wall" back to 195, some thirty times each in every one of its 3,600-tick stuck spells, 12 of them (the
 * console lines are on {@code claude/diag-e40a18-606-1111}, {@code diag/e40a18-53cd517-606-Kindred-pocket.txt}).
 */
public final class NativeSeabedWaterGameTests implements FabricGameTest {
    /**
     * Seed 606's own terrain, read offline (diagnosis only): x 190 to 200, y 54 to 62, z -130 to -122 round where Kindred
     * stood, by the layer from y 54 up, a row to a z from -130, a letter to an x from 190. {@code #} stone, {@code i}
     * diorite, {@code d} dirt, {@code G} gravel, {@code S} sand, {@code c} clay, {@code w} water, {@code g} seagrass,
     * {@code t} and {@code T} tall seagrass's lower and upper halves, {@code k} kelp's stem and {@code K} its top.
     */
    private static final String[][] GROUND_606 = {
            {"#####iiiiii", "######iiiii", "######iiiid", "########iii", "##########i", "###########", "###########", "###########", "###########"}, // y 54
            {"#########dd", "########ddd", "########ddd", "#######ddd#", "#######dd##", "######d####", "###########", "###########", "##########d"}, // y 55
            {"ddddddddddd", "ddddddddddd", "ddddddddddd", "ddddddddddd", "ddddddddddd", "ddddddddddd", "ddddddddGGG", "dddddddGGGG", "dddddddGGGG"}, // y 56
            {"ddddddddddd", "ddddddddddd", "ddddddddddw", "ddddddddddd", "dddddddddSd", "ddSSddSSSSS", "SSSSSSSSSSS", "SSSSSSSSSSS", "SSSSSSSSSSS"}, // y 57
            {"dddddddddww", "ddddddddwww", "ddddddddwkw", "cddddddkkwd", "ccdddddkwSd", "cccSddgSSSS", "ccSSSSSSSSS", "cSSSSSSSSSS", "SSSSSSSSSSw"}, // y 58
            {"wwwwwwwgkww", "wwwwwwwwwww", "wkkwwwwwwkw", "wwkwkwwkkww", "wwwwwwgkwww", "wwkwwwwwtww", "wwwwwwkkktw", "wwwwwwkwwwk", "wwwwwwwwwww"}, // y 59
            {"wwwwwwwwkww", "wwwwwwwwwww", "wkkwwwwwwkw", "wwkwkwwkkww", "wwwwwwwkwww", "wwkwwwwwTww", "wwwwwwKkkTw", "wwwwwwkwwwk", "wwwwwwwwwww"}, // y 60
            {"wwwwwwwwKww", "wwwwwwwwwww", "wKKwwwwwwKw", "wwKwKwwKKww", "wwwwwwwKwww", "wwKwwwwwwww", "wwwwwwwKKww", "wwwwwwKwwwK", "wwwwwwwwwww"}, // y 61
            {"wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww", "wwwwwwwwwww"}, // y 62
    };

    /** The sea's half width inside its walls: 41 by 41, about the open sea round where Kindred stood. */
    private static final int SEA = 20;
    /** "To the wall" lines allowed on the way: a few, not the run's thirty a spell. */
    private static final int TO_THE_WALL = 3;

    private static BlockState ground(char c) {
        return switch (c) {
            case '#' -> Blocks.STONE.getDefaultState();
            case 'i' -> Blocks.DIORITE.getDefaultState();
            case 'd' -> Blocks.DIRT.getDefaultState();
            case 'G' -> Blocks.GRAVEL.getDefaultState();
            case 'S' -> Blocks.SAND.getDefaultState();
            case 'c' -> Blocks.CLAY.getDefaultState();
            case 'w' -> Blocks.WATER.getDefaultState();
            case 'g' -> Blocks.SEAGRASS.getDefaultState();
            case 't' -> Blocks.TALL_SEAGRASS.getDefaultState().with(TallSeagrassBlock.HALF, DoubleBlockHalf.LOWER);
            case 'T' -> Blocks.TALL_SEAGRASS.getDefaultState().with(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);
            case 'k' -> Blocks.KELP_PLANT.getDefaultState();
            case 'K' -> Blocks.KELP.getDefaultState().with(KelpBlock.AGE, 21);
            default -> throw new IllegalArgumentException("No such ground: " + c);
        };
    }

    /**
     * The sea, from {@code o}, the cell Kindred stood in (195, 56, -126 on 606) at its feet, 52 below the world's: stone to
     * y 55, the seabed's three blocks to 58, the sea's four to 62, walled round 41 square so that it stays, with ground
     * outside the walls at the seabed's top. Round {@code o}, with {@code real}, seed 606's own ground from the block above;
     * elsewhere the same layers, the seabed of {@code bed}. Its tunnel two high, where Kindred stood and the cell east,
     * where it went to and fro; with {@code real}, its tunnels round there too: the corridor along z -126 from 190 that the
     * console shows it came along (190, 56, -126 to 196), and off it at 195 a way north to the block's edge and a step
     * south, as its tunnels turn north and south elsewhere in the console (196 to 197 from -133 to -126, 189 to 190 from
     * -127 to -123).
     */
    private static void sea(TestContext context, BlockPos o, boolean real, BlockState bed) {
        sea(context, o, o, real, bed);
    }

    /** As above, its tunnel at {@code at}. */
    private static void sea(TestContext context, BlockPos o, BlockPos at, boolean real, BlockState bed) {
        var world = context.getWorld();
        for (int x = -SEA - 2; x <= SEA + 2; x++) for (int z = -SEA - 2; z <= SEA + 2; z++) for (int y = -4; y <= 10; y++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            BlockState state;
            if (edge > SEA + 1) state = y <= 2 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
            else if (edge == SEA + 1) state = y <= 7 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
            else if (real && Math.abs(x) <= 5 && Math.abs(z) <= 4 && y >= -2 && y <= 6) state = ground(GROUND_606[y + 2][z + 4].charAt(x + 5));
            else state = y < 0 ? Blocks.STONE.getDefaultState() : y <= 2 ? bed : y <= 6 ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
            world.setBlockState(o.add(x, y, z), state);
        }
        var tunnel = new java.util.ArrayList<BlockPos>(List.of(at, at.east()));
        if (real) {
            for (int x = -5; x < 0; x++) tunnel.add(o.add(x, 0, 0));
            for (int z = -4; z < 0; z++) tunnel.add(o.add(0, 0, z));
            tunnel.add(o.add(0, 0, 1));
        }
        for (BlockPos cell : tunnel) for (int y = 0; y <= 1; y++) world.setBlockState(cell.up(y), Blocks.AIR.getDefaultState());
    }

    /** Kindred's kit on 606 at that point, as made: the cast route's with both buckets empty, cobblestone to seal with or none. */
    private static void kit(net.minecraft.server.network.ServerPlayerEntity player, boolean blocks) {
        var inventory = player.getInventory();
        inventory.clear();
        for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
        inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
        inventory.setStack(2, new ItemStack(Items.BUCKET, 2));
        inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
        inventory.setStack(4, new ItemStack(Items.BREAD, 16));
        inventory.setStack(5, new ItemStack(Items.STICK, 8));
        if (blocks) inventory.setStack(6, new ItemStack(Items.COBBLESTONE, 64));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.setAir(player.getMaxAir());
    }

    /**
     * The sea seen before it dug in, as a player sees it from the shore: stood on the sea's north wall, its campaign's own
     * sweep of what is round it, sight lines and all, until the water over its tunnel is among what it saw. Nothing is told
     * it; it saw the sea, not the seabed or what is under it.
     */
    private static void seeTheSea(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos o, BlockPos tunnel) {
        var player = f.player();
        player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 8, o.getZ() - SEA - 0.5, 0, 20);
        player.setVelocity(Vec3d.ZERO);
        var knowledge = f.runtime.campaignForTest(f.id).knowledge();
        for (int i = 0; i < 400 && !sawTheWaterOver(knowledge, tunnel); i++) knowledge.scan(player);
        context.assertTrue(sawTheWaterOver(knowledge, tunnel), "It saw the sea over its tunnel from the shore: "
                + knowledge.count(WorldKnowledge.Kind.WATER) + " water seen");
    }

    private static boolean sawTheWaterOver(WorldKnowledge knowledge, BlockPos o) {
        return knowledge.all(WorldKnowledge.Kind.WATER).stream().anyMatch(p -> p.getX() == o.getX() && p.getZ() == o.getZ() && p.getY() > o.getY() + 2);
    }

    /** Water in any open cell of the seabed's two lowest layers within four of {@code o}: its tunnel not dry. */
    private static String wet(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (BlockPos cell : BlockPos.iterate(o.add(-4, 0, -4), o.add(4, 1, 4))) {
            if (world.getFluidState(cell).isIn(FluidTags.WATER) && world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                return cell.subtract(o).toShortString();
            }
        }
        return null;
    }

    /** The open cells of the seabed's top layer within four of {@code o}: 606's kelp and seagrass where its bed is thin. */
    private static java.util.Set<BlockPos> openCeiling(TestContext context, BlockPos o) {
        var world = context.getWorld();
        var open = new java.util.HashSet<BlockPos>();
        for (BlockPos cell : BlockPos.iterate(o.add(-4, 2, -4), o.add(4, 2, 4))) {
            if (world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) open.add(cell.toImmutable());
        }
        return open;
    }

    /** A cell of the seabed's top layer within four of {@code o} open now that was not at first: its ceiling opened and not closed. */
    private static String opened(TestContext context, BlockPos o, java.util.Set<BlockPos> before) {
        for (BlockPos cell : openCeiling(context, o)) if (!before.contains(cell)) return cell.subtract(o).toShortString();
        return null;
    }

    /**
     * The water it wants is one block over its head: it opens that block, fills its bucket from the sea's source cell
     * behind it, puts a block of its own back at once, and its tunnel drains. Staged, from seed 606's own terrain, read
     * offline (diagnosis only; {@code claude/diag-e40a18-606-1111}): the ground round where Kindred stood as it was
     * generated, the open sea round it 41 square and walled, its tunnel's two cells, Kindred's kit as made at the step
     * that wants water for the cast, daylight held; and the sea seen from the shore before it is set in its tunnel, the
     * campaign's own sweep from the wall's top. Passes once its bucket holds water, its ceiling is closed and no water is
     * left in the tunnel, within 2,400 ticks, alive, with no more than {@value #TO_THE_WALL} "to the wall" lines on the way
     * and no action left to reconcile.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "seabed606", tickLimit = 3200)
    public void underTheSeaTheWaterOverItsHeadFillsItsBucketAndTheTunnelIsResealed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3100);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int[] watched = new int[1];
        int[] walls = {0};
        String[] last = {""};
        int[] filledAt = {-1};
        String[] wetSeen = {null};
        var bedOpen = new java.util.HashSet<BlockPos>();
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Seabed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-SEA - 2, -4, -SEA - 2), o.add(SEA + 2, 10, SEA + 2));
                    sea(context, o, true, Blocks.DIRT.getDefaultState());
                    bedOpen.addAll(openCeiling(context, o));
                    context.getWorld().setTimeOfDay(6000);
                    kit(f.player(), true);
                    seeTheSea(context, f, o, o);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In its tunnel: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), (player == null ? "No body" : "The player died ("
                            + (player.getRecentDamageSource() == null ? "no damage source" : player.getRecentDamageSource().getName()) + " at "
                            + player.getBlockPos().subtract(o).toShortString() + ")") + "; " + seen);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (progress.endsWith(": to the wall") && !progress.equals(last[0])) walls[0]++;
                    if (!progress.equals(last[0])) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = progress;
                    watched[0]++;
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(walls[0] <= TO_THE_WALL, "To and fro under the sea: " + walls[0] + " \"to the wall\" lines in "
                            + watched[0] + " ticks; " + tail);
                    context.assertTrue(watched[0] <= 2400, "No water in its bucket, or its tunnel not resealed and dry, after 2,400 ticks: "
                            + tail + " now " + progress + " @ " + player.getBlockPos().subtract(o).toShortString());
                    // Whether the sea came into its tunnel at all, for the log: the tunnel's dryness after is asked below.
                    String water = wet(context, o);
                    if (water != null && wetSeen[0] == null) wetSeen[0] = water + " at tick " + watched[0];
                    boolean filled = ItemMoves.count(player.getInventory(), Items.WATER_BUCKET) > 0;
                    if (filled && filledAt[0] < 0) filledAt[0] = watched[0];
                    context.assertTrue(filled, "Its bucket not filled yet");
                    String open = opened(context, o, bedOpen);
                    context.assertTrue(open == null, "Its ceiling open at " + open);
                    context.assertTrue(water == null, "Water left in its tunnel at " + water);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Seabed 606: bucket filled after {} ticks, tunnel resealed and dry after {} (water first in it: {}), {} \"to the wall\" lines; {}",
                            filledAt[0], watched[0], wetSeen[0], walls[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * As above with nothing to put back over the opening: it never opens its ceiling into the sea, and does not go to and
     * fro under it either; the climb out from under the sea ends where the sea is too wide to get out from under, and the
     * goal goes on from there. Staged as above, the seabed of clay, as at 606's west edge (190 to 192, 58, -127 to -124),
     * which gives no block it could seal with when dug; no cobblestone; its tunnel five south of the sea's middle, so that
     * the way its climb first heads runs on under the sea past the climb's reach; the sea seen from the shore. Passes once
     * its climb has said so, and 400 ticks on, alive, its ceiling never opened, no more than {@value #TO_THE_WALL} "to the
     * wall" lines.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "seabed606Unsealed", tickLimit = 3200)
    public void underTheSeaWithNothingToResealWithItLeavesTheCeilingAndHandsTheClimbBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3100);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        // Its tunnel five south of the sea's middle: twenty-five blocks of sea north of it, the way its climb first heads.
        BlockPos at = o.south(5);
        int[] watched = new int[1];
        int[] walls = {0};
        int[] handedBack = {-1};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "SeabedBare"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-SEA - 2, -4, -SEA - 2), o.add(SEA + 2, 10, SEA + 2));
                    sea(context, o, at, false, Blocks.CLAY.getDefaultState());
                    context.getWorld().setTimeOfDay(6000);
                    kit(f.player(), false);
                    seeTheSea(context, f, o, at);
                    var player = f.player();
                    player.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(at), "In its tunnel: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), (player == null ? "No body" : "The player died ("
                            + (player.getRecentDamageSource() == null ? "no damage source" : player.getRecentDamageSource().getName()) + " at "
                            + player.getBlockPos().subtract(o).toShortString() + ")") + "; " + seen);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (progress.endsWith(": to the wall") && !progress.equals(last[0])) walls[0]++;
                    if (!progress.equals(last[0])) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = progress;
                    watched[0]++;
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(walls[0] <= TO_THE_WALL, "To and fro under the sea: " + walls[0] + " \"to the wall\" lines in "
                            + watched[0] + " ticks; " + tail);
                    // Over the whole sea, not only round where it began: its climb goes along under the water.
                    var world = context.getWorld();
                    for (BlockPos cell : BlockPos.iterate(o.add(-SEA, 2, -SEA), o.add(SEA, 2, SEA))) {
                        if (world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                            context.assertTrue(false, "It opened its ceiling into the sea at " + cell.subtract(o).toShortString() + "; " + tail);
                        }
                    }
                    if (handedBack[0] < 0 && f.runtime.campaignForTest(f.id).lastEvents(16).stream().anyMatch(e -> e.contains("too wide to get out from under"))) {
                        handedBack[0] = watched[0];
                    }
                    context.assertTrue(watched[0] <= 2800, "The climb never handed back in 2,800 ticks: " + tail + " now " + progress + " @ "
                            + player.getBlockPos().subtract(o).toShortString());
                    context.assertTrue(handedBack[0] >= 0 && watched[0] >= handedBack[0] + 400, "Not handed back, and 400 ticks on, yet");
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Seabed 606, nothing to reseal with: handed back after {} ticks, {} \"to the wall\" lines; {}",
                            handedBack[0], walls[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
