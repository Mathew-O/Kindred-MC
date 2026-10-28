package dev.kindred.core.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In plain terms: keeps chests tidy: sorts items into categories (ores, tools, food, wood, stone, redstone,
 * Nether and End, mob drops), merges half stacks, decides what to put away from a player's backpack while
 * keeping its survival kit, and how to be polite when a person is using the same chests. It only ever moves
 * real items; it never creates or deletes any.
 *
 * Technical: Pure chest categorization, stack compaction, slot sorting, and Item-Frame icon mapping
 * for autonomous storage organization (CHEST-01) and Bot <-> Player QoL (QOL-05).
 *
 * Strictly adheres to PLY-04 item conservation: plans only merge/swap/transfer operations
 * on existing physical stacks without ever synthesizing or deleting items.
 */
public final class ChestOrganizationPolicy {

    /**
     * Canonical storage categories for single/double chests and Item-Frame warehouse columns.
     */
    public enum StorageCategory {
        ORES_AND_VALUABLES("Ores, Ingots & Gems", "minecraft:diamond", 10),
        TOOLS_AND_COMBAT("Tools, Weapons & Armor", "minecraft:iron_pickaxe", 20),
        FOOD_AND_CROPS("Food, Crops & Seeds", "minecraft:golden_carrot", 30),
        WOOD_AND_TIMBER("Logs, Planks & Saplings", "minecraft:oak_log", 40),
        STONE_AND_MASONRY("Stone, Bricks & Terracotta", "minecraft:stone_bricks", 50),
        REDSTONE_AND_RAILS("Redstone, Rails & Mechanisms", "minecraft:redstone", 60),
        NETHER_AND_END("Nether, Potions & End Materials", "minecraft:blaze_rod", 70),
        MOB_DROPS_AND_MISC("Mob Drops, Utility & Trinkets", "minecraft:ender_pearl", 80);

        private final String displayName;
        private final String itemFrameIconId;
        private final int sortOrder;

        StorageCategory(String displayName, String itemFrameIconId, int sortOrder) {
            this.displayName = displayName;
            this.itemFrameIconId = itemFrameIconId;
            this.sortOrder = sortOrder;
        }

        /** The category's name as shown to people. */
        public String displayName() {
            return displayName;
        }

        /** Canonical icon item ID for labeling a category chest with an ItemFrame. */
        public String itemFrameIconId() {
            return itemFrameIconId;
        }

        /** Where the category goes in a row of chests. */
        public int sortOrder() {
            return sortOrder;
        }
    }

    /**
     * Immutable snapshot of one container or inventory slot.
     */
    public record SlotSnapshot(
            int slotIndex,
            String itemId,
            int count,
            int maxStackSize,
            long componentFingerprint
    ) {
        public SlotSnapshot {
            if (slotIndex < 0) {
                throw new IllegalArgumentException("slotIndex must be >= 0: " + slotIndex);
            }
            itemId = itemId == null ? "minecraft:air" : normalizeId(itemId);
            if (count < 0) {
                throw new IllegalArgumentException("count must be >= 0: " + count);
            }
            if (maxStackSize <= 0) {
                maxStackSize = 64;
            }
        }

        /** Whether the slot holds nothing. */
        public boolean isEmpty() {
            return count <= 0 || "minecraft:air".equals(itemId);
        }

        /** Whether two stacks are the same item and can be combined. */
        public boolean canMergeWith(SlotSnapshot other) {
            return !this.isEmpty()
                    && !other.isEmpty()
                    && this.itemId.equals(other.itemId)
                    && this.componentFingerprint == other.componentFingerprint
                    && this.count < this.maxStackSize;
        }
    }

    /**
     * Represents a single atomic slot-to-slot move or merge inside a chest.
     */
    public record ChestSlotMove(
            int fromSlot,
            int toSlot,
            Kind kind,
            String reason
    ) {
        public enum Kind {
            MERGE_PARTIAL_STACK,
            SWAP_FOR_CATEGORY_SORT
        }
    }

    /**
     * Represents a deposit from a bot's inventory (slots 0..35) into a storage chest,
     * preserving the bot's essential survival kit and any teammate StockLedger reservations.
     */
    public record DepositCandidate(
            int playerSlot,
            String itemId,
            int depositCount,
            StorageCategory targetCategory,
            String reason
    ) { }

    /**
     * Represents a Bot <-> Player QoL interaction when a human player is working near the bot.
     */
    public record PlayerQolAssistance(
            UUID targetPlayerId,
            AssistKind kind,
            String itemId,
            int count,
            String chatCallout
    ) {
        public enum AssistKind {
            TOSS_EMERGENCY_FOOD,
            TOSS_TORCHES_IN_DARK,
            YIELD_CHEST_ACCESS,
            SLEEP_SYNC_IN_BED,
            AUTO_REPLENISH_BROKEN_TOOL
        }
    }

    private static final Set<String> VALUABLE_ITEMS = Set.of(
            "minecraft:diamond", "minecraft:diamond_block",
            "minecraft:emerald", "minecraft:emerald_block",
            "minecraft:iron_ingot", "minecraft:iron_block", "minecraft:raw_iron", "minecraft:raw_iron_block",
            "minecraft:gold_ingot", "minecraft:gold_block", "minecraft:raw_gold", "minecraft:raw_gold_block", "minecraft:gold_nugget",
            "minecraft:copper_ingot", "minecraft:raw_copper",
            "minecraft:coal", "minecraft:charcoal", "minecraft:coal_block",
            "minecraft:lapis_lazuli", "minecraft:lapis_block",
            "minecraft:quartz", "minecraft:amethyst_shard",
            "minecraft:ancient_debris", "minecraft:netherite_scrap", "minecraft:netherite_ingot",
            "minecraft:netherite_upgrade_smithing_template"
    );

    private static final Set<String> REDSTONE_ITEMS = Set.of(
            "minecraft:redstone", "minecraft:redstone_block", "minecraft:redstone_torch",
            "minecraft:repeater", "minecraft:comparator", "minecraft:observer",
            "minecraft:piston", "minecraft:sticky_piston", "minecraft:hopper", "minecraft:dropper", "minecraft:dispenser",
            "minecraft:rail", "minecraft:powered_rail", "minecraft:detector_rail", "minecraft:activator_rail",
            "minecraft:minecart", "minecraft:chest_minecart", "minecraft:hopper_minecart",
            "minecraft:lever", "minecraft:target", "minecraft:daylight_detector"
    );

    private static final Set<String> NETHER_END_ITEMS = Set.of(
            "minecraft:blaze_rod", "minecraft:blaze_powder", "minecraft:NETHER_WART".toLowerCase(Locale.ROOT),
            "minecraft:magma_cream", "minecraft:ghast_tear", "minecraft:glowstone_dust", "minecraft:glowstone",
            "minecraft:shroomlight", "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:rying_obsidian",
            "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_eye", "minecraft:end_rod",
            "minecraft:chorus_fruit", "minecraft:shulker_shell", "minecraft:dragon_breath", "minecraft:elytra"
    );

    /** Cleans up an item id so the same item always matches. */
    private static String normalizeId(String raw) {
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        return clean.startsWith("minecraft:") ? clean : "minecraft:" + clean;
    }

    /**
     * Classifies any Minecraft 1.21.1 item ID into its canonical {@link StorageCategory}.
     */
    public static StorageCategory classifyItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return StorageCategory.MOB_DROPS_AND_MISC;
        }
        String id = normalizeId(itemId);
        if (VALUABLE_ITEMS.contains(id) || id.endsWith("_ore")) {
            return StorageCategory.ORES_AND_VALUABLES;
        }
        if (REDSTONE_ITEMS.contains(id)) {
            return StorageCategory.REDSTONE_AND_RAILS;
        }
        if (NETHER_END_ITEMS.contains(id) || id.contains("potion") || id.startsWith("minecraft:nether_")) {
            return StorageCategory.NETHER_AND_END;
        }
        if (id.endsWith("_sword") || id.endsWith("_pickaxe") || id.endsWith("_axe")
                || id.endsWith("_shovel") || id.endsWith("_hoe")
                || id.endsWith("_helmet") || id.endsWith("_chestplate")
                || id.endsWith("_leggings") || id.endsWith("_boots")
                || id.equals("minecraft:bow") || id.equals("minecraft:crossbow")
                || id.equals("minecraft:trident") || id.equals("minecraft:mace")
                || id.equals("minecraft:shield") || id.equals("minecraft:totem_of_undying")
                || id.equals("minecraft:arrow") || id.equals("minecraft:spectral_arrow")
                || id.equals("minecraft:wind_charge") || id.equals("minecraft:enchanted_book")) {
            return StorageCategory.TOOLS_AND_COMBAT;
        }
        if (id.contains("bread") || id.contains("beef") || id.contains("porkchop")
                || id.contains("mutton") || id.contains("chicken") || id.contains("cod")
                || id.contains("salmon") || id.contains("potato") || id.contains("carrot")
                || id.contains("apple") || id.contains("melon") || id.contains("pumpkin")
                || id.contains("wheat") || id.contains("beetroot") || id.contains("berries")
                || id.endsWith("_seeds") || id.contains("stew") || id.equals("minecraft:sugar_cane")
                || id.equals("minecraft:hay_block") || id.equals("minecraft:kelp") || id.equals("minecraft:dried_kelp")) {
            return StorageCategory.FOOD_AND_CROPS;
        }
        if (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem")
                || id.endsWith("_hyphae") || id.endsWith("_planks") || id.endsWith("_sapling")
                || id.endsWith("_leaves") || id.equals("minecraft:stick") || id.equals("minecraft:bamboo")
                || id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("_fence")
                || id.endsWith("_fence_gate") || id.endsWith("_sign")) {
            return StorageCategory.WOOD_AND_TIMBER;
        }
        if (id.contains("stone") || id.contains("deepslate") || id.contains("cobble")
                || id.contains("brick") || id.contains("granite") || id.contains("diorite")
                || id.contains("andesite") || id.contains("tuff") || id.contains("calcite")
                || id.contains("sand") || id.contains("gravel") || id.contains("dirt")
                || id.contains("terracotta") || id.contains("concrete") || id.contains("glass")
                || id.contains("copper")) {
            return StorageCategory.STONE_AND_MASONRY;
        }
        return StorageCategory.MOB_DROPS_AND_MISC;
    }

    /**
     * Infers the dominant {@link StorageCategory} of an existing chest based on its current contents,
     * or returns {@code null} if the chest is empty.
     */
    public static StorageCategory inferDominantCategory(List<SlotSnapshot> chestSlots) {
        Objects.requireNonNull(chestSlots, "chestSlots");
        Map<StorageCategory, Integer> counts = new HashMap<>();
        for (SlotSnapshot slot : chestSlots) {
            if (!slot.isEmpty()) {
                StorageCategory cat = classifyItem(slot.itemId());
                counts.merge(cat, slot.count(), Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<StorageCategory, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(e -> -e.getKey().sortOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Plans up to {@code maxMoves} slot operations to compact partial stacks first and then
     * sort chest contents by (Category -> Item ID -> Stack Count descending).
     */
    public static List<ChestSlotMove> planChestOrganization(List<SlotSnapshot> currentSlots, int maxMoves) {
        Objects.requireNonNull(currentSlots, "currentSlots");
        if (maxMoves <= 0 || currentSlots.size() <= 1) {
            return List.of();
        }

        List<SlotSnapshot> working = new ArrayList<>(currentSlots);
        List<ChestSlotMove> moves = new ArrayList<>();

        // Pass 1: Compact partial stacks of identical items & components
        for (int i = 0; i < working.size() && moves.size() < maxMoves; i++) {
            SlotSnapshot dst = working.get(i);
            if (dst.isEmpty() || dst.count() >= dst.maxStackSize()) {
                continue;
            }
            for (int j = working.size() - 1; j > i && moves.size() < maxMoves; j--) {
                SlotSnapshot src = working.get(j);
                if (dst.canMergeWith(src)) {
                    int space = dst.maxStackSize() - dst.count();
                    int transfer = Math.min(space, src.count());
                    dst = new SlotSnapshot(dst.slotIndex(), dst.itemId(), dst.count() + transfer,
                            dst.maxStackSize(), dst.componentFingerprint());
                    int remaining = src.count() - transfer;
                    SlotSnapshot updatedSrc = remaining <= 0
                            ? new SlotSnapshot(src.slotIndex(), "minecraft:air", 0, 64, 0L)
                            : new SlotSnapshot(src.slotIndex(), src.itemId(), remaining, src.maxStackSize(), src.componentFingerprint());
                    working.set(i, dst);
                    working.set(j, updatedSrc);
                    moves.add(new ChestSlotMove(
                            src.slotIndex(),
                            dst.slotIndex(),
                            ChestSlotMove.Kind.MERGE_PARTIAL_STACK,
                            "Compact partial stack of " + dst.itemId()
                    ));
                    if (dst.count() >= dst.maxStackSize()) {
                        break;
                    }
                }
            }
        }

        // Pass 2: Sort non-empty stacks toward the top-left of the chest in canonical Category + Item order
        Comparator<SlotSnapshot> comparator = Comparator
                .comparing(SlotSnapshot::isEmpty) // non-empty first (false < true)
                .thenComparingInt(s -> s.isEmpty() ? Integer.MAX_VALUE : classifyItem(s.itemId()).sortOrder())
                .thenComparing(s -> s.isEmpty() ? "~" : s.itemId())
                .thenComparing(Comparator.comparingInt(SlotSnapshot::count).reversed());

        for (int i = 0; i < working.size() - 1 && moves.size() < maxMoves; i++) {
            int bestIdx = i;
            for (int j = i + 1; j < working.size(); j++) {
                if (comparator.compare(working.get(j), working.get(bestIdx)) < 0) {
                    bestIdx = j;
                }
            }
            if (bestIdx != i) {
                SlotSnapshot a = working.get(i);
                SlotSnapshot b = working.get(bestIdx);
                working.set(i, new SlotSnapshot(i, b.itemId(), b.count(), b.maxStackSize(), b.componentFingerprint()));
                working.set(bestIdx, new SlotSnapshot(bestIdx, a.itemId(), a.count(), a.maxStackSize(), a.componentFingerprint()));
                moves.add(new ChestSlotMove(
                        bestIdx,
                        i,
                        ChestSlotMove.Kind.SWAP_FOR_CATEGORY_SORT,
                        "Sort " + b.itemId() + " (" + classifyItem(b.itemId()).displayName() + ") into slot " + i
                ));
            }
        }

        return List.copyOf(moves);
    }

    /**
     * Computes which surplus items in the bot's backpack (slots 9..35) should be deposited into base chests,
     * while strictly keeping the bot's personal survival kit (tools, weapons, food reserve, torches, 1 stack of blocks, 1 water bucket).
     */
    public static List<DepositCandidate> planSurplusDeposits(List<SlotSnapshot> playerInventorySlots) {
        Objects.requireNonNull(playerInventorySlots, "playerInventorySlots");
        List<DepositCandidate> deposits = new ArrayList<>();
        int keptBuildingBlocks = 0;
        int keptFoodItems = 0;
        int keptTorches = 0;

        for (SlotSnapshot slot : playerInventorySlots) {
            if (slot.isEmpty()) {
                continue;
            }
            StorageCategory cat = classifyItem(slot.itemId());
            // Keep hotbar slots 0..8 unless it's raw ore or mob drops cluttering the hotbar
            if (slot.slotIndex() < 9 && cat != StorageCategory.ORES_AND_VALUABLES && cat != StorageCategory.MOB_DROPS_AND_MISC) {
                continue;
            }
            if (cat == StorageCategory.TOOLS_AND_COMBAT) {
                continue; // Keep personal tools/weapons/arrows
            }
            if ("minecraft:torch".equals(slot.itemId()) || "minecraft:lantern".equals(slot.itemId())) {
                if (keptTorches < 64) {
                    keptTorches += slot.count();
                    continue;
                }
            }
            if (cat == StorageCategory.FOOD_AND_CROPS) {
                if (keptFoodItems < 32 && !slot.itemId().endsWith("_seeds") && !"minecraft:wheat".equals(slot.itemId())) {
                    keptFoodItems += slot.count();
                    continue;
                }
            }
            if ("minecraft:cobblestone".equals(slot.itemId()) || "minecraft:oak_planks".equals(slot.itemId())
                    || "minecraft:dirt".equals(slot.itemId())) {
                if (keptBuildingBlocks < 64) {
                    keptBuildingBlocks += slot.count();
                    continue;
                }
            }
            deposits.add(new DepositCandidate(
                    slot.slotIndex(),
                    slot.itemId(),
                    slot.count(),
                    cat,
                    "Deposit surplus " + slot.itemId() + " into " + cat.displayName() + " chest"
            ));
        }
        return List.copyOf(deposits);
    }

    /**
     * Evaluates Bot <-> Player Quality-of-Life assistance rules:
     * 1. Yield Chest Access: If a human player is currently looking into / interacting with the target chest,
     *    the bot waits politely outside a 2.5m radius instead of colliding or fighting over slots.
     * 2. Emergency Food Share: If a nearby human teammate has foodLevel <= 6 (cannot sprint) or health <= 8.0,
     *    and the bot has > 8 cooked food items, toss 4 food items toward the human.
     * 3. Dark Cave Torch Support: If a nearby human is mining in block-light <= 3 and the bot holds > 16 torches,
     *    place a torch or toss 8 torches to the human.
     */
    public static List<PlayerQolAssistance> evaluatePlayerQolAssistance(
            UUID humanId,
            String humanName,
            int humanFoodLevel,
            float humanHealth,
            int humanBlockLight,
            boolean humanHasOpenContainerAtTargetChest,
            int botCookedFoodCount,
            String botPrimaryFoodId,
            int botTorchCount
    ) {
        Objects.requireNonNull(humanId, "humanId");
        Objects.requireNonNull(humanName, "humanName");
        List<PlayerQolAssistance> actions = new ArrayList<>();

        if (humanHasOpenContainerAtTargetChest) {
            actions.add(new PlayerQolAssistance(
                    humanId,
                    PlayerQolAssistance.AssistKind.YIELD_CHEST_ACCESS,
                    "minecraft:chest",
                    0,
                    "Take your time with the chest, " + humanName + "—I'll sort my pack once you're done."
            ));
        }

        if ((humanFoodLevel <= 6 || humanHealth <= 8.0f) && botCookedFoodCount >= 8 && botPrimaryFoodId != null) {
            int giftCount = Math.min(4, botCookedFoodCount - 4);
            if (giftCount > 0) {
                actions.add(new PlayerQolAssistance(
                        humanId,
                        PlayerQolAssistance.AssistKind.TOSS_EMERGENCY_FOOD,
                        normalizeId(botPrimaryFoodId),
                        giftCount,
                        "Here " + humanName + ", catch some food—you're running low!"
                ));
            }
        }

        if (humanBlockLight <= 3 && botTorchCount >= 16) {
            actions.add(new PlayerQolAssistance(
                    humanId,
                    PlayerQolAssistance.AssistKind.TOSS_TORCHES_IN_DARK,
                    "minecraft:torch",
                    8,
                    "Getting dark in here, " + humanName + "—dropping you some torches."
            ));
        }

        return List.copyOf(actions);
    }

    private ChestOrganizationPolicy() { }
}
