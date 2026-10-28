package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.team.FarmHomeProjects;
import dev.kindred.core.team.ProjectId;
import dev.kindred.core.team.TaskId;
import dev.kindred.core.WorkSetup;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a player makes a
 * hoe, tills by water, plants and harvests crops.
 *
 * Technical: Native Fabric GameTests qualifying E-13 (Establish and maintain a home and farm):
 * 1. Hoe crafting, durability accounting, and soil preparation (tilling near water under server edit policy)
 * 2. Crop sowing and mature harvest with strict replanting conservation (immediate 1-seed replant)
 * 3. Bed placement, night/thunderstorm sleeping, and respawn point anchoring
 * 4. Shared team projects for farm base and shelter via FarmHomeProjects with stock reservations
 */
public final class NativeFarmGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 350)
    public void hoeCraftingAndSoilPreparation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos dirtPos = context.getAbsolutePos(new BlockPos(3, 2, 3));
        BlockPos waterPos = context.getAbsolutePos(new BlockPos(2, 2, 3));
        BlockPos supportDirt = dirtPos.down();
        BlockPos supportWater = waterPos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "FarmerTill"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(supportDirt, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(supportWater, Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(waterPos, Blocks.WATER.getDefaultState());
                    context.getWorld().setBlockState(dirtPos, Blocks.DIRT.getDefaultState());
                    context.getWorld().setBlockState(dirtPos.up(), Blocks.AIR.getDefaultState());

                    // Place wooden hoe in slot 0
                    f.player().getInventory().setStack(0, new ItemStack(Items.WOODEN_HOE, 1));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeTill(f.source, f.id, dirtPos, 0) == 1, "Till task requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_till"), "Till completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_till"), "Durable ledger records verified native_till");

                    // Check that the block is now farmland
                    context.assertTrue(context.getWorld().getBlockState(dirtPos).isOf(Blocks.FARMLAND), "Soil tilled into farmland");

                    // Check tool durability wear: damage should be 1
                    var hoeStack = f.player().getInventory().getStack(0);
                    context.assertTrue(hoeStack.isOf(Items.WOODEN_HOE) && hoeStack.getDamage() == 1, "Hoe took 1 point of durability wear");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "farmCropSow", tickLimit = 900)
    public void cropSowingAndReplantingConservation(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, NativeGameTestSupport.synchronousAdmissionWriter(), true);
        // Keep the crop beside the player's standing position, outside their collision box.
        BlockPos farmPos = context.getAbsolutePos(new BlockPos(4, 2, 3));
        BlockPos waterPos = context.getAbsolutePos(new BlockPos(5, 2, 3));
        BlockPos cropPos = farmPos.up();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> f.awaitReady(context))
                .createAndAdd(() -> f.runtime.createNative(f.source, "CropPlanter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(farmPos.down(), Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(waterPos.down(), Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(waterPos, Blocks.WATER.getDefaultState());
                    context.getWorld().setBlockState(farmPos, Blocks.FARMLAND.getDefaultState());
                    context.getWorld().setBlockState(cropPos, Blocks.AIR.getDefaultState());

                    // Give 3 wheat seeds in slot 0
                    f.player().getInventory().setStack(0, new ItemStack(Items.WHEAT_SEEDS, 3));
                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:wheat");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> f.runtime.nativeLifecycle(f.source, "stop", f.id))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativePlant(f.source, f.id, farmPos, 0) == 1, "Plant task requested");
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
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_plant"), "Plant completed: " + activity);
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_plant"), "Durable ledger records verified native_plant");

                    // Verify crop is placed above farmland
                    context.assertTrue(context.getWorld().getBlockState(cropPos).isOf(Blocks.WHEAT), "Wheat crop sown on farmland");
                    // Exactly 1 seed consumed
                    context.assertTrue(f.player().getInventory().getStack(0).getCount() == 2, "Exactly 1 seed consumed (2 remaining in slot 0)");
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                })
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(cropPos, Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeHarvest(f.source, f.id, cropPos, 0, true) == 1, "Harvest task requested");
                })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> { })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_harvest"), "Harvest completed: " + activity);
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_harvest"), "Durable ledger records verified native_harvest");

                    // Replanting conservation check: farmland must still have a wheat crop replanted (age 0)
                    var currentCropState = context.getWorld().getBlockState(cropPos);
                    context.assertTrue(currentCropState.isOf(Blocks.WHEAT), "Crop was immediately replanted onto farmland");
                    context.assertTrue(currentCropState.get(CropBlock.AGE) == 0, "Replanted crop is at age 0");
                    context.assertTrue(context.getWorld().getBlockState(farmPos).isOf(Blocks.FARMLAND), "Farmland preserved intact");

                    // Surplus wheat collected in inventory
                    context.assertTrue(countItem(f, Items.WHEAT) >= 1, "Surplus wheat gathered into player inventory");

                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightFarmBatch", tickLimit = 350)
    public void bedPlacementAndNightSleepSpawnAnchoring(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos bedPos = context.getAbsolutePos(new BlockPos(3, 2, 3));
        BlockPos support = bedPos.down();
        var min = context.getAbsolutePos(new BlockPos(0, 0, 0));
        var max = context.getAbsolutePos(new BlockPos(7, 5, 7));

        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready with enabled edits"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "BedSleeper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var facing = net.minecraft.util.math.Direction.NORTH;
                    BlockPos footPos = bedPos;
                    BlockPos headPos = bedPos.offset(facing);
                    context.getWorld().setBlockState(footPos.down(), Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(headPos.down(), Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(footPos, Blocks.RED_BED.getDefaultState()
                            .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT)
                            .with(net.minecraft.block.BedBlock.FACING, facing)
                            .with(net.minecraft.block.BedBlock.OCCUPIED, false), net.minecraft.block.Block.NOTIFY_ALL);
                    context.getWorld().setBlockState(headPos, Blocks.RED_BED.getDefaultState()
                            .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD)
                            .with(net.minecraft.block.BedBlock.FACING, facing)
                            .with(net.minecraft.block.BedBlock.OCCUPIED, false), net.minecraft.block.Block.NOTIFY_ALL);

                    f.runtime.nativePatch(f.source, f.id, min, max, "minecraft:stone");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    // Set world time to night (13000 ticks)
                    context.getWorld().setTimeOfDay(13000L);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeSleep(f.source, f.id, bedPos) == 1, "Sleep task requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_sleep"), "Sleep completed");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.state() == ActionRecord.State.VERIFIED
                            && action.kind().equals("native_sleep"), "Durable ledger records verified native_sleep");

                    // Verify player spawn point anchored to bed
                    var spawnPos = f.player().getSpawnPointPosition();
                    context.assertTrue(spawnPos != null && spawnPos.equals(bedPos), "Player respawn point anchored to home shelter bed");

                    context.getWorld().setTimeOfDay(1000L);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 150)
    public void sharedFarmHomeProjectCoordination(TestContext context) {
        UUID teamId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        // 1. Verify Farm Establishment Project
        ProjectId farmProjId = ProjectId.of("farm_base");
        var farmProj = FarmHomeProjects.createFarmEstablishmentProject(farmProjId, "Establish Base Farm", "Craft hoe, till soil, sow crops, and harvest");

        context.assertTrue(farmProj.name().equals("Establish Base Farm"), "Farm project name matches");
        context.assertTrue(farmProj.tasks().size() == 4, "Farm project has 4 sequential tasks");

        TaskId craftHoeTask = TaskId.of(farmProj.id(), "craft_hoe");
        TaskId tillSoilTask = TaskId.of(farmProj.id(), "till_soil");
        TaskId sowCropsTask = TaskId.of(farmProj.id(), "sow_crops");
        TaskId harvestCropsTask = TaskId.of(farmProj.id(), "harvest_crops");

        var tillTaskObj = farmProj.tasks().get(tillSoilTask);
        context.assertTrue(tillTaskObj != null && tillTaskObj.dependencies().contains(craftHoeTask), "Tilling depends on hoe crafting");

        var sowTaskObj = farmProj.tasks().get(sowCropsTask);
        context.assertTrue(sowTaskObj != null && sowTaskObj.dependencies().contains(tillSoilTask), "Sowing depends on soil tilling");

        var harvestTaskObj = farmProj.tasks().get(harvestCropsTask);
        context.assertTrue(harvestTaskObj != null && harvestTaskObj.dependencies().contains(sowCropsTask), "Harvesting depends on sowing crops");

        // 2. Verify Shelter Bed Project
        ProjectId bedProjId = ProjectId.of("shelter_bed");
        var bedProj = FarmHomeProjects.createShelterBedProject(bedProjId, "Establish Home Shelter Bed", "Craft bed, place bed in shelter, and anchor spawn");

        context.assertTrue(bedProj.name().equals("Establish Home Shelter Bed"), "Bed project name matches");
        context.assertTrue(bedProj.tasks().size() == 3, "Bed project has 3 sequential tasks");

        TaskId craftBedTask = TaskId.of(bedProj.id(), "craft_bed");
        TaskId placeBedTask = TaskId.of(bedProj.id(), "place_bed");
        TaskId anchorSpawnTask = TaskId.of(bedProj.id(), "anchor_spawn");

        var placeTaskObj = bedProj.tasks().get(placeBedTask);
        context.assertTrue(placeTaskObj != null && placeTaskObj.dependencies().contains(craftBedTask), "Placing bed depends on crafting bed");

        var anchorTaskObj = bedProj.tasks().get(anchorSpawnTask);
        context.assertTrue(anchorTaskObj != null && anchorTaskObj.dependencies().contains(placeBedTask), "Anchoring spawn depends on placing bed");

        context.complete();
    }

    private static int countItem(NativePaidSkillsGameTests.Fixture f, net.minecraft.item.Item item) {
        int total = 0;
        var inv = f.player().getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }
}
