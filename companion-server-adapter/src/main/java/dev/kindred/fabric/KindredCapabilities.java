package dev.kindred.fabric;

import dev.kindred.core.Capabilities;
import dev.kindred.core.VanillaIds;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/**
 * In plain terms: tells core what this server's game says things are (F-38): which items its tags call logs,
 * planks or saplings, and how good each tool is, so a mod's tagged log or new pickaxe is understood on sight.
 *
 * Technical: Builds a {@link Capabilities} snapshot from the server's registries and tags. Called on the server
 * thread once the server has started and again after every datapack reload, and installed whole; core reads only
 * the plain strings it holds. For each exact {@link VanillaIds.Group} the item tag of that name is resolved (the
 * block tag where no item tag exists); tools are read from each tool item's class and material, a vanilla tool
 * keeping the tier {@link VanillaIds} gives it.
 */
final class KindredCapabilities {

    private KindredCapabilities() { }

    /** Builds the snapshot from the server's current tags and installs it. Server thread only. */
    static Capabilities refresh() {
        Capabilities facts = Capabilities.fromServer(tagMembers(), tools());
        Capabilities.install(facts);
        return facts;
    }

    /** Back to the vanilla facts once the server has stopped. */
    static void clear() {
        Capabilities.reset();
    }

    private static Map<String, Set<String>> tagMembers() {
        Map<String, Set<String>> out = new HashMap<>();
        for (VanillaIds.Group group : VanillaIds.groups()) {
            if (!group.exact()) continue;
            Identifier id = Identifier.tryParse(group.tag().substring(1));
            if (id == null) continue;
            Set<String> ids = new LinkedHashSet<>();
            var itemTag = TagKey.of(RegistryKeys.ITEM, id);
            Registries.ITEM.iterateEntries(itemTag).forEach(e -> ids.add(Registries.ITEM.getId(e.value()).toString()));
            if (ids.isEmpty()) {
                var blockTag = TagKey.of(RegistryKeys.BLOCK, id);
                Registries.BLOCK.iterateEntries(blockTag).forEach(e -> ids.add(Registries.BLOCK.getId(e.value()).toString()));
            }
            // A tag the server lacks altogether (none bound) leaves the vanilla members in place.
            if (!ids.isEmpty()) out.put(group.tag(), ids);
        }
        return out;
    }

    private static Map<String, Capabilities.Tool> tools() {
        Map<String, Capabilities.Tool> out = new HashMap<>();
        for (Item item : Registries.ITEM) {
            String kind = kind(item);
            if (kind == null || !(item instanceof ToolItem tool)) continue;
            out.put(Registries.ITEM.getId(item).toString(), new Capabilities.Tool(kind, tier(tool.getMaterial())));
        }
        return out;
    }

    private static String kind(Item item) {
        if (item instanceof PickaxeItem) return Capabilities.Tool.PICKAXE;
        if (item instanceof AxeItem) return Capabilities.Tool.AXE;
        if (item instanceof ShovelItem) return Capabilities.Tool.SHOVEL;
        if (item instanceof HoeItem) return Capabilities.Tool.HOE;
        if (item instanceof SwordItem) return Capabilities.Tool.SWORD;
        return null;
    }

    /** A material's tier by the blocks it cannot mine: the vanilla ladder, gold at 0 as in {@link VanillaIds}. */
    static int tier(ToolMaterial material) {
        TagKey<net.minecraft.block.Block> cannot = material.getInverseTag();
        if (cannot.equals(BlockTags.INCORRECT_FOR_NETHERITE_TOOL)) return 5;
        if (cannot.equals(BlockTags.INCORRECT_FOR_DIAMOND_TOOL)) return 4;
        if (cannot.equals(BlockTags.INCORRECT_FOR_IRON_TOOL)) return 3;
        if (cannot.equals(BlockTags.INCORRECT_FOR_STONE_TOOL)) return 2;
        if (cannot.equals(BlockTags.INCORRECT_FOR_GOLD_TOOL)) return 0;
        return 1;
    }
}
