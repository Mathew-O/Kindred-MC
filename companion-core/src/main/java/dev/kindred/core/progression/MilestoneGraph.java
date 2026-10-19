package dev.kindred.core.progression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Directed acyclic graph of survival milestones.
 * Evaluates physical postconditions and computes the active progression frontier.
 */
public final class MilestoneGraph {

    private final Map<MilestoneId, Milestone> milestones;
    private final List<MilestoneId> topologicalOrder;

    public MilestoneGraph(Map<MilestoneId, Milestone> milestones) {
        Objects.requireNonNull(milestones, "milestones");
        this.milestones = Map.copyOf(milestones);
        this.topologicalOrder = computeTopologicalOrder(this.milestones);
    }

    /**
     * Creates the canonical early-game survival progression graph (PLY-03, PROG-01).
     */
    public static MilestoneGraph canonical() {
        Map<MilestoneId, Milestone> map = new LinkedHashMap<>();

        // 1. Wood gathering
        map.put(MilestoneId.GATHER_WOOD, new Milestone(
                MilestoneId.GATHER_WOOD, "Gather Wood Logs", Set.of(),
                obs -> hasAnyWoodLog(obs),
                50.0
        ));

        // 2. Planks
        map.put(MilestoneId.CRAFT_PLANKS, new Milestone(
                MilestoneId.CRAFT_PLANKS, "Craft Wooden Planks", Set.of(MilestoneId.GATHER_WOOD),
                obs -> hasAnyPlank(obs),
                51.0
        ));

        // 3. Sticks
        map.put(MilestoneId.CRAFT_STICKS, new Milestone(
                MilestoneId.CRAFT_STICKS, "Craft Sticks", Set.of(MilestoneId.CRAFT_PLANKS),
                obs -> obs.has("minecraft:stick", 1) || hasPickaxeTier(obs, 1),
                52.0
        ));

        // 4. Crafting table creation
        map.put(MilestoneId.CRAFT_CRAFTING_TABLE, new Milestone(
                MilestoneId.CRAFT_CRAFTING_TABLE, "Craft Crafting Table", Set.of(MilestoneId.CRAFT_PLANKS),
                obs -> obs.has("minecraft:crafting_table", 1) || obs.hasNearbyCraftingTable(),
                53.0
        ));

        // 5. Deploy crafting table
        map.put(MilestoneId.DEPLOY_CRAFTING_TABLE, new Milestone(
                MilestoneId.DEPLOY_CRAFTING_TABLE, "Deploy Crafting Table", Set.of(MilestoneId.CRAFT_CRAFTING_TABLE),
                ProgressionObservation::hasNearbyCraftingTable,
                54.0
        ));

        // 6. Wooden pickaxe
        map.put(MilestoneId.CRAFT_WOODEN_PICKAXE, new Milestone(
                MilestoneId.CRAFT_WOODEN_PICKAXE, "Craft Wooden Pickaxe",
                Set.of(MilestoneId.DEPLOY_CRAFTING_TABLE, MilestoneId.CRAFT_STICKS),
                obs -> hasPickaxeTier(obs, 1),
                55.0
        ));

        // 7. Mine cobblestone
        map.put(MilestoneId.MINE_COBBLESTONE, new Milestone(
                MilestoneId.MINE_COBBLESTONE, "Mine Cobblestone", Set.of(MilestoneId.CRAFT_WOODEN_PICKAXE),
                obs -> obs.has("minecraft:cobblestone", 1) || hasPickaxeTier(obs, 2),
                56.0
        ));

        // 8. Stone pickaxe
        map.put(MilestoneId.CRAFT_STONE_PICKAXE, new Milestone(
                MilestoneId.CRAFT_STONE_PICKAXE, "Craft Stone Pickaxe",
                Set.of(MilestoneId.MINE_COBBLESTONE, MilestoneId.DEPLOY_CRAFTING_TABLE),
                obs -> hasPickaxeTier(obs, 2),
                57.0
        ));

        // 9. Craft furnace
        map.put(MilestoneId.CRAFT_FURNACE, new Milestone(
                MilestoneId.CRAFT_FURNACE, "Craft Furnace",
                Set.of(MilestoneId.MINE_COBBLESTONE, MilestoneId.DEPLOY_CRAFTING_TABLE),
                obs -> obs.has("minecraft:furnace", 1) || obs.hasNearbyFurnace(),
                58.0
        ));

        // 10. Deploy furnace
        map.put(MilestoneId.DEPLOY_FURNACE, new Milestone(
                MilestoneId.DEPLOY_FURNACE, "Deploy Furnace", Set.of(MilestoneId.CRAFT_FURNACE),
                ProgressionObservation::hasNearbyFurnace,
                59.0
        ));

        // 11. Smelt resources (charcoal / iron / food)
        map.put(MilestoneId.SMELT_RESOURCES, new Milestone(
                MilestoneId.SMELT_RESOURCES, "Smelt Resources", Set.of(MilestoneId.DEPLOY_FURNACE),
                obs -> obs.has("minecraft:iron_ingot", 1) || obs.has("minecraft:charcoal", 1)
                        || obs.has("minecraft:cooked_beef", 1) || obs.has("minecraft:cooked_porkchop", 1),
                60.0
        ));

        // 12. Iron pickaxe
        map.put(MilestoneId.CRAFT_IRON_PICKAXE, new Milestone(
                MilestoneId.CRAFT_IRON_PICKAXE, "Craft Iron Pickaxe",
                Set.of(MilestoneId.SMELT_RESOURCES, MilestoneId.DEPLOY_CRAFTING_TABLE),
                obs -> hasPickaxeTier(obs, 3),
                61.0
        ));

        // 13. Shield
        map.put(MilestoneId.CRAFT_SHIELD, new Milestone(
                MilestoneId.CRAFT_SHIELD, "Craft Shield",
                Set.of(MilestoneId.SMELT_RESOURCES, MilestoneId.CRAFT_PLANKS, MilestoneId.DEPLOY_CRAFTING_TABLE),
                obs -> obs.has("minecraft:shield", 1),
                62.0
        ));

        // 14. Iron sword
        map.put(MilestoneId.CRAFT_IRON_SWORD, new Milestone(
                MilestoneId.CRAFT_IRON_SWORD, "Craft Iron Sword",
                Set.of(MilestoneId.SMELT_RESOURCES, MilestoneId.DEPLOY_CRAFTING_TABLE),
                obs -> obs.has("minecraft:iron_sword", 1) || obs.has("minecraft:diamond_sword", 1)
                        || obs.has("minecraft:netherite_sword", 1),
                63.0
        ));

        // 15. Secure shelter
        map.put(MilestoneId.SECURE_SHELTER, new Milestone(
                MilestoneId.SECURE_SHELTER, "Secure Night Shelter", Set.of(MilestoneId.GATHER_WOOD),
                ProgressionObservation::hasShelter,
                64.0
        ));

        // 16. Craft hoe
        map.put(MilestoneId.CRAFT_HOE, new Milestone(
                MilestoneId.CRAFT_HOE, "Craft Agricultural Hoe",
                Set.of(MilestoneId.DEPLOY_CRAFTING_TABLE, MilestoneId.CRAFT_STICKS),
                ProgressionObservation::hasHoe,
                61.5
        ));

        // 17. Establish cultivated farm
        map.put(MilestoneId.ESTABLISH_FARM, new Milestone(
                MilestoneId.ESTABLISH_FARM, "Establish Cultivated Farm",
                Set.of(MilestoneId.CRAFT_HOE),
                obs -> obs.hasCultivatedFarm() || obs.has("minecraft:wheat", 1) || obs.has("minecraft:bread", 1) || obs.has("minecraft:carrot", 1) || obs.has("minecraft:potato", 1),
                62.0
        ));

        // 18. Craft bed
        map.put(MilestoneId.CRAFT_BED, new Milestone(
                MilestoneId.CRAFT_BED, "Craft Bed",
                Set.of(MilestoneId.DEPLOY_CRAFTING_TABLE),
                ProgressionObservation::hasBed,
                63.5
        ));

        // 19. Enter the Nether (E-15)
        map.put(MilestoneId.ENTER_NETHER, new Milestone(
                MilestoneId.ENTER_NETHER, "Enter the Nether",
                Set.of(MilestoneId.CRAFT_IRON_PICKAXE),
                ProgressionObservation::isInNether,
                65.0
        ));

        // 20. Gather Blaze Rods (E-15)
        map.put(MilestoneId.GATHER_BLAZE_RODS, new Milestone(
                MilestoneId.GATHER_BLAZE_RODS, "Gather Blaze Rods",
                Set.of(MilestoneId.ENTER_NETHER),
                obs -> obs.has("minecraft:blaze_rod", 1),
                66.0
        ));

        // 21. Gather Nether Wart (E-15)
        map.put(MilestoneId.GATHER_NETHER_WART, new Milestone(
                MilestoneId.GATHER_NETHER_WART, "Gather Nether Wart",
                Set.of(MilestoneId.ENTER_NETHER),
                obs -> obs.has("minecraft:nether_wart", 1),
                66.5
        ));

        return new MilestoneGraph(map);
    }

    /**
     * Evaluates the observation against the graph and history.
     */
    public ProgressionState evaluate(ProgressionObservation obs, Map<MilestoneId, Long> history) {
        Objects.requireNonNull(obs, "obs");
        Map<MilestoneId, Long> hist = history == null ? Map.of() : history;

        Map<MilestoneId, MilestoneSnapshot> snapshots = new LinkedHashMap<>();
        List<MilestoneId> activeFrontier = new ArrayList<>();
        List<MilestoneId> recoveryFrontier = new ArrayList<>();

        for (MilestoneId id : topologicalOrder) {
            Milestone milestone = milestones.get(id);
            boolean prerequisitesMet = true;
            for (MilestoneId prereq : milestone.prerequisites()) {
                MilestoneSnapshot prereqSnap = snapshots.get(prereq);
                if (prereqSnap == null || !prereqSnap.historicallyAchieved()) {
                    prerequisitesMet = false;
                    break;
                }
            }

            boolean physicalMet = milestone.isSatisfied(obs);
            boolean historicallyAchieved = hist.containsKey(id) || physicalMet;
            long firstTick = hist.getOrDefault(id, physicalMet ? obs.tick() : -1L);

            final MilestoneStatus status;
            if (physicalMet) {
                status = MilestoneStatus.ACHIEVED;
            } else if (historicallyAchieved) {
                status = MilestoneStatus.UNLOCKED;
                recoveryFrontier.add(id);
            } else if (!prerequisitesMet) {
                status = MilestoneStatus.LOCKED;
            } else {
                status = MilestoneStatus.UNLOCKED;
                activeFrontier.add(id);
            }

            snapshots.put(id, new MilestoneSnapshot(id, status, physicalMet, historicallyAchieved, firstTick));
        }

        // Sort frontiers by priority weight descending (higher priority targets first)
        Comparator<MilestoneId> priorityComparator = Comparator.comparingDouble(
                (MilestoneId id) -> milestones.get(id).priorityWeight()
        ).reversed();

        activeFrontier.sort(priorityComparator);
        recoveryFrontier.sort(priorityComparator);

        return new ProgressionState(obs.tick(), snapshots, activeFrontier, recoveryFrontier);
    }

    public Milestone getMilestone(MilestoneId id) {
        return milestones.get(id);
    }

    public List<MilestoneId> topologicalOrder() {
        return topologicalOrder;
    }

    private static boolean hasAnyWoodLog(ProgressionObservation obs) {
        return obs.has("minecraft:oak_log", 1) || obs.has("minecraft:birch_log", 1)
                || obs.has("minecraft:spruce_log", 1) || obs.has("minecraft:jungle_log", 1)
                || obs.has("minecraft:acacia_log", 1) || obs.has("minecraft:dark_oak_log", 1)
                || obs.has("minecraft:mangrove_log", 1) || obs.has("minecraft:cherry_log", 1);
    }

    private static boolean hasAnyPlank(ProgressionObservation obs) {
        return obs.has("minecraft:oak_planks", 1) || obs.has("minecraft:birch_planks", 1)
                || obs.has("minecraft:spruce_planks", 1) || obs.has("minecraft:jungle_planks", 1)
                || obs.has("minecraft:acacia_planks", 1) || obs.has("minecraft:dark_oak_planks", 1)
                || obs.has("minecraft:mangrove_planks", 1) || obs.has("minecraft:cherry_planks", 1)
                || obs.has("minecraft:bamboo_planks", 1);
    }

    private static boolean hasPickaxeTier(ProgressionObservation obs, int minimumTier) {
        if (minimumTier <= 1 && obs.has("minecraft:wooden_pickaxe", 1)) return true;
        if (minimumTier <= 2 && obs.has("minecraft:stone_pickaxe", 1)) return true;
        if (minimumTier <= 3 && obs.has("minecraft:iron_pickaxe", 1)) return true;
        if (minimumTier <= 4 && obs.has("minecraft:diamond_pickaxe", 1)) return true;
        return obs.has("minecraft:netherite_pickaxe", 1);
    }

    private static List<MilestoneId> computeTopologicalOrder(Map<MilestoneId, Milestone> map) {
        List<MilestoneId> order = new ArrayList<>();
        Map<MilestoneId, Integer> inDegree = new HashMap<>();
        Map<MilestoneId, List<MilestoneId>> dependents = new HashMap<>();

        for (MilestoneId id : map.keySet()) {
            inDegree.put(id, map.get(id).prerequisites().size());
            dependents.put(id, new ArrayList<>());
        }

        for (Milestone m : map.values()) {
            for (MilestoneId pre : m.prerequisites()) {
                if (!map.containsKey(pre)) {
                    throw new IllegalStateException("Milestone " + m.id() + " references unknown prerequisite: " + pre);
                }
                dependents.get(pre).add(m.id());
            }
        }

        List<MilestoneId> ready = new ArrayList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        while (!ready.isEmpty()) {
            MilestoneId current = ready.remove(0);
            order.add(current);
            for (MilestoneId dep : dependents.get(current)) {
                int count = inDegree.get(dep) - 1;
                inDegree.put(dep, count);
                if (count == 0) {
                    ready.add(dep);
                }
            }
        }

        if (order.size() != map.size()) {
            throw new IllegalStateException("Milestone graph contains a cycle!");
        }

        return List.copyOf(order);
    }
}
