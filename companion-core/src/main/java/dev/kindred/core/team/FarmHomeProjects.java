package dev.kindred.core.team;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Standard shared team projects for establishing and maintaining home shelter and farm bases (E-13).
 */
public final class FarmHomeProjects {
    private FarmHomeProjects() { }

    public static SharedProject createFarmEstablishmentProject(ProjectId id, String name, String description) {
        TaskId craftHoeId = TaskId.of(id, "craft_hoe");
        TaskId tillSoilId = TaskId.of(id, "till_soil");
        TaskId sowCropsId = TaskId.of(id, "sow_crops");
        TaskId harvestCropsId = TaskId.of(id, "harvest_crops");

        SharedTask craftHoeTask = SharedTask.open(
                craftHoeId, id, "Craft Agricultural Hoe", "native_craft",
                Map.of("recipe", "stone_hoe", "reserved_item", "minecraft:cobblestone", "reserved_count", "2"),
                Set.of()
        );

        SharedTask tillSoilTask = SharedTask.open(
                tillSoilId, id, "Till Irrigated Soil", "native_till",
                Map.of(),
                Set.of(craftHoeId)
        );

        SharedTask sowCropsTask = SharedTask.open(
                sowCropsId, id, "Sow Wheat Seeds on Farmland", "native_plant",
                Map.of("item", "minecraft:wheat_seeds", "reserved_item", "minecraft:wheat_seeds", "reserved_count", "1"),
                Set.of(tillSoilId)
        );

        SharedTask harvestCropsTask = SharedTask.open(
                harvestCropsId, id, "Harvest Mature Crops with Replanting Conservation", "native_harvest",
                Map.of("replant", "true"),
                Set.of(sowCropsId)
        );

        SharedProject project = SharedProject.create(id, name, description);
        return project
                .withTask(craftHoeTask)
                .withTask(tillSoilTask)
                .withTask(sowCropsTask)
                .withTask(harvestCropsTask);
    }

    public static SharedProject createShelterBedProject(ProjectId id, String name, String description) {
        TaskId craftBedId = TaskId.of(id, "craft_bed");
        TaskId placeBedId = TaskId.of(id, "place_bed");
        TaskId sleepBedId = TaskId.of(id, "anchor_spawn");

        SharedTask craftBedTask = SharedTask.open(
                craftBedId, id, "Craft Wool Bed", "native_craft",
                Map.of("recipe", "white_bed", "reserved_item", "minecraft:white_wool", "reserved_count", "3"),
                Set.of()
        );

        SharedTask placeBedTask = SharedTask.open(
                placeBedId, id, "Place Bed in Shelter", "native_place",
                Map.of("block", "minecraft:white_bed"),
                Set.of(craftBedId)
        );

        SharedTask sleepBedTask = SharedTask.open(
                sleepBedId, id, "Sleep and Anchor Respawn Point", "native_sleep",
                Map.of(),
                Set.of(placeBedId)
        );

        SharedProject project = SharedProject.create(id, name, description);
        return project
                .withTask(craftBedTask)
                .withTask(placeBedTask)
                .withTask(sleepBedTask);
    }
}
