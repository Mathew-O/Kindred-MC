package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

/**
 * In plain terms: a GameTest: a player rerolling a librarian's lectern who has climbed onto the lectern when it comes to
 * break it gets off it first, to wherever there is room, and never asks again and again to break the block it stands on;
 * with nowhere at all to step, it waits a while, then lets the villager be and says why.
 *
 * Technical (F-22c116, under E-45c): the reroll's pen, as {@link NativeRerollGameTests} stages it. When the reroll first
 * reaches its BREAK phase, Hal is set standing on the lectern and the villager is held (AI off) in a cell beside it,
 * and the pen's other cells at the lectern's height are filled with glass, so that none beside the lectern is free:
 * - {@code room}: glass at feet height only, its top a floor one up, beside the lectern, to step off onto;
 * - {@code none}: glass two high, so there is nowhere to step off to at all;
 * - {@code beside} (the control): no glass, the villager held, the pen's other cells free.
 * On CI (#451, run 45033457942) Hal stood on its lectern with no side free, its break was refused six times ("Would
 * remove the block the player stands on"), and the reroll gave up with the lectern still in the ground.
 */
public final class NativeRerollStepOffGameTests implements FabricGameTest {
    private static final BlockPos VILLAGER = new BlockPos(8, 2, 5), HAL = new BlockPos(7, 2, 6);

    /** The pen's other cells filled one high: it steps off onto the glass beside the lectern, breaks it and takes it back. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rerollStepOffRoom", tickLimit = 7000)
    public void onItsLecternWithNoSideFreeItStepsOffOntoTheBlockBesideAndTakesTheLecternBack(TestContext context) {
        scene(context, "room");
    }

    /** No room anywhere off the lectern: it waits its bound, then lets the villager be with its reason, never through refusals. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rerollStepOffNone", tickLimit = 7000)
    public void onItsLecternWithNowhereToStepItWaitsItsBoundThenLetsTheVillagerBeAndSaysWhy(TestContext context) {
        scene(context, "none");
    }

    /** The control: on its lectern with the pen's other cells free, it steps off beside it as before and takes it back. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rerollStepOffBeside", tickLimit = 7000)
    public void onItsLecternWithASideFreeItStepsOffBesideAsBefore(TestContext context) {
        scene(context, "beside");
    }

    private static void scene(TestContext context, String mode) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 6900);
        VillagerEntity[] villager = new VillagerEntity[1];
        BlockPos[] lectern = new BlockPos[1];
        boolean[] released = {false};
        context.runAtEveryTick(() -> {
            if (lectern[0] != null || villager[0] == null) return;
            String phase = f.runtime.reroll(f.id);
            if (phase == null || !phase.startsWith("BREAK")) return;
            var world = context.getWorld();
            BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
            BlockPos at = null;
            for (int x = 7; x <= 9 && at == null; x++) for (int z = 4; z <= 6 && at == null; z++) {
                if (world.getBlockState(o.add(x, 2, z)).isOf(Blocks.LECTERN)) at = o.add(x, 2, z);
            }
            if (at == null) return;
            lectern[0] = at;
            // The villager held in a cell beside the lectern, inside the pen.
            BlockPos held = null;
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos c = at.offset(side);
                if (c.getX() - o.getX() >= 7 && c.getX() - o.getX() <= 9 && c.getZ() - o.getZ() >= 4 && c.getZ() - o.getZ() <= 6) { held = c; break; }
            }
            var v = villager[0];
            v.setAiDisabled(true);
            v.refreshPositionAndAngles(held.getX() + 0.5, held.getY(), held.getZ() + 0.5, 0, 0);
            if (!mode.equals("beside")) {
                int high = mode.equals("none") ? 2 : 1;
                for (int x = 7; x <= 9; x++) for (int z = 4; z <= 6; z++) {
                    BlockPos c = o.add(x, 2, z);
                    if (c.equals(at) || c.equals(held)) continue;
                    for (int y = 0; y < high; y++) world.setBlockState(c.up(y), Blocks.GLASS.getDefaultState());
                }
            }
            var hal = f.player();
            hal.refreshPositionAndAngles(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, hal.getYaw(), hal.getPitch());
            KindredMod.LOGGER.info("Reroll step-off {}: Hal set on its lectern at {}, the villager held at {}", mode, at.toShortString(), held.toShortString());
        });
        // The villager let go once the lectern is gone, so that it can let its job go as vanilla's villager does.
        context.runAtEveryTick(() -> {
            if (lectern[0] == null || released[0] || context.getWorld().getBlockState(lectern[0]).isOf(Blocks.LECTERN)) return;
            released[0] = true;
            villager[0].setAiDisabled(false);
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.simulated(context, o, o.add(14, 0, 12));
                    FixtureGround.keep(context, o.add(0, 1, 0), o.add(14, 6, 12));
                    for (int x = 0; x <= 14; x++) for (int z = 0; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 6; x <= 10; x++) for (int z = 3; z <= 7; z++) {
                        if (x == 6 || x == 10 || z == 3 || z == 7) {
                            for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, z), Blocks.GLASS.getDefaultState());
                        }
                    }
                    world.setTimeOfDay(1000);
                    var jobless = EntityType.VILLAGER.create(world);
                    BlockPos at = context.getAbsolutePos(VILLAGER);
                    jobless.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    jobless.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
                    world.spawnEntity(jobless);
                    var hal = f.player();
                    BlockPos a = context.getAbsolutePos(HAL);
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    var inventory = hal.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.LECTERN));
                    hal.setHealth(20);
                    hal.getHungerManager().setFoodLevel(20);
                    villager[0] = jobless;
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                // Kept at the first look, there is no break to stage: the scene asks for a reroll's break, and is then staged.
                .createAndAdd(() -> context.assertTrue(lectern[0] != null || f.runtime.rerollsKept(f.id) == 1,
                        "At its break: " + f.runtime.reroll(f.id)))
                .createAndAdd(() -> {
                    if (lectern[0] == null) return;
                    var world = context.getWorld();
                    boolean standing = world.getBlockState(lectern[0]).isOf(Blocks.LECTERN);
                    int lecterns = count(f);
                    if (mode.equals("none")) {
                        context.assertTrue(f.runtime.reroll(f.id) == null && f.runtime.rerollsGivenUp(f.id) == 1,
                                "Let be: " + f.runtime.reroll(f.id) + " / refused " + f.runtime.rerollStepsRefused(f.id));
                    } else {
                        context.assertTrue(!standing && lecterns == 1, "Off its lectern and the lectern taken back: standing " + standing
                                + ", held " + lecterns + " / " + f.runtime.reroll(f.id) + " / refused " + f.runtime.rerollStepsRefused(f.id));
                    }
                })
                .createAndAdd(() -> {
                    if (lectern[0] == null) return;
                    int refused = f.runtime.rerollStepsRefused(f.id);
                    context.assertTrue(refused == 0, "No step of the reroll refused: " + refused);
                    if (mode.equals("none")) {
                        context.assertTrue(f.runtime.said(f.id).contains("There's no room to step off my lectern; I've left it standing."),
                                "Said why: " + f.runtime.said(f.id));
                    }
                    KindredMod.LOGGER.info("Reroll step-off {}: ended {} / given up {} / refused {} / said {}", mode, f.runtime.reroll(f.id),
                            f.runtime.rerollsGivenUp(f.id), refused, f.runtime.said(f.id));
                    villager[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static int count(NativePaidSkillsGameTests.Fixture f) {
        var inventory = f.player().getInventory();
        int lecterns = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(Items.LECTERN)) lecterns += inventory.getStack(slot).getCount();
        return lecterns;
    }
}
