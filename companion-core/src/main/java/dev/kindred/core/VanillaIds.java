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

    /** Timber not caught by the wood suffixes. */
    public static final Group CHEST_TIMBER = group("#kindred:storage/timber", false,
            "minecraft:stick", "minecraft:bamboo");

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

    private VanillaIds() { }
}
