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

import java.util.HashSet;
import java.util.Set;

/** A branch mine for ore, dug through fresh stone: its legs go on into ground it has not stood in. */
public final class NativeBranchMineGameTests implements FabricGameTest {
    private static final int FIELD = 50;
    /** Ticks a step of its tunnel may take at most: two blocks of stone broken and a step on, 42 ticks off a sprint. */
    private static final int PACE = 56;

    /**
     * A branch mine for iron through a slab of stone with no ore in it, a poor patch such as Echo's on 8675309, dug on a
     * server sprinting through its ticks as the evaluation runs it (`/tick sprint`): it digs on through fresh stone at
     * its usual pace, and its stuck watchdog, which counts ground it has never stood in (eight-block cells) as headway,
     * does not take it for stuck. In the ten seeds on `84b331c`, 45 of 144 stuck spells were branch mines for iron at
     * Y=16 to 21. Echo's first, at 40, 19, 67, came at the third leg of a column, legs 20 blocks long and 3 apart. A step
     * of its tunnel took about 100 ticks there and 42 here, as one task waited on another thread's work: on a sprinting
     * server vanilla holds a task handed to it from another thread until it is more than three ticks old, and each step
     * waited so seven or eight times (F-26a). Staged: the stone, a pocket in it, the morning held, the sprint, and its
     * kit as though made by the iron stage: stone pickaxes, a stone sword, bread, torches, coal, sticks, cobblestone, a
     * table and a furnace.
     */
    @GameTest(templateName = FixtureGround.WIDE, batchId = "branchMine", tickLimit = 15000)
    public void onASprintingServerABranchMineThroughFreshStoneKeepsItsPaceAndIsNoStuckSpell(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 14800);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 8, 60));
        Set<Long> cells = new HashSet<>();
        Set<Long> dug = new HashSet<>();
        int[] entries = {0, 0}; // cells entered along the mine: new, and stood in before
        long[] lastCell = {Long.MIN_VALUE};
        String[] spell = {null};
        int[] settle = new int[1];
        long[] firstTick = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    FixtureGround.keep(context, o.add(-FIELD, -2, -FIELD), o.add(FIELD, 4, FIELD));
                    FixtureGround.simulated(context, o.add(-FIELD, 0, -FIELD), o.add(FIELD, 0, FIELD));
                    for (int x = -FIELD; x <= FIELD; x++) for (int z = -FIELD; z <= FIELD; z++) for (int y = -2; y <= 4; y++) {
                        boolean pocket = x == 0 && z == 0 && (y == 0 || y == 1);
                        world.setBlockState(o.add(x, y, z), pocket ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 0, 0);
                    player.setVelocity(Vec3d.ZERO);
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(3, new ItemStack(Items.TORCH, 32));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(6, new ItemStack(Items.FURNACE));
                    inventory.setStack(7, new ItemStack(Items.COAL, 8));
                    inventory.setStack(8, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(9, new ItemStack(Items.STICK, 8));
                    inventory.setStack(10, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(11, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In its pocket"))
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "A moment"))
                .createAndAdd(() -> {
                    var ticks = context.getWorld().getServer().getTickManager();
                    FixtureGround.whenDone(context, ticks::stopSprinting);
                    context.assertTrue(ticks.startSprint(14_000), "Sprinting");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // The first failure is the one reported: once the runtime stops at the end, nothing more is to be seen.
                    context.assertTrue(spell[0] == null, spell[0]);
                    context.getWorld().setTimeOfDay(1000);
                    var bots = f.runtime.panelBots(Actor.console());
                    context.assertTrue(!bots.isEmpty() && f.player() != null, "Its panel and body");
                    String progress = bots.getFirst().goalProgress();
                    BlockPos feet = f.player().getBlockPos();
                    if (progress.contains("Branch mine for iron") && dug.add(feet.asLong())) {
                        if (dug.size() == 1) firstTick[0] = context.getTick();
                        long cell = BlockPos.asLong(feet.getX() >> 3, feet.getY() >> 3, feet.getZ() >> 3);
                        if (cell != lastCell[0]) entries[cells.add(cell) ? 0 : 1]++;
                        lastCell[0] = cell;
                    }
                    long ticks = dug.isEmpty() ? 0 : context.getTick() - firstTick[0];
                    String state = dug.size() + " blocks of its branch mine in " + ticks + " ticks; cells entered new " + entries[0]
                            + ", stood in before " + entries[1] + "; " + progress;
                    if (f.runtime.campaignForTest(f.id).stuckSpells() > 0) spell[0] = "A stuck spell in its branch mine through fresh stone after " + state;
                    // At its pace off a sprint, about 42 ticks a step: 160 steps in 6,800 ticks. A third over is too slow.
                    else if (ticks > PACE * 160L) spell[0] = "Its branch mine slowed: " + state;
                    context.assertTrue(spell[0] == null, spell[0]);
                    context.assertTrue(dug.size() >= 160, "Branch mining: " + state);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Branch mine through fresh stone, sprinting: {} blocks in {} ticks, cells entered new {}, stood in before {}, no stuck spell",
                            dug.size(), context.getTick() - firstTick[0], entries[0], entries[1]);
                    context.getWorld().getServer().getTickManager().stopSprinting();
                    context.assertTrue(f.player().isAlive(), "Alive");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
