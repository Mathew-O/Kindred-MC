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
import net.minecraft.util.math.Vec3d;

/** The trip down a stair of its own for lava or ore, and the way back up it. */
public final class NativeTripDownGameTests implements FabricGameTest {
    /** Steps of the stair, from its foot up to the open ground at its top. */
    private static final int STEPS = 13;
    /** Blocks above the foot that count as out of its shelter and on its way up. */
    private static final int CLIMBED = 6;

    /**
     * At the foot of its own stair with its last pickaxe worn out and no wood to make another, sealed in for the night with
     * cobbled deepslate: in the morning it punches its seal out by hand and climbs (F-25c4bc). On 20261030 in E-40's
     * iteration 2 (`e40a2-143806e`) Sol's last pickaxe wore out on its stair down for lava at 101, 35, 92, with no sticks
     * or planks left. It sealed itself in for the night and stood there for the last 41 game minutes of the run, 7 stuck
     * spells, "Gather logs for sticks - Head back up toward home": every way up refused, its seal not opened. The console
     * does not name the block it sealed with; it carried cobbled deepslate from Y=-54, the one block it seals with that
     * was past what it would punch by hand (hardness 3.0). Staged: a block of stone with a stair dug down through it one
     * step a block, three high a step, from open ground to a foot 13 down, and the two cells dug on beyond the foot;
     * night; the player at the foot, hurt and hungry, with cobbled deepslate and torches, no pickaxe, no wood and nothing
     * to eat; daylight held once it has sealed itself in.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "tripDownSeal", tickLimit = 6000)
    public void atTheFootOfItsStairWithNoPickaxeLeftItOpensItsSealInTheMorningAndClimbsOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5900);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 4, 60));
        boolean[] day = {false};
        long[] dawn = {0};
        int[] highest = {Integer.MIN_VALUE};
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stairfoot"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(14000);
                    FixtureGround.keep(context, o.add(-STEPS - 8, -2, -6), o.add(6, STEPS + 3, 6));
                    FixtureGround.simulated(context, o.add(-STEPS - 8, 0, -6), o.add(6, 0, 6));
                    for (int x = -STEPS - 8; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -2; y <= STEPS + 3; y++) {
                        BlockPos at = o.add(x, y, z);
                        int k = -x; // Steps up from the foot, westward.
                        // Each step of the stair three high, as it digs one going down: its feet, its head and the head
                        // room over the step down.
                        boolean stair = z == 0 && k >= 0 && k <= STEPS && y >= k && y <= k + 2;
                        // The two cells it had dug on beyond the foot when night fell.
                        boolean ahead = z == 0 && x == 1 && (y == 0 || y == 1);
                        boolean ground = y <= STEPS - 1;
                        world.setBlockState(at, stair || ahead || !ground ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // What it digs at depth, as Sol carried from Y=-54 down its first stair: the night's seal is made of it.
                    inventory.setStack(0, new ItemStack(Items.COBBLED_DEEPSLATE, 24));
                    inventory.setStack(1, new ItemStack(Items.TORCH, 4));
                    player.setHealth(8);
                    player.getHungerManager().setFoodLevel(5);
                    player.getHungerManager().setSaturationLevel(0);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "At the foot"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    // Sealed in for the night: the way on ahead, and the stair's next step and the head room over it.
                    var world = context.getWorld();
                    var bots = f.runtime.panelBots(Actor.console());
                    String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
                    boolean sealed = !world.getBlockState(o.east()).isAir() && !world.getBlockState(o.west().up()).isAir()
                            && !world.getBlockState(o.up(2)).isAir();
                    context.assertTrue(sealed, "Not sealed for the night: " + progress);
                    world.setTimeOfDay(1000);
                    day[0] = true;
                    dawn[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null && f.player().isAlive(), "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    last[0] = progress;
                    BlockPos feet = f.player().getBlockPos();
                    highest[0] = Math.max(highest[0], feet.getY() - o.getY());
                    // Out of its shelter and up its stair, half way to the top at least.
                    context.assertTrue(day[0] && highest[0] >= CLIMBED, "Still at " + feet.subtract(o).toShortString()
                            + " from the foot, highest " + highest[0] + ", " + (context.getTick() - dawn[0]) + " ticks after daylight; " + progress);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Stair foot: {} up from the foot {} ticks after daylight; {}", highest[0], context.getTick() - dawn[0], last[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In a shaft of its own under the desert's sandstone, the block over its head with sand resting on it and no pickaxe:
     * it gets out from under the sand from the cell beside it and climbs to the surface (F-25c4bd). On 31102026 in E-40's
     * iteration 2 (`e40a2-143806e`) Sol, out of sticks with no pickaxe, pillared and punched its way up a shaft one wide
     * from Y=52 to 64. There the block over its head was sandstone with four sand on it (read offline from a fresh world
     * on the seed, diagnosis only). Every way up was refused for the sand, the pillar for the block over its head, and it
     * stood there for the rest of the run, 6 stuck spells ("Gather logs for sticks - Head back up toward home"). Staged: a
     * block of sandstone with four sand on top, open sky above; a pocket two high in the sandstone, its ceiling a block
     * of sandstone under the sand; daylight held; the player in the pocket, fed, with cobblestone and bread, no pickaxe
     * and no wood.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "underSand", tickLimit = 6000)
    public void inAShaftUnderSandstoneWithSandOnItAndNoPickaxeItGetsOutFromBesideAndClimbs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5900);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 4, 60));
        long[] start = {0};
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sandshaft"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-8, -4, -8), o.add(8, 12, 8));
                    FixtureGround.simulated(context, o.add(-8, 0, -8), o.add(8, 0, 8));
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -4; y <= 12; y++) {
                        BlockPos at = o.add(x, y, z);
                        boolean pocket = x == 0 && z == 0 && (y == 0 || y == 1);
                        var state = pocket || y >= 7 ? Blocks.AIR.getDefaultState()
                                : y >= 3 ? Blocks.SAND.getDefaultState() : Blocks.SANDSTONE.getDefaultState();
                        world.setBlockState(at, state);
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In the shaft"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null && f.player().isAlive(), "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    last[0] = progress;
                    BlockPos feet = f.player().getBlockPos();
                    // Out on the sand: the open ground at the top.
                    context.assertTrue(feet.getY() >= o.getY() + 7 && f.player().isOnGround(), "Still at " + feet.subtract(o).toShortString()
                            + " from where it stood, " + (context.getTick() - start[0]) + " ticks on; " + progress);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 18, "Unhurt: " + player.getHealth());
                    KindredMod.LOGGER.info("Sand shaft: out on the sand {} ticks on; {}", context.getTick() - start[0], last[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In a shaft of its own through rock, water beside the block over its head and no pickaxe: it gets out from the cell
     * beside it on the dry side and climbs to the surface, the water left where it is (F-25c4bd). On 101 in E-40's
     * iteration 2 (`e40a2-143806e`) Kindred, out of sticks with no pickaxe, pillared and punched its way up a shaft one
     * wide from Y=48 to 53. There the block over its head (stone) had water beside it, a pocket of three sources at
     * Y=55 to 57 (read offline from a fresh world on the seed, diagnosis only). Every way up was refused for the water,
     * the pillar for the block over its head, and it stood there from the first quarter of the run to its end, 16 stuck
     * spells. Staged: a block of stone with open sky over it; a pocket two high in it; three water sources in a column
     * beside the block over its head, walled in by stone; daylight held; the player in the pocket, fed, with cobblestone
     * and bread, no pickaxe and no wood.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "besideWater", tickLimit = 6000)
    public void inAShaftWithWaterBesideTheBlockOverItsHeadAndNoPickaxeItGetsOutFromBesideAndClimbs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5900);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 4, 60));
        long[] start = {0};
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wetshaft"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-8, -4, -8), o.add(8, 12, 8));
                    FixtureGround.simulated(context, o.add(-8, 0, -8), o.add(8, 0, 8));
                    for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -4; y <= 12; y++) {
                        BlockPos at = o.add(x, y, z);
                        boolean pocket = x == 0 && z == 0 && (y == 0 || y == 1);
                        boolean water = x == 0 && z == 1 && y >= 2 && y <= 4;
                        var state = pocket || y >= 7 ? Blocks.AIR.getDefaultState()
                                : water ? Blocks.WATER.getDefaultState() : Blocks.STONE.getDefaultState();
                        world.setBlockState(at, state);
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In the shaft"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null && f.player().isAlive(), "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    last[0] = progress;
                    BlockPos feet = f.player().getBlockPos();
                    // Out on the open ground at the top.
                    context.assertTrue(feet.getY() >= o.getY() + 7 && f.player().isOnGround(), "Still at " + feet.subtract(o).toShortString()
                            + " from where it stood, " + (context.getTick() - start[0]) + " ticks on; " + progress);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 18, "Unhurt: " + player.getHealth());
                    // The water left where it was: its three sources, and no water anywhere else.
                    var world = context.getWorld();
                    int wet = 0;
                    for (BlockPos at : BlockPos.iterate(o.add(-8, -4, -8), o.add(8, 12, 8))) if (!world.getFluidState(at).isEmpty()) wet++;
                    context.assertTrue(wet == 3, "Water let out: " + wet + " cells wet");
                    KindredMod.LOGGER.info("Wet shaft: out on top {} ticks on; {}", context.getTick() - start[0], last[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Come down through a cave's ceiling onto a little island in a lava lake, every walk off it over the lava: it pillars
     * back up into the hole it came down through, rather than walk "to the wall" across the lava again and again
     * (F-25c4be). On 20261030 in E-40's iteration 3 (`e40a3-05b58fd`) Echo's stair down for iron broke through the ceiling
     * of a cave two high over a lava lake at 41, 72, 23. It came down onto two blocks of stone and andesite in the lava
     * (read offline from a fresh world on the seed, diagnosis only). Its walks "to the wall" found no route 40,419 times,
     * each asked for again at once, and it stood there from tick 4,500 to the end of the run, 38 stuck spells. Staged: a block of stone with open sky over it; a cave two high in it with a floor of lava but for an
     * island of two blocks; a hole one wide from the cave's ceiling over the island's second block up to the surface;
     * daylight held; the player on the island's first block, fed, with a stone pickaxe, cobblestone, torches and bread.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "lavaIsland", tickLimit = 4000)
    public void onAnIslandInALavaLakeUnderTheHoleItCameDownThroughItPillarsBackUpIntoIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3900);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 6, 60));
        long[] start = {0};
        int[] highest = {Integer.MIN_VALUE};
        int[] walls = {0};
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Islander"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-8, -4, -6), o.add(8, 12, 6));
                    FixtureGround.simulated(context, o.add(-8, 0, -6), o.add(8, 0, 6));
                    for (int x = -8; x <= 8; x++) for (int z = -6; z <= 6; z++) for (int y = -4; y <= 12; y++) {
                        BlockPos at = o.add(x, y, z);
                        boolean cave = x >= -5 && x <= 3 && Math.abs(z) <= 3;
                        boolean island = z == 0 && (x == 0 || x == 1) && y == -1;
                        boolean hole = x == 1 && z == 0 && y >= 2 && y <= 9;
                        var state = y >= 10 || hole || cave && (y == 0 || y == 1) ? Blocks.AIR.getDefaultState()
                                : cave && y == -1 && !island ? Blocks.LAVA.getDefaultState()
                                : island && x == 1 ? Blocks.ANDESITE.getDefaultState() : Blocks.STONE.getDefaultState();
                        world.setBlockState(at, state);
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.TORCH, 8));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "On the island"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    start[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null && f.player().isAlive(), "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    if (progress.endsWith(": to the wall") && !progress.equals(last[0])) walls[0]++;
                    last[0] = progress;
                    BlockPos feet = f.player().getBlockPos();
                    highest[0] = Math.max(highest[0], feet.getY() - o.getY());
                    // Back up into the hole it came down through, off the island.
                    context.assertTrue(highest[0] >= 3, "Still on the island at " + feet.subtract(o).toShortString() + ", "
                            + (context.getTick() - start[0]) + " ticks on, " + walls[0] + " walks to the wall; " + progress);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 18 && !player.isOnFire(), "Unhurt: " + player.getHealth());
                    KindredMod.LOGGER.info("Lava island: up into its hole {} ticks on, {} walks to the wall; {}", context.getTick() - start[0], walls[0], last[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
