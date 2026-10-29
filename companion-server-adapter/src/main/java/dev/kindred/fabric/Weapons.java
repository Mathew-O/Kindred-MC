package dev.kindred.fabric;

import dev.kindred.core.tactics.DamageModel;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In plain terms: how an AI player weighs up its own swords, axes and mace: what kind, what material and how
 * sharp, so it can pick the right one to fight with.
 *
 * Technical: The player's own melee weapons as {@link DamageModel} sees them (E-39e): kind, material and
 * Sharpness.
 */
final class Weapons {
    private Weapons() { }

    /** The weapon a stack is, or empty for anything that strikes as the hand does. */
    static Optional<DamageModel.Weapon> of(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        if (stack.isOf(Items.MACE)) return Optional.of(new DamageModel.Weapon(DamageModel.Kind.MACE, DamageModel.Tier.NONE, sharpness(stack)));
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        DamageModel.Kind kind = path.endsWith("_sword") ? DamageModel.Kind.SWORD : path.endsWith("_axe") && !path.endsWith("pickaxe") ? DamageModel.Kind.AXE : null;
        if (kind == null) return Optional.empty();
        DamageModel.Tier tier = switch (path.substring(0, path.indexOf('_'))) {
            case "wooden" -> DamageModel.Tier.WOOD;
            case "stone" -> DamageModel.Tier.STONE;
            case "golden" -> DamageModel.Tier.GOLD;
            case "iron" -> DamageModel.Tier.IRON;
            case "diamond" -> DamageModel.Tier.DIAMOND;
            case "netherite" -> DamageModel.Tier.NETHERITE;
            default -> null;
        };
        return tier == null ? Optional.empty() : Optional.of(new DamageModel.Weapon(kind, tier, sharpness(stack)));
    }

    private static int sharpness(ItemStack stack) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(Enchantments.SHARPNESS)) return entry.getIntValue();
        }
        return 0;
    }

    /** A weapon the player holds, and the inventory slot it is in. */
    record Held(int slot, DamageModel.Weapon weapon) { }

    /** Every weapon in the player's inventory, with the slot it is in. */
    static List<Held> held(ServerPlayerEntity player) {
        var held = new ArrayList<Held>();
        for (int slot = 0; slot < 36; slot++) {
            int s = slot;
            of(player.getInventory().getStack(slot)).ifPresent(w -> held.add(new Held(s, w)));
        }
        return held;
    }

    /**
     * The slot to fight with: the weapon that deals the most over the fight, landing criticals, unless one harder hit
     * finishes {@code health} where the steadiest weapon's would not; empty to fight with the hand.
     */
    static Optional<Held> choose(ServerPlayerEntity player, double health) {
        return choose(player, health, false);
    }

    /** As {@link #choose(ServerPlayerEntity, double)}; with {@code hotbar}, only what is in the hotbar (no swap mid-fight). */
    static Optional<Held> choose(ServerPlayerEntity player, double health, boolean hotbar) {
        var held = held(player).stream().filter(h -> !hotbar || h.slot() < 9).toList();
        if (held.isEmpty()) return Optional.empty();
        var weapons = held.stream().map(Held::weapon).toList();
        var best = DamageModel.best(weapons, true, 0).orElseThrow();
        var hardest = DamageModel.hardest(weapons, true, 0).orElseThrow();
        var pick = DamageModel.hit(best, 1, true, 0, 0) < health && DamageModel.hit(hardest, 1, true, 0, 0) >= health ? hardest : best;
        return held.stream().filter(h -> h.weapon().equals(pick)).findFirst();
    }
}
