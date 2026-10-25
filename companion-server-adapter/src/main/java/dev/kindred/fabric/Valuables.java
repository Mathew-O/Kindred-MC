package dev.kindred.fabric;

import dev.kindred.core.guide.Worth;
import dev.kindred.core.team.ShareOut;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * A player's things as the value model sees them (E-42): what a stack is, what the player holds, wears and is short
 * of, and what kind of container a chest is from what stands round it. Server thread only.
 */
final class Valuables {
    private Valuables() { }

    static String id(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    /** The levels of the enchantments on a stack, a book's stored ones too. */
    static int enchantments(ItemStack stack) {
        int levels = 0;
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) levels += entry.getIntValue();
        var stored = stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (var entry : stored.getEnchantmentEntries()) levels += entry.getIntValue();
        return levels;
    }

    static Worth.Stack stack(ItemStack stack) {
        return new Worth.Stack(id(stack), stack.getCount(), enchantments(stack));
    }

    /** What the player holds (its kit too), the best kit it has for each slot, worn or carried, and what its run wants it to have. */
    static Worth.Holding holding(ServerPlayerEntity player, Map<String, Integer> wanted) {
        var held = new HashMap<String, Integer>();
        var kit = new EnumMap<ShareOut.Slot, Worth.Piece>(ShareOut.Slot.class);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            held.merge(id(stack), stack.getCount(), Integer::sum);
            note(kit, stack);
        }
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack worn = player.getEquippedStack(slot);
            if (!worn.isEmpty()) note(kit, worn);
        }
        return new Worth.Holding(held, kit, wanted);
    }

    /** Keeps the better of two pieces for a slot: the one with more to it, enchantments breaking a tie. */
    private static void note(Map<ShareOut.Slot, Worth.Piece> kit, ItemStack stack) {
        String item = id(stack);
        var slot = Worth.slotOf(item);
        if (slot.isEmpty()) return;
        var piece = Worth.pieceOf(item, enchantments(stack));
        var own = kit.getOrDefault(slot.get(), Worth.Piece.NONE);
        double gain = ShareOut.gain(slot.get(), own.material(), piece.material());
        if (gain > 0 || gain == 0 && piece.enchantments() > own.enchantments()) kit.put(slot.get(), piece);
    }

    /**
     * A villager's offer as its trading screen shows it (E-45a): its asks at the price it shows, the villager's own,
     * what it yields and the enchantments on it, and its uses.
     */
    static dev.kindred.core.guide.Trades.Offer offer(net.minecraft.village.TradeOffer offer) {
        var first = goods(offer.getDisplayedFirstBuyItem());
        var secondStack = offer.getDisplayedSecondBuyItem();
        var second = secondStack.isEmpty() ? null : goods(secondStack);
        ItemStack sold = offer.getSellItem();
        var enchants = new java.util.ArrayList<dev.kindred.core.guide.Trades.Enchant>();
        for (var entry : sold.getEnchantments().getEnchantmentEntries()) enchants.add(enchant(entry.getKey(), entry.getIntValue()));
        var stored = sold.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        for (var entry : stored.getEnchantmentEntries()) enchants.add(enchant(entry.getKey(), entry.getIntValue()));
        int maxUses = Math.max(1, offer.getMaxUses());
        return new dev.kindred.core.guide.Trades.Offer(first, second, goods(sold), enchants, Math.max(0, Math.min(offer.getUses(), maxUses)), maxUses);
    }

    private static dev.kindred.core.guide.Trades.Goods goods(ItemStack stack) {
        return new dev.kindred.core.guide.Trades.Goods(id(stack), Math.max(1, stack.getCount()));
    }

    private static dev.kindred.core.guide.Trades.Enchant enchant(net.minecraft.registry.entry.RegistryEntry<net.minecraft.enchantment.Enchantment> key,
                                                                 int level) {
        return new dev.kindred.core.guide.Trades.Enchant(key.getKey().map(k -> k.getValue().toString()).orElse("minecraft:unknown"), Math.max(1, level));
    }

    static Worth.Judgement judge(ItemStack stack, Worth.Holding holding) {
        return Worth.judge(stack(stack), holding);
    }

    /**
     * A chest or barrel nobody has opened since the world made it: it still holds its loot table, which the first to open
     * it rolls. A player's own storage never has one. Read without rolling it: its contents are not looked at.
     */
    static boolean unrolled(ServerWorld world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockEntity(pos) instanceof LootableContainerBlockEntity chest && chest.getLootTable() != null;
    }

    static boolean unrolled(StorageMinecartEntity cart) {
        return cart.isAlive() && cart.getLootTable() != null;
    }

    /**
     * What kind of container a chest is, from what a player sees round it: netherrack, magma, gold or crying obsidian
     * by it, a ruined portal; a spawner, a dungeon; otherwise any.
     */
    static Worth.Container kind(ServerWorld world, BlockPos chest) {
        for (BlockPos pos : BlockPos.iterate(chest.add(-4, -2, -4), chest.add(4, 3, 4))) {
            if (!world.isChunkLoaded(pos)) continue;
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.SPAWNER)) return Worth.Container.DUNGEON;
            if (state.isOf(Blocks.NETHERRACK) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CRYING_OBSIDIAN) || state.isOf(Blocks.GOLD_BLOCK)) {
                return Worth.Container.RUINED_PORTAL;
            }
        }
        return Worth.Container.OTHER;
    }
}
