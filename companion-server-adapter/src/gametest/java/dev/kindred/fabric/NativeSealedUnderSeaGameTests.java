package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Sealed in a shaft under the sea with no pickaxe (F-25c4dg). In E-40's iteration 22 (`e40a22-d2389c9`, #310) on seed 101
 * Kindred, with no pickaxe of any kind, gathering logs for sticks, dug out from under the water by hand and pillared twice
 * up a shaft one block across: its feet at -244, 46, -79, its two dirt blocks under it, and over its head at 48 a sunken
 * ship's dark oak plank with the sea over that. From then on its climb for logs offered nothing, and "Head back up toward
 * home" was "No walkable route" for the rest of the run, some 96,000 ticks (the console and the spell replays are on
 * {@code claude/corpus-data-e40a22-1114}).
 */
public final class NativeSealedUnderSeaGameTests implements FabricGameTest {
    /** The sea's half width inside its walls. */
    private static final int SEA = 12;
    /** The sea's top, above its feet: the replay's box shows water to 54, eight over its feet; sea level is 62, sixteen over. */
    private static final int SURFACE = 16;

    /**
     * The scene, from {@code o}, the cell its feet were in: stone to its feet's layer, then a layer of gravel, then the sea to
     * {@link #SURFACE}, walled round with stone to the sea's top so that dry ground is within reach once out of the water;
     * its shaft two high at {@code o}, its two dirt blocks under it, the plank over it.
     */
    private static void scene(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (int x = -SEA - 2; x <= SEA + 2; x++) for (int z = -SEA - 2; z <= SEA + 2; z++) for (int y = -6; y <= SURFACE + 2; y++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            BlockState state;
            if (edge > SEA) state = y <= SURFACE ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
            else if (y <= 0) state = Blocks.STONE.getDefaultState();
            else if (y == 1) state = Blocks.GRAVEL.getDefaultState();
            else if (y <= SURFACE) state = Blocks.WATER.getDefaultState();
            else state = Blocks.AIR.getDefaultState();
            world.setBlockState(o.add(x, y, z), state);
        }
        world.setBlockState(o.down(2), Blocks.DIRT.getDefaultState());
        world.setBlockState(o.down(), Blocks.DIRT.getDefaultState());
        world.setBlockState(o, Blocks.AIR.getDefaultState());
        world.setBlockState(o.up(), Blocks.AIR.getDefaultState());
        world.setBlockState(o.up(2), Blocks.DARK_OAK_PLANKS.getDefaultState());
    }

    /** What Kindred held at spell 1 (Kindred-47820's replay), slot for slot: no pickaxe. */
    private static void kit(net.minecraft.server.network.ServerPlayerEntity player) {
        var inventory = player.getInventory();
        inventory.clear();
        for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
        Object[][] held = {{0, Items.COOKED_PORKCHOP, 3}, {1, Items.COAL, 4}, {2, Items.DIRT, 14}, {3, Items.COBBLESTONE, 64},
                {4, Items.STONE_SWORD, 1}, {5, Items.STICK, 1}, {7, Items.GRAVEL, 40}, {8, Items.GRANITE, 11}, {9, Items.COBBLESTONE, 64},
                {10, Items.OAK_PLANKS, 1}, {11, Items.CRAFTING_TABLE, 1}, {12, Items.COBBLESTONE, 64}, {13, Items.DIORITE, 64},
                {14, Items.GRANITE, 64}, {15, Items.ANDESITE, 64}, {16, Items.COBBLESTONE, 64}, {17, Items.COBBLESTONE, 64},
                {18, Items.COBBLESTONE, 64}, {19, Items.GRANITE, 64}, {20, Items.COBBLESTONE, 64}, {21, Items.RAW_COPPER, 7},
                {22, Items.COBBLESTONE, 64}, {23, Items.COBBLESTONE, 64}, {24, Items.DIORITE, 24}, {25, Items.COBBLESTONE, 64},
                {26, Items.FLINT, 2}, {27, Items.FURNACE, 1}, {28, Items.COBBLESTONE, 64}, {29, Items.COBBLESTONE, 29}, {30, Items.ANDESITE, 17}};
        for (Object[] h : held) inventory.setStack((Integer) h[0], new ItemStack((Item) h[1], (Integer) h[2]));
        inventory.selectedSlot = 2;
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.setAir(player.getMaxAir());
    }

    /**
     * In plain terms: a player shut in a hole under the sea with no pickaxe breaks the plank over its head, swims up and
     * climbs out, rather than stand there for good.
     *
     * Technical: F-25c4dg, e40a22's seed 101 Kindred (above). STAGED: the shaft as the replay shows it, its feet's cell and
     * the one over it open, its two dirt blocks under it, stone round its feet and a layer of gravel over that, the dark oak
     * plank over its head and the sea over the plank, 16 deep to its top (sea level; the replay's box shows only the first
     * eight), walled round 25 square with stone to the sea's top; Kindred's kit at the spell, slot for slot, no pickaxe;
     * daylight held; the portal goal. Passes once it stands on dry ground under the open sky, alive, within 2,400 ticks,
     * with no action left to reconcile.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "sealedUnderSea", tickLimit = 3200)
    public void sealedUnderTheSeaWithNoPickaxeItOpensTheRoofAndSwimsUp(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3100);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 6, 40));
        int[] watched = new int[1];
        String[] last = {""};
        int[] minAir = {Integer.MAX_VALUE};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sealed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-SEA - 2, -6, -SEA - 2), o.add(SEA + 2, SURFACE + 2, SEA + 2));
                    scene(context, o);
                    context.getWorld().setTimeOfDay(10925);
                    kit(f.player());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In its shaft: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(10925);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), (player == null ? "No body" : "The player died ("
                            + (player.getRecentDamageSource() == null ? "no damage source" : player.getRecentDamageSource().getName()) + " at "
                            + player.getBlockPos().subtract(o).toShortString() + ")") + "; " + seen);
                    minAir[0] = Math.min(minAir[0], player.getAir());
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = progress;
                    watched[0]++;
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(watched[0] <= 2400, "Still under the sea after 2,400 ticks: " + tail + " now " + progress + " @ "
                            + player.getBlockPos().subtract(o).toShortString());
                    boolean out = player.isOnGround() && !player.isTouchingWater()
                            && context.getWorld().isSkyVisible(player.getBlockPos().up()) && player.getBlockY() > o.getY() + SURFACE;
                    context.assertTrue(out, "Not out on dry ground under the sky yet");
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Sealed under the sea: out on dry ground after {} ticks at {}, health {}, least air {}; {}", watched[0],
                            f.player().getBlockPos().subtract(o).toShortString(), f.player().getHealth(), minAir[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
