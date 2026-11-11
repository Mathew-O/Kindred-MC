package dev.kindred.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * In plain terms: the one place in core that names vanilla Minecraft things by id (F-38). Everything else asks
 * {@link Capabilities} what a thing is. Each id here says which tag it would be read from; where the game has
 * such a tag the server's own tag replaces the list below, so a mod's tagged log counts as a log.
 *
 * Technical: Two kinds of entry. A {@link Group} is a set of vanilla ids standing for a tag: {@code exact} when
 * the list is that tag's vanilla 1.21.1 content (the adapter then takes the server's tag instead), not exact when
 * no tag says the same (the list stays as written; the tag named is the one a profile or datapack would use, in the
 * {@code kindred} namespace when vanilla and {@code c} have none). A single id is a fallback where no tag exists
 * (the Nether portal frame is obsidian; air is air), with the tag it would use in its comment. No other main
 * class in core may hold a {@code minecraft:} literal: {@code VanillaIdsLiteralTest} fails if one does.
 */
public final class VanillaIds {

    /** The vanilla namespace, with its colon. */
    public static final String NAMESPACE = "minecraft:";

    /**
     * Vanilla ids standing for a tag. {@code exact}: these are the tag's vanilla members, so the server's tag
     * replaces them; otherwise no tag says the same, and these ids stand as written.
     */
    public record Group(String tag, boolean exact, Set<String> ids) {
        public Group {
            if (!tag.startsWith("#")) throw new IllegalArgumentException("a tag starts with #: " + tag);
            ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        }
    }

    private static final List<Group> GROUPS = new ArrayList<>();

    private static Group group(String tag, boolean exact, String... ids) {
        Group group = new Group(tag, exact, Set.of(ids));
        GROUPS.add(group);
        return group;
    }

    /** Every group, in the order declared. */
    public static List<Group> groups() { return List.copyOf(GROUPS); }

    // ---- Single ids: no tag says these. The tag each would use is in its comment. ----

    /** Air: an empty slot or cell. Would use the block state's isAir; an item stack's isEmpty. */
    public static final String AIR = "minecraft:air";
    /** #c:chests would hold it; a chest placed or asked for by name. */
    public static final String CHEST = "minecraft:chest";
    /** #kindred:light_sources would hold it. */
    public static final String TORCH = "minecraft:torch";
    /** #kindred:light_sources would hold it. */
    public static final String LANTERN = "minecraft:lantern";
    /** #c:cobblestones would hold it; the commonest building block. */
    public static final String COBBLESTONE = "minecraft:cobblestone";
    /** #minecraft:planks holds it; the default plank when no wood is held. */
    public static final String OAK_PLANKS = "minecraft:oak_planks";
    /** #minecraft:dirt holds it; the plainest filler block. */
    public static final String DIRT = "minecraft:dirt";
    /** #c:crops/wheat would hold it. */
    public static final String WHEAT = "minecraft:wheat";

    // ---- Item-frame icons for the chest categories: shown, not reasoned about; would be the first item of each category's tag. ----

    public static final String ICON_VALUABLES = "minecraft:diamond";
    public static final String ICON_TOOLS = "minecraft:iron_pickaxe";
    public static final String ICON_FOOD = "minecraft:golden_carrot";
    public static final String ICON_WOOD = "minecraft:oak_log";
    public static final String ICON_STONE = "minecraft:stone_bricks";
    public static final String ICON_REDSTONE = "minecraft:redstone";
    public static final String ICON_NETHER_END = "minecraft:blaze_rod";
    public static final String ICON_MISC = "minecraft:ender_pearl";

    // ---- Chest categories (ChestOrganizationPolicy). None is one tag; each would be a kindred tag. ----

    /** Ores, ingots, gems and their blocks: #c:ingots, #c:gems, #c:raw_materials, #c:storage_blocks, #minecraft:coals together. */
    public static final Group CHEST_VALUABLES = group("#kindred:storage/valuables", false,
            "minecraft:diamond", "minecraft:diamond_block",
            "minecraft:emerald", "minecraft:emerald_block",
            "minecraft:iron_ingot", "minecraft:iron_block", "minecraft:raw_iron", "minecraft:raw_iron_block",
            "minecraft:gold_ingot", "minecraft:gold_block", "minecraft:raw_gold", "minecraft:raw_gold_block",
            "minecraft:gold_nugget",
            "minecraft:copper_ingot", "minecraft:raw_copper",
            "minecraft:coal", "minecraft:charcoal", "minecraft:coal_block",
            "minecraft:lapis_lazuli", "minecraft:lapis_block",
            "minecraft:quartz", "minecraft:amethyst_shard",
            "minecraft:ancient_debris", "minecraft:netherite_scrap", "minecraft:netherite_ingot",
            "minecraft:netherite_upgrade_smithing_template");

    /** Redstone parts, rails and carts. */
    public static final Group CHEST_REDSTONE = group("#kindred:storage/redstone", false,
            "minecraft:redstone", "minecraft:redstone_block", "minecraft:redstone_torch",
            "minecraft:repeater", "minecraft:comparator", "minecraft:observer",
            "minecraft:piston", "minecraft:sticky_piston", "minecraft:hopper", "minecraft:dropper",
            "minecraft:dispenser",
            "minecraft:rail", "minecraft:powered_rail", "minecraft:detector_rail", "minecraft:activator_rail",
            "minecraft:minecart", "minecraft:chest_minecart", "minecraft:hopper_minecart",
            "minecraft:lever", "minecraft:target", "minecraft:daylight_detector");

    /** Nether and End materials. */
    public static final Group CHEST_NETHER_END = group("#kindred:storage/nether_and_end", false,
            "minecraft:blaze_rod", "minecraft:blaze_powder", "minecraft:nether_wart",
            "minecraft:magma_cream", "minecraft:ghast_tear", "minecraft:glowstone_dust", "minecraft:glowstone",
            "minecraft:shroomlight", "minecraft:soul_sand", "minecraft:soul_soil",
            "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_eye", "minecraft:end_rod",
            "minecraft:chorus_fruit", "minecraft:shulker_shell", "minecraft:dragon_breath", "minecraft:elytra");

    /** Items whose id begins with this are Nether materials in the chest sort; would be #kindred:storage/nether_and_end. */
    public static final String NETHER_PREFIX = "minecraft:nether_";

    /** Combat gear that is not a tool or armour piece by name. */
    public static final Group CHEST_COMBAT = group("#kindred:storage/combat", false,
            "minecraft:bow", "minecraft:crossbow", "minecraft:trident", "minecraft:mace",
            "minecraft:shield", "minecraft:totem_of_undying", "minecraft:arrow", "minecraft:spectral_arrow",
            "minecraft:wind_charge", "minecraft:enchanted_book");

    /** Crops and food not caught by the food words. */
    public static final Group CHEST_CROPS = group("#kindred:storage/crops", false,
            "minecraft:sugar_cane", "minecraft:hay_block", "minecraft:kelp", "minecraft:dried_kelp");

    /** Timber not caught by the wood tags (the mushroom stem was filed as timber by its "_stem" ending). */
    public static final Group CHEST_TIMBER = group("#kindred:storage/timber", false,
            "minecraft:stick", "minecraft:bamboo", "minecraft:mushroom_stem");

    // ---- Single ids moved here from core by name (F-38). Each says the tag or component it would use. ----

    /** Would use an advancement id; no tag. */
    public static final String ADV_END_KILL_DRAGON = "minecraft:end/kill_dragon";
    /** Would use an advancement id; no tag. */
    public static final String ADV_NETHER_OBTAIN_BLAZE_ROD = "minecraft:nether/obtain_blaze_rod";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_ENCHANT_ITEM = "minecraft:story/enchant_item";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_ENTER_THE_END = "minecraft:story/enter_the_end";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_ENTER_THE_NETHER = "minecraft:story/enter_the_nether";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_FOLLOW_ENDER_EYE = "minecraft:story/follow_ender_eye";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_MINE_DIAMOND = "minecraft:story/mine_diamond";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_MINE_STONE = "minecraft:story/mine_stone";
    /** Would use an advancement id; no tag. */
    public static final String ADV_STORY_SMELT_IRON = "minecraft:story/smelt_iron";
    /** Would use #c:ores. */
    public static final String ANCIENT_DEBRIS = "minecraft:ancient_debris";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String ANDESITE = "minecraft:andesite";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String ANVIL = "minecraft:anvil";
    /** Would use a potion or effect key; no tag. */
    public static final String AWKWARD = "minecraft:awkward";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String BAMBOO = "minecraft:bamboo";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String BARRIER = "minecraft:barrier";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String BEDROCK = "minecraft:bedrock";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BEEHIVE = "minecraft:beehive";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String BEETROOT = "minecraft:beetroot";
    /** Would use #c:seeds. */
    public static final String BEETROOT_SEEDS = "minecraft:beetroot_seeds";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BEE_NEST = "minecraft:bee_nest";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BELL = "minecraft:bell";
    /** Would use #c:concretes. */
    public static final String BLACK_CONCRETE = "minecraft:black_concrete";
    /** Would use #c:player_workstations/furnaces. */
    public static final String BLAST_FURNACE = "minecraft:blast_furnace";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BLAZE_POWDER = "minecraft:blaze_powder";
    /** Would use #c:concretes. */
    public static final String BLUE_CONCRETE = "minecraft:blue_concrete";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BOOK = "minecraft:book";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BOOKSHELF = "minecraft:bookshelf";
    /** Would use #c:tools/ranged_weapons or the projectile interface. */
    public static final String BOW = "minecraft:bow";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String BRICKS = "minecraft:bricks";
    /** Would use the fluid state. */
    public static final String BUBBLE_COLUMN = "minecraft:bubble_column";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String BUDDING_AMETHYST = "minecraft:budding_amethyst";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String CACTUS = "minecraft:cactus";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String CARVED_PUMPKIN = "minecraft:carved_pumpkin";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String CAT = "minecraft:cat";
    /** Would use BlockState.isAir. */
    public static final String CAVE_AIR = "minecraft:cave_air";
    /** Would use #c:storage_blocks. */
    public static final String CHAIN_COMMAND_BLOCK = "minecraft:chain_command_block";
    /** Would use the food component. */
    public static final String CHICKEN = "minecraft:chicken";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String CHIPPED_ANVIL = "minecraft:chipped_anvil";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String CHORUS_PLANT = "minecraft:chorus_plant";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String COARSE_DIRT = "minecraft:coarse_dirt";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String COBBLED_DEEPSLATE = "minecraft:cobbled_deepslate";
    /** Would use #c:storage_blocks. */
    public static final String COMMAND_BLOCK = "minecraft:command_block";
    /** Would use #c:storage_blocks. */
    public static final String COPPER_BLOCK = "minecraft:copper_block";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String COW = "minecraft:cow";
    /** Would use the hostile class and a behaviour. */
    public static final String CREEPER = "minecraft:creeper";
    /** Would use #c:tools/ranged_weapons or the projectile interface. */
    public static final String CROSSBOW = "minecraft:crossbow";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DAMAGED_ANVIL = "minecraft:damaged_anvil";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DANDELION = "minecraft:dandelion";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DEEPSLATE = "minecraft:deepslate";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DEEPSLATE_BRICKS = "minecraft:deepslate_bricks";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String DIAMOND_AXE = "minecraft:diamond_axe";
    /** Would use the armour and toughness attributes. */
    public static final String DIAMOND_BOOTS = "minecraft:diamond_boots";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String DIAMOND_PICKAXE = "minecraft:diamond_pickaxe";
    /** Would use the attack damage and speed attributes. */
    public static final String DIAMOND_SWORD = "minecraft:diamond_sword";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DIORITE = "minecraft:diorite";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DIRT_PATH = "minecraft:dirt_path";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DISPENSER = "minecraft:dispenser";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String DONKEY = "minecraft:donkey";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String DROPPER = "minecraft:dropper";
    /** Would use an enchantment key; no tag. */
    public static final String EFFICIENCY = "minecraft:efficiency";
    /** Would use the hostile class and a behaviour. */
    public static final String ELDER_GUARDIAN = "minecraft:elder_guardian";
    /** Would use #c:gems. */
    public static final String EMERALD = "minecraft:emerald";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String ENCHANTING_TABLE = "minecraft:enchanting_table";
    /** Would use #c:chests. */
    public static final String ENDER_CHEST = "minecraft:ender_chest";
    /** Would use the hostile class and a behaviour. */
    public static final String ENDER_DRAGON = "minecraft:ender_dragon";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String END_GATEWAY = "minecraft:end_gateway";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String END_PORTAL = "minecraft:end_portal";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String END_PORTAL_FRAME = "minecraft:end_portal_frame";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String FARMLAND = "minecraft:farmland";
    /** Would use an enchantment key; no tag. */
    public static final String FEATHER_FALLING = "minecraft:feather_falling";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String FERN = "minecraft:fern";
    /** Would use #minecraft:fire. */
    public static final String FIRE = "minecraft:fire";
    /** Would use an enchantment key; no tag. */
    public static final String FIRE_PROTECTION = "minecraft:fire_protection";
    /** Would use a potion or effect key; no tag. */
    public static final String FIRE_RESISTANCE = "minecraft:fire_resistance";
    /** Would use an enchantment key; no tag. */
    public static final String FORTUNE = "minecraft:fortune";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String FROSTED_ICE = "minecraft:frosted_ice";
    /** Would use #c:glass_blocks. */
    public static final String GLASS = "minecraft:glass";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String GLASS_BOTTLE = "minecraft:glass_bottle";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String GLISTERING_MELON_SLICE = "minecraft:glistering_melon_slice";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String GLOWSTONE = "minecraft:glowstone";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String GOLDEN_AXE = "minecraft:golden_axe";
    /** Would use the armour and toughness attributes. */
    public static final String GOLDEN_HELMET = "minecraft:golden_helmet";
    /** Would use the attack damage and speed attributes. */
    public static final String GOLDEN_SWORD = "minecraft:golden_sword";
    /** Would use #c:storage_blocks. */
    public static final String GOLD_BLOCK = "minecraft:gold_block";
    /** Would use #c:ingots. */
    public static final String GOLD_INGOT = "minecraft:gold_ingot";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String GRANITE = "minecraft:granite";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String GRAVEL = "minecraft:gravel";
    /** Would use a potion or effect key; no tag. */
    public static final String HEALING = "minecraft:healing";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String HOPPER = "minecraft:hopper";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String HORSE = "minecraft:horse";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_CHISELED_STONE_BRICKS = "minecraft:infested_chiseled_stone_bricks";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_COBBLESTONE = "minecraft:infested_cobblestone";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_CRACKED_STONE_BRICKS = "minecraft:infested_cracked_stone_bricks";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_DEEPSLATE = "minecraft:infested_deepslate";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_MOSSY_STONE_BRICKS = "minecraft:infested_mossy_stone_bricks";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_STONE = "minecraft:infested_stone";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String INFESTED_STONE_BRICKS = "minecraft:infested_stone_bricks";
    /** Would use an enchantment key; no tag. */
    public static final String INFINITY = "minecraft:infinity";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String IRON_AXE = "minecraft:iron_axe";
    /** Would use #c:storage_blocks. */
    public static final String IRON_BLOCK = "minecraft:iron_block";
    /** Would use the armour and toughness attributes. */
    public static final String IRON_BOOTS = "minecraft:iron_boots";
    /** Would use the armour and toughness attributes. */
    public static final String IRON_CHESTPLATE = "minecraft:iron_chestplate";
    /** Would use #minecraft:doors. */
    public static final String IRON_DOOR = "minecraft:iron_door";
    /** Would use the attack damage and speed attributes. */
    public static final String IRON_SWORD = "minecraft:iron_sword";
    /** Would use #kindred:light_sources. */
    public static final String JACK_O_LANTERN = "minecraft:jack_o_lantern";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String JIGSAW = "minecraft:jigsaw";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String KELP = "minecraft:kelp";
    /** Would use #minecraft:climbable. */
    public static final String LADDER = "minecraft:ladder";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String LARGE_FERN = "minecraft:large_fern";
    /** Would use the fluid state. */
    public static final String LAVA = "minecraft:lava";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String LEATHER = "minecraft:leather";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String LECTERN = "minecraft:lectern";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String LEVER = "minecraft:lever";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String LIBRARIAN = "minecraft:librarian";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String LIGHT = "minecraft:light";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String LOOM = "minecraft:loom";
    /** Would use an enchantment key; no tag. */
    public static final String LOOTING = "minecraft:looting";
    /** Would use the attack damage and speed attributes. */
    public static final String MACE = "minecraft:mace";
    /** Would use #c:storage_blocks. */
    public static final String MAGMA_BLOCK = "minecraft:magma_block";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String MAGMA_CREAM = "minecraft:magma_cream";
    /** Would use #c:seeds. */
    public static final String MELON_SEEDS = "minecraft:melon_seeds";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String MOSSY_COBBLESTONE = "minecraft:mossy_cobblestone";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String MOVING_PISTON = "minecraft:moving_piston";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String MULE = "minecraft:mule";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String NETHERITE_AXE = "minecraft:netherite_axe";
    /** Would use #c:ingots. */
    public static final String NETHERITE_INGOT = "minecraft:netherite_ingot";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String NETHERITE_SCRAP = "minecraft:netherite_scrap";
    /** Would use the attack damage and speed attributes. */
    public static final String NETHERITE_SWORD = "minecraft:netherite_sword";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String NETHERITE_UPGRADE_SMITHING_TEMPLATE = "minecraft:netherite_upgrade_smithing_template";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String NETHERRACK = "minecraft:netherrack";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String NETHER_PORTAL = "minecraft:nether_portal";
    /** Would use #minecraft:fences. */
    public static final String OAK_FENCE = "minecraft:oak_fence";
    /** Would use #minecraft:slabs. */
    public static final String OAK_SLAB = "minecraft:oak_slab";
    /** Would use #minecraft:stairs. */
    public static final String OAK_STAIRS = "minecraft:oak_stairs";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String OBSERVER = "minecraft:observer";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String OBSIDIAN = "minecraft:obsidian";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String PARROT = "minecraft:parrot";
    /** Would use #minecraft:slabs. */
    public static final String PETRIFIED_OAK_SLAB = "minecraft:petrified_oak_slab";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String PHANTOM_MEMBRANE = "minecraft:phantom_membrane";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String PIG = "minecraft:pig";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String PISTON = "minecraft:piston";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String PISTON_HEAD = "minecraft:piston_head";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String POLISHED_ANDESITE = "minecraft:polished_andesite";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String POPPY = "minecraft:poppy";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String POWDER_SNOW = "minecraft:powder_snow";
    /** Would use an enchantment key; no tag. */
    public static final String POWER = "minecraft:power";
    /** Would use an enchantment key; no tag. */
    public static final String PROJECTILE_PROTECTION = "minecraft:projectile_protection";
    /** Would use an enchantment key; no tag. */
    public static final String PROTECTION = "minecraft:protection";
    /** Would use #c:seeds. */
    public static final String PUMPKIN_SEEDS = "minecraft:pumpkin_seeds";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String RAIL = "minecraft:rail";
    /** Would use #kindred:light_sources. */
    public static final String REDSTONE_TORCH = "minecraft:redstone_torch";
    /** Would use #kindred:light_sources. */
    public static final String REDSTONE_WALL_TORCH = "minecraft:redstone_wall_torch";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String REDSTONE_WIRE = "minecraft:redstone_wire";
    /** Would use #minecraft:beds. */
    public static final String RED_BED = "minecraft:red_bed";
    /** Would use #c:concretes. */
    public static final String RED_CONCRETE = "minecraft:red_concrete";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String RED_SAND = "minecraft:red_sand";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String RED_SANDSTONE = "minecraft:red_sandstone";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String REINFORCED_DEEPSLATE = "minecraft:reinforced_deepslate";
    /** Would use #c:storage_blocks. */
    public static final String REPEATING_COMMAND_BLOCK = "minecraft:repeating_command_block";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SAND = "minecraft:sand";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SANDSTONE = "minecraft:sandstone";
    /** Would use #minecraft:climbable. */
    public static final String SCAFFOLDING = "minecraft:scaffolding";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SEAGRASS = "minecraft:seagrass";
    /** Would use an enchantment key; no tag. */
    public static final String SHARPNESS = "minecraft:sharpness";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String SHEEP = "minecraft:sheep";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SHORT_GRASS = "minecraft:short_grass";
    /** Would use a potion or effect key; no tag. */
    public static final String SLOW_FALLING = "minecraft:slow_falling";
    /** Would use #c:player_workstations/furnaces. */
    public static final String SMOKER = "minecraft:smoker";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SMOOTH_QUARTZ = "minecraft:smooth_quartz";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SMOOTH_SANDSTONE = "minecraft:smooth_sandstone";
    /** Would use #minecraft:slabs. */
    public static final String SMOOTH_STONE_SLAB = "minecraft:smooth_stone_slab";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SNOW = "minecraft:snow";
    /** Would use #minecraft:fire. */
    public static final String SOUL_FIRE = "minecraft:soul_fire";
    /** Would use #kindred:light_sources. */
    public static final String SOUL_LANTERN = "minecraft:soul_lantern";
    /** Would use #kindred:light_sources. */
    public static final String SOUL_TORCH = "minecraft:soul_torch";
    /** Would use #kindred:light_sources. */
    public static final String SOUL_WALL_TORCH = "minecraft:soul_wall_torch";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String SPAWNER = "minecraft:spawner";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String STICKY_PISTON = "minecraft:sticky_piston";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String STONECUTTER = "minecraft:stonecutter";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String STONE_AXE = "minecraft:stone_axe";
    /** Would use the attack damage and speed attributes. */
    public static final String STONE_SWORD = "minecraft:stone_sword";
    /** Would use a potion or effect key; no tag. */
    public static final String STRENGTH = "minecraft:strength";
    /** Would use #c:storage_blocks. */
    public static final String STRUCTURE_BLOCK = "minecraft:structure_block";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String STRUCTURE_VOID = "minecraft:structure_void";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String SUGAR_CANE = "minecraft:sugar_cane";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SUSPICIOUS_GRAVEL = "minecraft:suspicious_gravel";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String SUSPICIOUS_SAND = "minecraft:suspicious_sand";
    /** Would use an enchantment key; no tag. */
    public static final String SWEEPING_EDGE = "minecraft:sweeping_edge";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String SWEET_BERRY_BUSH = "minecraft:sweet_berry_bush";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String TALL_GRASS = "minecraft:tall_grass";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String TALL_SEAGRASS = "minecraft:tall_seagrass";
    /** Would use #c:storage_blocks. */
    public static final String TEST_BLOCK = "minecraft:test_block";
    /** Would use #c:storage_blocks. */
    public static final String TEST_INSTANCE_BLOCK = "minecraft:test_instance_block";
    /** Would use a dimension key; no tag. */
    public static final String THE_END = "minecraft:the_end";
    /** Would use #c:seeds. */
    public static final String TORCHFLOWER_SEEDS = "minecraft:torchflower_seeds";
    /** Would use #c:chests. */
    public static final String TRAPPED_CHEST = "minecraft:trapped_chest";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String TRIAL_SPAWNER = "minecraft:trial_spawner";
    /** Would use the attack damage and speed attributes. */
    public static final String TRIDENT = "minecraft:trident";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String TRIPWIRE = "minecraft:tripwire";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String TRIPWIRE_HOOK = "minecraft:tripwire_hook";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String TUFF = "minecraft:tuff";
    /** Would use an enchantment key; no tag. */
    public static final String UNBREAKING = "minecraft:unbreaking";
    /** Would use #kindred:unbreakable; no vanilla tag. */
    public static final String VAULT = "minecraft:vault";
    /** Would use #minecraft:climbable. */
    public static final String VINE = "minecraft:vine";
    /** Would use BlockState.isAir. */
    public static final String VOID_AIR = "minecraft:void_air";
    /** Would use #kindred:light_sources. */
    public static final String WALL_TORCH = "minecraft:wall_torch";
    /** Would use the hostile class and a behaviour. */
    public static final String WARDEN = "minecraft:warden";
    /** Would use the fluid state. */
    public static final String WATER = "minecraft:water";
    /** Would use #minecraft:beds. */
    public static final String WHITE_BED = "minecraft:white_bed";
    /** Would use #c:concretes. */
    public static final String WHITE_CONCRETE = "minecraft:white_concrete";
    /** Would use #minecraft:wool. */
    public static final String WHITE_WOOL = "minecraft:white_wool";
    /** Would use the hostile class and a behaviour. */
    public static final String WITHER = "minecraft:wither";
    /** Would use the entity type; #kindred:livestock or a villager profession. */
    public static final String WOLF = "minecraft:wolf";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String WOODEN_AXE = "minecraft:wooden_axe";
    /** Would use the tool material and #minecraft:pickaxes/axes/shovels/hoes. */
    public static final String WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    /** Would use the attack damage and speed attributes. */
    public static final String WOODEN_SWORD = "minecraft:wooden_sword";
    /** Would use the food component. */
    public static final String BREAD = "minecraft:bread";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String CARROT = "minecraft:carrot";
    /** Would use #minecraft:coals; the fuel registry. */
    public static final String CHARCOAL = "minecraft:charcoal";
    /** Would use the food component. */
    public static final String COOKED_BEEF = "minecraft:cooked_beef";
    /** Would use the food component. */
    public static final String COOKED_PORKCHOP = "minecraft:cooked_porkchop";
    /** Would use #c:player_workstations/crafting_tables. */
    public static final String CRAFTING_TABLE = "minecraft:crafting_table";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String FLINT_AND_STEEL = "minecraft:flint_and_steel";
    /** Would use #c:player_workstations/furnaces. */
    public static final String FURNACE = "minecraft:furnace";
    /** Would use #c:ingots. */
    public static final String IRON_INGOT = "minecraft:iron_ingot";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String NETHER_WART = "minecraft:nether_wart";
    /** Would use a dimension key; no tag. */
    public static final String OVERWORLD = "minecraft:overworld";
    /** Would use #minecraft:crops or #c:crops. */
    public static final String POTATO = "minecraft:potato";
    /** Would use the armour and toughness attributes. */
    public static final String SHIELD = "minecraft:shield";
    /** Would use #kindred:palette; a block or item chosen by name. */
    public static final String STONE = "minecraft:stone";
    /** Would use a dimension key; no tag. */
    public static final String THE_NETHER = "minecraft:the_nether";
    /** Would use #c:seeds. */
    public static final String WHEAT_SEEDS = "minecraft:wheat_seeds";
    // ---- Wood. Exact: the server's tags replace these, so a tagged modded wood is wood. ----

    /** #minecraft:logs as in vanilla 1.21.1. */
    public static final Group LOGS = group("#minecraft:logs", true,
            "minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood",
            "minecraft:spruce_log", "minecraft:spruce_wood", "minecraft:stripped_spruce_log", "minecraft:stripped_spruce_wood",
            "minecraft:birch_log", "minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood",
            "minecraft:jungle_log", "minecraft:jungle_wood", "minecraft:stripped_jungle_log", "minecraft:stripped_jungle_wood",
            "minecraft:acacia_log", "minecraft:acacia_wood", "minecraft:stripped_acacia_log", "minecraft:stripped_acacia_wood",
            "minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood",
            "minecraft:mangrove_log", "minecraft:mangrove_wood", "minecraft:stripped_mangrove_log", "minecraft:stripped_mangrove_wood",
            "minecraft:cherry_log", "minecraft:cherry_wood", "minecraft:stripped_cherry_log", "minecraft:stripped_cherry_wood",
            "minecraft:crimson_stem", "minecraft:crimson_hyphae", "minecraft:stripped_crimson_stem", "minecraft:stripped_crimson_hyphae",
            "minecraft:warped_stem", "minecraft:warped_hyphae", "minecraft:stripped_warped_stem", "minecraft:stripped_warped_hyphae");

    /** #minecraft:planks as in vanilla 1.21.1. */
    public static final Group PLANKS = group("#minecraft:planks", true,
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
            "minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:crimson_planks", "minecraft:warped_planks",
            "minecraft:mangrove_planks", "minecraft:bamboo_planks", "minecraft:cherry_planks");

    /** #minecraft:saplings as in vanilla 1.21.1. */
    public static final Group SAPLINGS = group("#minecraft:saplings", true,
            "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling", "minecraft:jungle_sapling",
            "minecraft:acacia_sapling", "minecraft:dark_oak_sapling", "minecraft:azalea", "minecraft:flowering_azalea",
            "minecraft:mangrove_propagule", "minecraft:cherry_sapling");

    /** The wool a bed is counted from; #minecraft:wool holds these and twelve more, which the count never took. */
    public static final Group BED_WOOL = group("#kindred:bed_wool", false,
            "minecraft:white_wool", "minecraft:black_wool", "minecraft:red_wool", "minecraft:yellow_wool");

    /** #c:rods/wooden holds it; sticks for tools, torches and fences. */
    public static final String STICK = "minecraft:stick";

    /** Small things an idle player picks up off the ground; would be #kindred:trinkets (saplings by #minecraft:saplings). */
    public static final Group TRINKETS = group("#kindred:trinkets", false,
            "minecraft:stick",
            "minecraft:feather", "minecraft:flint", "minecraft:wheat_seeds",
            "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:beetroot_seeds",
            "minecraft:sweet_berries", "minecraft:apple", "minecraft:bone",
            "minecraft:string", "minecraft:nautilus_shell", "minecraft:dandelion",
            "minecraft:poppy", "minecraft:cornflower", "minecraft:lily_of_the_valley");

    // ---- Tools. Would be read from the item's tool material (#minecraft:pickaxes and the incorrect_for_*_tool tags);
    // vanilla's stand as written, gold at tier 0 because nothing counted it as a tool tier before. ----

    private static final java.util.Map<String, Capabilities.Tool> TOOLS = buildTools();

    private static java.util.Map<String, Capabilities.Tool> buildTools() {
        java.util.Map<String, Capabilities.Tool> tools = new java.util.HashMap<>();
        tool(tools, "minecraft:wooden_pickaxe", Capabilities.Tool.PICKAXE, 1);
        tool(tools, "minecraft:stone_pickaxe", Capabilities.Tool.PICKAXE, 2);
        tool(tools, "minecraft:iron_pickaxe", Capabilities.Tool.PICKAXE, 3);
        tool(tools, "minecraft:diamond_pickaxe", Capabilities.Tool.PICKAXE, 4);
        tool(tools, "minecraft:netherite_pickaxe", Capabilities.Tool.PICKAXE, 5);
        tool(tools, "minecraft:golden_pickaxe", Capabilities.Tool.PICKAXE, 0);
        tool(tools, "minecraft:wooden_axe", Capabilities.Tool.AXE, 1);
        tool(tools, "minecraft:stone_axe", Capabilities.Tool.AXE, 2);
        tool(tools, "minecraft:iron_axe", Capabilities.Tool.AXE, 3);
        tool(tools, "minecraft:diamond_axe", Capabilities.Tool.AXE, 4);
        tool(tools, "minecraft:netherite_axe", Capabilities.Tool.AXE, 5);
        tool(tools, "minecraft:golden_axe", Capabilities.Tool.AXE, 0);
        tool(tools, "minecraft:wooden_shovel", Capabilities.Tool.SHOVEL, 1);
        tool(tools, "minecraft:stone_shovel", Capabilities.Tool.SHOVEL, 2);
        tool(tools, "minecraft:iron_shovel", Capabilities.Tool.SHOVEL, 3);
        tool(tools, "minecraft:diamond_shovel", Capabilities.Tool.SHOVEL, 4);
        tool(tools, "minecraft:netherite_shovel", Capabilities.Tool.SHOVEL, 5);
        tool(tools, "minecraft:golden_shovel", Capabilities.Tool.SHOVEL, 0);
        tool(tools, "minecraft:wooden_hoe", Capabilities.Tool.HOE, 1);
        tool(tools, "minecraft:stone_hoe", Capabilities.Tool.HOE, 2);
        tool(tools, "minecraft:iron_hoe", Capabilities.Tool.HOE, 3);
        tool(tools, "minecraft:diamond_hoe", Capabilities.Tool.HOE, 4);
        tool(tools, "minecraft:netherite_hoe", Capabilities.Tool.HOE, 5);
        tool(tools, "minecraft:golden_hoe", Capabilities.Tool.HOE, 0);
        tool(tools, "minecraft:wooden_sword", Capabilities.Tool.SWORD, 1);
        tool(tools, "minecraft:stone_sword", Capabilities.Tool.SWORD, 2);
        tool(tools, "minecraft:iron_sword", Capabilities.Tool.SWORD, 3);
        tool(tools, "minecraft:diamond_sword", Capabilities.Tool.SWORD, 4);
        tool(tools, "minecraft:netherite_sword", Capabilities.Tool.SWORD, 5);
        tool(tools, "minecraft:golden_sword", Capabilities.Tool.SWORD, 0);
        return java.util.Map.copyOf(tools);
    }

    private static void tool(java.util.Map<String, Capabilities.Tool> tools, String id, String kind, int tier) {
        tools.put(id, new Capabilities.Tool(kind, tier));
    }

    /** Every vanilla tool, by id. */
    public static java.util.Map<String, Capabilities.Tool> tools() { return TOOLS; }

    // ---- Route facts (F-35d's Terrain): which blocks hurt or trap a body, and which it climbs. ----

    /**
     * Blocks that hurt or trap a body entering them, as NativeNavigator.hazard names them: no vanilla tag says the
     * same, so a mod's hazard comes from its profile (F-36's {@code hazard}).
     */
    public static final Group HAZARDS = group("#kindred:hazards", false,
            "minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:cactus", "minecraft:sweet_berry_bush", "minecraft:cobweb", "minecraft:powder_snow",
            "minecraft:wither_rose", "minecraft:magma_block", "minecraft:pointed_dripstone");

    /** #minecraft:climbable as in vanilla 1.21.1 (block tag): ladders, vines and scaffolding. */
    public static final Group CLIMBABLE = group("#minecraft:climbable", true,
            "minecraft:ladder", "minecraft:vine", "minecraft:scaffolding", "minecraft:weeping_vines",
            "minecraft:weeping_vines_plant", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:cave_vines", "minecraft:cave_vines_plant");

    // ---- Shapes and kinds core used to tell by an id's ending (F-38): each is its tag's vanilla 1.21.1 item members,
    // which on vanilla are exactly the ids that ending matched (checked against the generated registry). ----

    /** #minecraft:doors as in vanilla 1.21.1 (item tag): doors, wooden, iron and copper. */
    public static final Group DOORS = group("#minecraft:doors", true,
            "minecraft:acacia_door", "minecraft:bamboo_door", "minecraft:birch_door", "minecraft:cherry_door",
            "minecraft:copper_door", "minecraft:crimson_door", "minecraft:dark_oak_door",
            "minecraft:exposed_copper_door", "minecraft:iron_door", "minecraft:jungle_door",
            "minecraft:mangrove_door", "minecraft:oak_door", "minecraft:oxidized_copper_door",
            "minecraft:spruce_door", "minecraft:warped_door", "minecraft:waxed_copper_door",
            "minecraft:waxed_exposed_copper_door", "minecraft:waxed_oxidized_copper_door",
            "minecraft:waxed_weathered_copper_door", "minecraft:weathered_copper_door");

    /** #minecraft:trapdoors as in vanilla 1.21.1 (item tag): trapdoors. */
    public static final Group TRAPDOORS = group("#minecraft:trapdoors", true,
            "minecraft:acacia_trapdoor", "minecraft:bamboo_trapdoor", "minecraft:birch_trapdoor",
            "minecraft:cherry_trapdoor", "minecraft:copper_trapdoor", "minecraft:crimson_trapdoor",
            "minecraft:dark_oak_trapdoor", "minecraft:exposed_copper_trapdoor", "minecraft:iron_trapdoor",
            "minecraft:jungle_trapdoor", "minecraft:mangrove_trapdoor", "minecraft:oak_trapdoor",
            "minecraft:oxidized_copper_trapdoor", "minecraft:spruce_trapdoor", "minecraft:warped_trapdoor",
            "minecraft:waxed_copper_trapdoor", "minecraft:waxed_exposed_copper_trapdoor",
            "minecraft:waxed_oxidized_copper_trapdoor", "minecraft:waxed_weathered_copper_trapdoor",
            "minecraft:weathered_copper_trapdoor");

    /** #minecraft:beds as in vanilla 1.21.1 (item tag): the sixteen beds. */
    public static final Group BEDS = group("#minecraft:beds", true,
            "minecraft:black_bed", "minecraft:blue_bed", "minecraft:brown_bed", "minecraft:cyan_bed",
            "minecraft:gray_bed", "minecraft:green_bed", "minecraft:light_blue_bed", "minecraft:light_gray_bed",
            "minecraft:lime_bed", "minecraft:magenta_bed", "minecraft:orange_bed", "minecraft:pink_bed",
            "minecraft:purple_bed", "minecraft:red_bed", "minecraft:white_bed", "minecraft:yellow_bed");

    /** #minecraft:stairs as in vanilla 1.21.1 (item tag): stairs. */
    public static final Group STAIRS = group("#minecraft:stairs", true,
            "minecraft:acacia_stairs", "minecraft:andesite_stairs", "minecraft:bamboo_mosaic_stairs",
            "minecraft:bamboo_stairs", "minecraft:birch_stairs", "minecraft:blackstone_stairs",
            "minecraft:brick_stairs", "minecraft:cherry_stairs", "minecraft:cobbled_deepslate_stairs",
            "minecraft:cobblestone_stairs", "minecraft:crimson_stairs", "minecraft:cut_copper_stairs",
            "minecraft:dark_oak_stairs", "minecraft:dark_prismarine_stairs", "minecraft:deepslate_brick_stairs",
            "minecraft:deepslate_tile_stairs", "minecraft:diorite_stairs", "minecraft:end_stone_brick_stairs",
            "minecraft:exposed_cut_copper_stairs", "minecraft:granite_stairs", "minecraft:jungle_stairs",
            "minecraft:mangrove_stairs", "minecraft:mossy_cobblestone_stairs", "minecraft:mossy_stone_brick_stairs",
            "minecraft:mud_brick_stairs", "minecraft:nether_brick_stairs", "minecraft:oak_stairs",
            "minecraft:oxidized_cut_copper_stairs", "minecraft:polished_andesite_stairs",
            "minecraft:polished_blackstone_brick_stairs", "minecraft:polished_blackstone_stairs",
            "minecraft:polished_deepslate_stairs", "minecraft:polished_diorite_stairs",
            "minecraft:polished_granite_stairs", "minecraft:polished_tuff_stairs",
            "minecraft:prismarine_brick_stairs", "minecraft:prismarine_stairs", "minecraft:purpur_stairs",
            "minecraft:quartz_stairs", "minecraft:red_nether_brick_stairs", "minecraft:red_sandstone_stairs",
            "minecraft:sandstone_stairs", "minecraft:smooth_quartz_stairs", "minecraft:smooth_red_sandstone_stairs",
            "minecraft:smooth_sandstone_stairs", "minecraft:spruce_stairs", "minecraft:stone_brick_stairs",
            "minecraft:stone_stairs", "minecraft:tuff_brick_stairs", "minecraft:tuff_stairs",
            "minecraft:warped_stairs", "minecraft:waxed_cut_copper_stairs",
            "minecraft:waxed_exposed_cut_copper_stairs", "minecraft:waxed_oxidized_cut_copper_stairs",
            "minecraft:waxed_weathered_cut_copper_stairs", "minecraft:weathered_cut_copper_stairs");

    /** #minecraft:slabs as in vanilla 1.21.1 (item tag): slabs. */
    public static final Group SLABS = group("#minecraft:slabs", true,
            "minecraft:acacia_slab", "minecraft:andesite_slab", "minecraft:bamboo_mosaic_slab",
            "minecraft:bamboo_slab", "minecraft:birch_slab", "minecraft:blackstone_slab", "minecraft:brick_slab",
            "minecraft:cherry_slab", "minecraft:cobbled_deepslate_slab", "minecraft:cobblestone_slab",
            "minecraft:crimson_slab", "minecraft:cut_copper_slab", "minecraft:cut_red_sandstone_slab",
            "minecraft:cut_sandstone_slab", "minecraft:dark_oak_slab", "minecraft:dark_prismarine_slab",
            "minecraft:deepslate_brick_slab", "minecraft:deepslate_tile_slab", "minecraft:diorite_slab",
            "minecraft:end_stone_brick_slab", "minecraft:exposed_cut_copper_slab", "minecraft:granite_slab",
            "minecraft:jungle_slab", "minecraft:mangrove_slab", "minecraft:mossy_cobblestone_slab",
            "minecraft:mossy_stone_brick_slab", "minecraft:mud_brick_slab", "minecraft:nether_brick_slab",
            "minecraft:oak_slab", "minecraft:oxidized_cut_copper_slab", "minecraft:petrified_oak_slab",
            "minecraft:polished_andesite_slab", "minecraft:polished_blackstone_brick_slab",
            "minecraft:polished_blackstone_slab", "minecraft:polished_deepslate_slab",
            "minecraft:polished_diorite_slab", "minecraft:polished_granite_slab", "minecraft:polished_tuff_slab",
            "minecraft:prismarine_brick_slab", "minecraft:prismarine_slab", "minecraft:purpur_slab",
            "minecraft:quartz_slab", "minecraft:red_nether_brick_slab", "minecraft:red_sandstone_slab",
            "minecraft:sandstone_slab", "minecraft:smooth_quartz_slab", "minecraft:smooth_red_sandstone_slab",
            "minecraft:smooth_sandstone_slab", "minecraft:smooth_stone_slab", "minecraft:spruce_slab",
            "minecraft:stone_brick_slab", "minecraft:stone_slab", "minecraft:tuff_brick_slab", "minecraft:tuff_slab",
            "minecraft:warped_slab", "minecraft:waxed_cut_copper_slab", "minecraft:waxed_exposed_cut_copper_slab",
            "minecraft:waxed_oxidized_cut_copper_slab", "minecraft:waxed_weathered_cut_copper_slab",
            "minecraft:weathered_cut_copper_slab");

    /** #minecraft:fence_gates as in vanilla 1.21.1 (item tag): fence gates. */
    public static final Group FENCE_GATES = group("#minecraft:fence_gates", true,
            "minecraft:acacia_fence_gate", "minecraft:bamboo_fence_gate", "minecraft:birch_fence_gate",
            "minecraft:cherry_fence_gate", "minecraft:crimson_fence_gate", "minecraft:dark_oak_fence_gate",
            "minecraft:jungle_fence_gate", "minecraft:mangrove_fence_gate", "minecraft:oak_fence_gate",
            "minecraft:spruce_fence_gate", "minecraft:warped_fence_gate");

    /** #minecraft:fences as in vanilla 1.21.1 (item tag): fences. */
    public static final Group FENCES = group("#minecraft:fences", true,
            "minecraft:acacia_fence", "minecraft:bamboo_fence", "minecraft:birch_fence", "minecraft:cherry_fence",
            "minecraft:crimson_fence", "minecraft:dark_oak_fence", "minecraft:jungle_fence",
            "minecraft:mangrove_fence", "minecraft:nether_brick_fence", "minecraft:oak_fence",
            "minecraft:spruce_fence", "minecraft:warped_fence");

    /** #minecraft:wool_carpets as in vanilla 1.21.1 (item tag): wool carpets (moss carpet is not one; see MOSS_CARPET). */
    public static final Group WOOL_CARPETS = group("#minecraft:wool_carpets", true,
            "minecraft:black_carpet", "minecraft:blue_carpet", "minecraft:brown_carpet", "minecraft:cyan_carpet",
            "minecraft:gray_carpet", "minecraft:green_carpet", "minecraft:light_blue_carpet",
            "minecraft:light_gray_carpet", "minecraft:lime_carpet", "minecraft:magenta_carpet",
            "minecraft:orange_carpet", "minecraft:pink_carpet", "minecraft:purple_carpet", "minecraft:red_carpet",
            "minecraft:white_carpet", "minecraft:yellow_carpet");

    /** #minecraft:pressure_plates as in vanilla 1.21.1 (item tag): pressure plates. */
    public static final Group PRESSURE_PLATES = group("#minecraft:pressure_plates", true,
            "minecraft:acacia_pressure_plate", "minecraft:bamboo_pressure_plate", "minecraft:birch_pressure_plate",
            "minecraft:cherry_pressure_plate", "minecraft:crimson_pressure_plate",
            "minecraft:dark_oak_pressure_plate", "minecraft:heavy_weighted_pressure_plate",
            "minecraft:jungle_pressure_plate", "minecraft:light_weighted_pressure_plate",
            "minecraft:mangrove_pressure_plate", "minecraft:oak_pressure_plate",
            "minecraft:polished_blackstone_pressure_plate", "minecraft:spruce_pressure_plate",
            "minecraft:stone_pressure_plate", "minecraft:warped_pressure_plate");

    /** #c:concrete_powders as in vanilla 1.21.1 (item tag): concrete powders. */
    public static final Group CONCRETE_POWDERS = group("#c:concrete_powders", true,
            "minecraft:black_concrete_powder", "minecraft:blue_concrete_powder", "minecraft:brown_concrete_powder",
            "minecraft:cyan_concrete_powder", "minecraft:gray_concrete_powder", "minecraft:green_concrete_powder",
            "minecraft:light_blue_concrete_powder", "minecraft:light_gray_concrete_powder",
            "minecraft:lime_concrete_powder", "minecraft:magenta_concrete_powder", "minecraft:orange_concrete_powder",
            "minecraft:pink_concrete_powder", "minecraft:purple_concrete_powder", "minecraft:red_concrete_powder",
            "minecraft:white_concrete_powder", "minecraft:yellow_concrete_powder");

    /** #c:glazed_terracottas as in vanilla 1.21.1 (item tag): glazed terracottas. */
    public static final Group GLAZED_TERRACOTTAS = group("#c:glazed_terracottas", true,
            "minecraft:black_glazed_terracotta", "minecraft:blue_glazed_terracotta",
            "minecraft:brown_glazed_terracotta", "minecraft:cyan_glazed_terracotta",
            "minecraft:gray_glazed_terracotta", "minecraft:green_glazed_terracotta",
            "minecraft:light_blue_glazed_terracotta", "minecraft:light_gray_glazed_terracotta",
            "minecraft:lime_glazed_terracotta", "minecraft:magenta_glazed_terracotta",
            "minecraft:orange_glazed_terracotta", "minecraft:pink_glazed_terracotta",
            "minecraft:purple_glazed_terracotta", "minecraft:red_glazed_terracotta",
            "minecraft:white_glazed_terracotta", "minecraft:yellow_glazed_terracotta");

    /** #c:ores as in vanilla 1.21.1 (item tag): ores, ancient debris among them. */
    public static final Group ORES = group("#c:ores", true,
            "minecraft:ancient_debris", "minecraft:coal_ore", "minecraft:copper_ore", "minecraft:deepslate_coal_ore",
            "minecraft:deepslate_copper_ore", "minecraft:deepslate_diamond_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:deepslate_gold_ore", "minecraft:deepslate_iron_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:deepslate_redstone_ore", "minecraft:diamond_ore", "minecraft:emerald_ore",
            "minecraft:gold_ore", "minecraft:iron_ore", "minecraft:lapis_ore", "minecraft:nether_gold_ore",
            "minecraft:nether_quartz_ore", "minecraft:redstone_ore");

    /** #minecraft:signs as in vanilla 1.21.1 (item tag): standing and wall signs. */
    public static final Group SIGNS = group("#minecraft:signs", true,
            "minecraft:acacia_sign", "minecraft:bamboo_sign", "minecraft:birch_sign", "minecraft:cherry_sign",
            "minecraft:crimson_sign", "minecraft:dark_oak_sign", "minecraft:jungle_sign", "minecraft:mangrove_sign",
            "minecraft:oak_sign", "minecraft:spruce_sign", "minecraft:warped_sign");

    /** #minecraft:hanging_signs as in vanilla 1.21.1 (item tag): hanging signs. */
    public static final Group HANGING_SIGNS = group("#minecraft:hanging_signs", true,
            "minecraft:acacia_hanging_sign", "minecraft:bamboo_hanging_sign", "minecraft:birch_hanging_sign",
            "minecraft:cherry_hanging_sign", "minecraft:crimson_hanging_sign", "minecraft:dark_oak_hanging_sign",
            "minecraft:jungle_hanging_sign", "minecraft:mangrove_hanging_sign", "minecraft:oak_hanging_sign",
            "minecraft:spruce_hanging_sign", "minecraft:warped_hanging_sign");

    /** #c:seeds as in vanilla 1.21.1 (item tag): seeds. */
    public static final Group SEEDS = group("#c:seeds", true,
            "minecraft:beetroot_seeds", "minecraft:melon_seeds", "minecraft:pumpkin_seeds",
            "minecraft:torchflower_seeds", "minecraft:wheat_seeds");

    /** #minecraft:leaves as in vanilla 1.21.1 (item tag): leaves. */
    public static final Group LEAVES = group("#minecraft:leaves", true,
            "minecraft:acacia_leaves", "minecraft:azalea_leaves", "minecraft:birch_leaves", "minecraft:cherry_leaves",
            "minecraft:dark_oak_leaves", "minecraft:flowering_azalea_leaves", "minecraft:jungle_leaves",
            "minecraft:mangrove_leaves", "minecraft:oak_leaves", "minecraft:spruce_leaves");

    /** #minecraft:swords as in vanilla 1.21.1 (item tag): swords. */
    public static final Group SWORDS = group("#minecraft:swords", true,
            "minecraft:diamond_sword", "minecraft:golden_sword", "minecraft:iron_sword", "minecraft:netherite_sword",
            "minecraft:stone_sword", "minecraft:wooden_sword");

    /** #minecraft:pickaxes as in vanilla 1.21.1 (item tag): pickaxes. */
    public static final Group PICKAXES = group("#minecraft:pickaxes", true,
            "minecraft:diamond_pickaxe", "minecraft:golden_pickaxe", "minecraft:iron_pickaxe",
            "minecraft:netherite_pickaxe", "minecraft:stone_pickaxe", "minecraft:wooden_pickaxe");

    /** #minecraft:axes as in vanilla 1.21.1 (item tag): axes. */
    public static final Group AXES = group("#minecraft:axes", true,
            "minecraft:diamond_axe", "minecraft:golden_axe", "minecraft:iron_axe", "minecraft:netherite_axe",
            "minecraft:stone_axe", "minecraft:wooden_axe");

    /** #minecraft:shovels as in vanilla 1.21.1 (item tag): shovels. */
    public static final Group SHOVELS = group("#minecraft:shovels", true,
            "minecraft:diamond_shovel", "minecraft:golden_shovel", "minecraft:iron_shovel",
            "minecraft:netherite_shovel", "minecraft:stone_shovel", "minecraft:wooden_shovel");

    /** #minecraft:hoes as in vanilla 1.21.1 (item tag): hoes. */
    public static final Group HOES = group("#minecraft:hoes", true,
            "minecraft:diamond_hoe", "minecraft:golden_hoe", "minecraft:iron_hoe", "minecraft:netherite_hoe",
            "minecraft:stone_hoe", "minecraft:wooden_hoe");

    /** #minecraft:head_armor as in vanilla 1.21.1 (item tag): helmets, the turtle shell among them. */
    public static final Group HEAD_ARMOR = group("#minecraft:head_armor", true,
            "minecraft:chainmail_helmet", "minecraft:diamond_helmet", "minecraft:golden_helmet",
            "minecraft:iron_helmet", "minecraft:leather_helmet", "minecraft:netherite_helmet",
            "minecraft:turtle_helmet");

    /** #minecraft:chest_armor as in vanilla 1.21.1 (item tag): chestplates. */
    public static final Group CHEST_ARMOR = group("#minecraft:chest_armor", true,
            "minecraft:chainmail_chestplate", "minecraft:diamond_chestplate", "minecraft:golden_chestplate",
            "minecraft:iron_chestplate", "minecraft:leather_chestplate", "minecraft:netherite_chestplate");

    /** #minecraft:leg_armor as in vanilla 1.21.1 (item tag): leggings. */
    public static final Group LEG_ARMOR = group("#minecraft:leg_armor", true,
            "minecraft:chainmail_leggings", "minecraft:diamond_leggings", "minecraft:golden_leggings",
            "minecraft:iron_leggings", "minecraft:leather_leggings", "minecraft:netherite_leggings");

    /** #minecraft:foot_armor as in vanilla 1.21.1 (item tag): boots. */
    public static final Group FOOT_ARMOR = group("#minecraft:foot_armor", true,
            "minecraft:chainmail_boots", "minecraft:diamond_boots", "minecraft:golden_boots", "minecraft:iron_boots",
            "minecraft:leather_boots", "minecraft:netherite_boots");


    /** Moss carpet: placed on top like a carpet, but not in #minecraft:wool_carpets. Would be its block's shape. */
    public static final String MOSS_CARPET = "minecraft:moss_carpet";


    // ---- Woods a build is made of: the eight it always used, each family named the vanilla way. Would be read from
    // the recipes that take each #minecraft:logs item, as the adapter does for the server's other woods. ----

    private static final List<Capabilities.Wood> WOODS = buildWoods();

    private static List<Capabilities.Wood> buildWoods() {
        List<Capabilities.Wood> woods = new ArrayList<>();
        for (String w : new String[] {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"}) {
            String n = NAMESPACE + w;
            woods.add(new Capabilities.Wood(w, n + "_log", n + "_planks", n + "_stairs", n + "_slab", n + "_fence",
                    n + "_fence_gate", n + "_door", n + "_trapdoor"));
        }
        return List.copyOf(woods);
    }

    /** Vanilla's building woods, in the order they were always listed. */
    public static List<Capabilities.Wood> woods() { return WOODS; }

    /**
     * Planks in #minecraft:planks that builds never used (Nether stems, bamboo): kept out of the server's woods so a
     * vanilla build is chosen as before.
     */
    public static final Group UNBUILT_PLANKS = group("#kindred:unbuilt_planks", false,
            "minecraft:crimson_planks", "minecraft:warped_planks", "minecraft:bamboo_planks");

    /** Whether these planks are ones builds never used. */
    public static boolean unbuiltWood(String planks) { return UNBUILT_PLANKS.ids().contains(planks); }

    private VanillaIds() { }
}
