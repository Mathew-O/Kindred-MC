package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The bed it carries goes down only in a pocket with no way in left (F-25c4di1). In E-40 rung c (`e40c4-b23acc0`, #334)
 * on 303 Kindred, the first block of its seal was refused and its bed went down in a pocket still open; woken among mobs,
 * it died 23 times, each time back at that bed. On iteration 23 (`e40a23-3115b1c`, #352) on 101 Sol, the pocket was never
 * sealed at all: its bed went down at −377, 31, −143 in a cave and it died there 12 times (RUNNER 2's
 * {@code claude/corpus-data-e40c4-1115} and {@code claude/corpus-data-e40a23-1115}). Before, {@code carriedBedNight} set
 * the bed down whenever its seal gave nothing to do, and the seal gives nothing for a cave too open to seal, or when no
 * block will go in, as well as for a pocket already sealed.
 */
public final class NativeBedPocketGameTests implements FabricGameTest {
    /** How long a night it is watched for a bed set down. */
    private static final int WATCH = 600;

    /** Rock round {@code o}, with no sky: x −6 to 8, z −5 to 5, y −2 to 6. */
    private static void rock(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-6, -2, -5), o.add(8, 6, 5));
        for (BlockPos cell : BlockPos.iterate(o.add(-6, -2, -5), o.add(8, 6, 5))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
    }

    private static void air(TestContext context, BlockPos from, BlockPos to) {
        for (BlockPos cell : BlockPos.iterate(from, to)) context.getWorld().setBlockState(cell, Blocks.AIR.getDefaultState());
    }

    /**
     * Its night with home out of reach and a bed in its pack, in the staged ground {@code stage} lays round its feet at
     * {@code o}; fails at the first bed block seen within the staged rock, passes after {@value #WATCH} ticks of night with
     * none, alive.
     */
    private static void night(TestContext context, String name, java.util.function.Consumer<BlockPos> stage) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, WATCH + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        int[] watched = new int[1];
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    stage.accept(o);
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // Its bed in its hotbar and nothing else: no block to seal with. No home bed: home is out of reach.
                    player.getInventory().setStack(0, new ItemStack(Items.WHITE_BED));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 10);
                    player.setVelocity(Vec3d.ZERO);
                    context.getWorld().setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> {
                    // Night held, so no sleep of its own ends the watch early.
                    context.getWorld().setTimeOfDay(14_000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    String note = String.valueOf(f.runtime.nativeLastResult(f.id));
                    if (!note.equals(last[0])) seen.append(watched[0]).append(": ").append(note).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = note;
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    for (BlockPos cell : BlockPos.iterate(o.add(-6, -2, -5), o.add(8, 6, 5))) {
                        if (context.getWorld().getBlockState(cell).isIn(BlockTags.BEDS)) {
                            throw new net.minecraft.test.GameTestException("Its bed set down in a pocket with a way in at "
                                    + cell.subtract(o).toShortString() + ": " + tail);
                        }
                    }
                    context.assertTrue(++watched[0] > WATCH, "Watching the night");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getInventory().count(Items.WHITE_BED) == 1, "The bed still in its pack");
                    KindredMod.LOGGER.info("Bed pocket ({}): no bed set down in {} ticks of night; {}; {}", name, WATCH, f.runtime.bedChoreForTest(f.id), seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: an AI player far from home at night, with its bed but standing at the edge of a cave it cannot wall
     * off, does not set its bed down there, where it would wake among the cave's mobs and come back to it each time it died.
     *
     * Technical: F-25c4di1, as e40a23's 101 Sol (above). STAGED: rock round it with no sky; its feet on the floor of a cave
     * five wide, seven deep and three high, open north, south, west and over its head (seven ways in, more than its seal
     * takes on), with room for a bed east of its feet dug two long into the rock; its bed and nothing else; no home bed;
     * night held; no goal, its own play. Fails at the first bed block set down in the staged rock; passes after
     * {@value #WATCH} ticks of night with none, alive and the bed still in its pack.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedPocketCave", tickLimit = WATCH + 800)
    public void atACaveItCannotSealItDoesNotSetItsBedDown(TestContext context) {
        night(context, "Caver", o -> {
            rock(context, o);
            air(context, o.add(-4, 0, -3), o.add(0, 2, 3));
            air(context, o.add(1, 0, 0), o.add(2, 1, 0));
        });
    }

    /**
     * In plain terms: an AI player far from home at night in a tunnel with one way in and nothing to block it with does not
     * set its bed down there, open to whatever walks in.
     *
     * Technical: F-25c4di1, as e40c4's 303 Kindred (above), its seal refused. STAGED: rock round it with no sky; its feet at
     * the end of a tunnel one wide and two high running five west (one way in, two cells), with room for a bed open north of
     * its feet; its bed and nothing else, so no block to seal with; no home bed; night held; no goal, its own play. Fails at the
     * first bed block set down in the staged rock; passes after {@value #WATCH} ticks of night with none, alive and the bed
     * still in its pack.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedPocketOpen", tickLimit = WATCH + 800)
    public void withAWayInItCannotCloseItDoesNotSetItsBedDown(TestContext context) {
        night(context, "Tunneller", o -> {
            rock(context, o);
            air(context, o.add(-5, 0, 0), o.add(0, 1, 0));
            // Its bed's room open north of its feet, the first way its choice of room looks (dug by hand, a room in the
            // rock takes the watch's whole night).
            air(context, o.add(0, 0, -2), o.add(0, 1, -1));
        });
    }

    /**
     * In plain terms: an AI player sealed in for the night with its bed and its torches, in a pocket whose only open cells
     * beside it are where its bed is to go, lights the pocket without putting its torch where the bed goes, and sets its bed
     * down; it does not put a torch there, break it to make room for the bed and put it back again, lap after lap.
     *
     * Technical: F-22c127, from E-40 iteration 30 (`e40a30-63b97ac`, RUNNER 2's {@code claude/corpus-data-e40a30-consoles-1123}):
     * on 303 Kindred, sealed in at −167, −54, 128, "light the way - Place a torch at −167, −54, 127" and "Room for its bed at
     * −167, −54, 127" took turns 541 times in 2 minutes 46 seconds; on 303 Echo at 183, 36, 18, 547 times. Its torch went in
     * the cell north of its feet, the bed's foot, and the bed's room broke it. STAGED: rock round it with no sky; its feet in a
     * cell one wide and two high, sealed, in rock it digs its bed's room from, east of its feet, obsidian north; its bed, eight torches and its stone tools
     * in its hotbar; no home bed; night held; the portal goal under way. Fails once a torch has gone into the bed's
     * room three times; passes once its bed is down in that room within {@value #WATCH} ticks, alive.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedPocketTorch", tickLimit = WATCH + 800)
    public void sealedInWithItsBedAndTorchesItLightsTheNightAndSetsItsBedDownNotItsTorchInTheBedsRoom(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, WATCH + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        var room = java.util.List.of(o.add(1, 0, 0), o.add(1, 1, 0), o.add(2, 0, 0), o.add(2, 1, 0));
        int[] watched = new int[1], torched = new int[1];
        boolean[] torchThere = {false};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Lamplighter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    rock(context, o);
                    // Its feet's cell only: the bed's room east of it is rock it digs, as 303 Kindred's was. North, the way its
                    // digging heads before it has dug anywhere and the one cell a torch never goes, obsidian it does not dig
                    // for a bed, so the room is east, where on 303 its heading (west) let a torch go.
                    air(context, o, o.add(0, 1, 0));
                    context.getWorld().setBlockState(o.add(0, 0, -1), Blocks.OBSIDIAN.getDefaultState());
                    context.getWorld().setBlockState(o.add(0, 1, -1), Blocks.OBSIDIAN.getDefaultState());
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // Its stone tools, as on the iron stage 303 Kindred was on.
                    player.getInventory().setStack(0, new ItemStack(Items.WHITE_BED));
                    player.getInventory().setStack(1, new ItemStack(Items.TORCH, 8));
                    player.getInventory().setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(3, new ItemStack(Items.STONE_AXE));
                    player.getInventory().setStack(4, new ItemStack(Items.STONE_SWORD));
                    player.getInventory().setStack(5, new ItemStack(Items.STONE_SHOVEL));
                    player.getInventory().setStack(6, new ItemStack(Items.BREAD, 8));
                    player.getInventory().setStack(7, new ItemStack(Items.CRAFTING_TABLE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 10);
                    player.setVelocity(Vec3d.ZERO);
                    context.getWorld().setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(14_000);
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died; " + seen);
                    String note = String.valueOf(f.runtime.nativeLastResult(f.id));
                    if (!note.equals(last[0])) seen.append(watched[0]).append(": ").append(note).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = note;
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    boolean torch = room.stream().anyMatch(cell -> world.getBlockState(cell).isOf(Blocks.TORCH) || world.getBlockState(cell).isOf(Blocks.WALL_TORCH));
                    if (torch && !torchThere[0]) torched[0]++;
                    torchThere[0] = torch;
                    context.assertTrue(torched[0] < 3, "A torch put in its bed's room " + torched[0] + " times, its bed not down: " + tail);
                    boolean bed = room.stream().anyMatch(cell -> world.getBlockState(cell).isIn(BlockTags.BEDS));
                    ++watched[0];
                    context.assertTrue(bed, "Its bed down in its room (" + watched[0] + " ticks, a torch there " + torched[0] + " times): " + tail);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Bed pocket (torch): its bed down after {} ticks, a torch in its room {} times; {}", watched[0], torched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
