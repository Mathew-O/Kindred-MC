package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** Autonomous candidate selection and skill dispatch under server edit policy (F-25c2). */
public final class NativeAutonomyGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyEat", tickLimit = 420)
    public void hungryNativePlayerAutonomouslyEats(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 410);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Eater"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
                    f.player().getHungerManager().setFoodLevel(14);
                    f.player().getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_eat"),
                            "Autonomous eat completes under paid activity session");
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1, "Exactly one apple consumed");
                    context.assertTrue(f.player().getHungerManager().getFoodLevel() >= 18, "Hunger restored");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyEquip", tickLimit = 280)
    public void unequippedArmorIsAutonomouslyEquipped(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true);
        f.runnerAwaitingReady(context, "Runtime ready with enabled edits")
                .createAndAdd(() -> f.runtime.createNative(f.source, "Equipper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(10, new ItemStack(Items.IRON_CHESTPLATE));
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.CHEST).isEmpty(), "Chestplate slot initially empty");
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_equip"),
                            "Autonomous equip completes under paid activity session");
                    context.assertTrue(f.player().getInventory().getStack(10).isEmpty(), "Armor slot in inventory vacated");
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE),
                            "Chestplate equipped to player body");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyMine", tickLimit = 400)
    public void scopedLogIsAutonomouslyMined(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Logger"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.player().lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                            net.minecraft.util.math.Vec3d.ofCenter(target));
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_mine"),
                            "Autonomous mine completes under paid activity session");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_mine"), "Durable action recorded and verified");
                    context.assertTrue(context.getWorld().getBlockState(target).isAir(), "Scoped log block broken");
                    context.assertTrue(f.player().getInventory().getStack(0).getDamage() == 1, "Owned axe wears once");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The server's AI world edits off (its default, D4): the player still eats when it is hungry, a reflex that changes
     * no block; but it equips nothing, mines nothing and starts no work. Before the reflexes were separated from the
     * edit switch, it went hungry too.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyDisabled", tickLimit = 480)
    public void withEditsOffItStillEatsButEquipsAndMinesNothing(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 470); // default edits disabled
        BlockPos target = target(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> f.awaitReady(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Unqualified"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(target, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, target, target, "minecraft:oak_log");
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
                    // One apple's worth from full: it eats while short of full, as before.
                    f.player().getHungerManager().setFoodLevel(16);
                    f.player().getInventory().setStack(10, new ItemStack(Items.IRON_CHESTPLATE));
                    f.player().getInventory().setStack(1, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1
                        && f.player().getHungerManager().getFoodLevel() == 20, "One apple eaten, edits off: " + f.player().getInventory().getStack(0)))
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.explanation().contains("Autonomous progression awaits qualified body, skills and activity admission"),
                            "Decision loop reports its work awaits qualification: " + status.explanation());
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 1, "No more food eaten than the one");
                    context.assertTrue(f.player().getInventory().getStack(10).isOf(Items.IRON_CHESTPLATE), "Armor remains in inventory");
                    context.assertTrue(f.player().getEquippedStack(EquipmentSlot.CHEST).isEmpty(), "Chest slot empty");
                    context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "Log remains intact");
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue((activity.phase().equals("UNLOADED") || activity.phase().equals("IDLE"))
                            && !activity.work().startsWith("Running"), "No activity running after the meal");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyStop", tickLimit = 380)
    public void stopLatchesAndHaltsAutonomousAction(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true, 370);
        f.runnerAwaitingReady(context, "Runtime ready with enabled edits")
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stopper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.APPLE, 2));
                    f.player().getHungerManager().setFoodLevel(14);
                    f.player().getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isUsingItem(), "Autonomously started eating");
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                    context.assertTrue(!f.player().isUsingItem(), "Stop synchronously clears item use inputs");
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Stop is latched");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 2, "Cancelled food not consumed");
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("IDLE"), "Activity session stopped");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyPickaxe", tickLimit = 680)
    public void autonomousProgressionFromPlanksToPickaxe(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 650);
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Crafter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(3, 2, 4)), Blocks.CRAFTING_TABLE.getDefaultState());
                    // Give 10 oak planks: enough for table (4), sticks (2), and pickaxe (3) + 1 spare
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 10));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:crafting_table");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    boolean hasPickaxe = false;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.WOODEN_PICKAXE)) {
                            hasPickaxe = true;
                            break;
                        }
                    }
                    context.assertTrue(hasPickaxe, "Autonomous progression successfully crafted wooden pickaxe");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyApproach", tickLimit = 520)
    public void autonomousApproachAndMineDistantScopedLog(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true, 430);
        BlockPos[] distantLog = new BlockPos[1];

        f.runnerAwaitingReady(context, "Runtime ready with enabled edits")
                .createAndAdd(() -> f.runtime.createNative(f.source, "ApproachMiner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    // In the morning: at nightfall a player out working turns back for shelter, and the test server keeps
                    // its day cycle running from test to test (full runs 19 and 20 on 2026-10-24).
                    context.getWorld().setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos();
                    distantLog[0] = p.east(5);
                    for (int dx = 0; dx <= 6; dx++) {
                        context.getWorld().setBlockState(p.east(dx).down(), Blocks.STONE.getDefaultState());
                        for (int dy = 0; dy <= 2; dy++) {
                            context.getWorld().setBlockState(p.east(dx).up(dy), Blocks.AIR.getDefaultState());
                        }
                    }
                    context.getWorld().setBlockState(distantLog[0], Blocks.OAK_LOG.getDefaultState());

                    BlockPos min = new BlockPos(Math.min(p.getX(), distantLog[0].getX()) - 1, p.getY() - 1, Math.min(p.getZ(), distantLog[0].getZ()) - 1);
                    BlockPos max = new BlockPos(Math.max(p.getX(), distantLog[0].getX()) + 1, p.getY() + 3, Math.max(p.getZ(), distantLog[0].getZ()) + 1);
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    if (!context.getWorld().getBlockState(distantLog[0]).isAir()) {
                        // What lay between it and the log, for a failure in a full run (runs 19 and 20 on 2026-10-24).
                        var player = f.player();
                        var around = new StringBuilder();
                        for (int dx = -1; dx <= 6; dx++) for (int dy = -1; dy <= 2; dy++) {
                            var state = context.getWorld().getBlockState(distantLog[0].west(5).add(dx, dy, 0));
                            if (!state.isAir()) around.append(dx).append(',').append(dy).append('=').append(net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).getPath()).append(' ');
                        }
                        context.assertTrue(false, "Distant log autonomously approached and broken: at " + (player == null ? "gone" : player.getPos())
                                + ", log at " + distantLog[0].toShortString() + "; around: " + around + "/ " + f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(f.player().getInventory().getStack(0).getDamage() >= 1, "Axe tool durability damaged once");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_mine"), "Verified durable action recorded");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "autonomyStone", tickLimit = 640)
    public void autonomousProgressionToStonePickaxeAndFurnace(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 610);
        BlockPos tablePos = context.getAbsolutePos(new BlockPos(3, 2, 4));
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "StoneAdvancer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(tablePos, Blocks.CRAFTING_TABLE.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.WOODEN_PICKAXE, 1));
                    f.player().getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 11));
                    f.player().getInventory().setStack(2, new ItemStack(Items.STICK, 2));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {})
                .createAndAdd(() -> {
                    if (f.player() == null) return;
                    boolean hasStonePickaxe = false;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.STONE_PICKAXE)) {
                            hasStonePickaxe = true;
                            break;
                        }
                    }
                    context.assertTrue(hasStonePickaxe, "Autonomous progression crafted stone pickaxe");
                    boolean hasFurnaceItemOrPlaced = false;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.FURNACE)) {
                            hasFurnaceItemOrPlaced = true;
                            break;
                        }
                    }
                    if (!hasFurnaceItemOrPlaced) {
                        hasFurnaceItemOrPlaced = context.getWorld().getBlockState(tablePos.east()).isOf(Blocks.FURNACE)
                                || context.getWorld().getBlockState(tablePos.west()).isOf(Blocks.FURNACE)
                                || context.getWorld().getBlockState(tablePos.north()).isOf(Blocks.FURNACE)
                                || context.getWorld().getBlockState(tablePos.south()).isOf(Blocks.FURNACE);
                    }
                    context.assertTrue(hasFurnaceItemOrPlaced, "Autonomous progression crafted or placed furnace");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static BlockPos target(TestContext context) { return context.getAbsolutePos(new BlockPos(3, 2, 5)); }
}
