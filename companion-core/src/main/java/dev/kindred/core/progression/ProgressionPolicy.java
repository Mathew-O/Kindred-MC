package dev.kindred.core.progression;

import dev.kindred.core.TaskPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure policy that maps a ProgressionState and physical ProgressionObservation into actionable candidate recommendations.
 */
public final class ProgressionPolicy {

    private ProgressionPolicy() { }

    /**
     * Recommends actionable candidate tasks to advance or recover milestones on the active frontier.
     */
    public static List<ProgressionRecommendation> recommend(ProgressionState state, ProgressionObservation obs) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(obs, "obs");

        List<ProgressionRecommendation> recommendations = new ArrayList<>();

        // 1. Recovery frontier (broken/lost gear previously earned takes precedence)
        for (MilestoneId id : state.recoveryFrontier()) {
            recommendForMilestone(id, obs, 5.0).ifPresent(recommendations::add);
        }

        // 2. Active progression frontier
        for (MilestoneId id : state.activeFrontier()) {
            recommendForMilestone(id, obs, 0.0).ifPresent(recommendations::add);
        }

        return List.copyOf(recommendations);
    }

    private static java.util.Optional<ProgressionRecommendation> recommendForMilestone(
            MilestoneId id, ProgressionObservation obs, double needBonus) {

        int planksCount = getPlanksCount(obs);
        int logsCount = getLogsCount(obs);
        int sticksCount = obs.count("minecraft:stick");
        int cobbleCount = obs.count("minecraft:cobblestone");
        int ironIngotCount = obs.count("minecraft:iron_ingot");

        switch (id) {
            case GATHER_WOOD -> {
                if (obs.hasAssignedLogPatch()) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_gather", Map.of("count", "1", "slot", "0"),
                            TaskPreferences.Priority.PROJECT, 60.0 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Gather Wood Logs"
                    ));
                }
            }
            case CRAFT_PLANKS -> {
                if (logsCount >= 1) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_craft", Map.of("recipe", "oak_planks"),
                            TaskPreferences.Priority.PROJECT, 60.2 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Craft Wooden Planks"
                    ));
                }
            }
            case CRAFT_STICKS -> {
                if (sticksCount < 2 && planksCount >= 2 && obs.hasNearbyCraftingTable()) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_craft", Map.of("recipe", "stick"),
                            TaskPreferences.Priority.PROJECT, 60.4 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Craft Sticks"
                    ));
                }
            }
            case CRAFT_CRAFTING_TABLE -> {
                if (!obs.hasNearbyCraftingTable() && !obs.has("minecraft:crafting_table", 1)) {
                    if (planksCount >= 4) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "crafting_table"),
                                TaskPreferences.Priority.PROJECT, 60.5 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Crafting Table"
                        ));
                    }
                }
            }
            case DEPLOY_CRAFTING_TABLE -> {
                if (obs.has("minecraft:crafting_table", 1) && !obs.hasNearbyCraftingTable()) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_place", Map.of("block", "minecraft:crafting_table"),
                            TaskPreferences.Priority.PROJECT, 60.8 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Deploy Crafting Table"
                    ));
                }
            }
            case CRAFT_WOODEN_PICKAXE -> {
                if (obs.hasNearbyCraftingTable()) {
                    if (planksCount >= 3 && sticksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "wooden_pickaxe"),
                                TaskPreferences.Priority.PROJECT, 62.5 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Wooden Pickaxe"
                        ));
                    } else if (sticksCount < 2 && planksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stick"),
                                TaskPreferences.Priority.PROJECT, 60.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Preparing sticks for Wooden Pickaxe"
                        ));
                    }
                }
            }
            case MINE_COBBLESTONE -> {
                if (obs.hasAssignedStonePatch() && (obs.has("minecraft:wooden_pickaxe", 1)
                        || obs.has("minecraft:stone_pickaxe", 1) || obs.has("minecraft:iron_pickaxe", 1))) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_mine", Map.of("resource", "minecraft:stone"),
                            TaskPreferences.Priority.PROJECT, 60.0 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 1.0),
                            "Advancing milestone: Mine Cobblestone"
                    ));
                }
            }
            case CRAFT_STONE_PICKAXE -> {
                if (obs.hasNearbyCraftingTable()) {
                    if (cobbleCount >= 3 && sticksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stone_pickaxe"),
                                TaskPreferences.Priority.PROJECT, 62.0 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Stone Pickaxe"
                        ));
                    } else if (sticksCount < 2 && planksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stick"),
                                TaskPreferences.Priority.PROJECT, 60.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Preparing sticks for Stone Pickaxe"
                        ));
                    }
                }
            }
            case CRAFT_FURNACE -> {
                if (obs.hasNearbyCraftingTable() && !obs.hasNearbyFurnace() && !obs.has("minecraft:furnace", 1)) {
                    if (cobbleCount >= 8) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "furnace"),
                                TaskPreferences.Priority.PROJECT, 61.5 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Furnace"
                        ));
                    }
                }
            }
            case DEPLOY_FURNACE -> {
                if (obs.has("minecraft:furnace", 1) && !obs.hasNearbyFurnace()) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_place", Map.of("block", "minecraft:furnace"),
                            TaskPreferences.Priority.PROJECT, 61.2 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Deploy Furnace"
                    ));
                }
            }
            case SMELT_RESOURCES -> {
                if (obs.hasNearbyFurnace()) {
                    if (obs.hasSmeltOutputWaiting()) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_smelt", Map.of("action", "extract"),
                                TaskPreferences.Priority.PROJECT, 62.5 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Extracting finished smelter products"
                        ));
                    } else if (obs.canSmelt()) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_smelt", Map.of("action", "load"),
                                TaskPreferences.Priority.PROJECT, 61.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Loading ingredients and fuel into furnace"
                        ));
                    }
                }
            }
            case CRAFT_IRON_PICKAXE -> {
                if (obs.hasNearbyCraftingTable()) {
                    if (ironIngotCount >= 3 && sticksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "iron_pickaxe"),
                                TaskPreferences.Priority.PROJECT, 63.0 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Iron Pickaxe"
                        ));
                    } else if (sticksCount < 2 && planksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stick"),
                                TaskPreferences.Priority.PROJECT, 60.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Preparing sticks for Iron Pickaxe"
                        ));
                    }
                }
            }
            case CRAFT_SHIELD -> {
                if (obs.hasNearbyCraftingTable() && ironIngotCount >= 1 && planksCount >= 6) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_craft", Map.of("recipe", "shield"),
                            TaskPreferences.Priority.PROJECT, 62.8 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Craft Shield"
                    ));
                }
            }
            case CRAFT_IRON_SWORD -> {
                if (obs.hasNearbyCraftingTable()) {
                    if (ironIngotCount >= 2 && sticksCount >= 1) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "iron_sword"),
                                TaskPreferences.Priority.PROJECT, 62.6 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Iron Sword"
                        ));
                    } else if (sticksCount < 1 && planksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stick"),
                                TaskPreferences.Priority.PROJECT, 60.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Preparing sticks for Iron Sword"
                        ));
                    }
                }
            }
            case SECURE_SHELTER -> {
                if (obs.isNight() && !obs.hasShelter() && cobbleCount >= 1) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_place", Map.of("block", "minecraft:cobblestone"),
                            TaskPreferences.Priority.URGENT, 95.0 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.0),
                            "Emergency night shelter barricade"
                    ));
                }
            }
            case CRAFT_HOE -> {
                if (obs.hasNearbyCraftingTable()) {
                    if (cobbleCount >= 2 && sticksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stone_hoe"),
                                TaskPreferences.Priority.PROJECT, 61.5 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Stone Hoe"
                        ));
                    } else if (planksCount >= 2 && sticksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "wooden_hoe"),
                                TaskPreferences.Priority.PROJECT, 61.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Craft Wooden Hoe"
                        ));
                    } else if (sticksCount < 2 && planksCount >= 2) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_craft", Map.of("recipe", "stick"),
                                TaskPreferences.Priority.PROJECT, 60.4 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Preparing sticks for Hoe"
                        ));
                    }
                }
            }
            case ESTABLISH_FARM -> {
                if (obs.hasHoe() && obs.hasNearbyFarmland() && obs.has("minecraft:wheat_seeds", 1)) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_plant", Map.of("item", "minecraft:wheat_seeds"),
                            TaskPreferences.Priority.PROJECT, 62.0 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Sow Seeds on Farmland"
                    ));
                } else if (obs.hasHoe()) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_till", Map.of(),
                            TaskPreferences.Priority.PROJECT, 61.8 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Till Soil into Farmland"
                    ));
                }
            }
            case CRAFT_BED -> {
                int woolCount = obs.count("minecraft:white_wool") + obs.count("minecraft:black_wool")
                        + obs.count("minecraft:red_wool") + obs.count("minecraft:yellow_wool");
                if (obs.hasNearbyCraftingTable() && woolCount >= 3 && planksCount >= 3) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_craft", Map.of("recipe", "white_bed"),
                            TaskPreferences.Priority.PROJECT, 63.5 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Craft Bed"
                    ));
                }
            }
            case ENTER_NETHER -> {
                if (!obs.isInNether() && obs.has("minecraft:flint_and_steel", 1)) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_ignite_portal", Map.of(),
                            TaskPreferences.Priority.PROJECT, 65.0 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Ignite and enter Nether portal"
                    ));
                }
            }
            case GATHER_BLAZE_RODS -> {
                if (obs.isInNether()) {
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_traverse", Map.of("target", "nether_fortress"),
                            TaskPreferences.Priority.PROJECT, 66.0 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Search Nether Fortress for Blaze Rods"
                    ));
                }
            }
            case GATHER_NETHER_WART -> {
                if (obs.isInNether()) {
                    if (obs.hasAssignedNetherWartPatch()) {
                        return java.util.Optional.of(new ProgressionRecommendation(
                                id, "native_gather", Map.of("count", "1", "slot", "0", "resource", "minecraft:nether_wart"),
                                TaskPreferences.Priority.PROJECT, 67.0 + needBonus,
                                new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                                "Advancing milestone: Gather Nether Wart from assigned patch"
                        ));
                    }
                    return java.util.Optional.of(new ProgressionRecommendation(
                            id, "native_traverse", Map.of("target", "nether_fortress"),
                            TaskPreferences.Priority.PROJECT, 66.5 + needBonus,
                            new TaskPreferences.Affinities(0.0, 0.0, 0.0, 0.8),
                            "Advancing milestone: Search Nether Fortress for Nether Wart"
                    ));
                }
            }
        }

        return java.util.Optional.empty();
    }

    private static int getLogsCount(ProgressionObservation obs) {
        return obs.count("minecraft:oak_log") + obs.count("minecraft:birch_log")
                + obs.count("minecraft:spruce_log") + obs.count("minecraft:jungle_log")
                + obs.count("minecraft:acacia_log") + obs.count("minecraft:dark_oak_log")
                + obs.count("minecraft:mangrove_log") + obs.count("minecraft:cherry_log");
    }

    private static int getPlanksCount(ProgressionObservation obs) {
        return obs.count("minecraft:oak_planks") + obs.count("minecraft:birch_planks")
                + obs.count("minecraft:spruce_planks") + obs.count("minecraft:jungle_planks")
                + obs.count("minecraft:acacia_planks") + obs.count("minecraft:dark_oak_planks")
                + obs.count("minecraft:mangrove_planks") + obs.count("minecraft:cherry_planks")
                + obs.count("minecraft:bamboo_planks");
    }
}
