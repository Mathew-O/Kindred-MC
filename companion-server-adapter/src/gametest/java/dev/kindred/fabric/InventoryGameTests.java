package dev.kindred.fabric;

import dev.kindred.core.InventorySummary;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Native GameTests for F-07: authoritative inventory slots, equipment reflections,
 * item conservation during transfer, attributed pickup, and NBT serialization.
 */
public final class InventoryGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void transferReconcilesSourceAndDestinationConservingItems(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 16));

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(new BlockPos(2, 2, 1)));
        context.assertTrue(chest != null, "chest block entity exists");

        UUID actionId = UUID.randomUUID();
        ItemMoves.TransferResult result = ItemMoves.transfer(actionId, body.inventory(), chest, Items.OAK_LOG, 10);

        context.assertTrue(result.reconciled(), "Transfer must reconcile source and destination deltas");
        context.assertTrue(result.fullySucceeded(), "Requested 10 and transferred 10");
        context.assertEquals(10, result.transferred(), "10 logs transferred");
        context.assertEquals(6, ItemMoves.count(body.inventory(), Items.OAK_LOG), "6 logs remain in body");
        context.assertEquals(10, ItemMoves.count(chest, Items.OAK_LOG), "10 logs in chest");
        context.assertEquals(16, ItemMoves.count(body.inventory(), Items.OAK_LOG) + ItemMoves.count(chest, Items.OAK_LOG), "Items conserved");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void transferRollsBackExcessWhenDestinationPartiallyRejects(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 10));

        context.setBlockState(2, 2, 1, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(new BlockPos(2, 2, 1)));
        context.assertTrue(chest != null, "chest block entity exists");

        // Fill all chest slots except slot 0, which has 61 logs (room for only 3 more logs)
        for (int i = 0; i < chest.size(); i++) {
            chest.setStack(i, new ItemStack(Items.DIRT, 64));
        }
        chest.setStack(0, new ItemStack(Items.OAK_LOG, 61));

        UUID actionId = UUID.randomUUID();
        // Request transfer of 10 logs; chest only has room for 3
        ItemMoves.TransferResult result = ItemMoves.transfer(actionId, body.inventory(), chest, Items.OAK_LOG, 10);

        context.assertTrue(result.reconciled(), "Partial transfer must reconcile exactly");
        context.assertEquals(3, result.transferred(), "Exactly 3 logs accepted");
        context.assertEquals(7, ItemMoves.count(body.inventory(), Items.OAK_LOG), "7 unaccepted logs safely rolled back into body");
        context.assertEquals(64, ItemMoves.count(chest, Items.OAK_LOG), "Chest slot 0 capped at 64");
        context.assertEquals(71, ItemMoves.count(body.inventory(), Items.OAK_LOG) + ItemMoves.count(chest, Items.OAK_LOG), "Zero logs lost or duplicated");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void attributedPickupSeparatesWantedFromUnrelatedDrops(TestContext context) {
        CompanionEntity body = body(context);
        Vec3d pos = context.getAbsolute(new Vec3d(1.5, 2.0, 1.5));

        // Spawn 4 oak logs and 8 stone blocks as item entities near body
        ItemEntity logDrop = new ItemEntity(context.getWorld(), pos.x, pos.y, pos.z, new ItemStack(Items.OAK_LOG, 4));
        ItemEntity stoneDrop = new ItemEntity(context.getWorld(), pos.x, pos.y, pos.z, new ItemStack(Items.STONE, 8));
        context.getWorld().spawnEntity(logDrop);
        context.getWorld().spawnEntity(stoneDrop);

        UUID actionId = UUID.randomUUID();
        ItemMoves.PickupResult result = ItemMoves.attributedPickup(actionId, body, Items.OAK_LOG, 4);

        context.assertTrue(result.reconciled(), "Attributed pickup must reconcile inventory change");
        context.assertEquals(4, result.pickedUp(), "Picked up exactly 4 logs");
        context.assertEquals(4, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Companion has 4 logs");
        context.assertEquals(0, ItemMoves.count(body.inventory(), Items.STONE), "Companion did not touch unrelated stone drops");
        context.assertTrue(stoneDrop.isAlive(), "Unrelated drop remains on ground");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void visualEquipmentReflectsActualInventory(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.IRON_AXE, 1));

        context.assertTrue(body.getEquippedStack(EquipmentSlot.MAINHAND).isEmpty(), "Initially empty hand");

        boolean equipped = body.equipFromInventory(EquipmentSlot.MAINHAND, Items.IRON_AXE);
        context.assertTrue(equipped, "Equipping from inventory succeeds when item present");
        context.assertEquals(Items.IRON_AXE, body.getEquippedStack(EquipmentSlot.MAINHAND).getItem(), "Main hand reflects owned item");

        boolean failEquip = body.equipFromInventory(EquipmentSlot.MAINHAND, Items.DIAMOND_SWORD);
        context.assertFalse(failEquip, "Cannot equip items not present in inventory");

        body.clearEquipment(EquipmentSlot.MAINHAND);
        context.assertTrue(body.getEquippedStack(EquipmentSlot.MAINHAND).isEmpty(), "Main hand cleared");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void inventorySummaryTracksRevisionsAndItemCounts(TestContext context) {
        CompanionEntity body = body(context);

        InventorySummary initial = body.summary();
        context.assertEquals(0, initial.totalItems(), "Initial summary has 0 items");
        context.assertEquals(0, initial.occupiedSlots(), "Initial summary has 0 occupied slots");
        context.assertEquals(27, initial.freeSlots(), "Initial summary has 27 free slots");
        long rev0 = body.inventoryRevision();

        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 32));
        long rev1 = body.inventoryRevision();
        context.assertTrue(rev1 > rev0, "Revision increments on stack insertion");

        InventorySummary afterAdd = body.summary();
        context.assertEquals(32, afterAdd.totalItems(), "Summary reflects 32 total items");
        context.assertEquals(1, afterAdd.occupiedSlots(), "Summary reflects 1 occupied slot");
        context.assertEquals(32, afterAdd.count("minecraft:oak_log"), "Item count accurate");

        body.inventory().setStack(1, new ItemStack(Items.IRON_PICKAXE, 1));
        InventorySummary multi = body.summary();
        context.assertEquals(33, multi.totalItems(), "Summary reflects 33 total items");
        context.assertEquals(2, multi.occupiedSlots(), "Summary reflects 2 occupied slots");
        context.assertEquals(1, multi.count("minecraft:iron_pickaxe"), "Pickaxe count accurate");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nativeInventoryPersistsAcrossNbtSaveAndRestore(TestContext context) {
        CompanionEntity body = body(context);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 12));
        body.inventory().setStack(5, new ItemStack(Items.BREAD, 3));

        NbtCompound nbt = new NbtCompound();
        body.writeCustomDataToNbt(nbt);

        CompanionEntity restored = body(context);
        restored.readCustomDataFromNbt(nbt);

        context.assertEquals(12, ItemMoves.count(restored.inventory(), Items.OAK_LOG), "12 oak logs restored");
        context.assertEquals(3, ItemMoves.count(restored.inventory(), Items.BREAD), "3 bread restored");
        context.assertEquals(body.inventoryRevision(), restored.inventoryRevision(), "Inventory revision restored");

        context.complete();
    }

    private static CompanionEntity body(TestContext context) {
        CompanionEntity body = KindredMod.COMPANION.create(context.getWorld());
        if (body == null) throw new IllegalStateException("Missing companion factory");
        body.bind(UUID.randomUUID(), UUID.randomUUID());
        Vec3d position = context.getAbsolute(new Vec3d(1.5, 2.0, 1.5));
        body.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return body;
    }
}
