package dev.kindred.fabric;

import dev.kindred.core.ClaimRegistry;
import dev.kindred.core.EditPolicy;
import dev.kindred.core.SkillBody;
import dev.kindred.core.WorkSetup;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Real blocks and stacks. These do not by themselves prove an unattended zero-player run. */
public final class ChoreGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void stacksMoveBetweenInventoryAndChestWithoutCopying(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 8));
        context.setBlockState(2, 2, 1, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(new BlockPos(2, 2, 1)));
        context.assertTrue(chest != null, "chest");
        var result = ItemMoves.transfer(UUID.randomUUID(), body.inventory(), chest, Items.OAK_LOG, 5);
        context.assertTrue(result.fullySucceeded(), "Transfer reconciles: " + result);
        context.assertEquals(3, ItemMoves.count(body.inventory(), Items.OAK_LOG), "The rest stays in the companion");
        context.assertEquals(5, ItemMoves.count(chest, Items.OAK_LOG), "The chest gained the same five");
        // Real stacks move, so wear and other components travel with the item instead of being reset.
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamage(37);
        body.inventory().setStack(1, worn);
        var tool = ItemMoves.transfer(UUID.randomUUID(), body.inventory(), chest, Items.IRON_PICKAXE, 1);
        context.assertTrue(tool.fullySucceeded(), "Tool transfer reconciles");
        boolean wornInChest = false;
        for (int i = 0; i < chest.size(); i++) {
            if (chest.getStack(i).isOf(Items.IRON_PICKAXE) && chest.getStack(i).getDamage() == 37) wornInChest = true;
        }
        context.assertTrue(wornInChest, "The moved pickaxe keeps its damage");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void gatherBreaksOnlyTheDesignatedLogAndPicksItUp(TestContext context) {
        for (int y = 1; y <= 4; y++) context.setBlockState(1, y, 1, y == 1 ? Blocks.STONE : Blocks.AIR);
        context.setBlockState(2, 2, 1, Blocks.OAK_LOG);
        CompanionEntity body = body(context);
        body.refreshPositionAndAngles(context.getAbsolute(new Vec3d(1.5, 2, 1.5)).x, context.getAbsolute(new Vec3d(1.5, 2, 1.5)).y,
                context.getAbsolute(new Vec3d(1.5, 2, 1.5)).z, 0, 0);
        BlockPos log = context.getAbsolutePos(new BlockPos(2, 2, 1));
        var home = new WorkSetup.BlockPoint(log.getX(), log.getY(), log.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withPatch(new WorkSetup.ResourcePatch(home, home, "minecraft:oak_log"));
        var gather = new ChoreSkills.Gather(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step = gather.tick(Map.of("count", "1"), Optional.empty(), true);
        context.assertTrue(step.status() != SkillBody.Status.BLOCKED, "A permitted log breaks: " + step.reason());
        context.assertTrue(context.getWorld().getBlockState(log).isAir(), "The log block is gone");
        for (int i = 0; i < 3 && ItemMoves.count(body.inventory(), Items.OAK_LOG) < 1; i++) {
            gather.tick(Map.of("count", "1"), Optional.empty(), false);
        }
        context.assertTrue(ItemMoves.count(body.inventory(), Items.OAK_LOG) >= 1, "The drop was picked up");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void partialGatheringThenPauseAndResumeDoesNotGatherOriginalCountAgain(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 2)); // Starts with 2 logs
        BlockPos log1 = context.getAbsolutePos(new BlockPos(2, 2, 1));
        BlockPos log2 = context.getAbsolutePos(new BlockPos(2, 2, 2));
        context.setBlockState(new BlockPos(2, 2, 1), Blocks.OAK_LOG);
        context.setBlockState(new BlockPos(2, 2, 2), Blocks.OAK_LOG);

        var home = new WorkSetup.BlockPoint(log1.getX(), log1.getY(), log1.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withPatch(new WorkSetup.ResourcePatch(new WorkSetup.BlockPoint(log1.getX(), log1.getY(), log1.getZ()),
                        new WorkSetup.BlockPoint(log2.getX(), log2.getY(), log2.getZ()), "minecraft:oak_log"));

        var gather1 = new ChoreSkills.Gather(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        // Ask for 2 logs (targetTotal = 2 + 2 = 4)
        SkillBody.Step step1 = gather1.tick(Map.of("count", "2"), Optional.empty(), true);
        context.assertTrue(step1.status() == SkillBody.Status.RUNNING, "Gather started: " + step1.progress());

        // Tick to break log1 and pick it up
        for (int i = 0; i < 3 && ItemMoves.count(body.inventory(), Items.OAK_LOG) < 3; i++) {
            step1 = gather1.tick(Map.of("count", "2"), Optional.empty(), false);
        }
        context.assertEquals(3, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Collected 1 log, now carrying 3");

        // Simulate pause and checkpoint saving
        dev.kindred.core.Checkpoint cp = new dev.kindred.core.Checkpoint("gather_logs", UUID.randomUUID(), 0L, Map.of(), step1.progress(), System.currentTimeMillis());

        // Resume with fresh Gather instance from checkpoint
        var gather2 = new ChoreSkills.Gather(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step2 = gather2.tick(Map.of("count", "2"), Optional.of(cp), false);

        // Break and pickup log2
        for (int i = 0; i < 3 && ItemMoves.count(body.inventory(), Items.OAK_LOG) < 4; i++) {
            step2 = gather2.tick(Map.of("count", "2"), Optional.of(cp), false);
        }

        context.assertEquals(4, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Exactly 4 logs reached; did not gather 2 more to 5");
        context.assertTrue(step2.status() == SkillBody.Status.COMPLETED, "Completed upon reaching target total");
        context.assertEquals("2", step2.progress().get("collected"), "Verified contribution is 2");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void gatherRefusesToHarvestOutsideDesignatedPatch(TestContext context) {
        CompanionEntity body = body(context);
        BlockPos outsideLog = context.getAbsolutePos(new BlockPos(5, 2, 5));
        context.setBlockState(new BlockPos(5, 2, 5), Blocks.OAK_LOG);

        BlockPos patchPos = context.getAbsolutePos(new BlockPos(1, 2, 1));
        var home = new WorkSetup.BlockPoint(patchPos.getX(), patchPos.getY(), patchPos.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withPatch(new WorkSetup.ResourcePatch(home, home, "minecraft:oak_log"));

        var gather = new ChoreSkills.Gather(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step = gather.tick(Map.of("count", "1"), Optional.empty(), true);

        context.assertTrue(step.status() == SkillBody.Status.BLOCKED, "Must be blocked when patch has no logs: " + step.reason());
        context.assertTrue(context.getWorld().getBlockState(outsideLog).isOf(Blocks.OAK_LOG), "Log outside patch was not touched");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void brokenBlockWithoutPickupDoesNotCountAsCollectionSuccess(TestContext context) {
        CompanionEntity body = body(context);
        BlockPos log = context.getAbsolutePos(new BlockPos(2, 2, 1));
        context.setBlockState(new BlockPos(2, 2, 1), Blocks.OAK_LOG);

        var home = new WorkSetup.BlockPoint(log.getX(), log.getY(), log.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withPatch(new WorkSetup.ResourcePatch(home, home, "minecraft:oak_log"));

        // Gate that discards item drop after breaking
        var gather = new ChoreSkills.Gather(body, setup, () -> new EditPolicy.ServerPolicy(true, true), new ChoreSkills.Gate() {
            @Override public boolean prepared(String key, String kind, String preconditions) { return true; }
            @Override public void committed(String key, String outcome) {
                // Remove all item entities near log
                for (var drop : ((net.minecraft.server.world.ServerWorld) body.getWorld()).getEntitiesByClass(
                        net.minecraft.entity.ItemEntity.class, body.getBoundingBox().expand(4.0), e -> true)) {
                    drop.discard();
                }
            }
        });

        SkillBody.Step step = gather.tick(Map.of("count", "1"), Optional.empty(), true);
        context.assertFalse(step.status() == SkillBody.Status.COMPLETED, "Breaking alone without pickup cannot complete gather");
        context.assertEquals(0, ItemMoves.count(body.inventory(), Items.OAK_LOG), "No logs in inventory");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void inventorySurvivesTheNativeSave(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 4));
        var nbt = body.writeNbt(new net.minecraft.nbt.NbtCompound());
        CompanionEntity restored = body(context);
        restored.readNbt(nbt);
        context.assertEquals(4, ItemMoves.count(restored.inventory(), Items.OAK_LOG), "Slots reload from the entity save");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void competingCompanionsCannotClaimTheSameTargetSimultaneously(TestContext context) {
        ClaimRegistry registry = new ClaimRegistry();
        UUID epoch = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();

        for (int y = 1; y <= 4; y++) context.setBlockState(1, y, 1, y == 1 ? Blocks.STONE : Blocks.AIR);
        context.setBlockState(2, 2, 1, Blocks.OAK_LOG);
        BlockPos log = context.getAbsolutePos(new BlockPos(2, 2, 1));
        String claimKey = "break:" + log.toShortString();

        CompanionEntity bodyA = body(context);
        CompanionEntity bodyB = body(context);

        var home = new WorkSetup.BlockPoint(log.getX(), log.getY(), log.getZ());
        WorkSetup setupA = WorkSetup.empty(bodyA.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withPatch(new WorkSetup.ResourcePatch(home, home, "minecraft:oak_log"));
        WorkSetup setupB = WorkSetup.empty(bodyB.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withPatch(new WorkSetup.ResourcePatch(home, home, "minecraft:oak_log"));

        ChoreSkills.Gate gateA = new ChoreSkills.Gate() {
            @Override
            public boolean prepared(String key, String kind, String preconditions) {
                return registry.acquire(taskA, epoch, java.util.List.of(key), System.currentTimeMillis(), 30_000).isPresent();
            }
            @Override
            public void committed(String key, String outcome) {
                registry.releaseKey(taskA, key);
            }
            @Override
            public void rolledBack(String key, String reason) {
                registry.releaseKey(taskA, key);
            }
        };

        ChoreSkills.Gate gateB = new ChoreSkills.Gate() {
            @Override
            public boolean prepared(String key, String kind, String preconditions) {
                return registry.acquire(taskB, epoch, java.util.List.of(key), System.currentTimeMillis(), 30_000).isPresent();
            }
            @Override
            public void committed(String key, String outcome) {
                registry.releaseKey(taskB, key);
            }
            @Override
            public void rolledBack(String key, String reason) {
                registry.releaseKey(taskB, key);
            }
        };

        // Task A acquires claim
        context.assertTrue(gateA.prepared(claimKey, "break_block", "log"), "Companion A acquires claim on log");
        context.assertEquals(taskA, registry.holderOf(claimKey, System.currentTimeMillis()).orElseThrow(), "Task A is claim holder");

        // Task B tries to claim the same contested target
        context.assertFalse(gateB.prepared(claimKey, "break_block", "log"), "Companion B denied claim while A holds it");

        // Release task A
        gateA.committed(claimKey, "broke log");
        context.assertTrue(registry.holderOf(claimKey, System.currentTimeMillis()).isEmpty(), "Key is released");

        // Now Task B can acquire claim
        context.assertTrue(gateB.prepared(claimKey, "break_block", "log"), "Companion B acquires claim once released");
        context.assertEquals(taskB, registry.holderOf(claimKey, System.currentTimeMillis()).orElseThrow(), "Task B is claim holder");

        // Halt / removal of B releases ownership immediately
        registry.releaseTask(taskB);
        context.assertTrue(registry.holderOf(claimKey, System.currentTimeMillis()).isEmpty(), "Halt releases task claims immediately");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void depositTransfersExactCountIntoAssignedChestAndConservesItems(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 16));

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        BlockPos chestPos = context.getAbsolutePos(new BlockPos(2, 2, 1));
        var chestObs = StorageTargets.observe(context.getWorld(), chestPos);
        context.assertTrue(chestObs != null && chestObs.container(), "Chest observed");

        var target = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ()),
                chestObs.blockId(), chestObs.version());
        var home = new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withStorage(target);

        var deposit = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step = deposit.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.empty(), true);

        context.assertTrue(step.status() == SkillBody.Status.COMPLETED, "Deposit completed: " + step.reason());
        context.assertEquals("10", step.progress().get("deposited"), "10 logs deposited");
        context.assertEquals(6, ItemMoves.count(body.inventory(), Items.OAK_LOG), "6 logs remain in companion inventory");

        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(chestPos);
        context.assertEquals(10, ItemMoves.count(chest, Items.OAK_LOG), "Chest received exactly 10 logs");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void depositWithExistingStackMergesConservingItems(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 16));

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        BlockPos chestPos = context.getAbsolutePos(new BlockPos(2, 2, 1));
        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(chestPos);
        chest.setStack(0, new ItemStack(Items.OAK_LOG, 55)); // 55 logs in first slot

        var chestObs = StorageTargets.observe(context.getWorld(), chestPos);
        var target = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ()),
                chestObs.blockId(), chestObs.version());
        var home = new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withStorage(target);

        var deposit = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step = deposit.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.empty(), true);

        context.assertTrue(step.status() == SkillBody.Status.COMPLETED, "Deposit completed");
        context.assertEquals("10", step.progress().get("deposited"), "10 logs deposited");
        context.assertEquals(6, ItemMoves.count(body.inventory(), Items.OAK_LOG), "6 logs remain in body");
        context.assertEquals(65, ItemMoves.count(chest, Items.OAK_LOG), "55 + 10 = 65 logs in chest");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void depositIntoFullChestReportsHonestBlockerWithoutItemLoss(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 16));

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        BlockPos chestPos = context.getAbsolutePos(new BlockPos(2, 2, 1));
        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(chestPos);
        // Fill every slot of chest with cobblestone
        for (int i = 0; i < chest.size(); i++) {
            chest.setStack(i, new ItemStack(Items.COBBLESTONE, 64));
        }

        var chestObs = StorageTargets.observe(context.getWorld(), chestPos);
        var target = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ()),
                chestObs.blockId(), chestObs.version());
        var home = new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withStorage(target);

        var deposit = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step = deposit.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.empty(), true);

        context.assertTrue(step.status() == SkillBody.Status.BLOCKED, "Full chest must block deposit: " + step.reason());
        context.assertEquals(16, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Zero item loss: companion retains all 16 logs");
        context.assertEquals(0, ItemMoves.count(chest, Items.OAK_LOG), "Chest received 0 logs");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void depositWithPartialCapacityCheckpointsVerifiedTransferAndResumesWithoutDoubleCounting(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 10)); // 10 logs

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        BlockPos chestPos = context.getAbsolutePos(new BlockPos(2, 2, 1));
        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(chestPos);
        // Fill 26 slots with 64 cobblestone, slot 0 with 61 oak logs (only 3 spaces free)
        chest.setStack(0, new ItemStack(Items.OAK_LOG, 61));
        for (int i = 1; i < chest.size(); i++) {
            chest.setStack(i, new ItemStack(Items.COBBLESTONE, 64));
        }

        var chestObs = StorageTargets.observe(context.getWorld(), chestPos);
        var target = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ()),
                chestObs.blockId(), chestObs.version());
        var home = new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withStorage(target);

        var deposit1 = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step1 = deposit1.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.empty(), true);

        // First transfer transferred 3 items (what fit into slot 0)
        context.assertEquals("3", step1.progress().get("deposited"), "Checkpointed 3 transferred logs");
        context.assertEquals(7, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Companion has 7 logs remaining");
        context.assertEquals(64, ItemMoves.count(chest, Items.OAK_LOG), "Chest slot 0 filled to 64");

        // Save checkpoint
        dev.kindred.core.Checkpoint cp = new dev.kindred.core.Checkpoint("deposit", UUID.randomUUID(), 0L, Map.of(), step1.progress(), System.currentTimeMillis());

        // Player frees up slot 1 in the chest
        chest.setStack(1, ItemStack.EMPTY);

        // Resume with fresh Deposit instance from checkpoint
        var deposit2 = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step2 = deposit2.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.of(cp), false);

        context.assertTrue(step2.status() == SkillBody.Status.COMPLETED, "Deposit completed upon finishing remaining need: " + step2.reason());
        context.assertEquals("10", step2.progress().get("deposited"), "Total 10 logs deposited");
        context.assertEquals(0, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Companion has 0 logs remaining");
        context.assertEquals(71, ItemMoves.count(chest, Items.OAK_LOG), "61 + 10 = 71 logs in chest; did not deposit extra");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void depositRefusesReplacedOrBrokenChest(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 16));

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        BlockPos chestPos = context.getAbsolutePos(new BlockPos(2, 2, 1));
        var chestObs = StorageTargets.observe(context.getWorld(), chestPos);

        var target = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ()),
                chestObs.blockId(), chestObs.version());
        var home = new WorkSetup.BlockPoint(chestPos.getX(), chestPos.getY(), chestPos.getZ());
        WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                .withHome(new WorkSetup.HomeMarker(home))
                .withArea(WorkSetup.WorkRegion.around(home))
                .withStorage(target);

        // Replace chest with barrel
        context.setBlockState(2, 2, 1, Blocks.BARREL);
        var deposit1 = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step1 = deposit1.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.empty(), true);
        context.assertTrue(step1.status() == SkillBody.Status.BLOCKED, "Replaced chest must block: " + step1.reason());

        // Replace with stone (not a container)
        context.setBlockState(2, 2, 1, Blocks.STONE);
        var deposit2 = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (key, kind, pre) -> true);
        SkillBody.Step step2 = deposit2.tick(Map.of("count", "10", "item", "minecraft:oak_log"), Optional.empty(), true);
        context.assertTrue(step2.status() == SkillBody.Status.BLOCKED, "Broken container must block: " + step2.reason());

        context.assertEquals(16, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Companion inventory untouched");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void gatherToDepositChainCompletesWithExactConservationAcrossTenLayouts(TestContext context) {
        BlockPos[] logOffsets = {
                new BlockPos(1, 2, 1), new BlockPos(2, 2, 1), new BlockPos(3, 2, 1),
                new BlockPos(1, 2, 2), new BlockPos(2, 2, 2), new BlockPos(3, 2, 2),
                new BlockPos(1, 2, 3), new BlockPos(2, 2, 3), new BlockPos(3, 2, 3),
                new BlockPos(2, 3, 2)
        };
        BlockPos[] chestOffsets = {
                new BlockPos(4, 2, 1), new BlockPos(4, 2, 2), new BlockPos(4, 2, 3),
                new BlockPos(4, 2, 4), new BlockPos(3, 2, 4), new BlockPos(2, 2, 4),
                new BlockPos(1, 2, 4), new BlockPos(0, 2, 3), new BlockPos(0, 2, 2),
                new BlockPos(0, 2, 1)
        };

        for (int i = 0; i < 10; i++) {
            BlockPos logRel = logOffsets[i];
            BlockPos chestRel = chestOffsets[i];

            context.setBlockState(logRel, Blocks.OAK_LOG);
            context.setBlockState(chestRel, Blocks.CHEST);

            BlockPos logAbs = context.getAbsolutePos(logRel);
            BlockPos chestAbs = context.getAbsolutePos(chestRel);

            CompanionEntity body = body(context);
            var chestObs = StorageTargets.observe(context.getWorld(), chestAbs);
            var storageTarget = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(chestAbs.getX(), chestAbs.getY(), chestAbs.getZ()),
                    chestObs.blockId(), chestObs.version());
            var home = new WorkSetup.BlockPoint(logAbs.getX(), logAbs.getY(), logAbs.getZ());
            WorkSetup setup = WorkSetup.empty(body.companionId(), "minecraft:overworld")
                    .withHome(new WorkSetup.HomeMarker(home))
                    .withArea(WorkSetup.WorkRegion.around(home))
                    .withPatch(new WorkSetup.ResourcePatch(home, home, "minecraft:oak_log"))
                    .withStorage(storageTarget);

            // Phase 1: Gather 1 log
            var gather = new ChoreSkills.Gather(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (k, kind, p) -> true);
            body.refreshPositionAndAngles(logAbs.getX() + 0.5, logAbs.getY(), logAbs.getZ() + 0.5, 0, 0);
            SkillBody.Step gatherStep = gather.tick(Map.of("count", "1"), Optional.empty(), true);
            for (int t = 0; t < 3 && ItemMoves.count(body.inventory(), Items.OAK_LOG) < 1; t++) {
                gatherStep = gather.tick(Map.of("count", "1"), Optional.empty(), false);
            }
            context.assertEquals(1, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Layout " + i + ": Gathered 1 log");

            // Phase 2: Deposit 1 log into assigned chest
            var deposit = new ChoreSkills.Deposit(body, setup, () -> new EditPolicy.ServerPolicy(true, true), (k, kind, p) -> true);
            body.refreshPositionAndAngles(chestAbs.getX() + 0.5, chestAbs.getY(), chestAbs.getZ() + 0.5, 0, 0);
            SkillBody.Step depositStep = deposit.tick(Map.of("count", "1", "item", "minecraft:oak_log"), Optional.empty(), true);
            context.assertTrue(depositStep.status() == SkillBody.Status.COMPLETED, "Layout " + i + ": Deposit completed: " + depositStep.reason());
            context.assertEquals(0, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Layout " + i + ": Companion inventory empty");

            ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(chestAbs);
            context.assertEquals(1, ItemMoves.count(chest, Items.OAK_LOG), "Layout " + i + ": Chest received exactly 1 log");

            // Cleanup for next layout iteration
            context.setBlockState(logRel, Blocks.AIR);
            context.setBlockState(chestRel, Blocks.AIR);
        }

        context.complete();
    }

    private static CompanionEntity body(TestContext context) {
        CompanionEntity body = KindredMod.COMPANION.create(context.getWorld());
        if (body == null) throw new IllegalStateException("Missing companion factory");
        body.bind(body.getUuid(), UUID.randomUUID());
        Vec3d position = context.getAbsolute(new Vec3d(1.5, 2, 1.5));
        body.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return body;
    }
}
