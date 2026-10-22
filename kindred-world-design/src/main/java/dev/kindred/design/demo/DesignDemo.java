package dev.kindred.design.demo;

import dev.kindred.design.model.DesignModel.*;
import dev.kindred.design.planning.*;
import dev.kindred.design.validation.StaticValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Entirely synthetic facts and stock; nothing here is earned gameplay evidence. */
public final class DesignDemo {
    private DesignDemo() {}

    public static Brief brief(int rotation, long seed, Pos origin) {
        Pos anchorOffset = switch (rotation) {
            case 0 -> new Pos(2, 1, -3);
            case 1 -> new Pos(3, 1, 2);
            case 2 -> new Pos(-2, 1, 3);
            case 3 -> new Pos(-3, 1, -2);
            default -> throw new IllegalArgumentException("Rotation must be 0..3");
        };
        return new Brief("pilot_workshop", new UUID(0, 1), "minecraft:overworld",
                new Box(origin.add(-6, -1, -6), origin.add(6, 5, 6)), origin,
                origin.add(anchorOffset.x(), anchorOffset.y(), anchorOffset.z()), rotation, seed, 256);
    }

    public static Observation flatObservation(Brief brief) {
        Map<Pos, Cell> cells = new HashMap<>();
        for (Pos p : brief.site().cells()) {
            cells.put(p, p.y() < brief.floorOrigin().y() ? Cell.SOLID : Cell.AIR);
        }
        return new Observation("synthetic_flat_01", brief.worldId(), brief.dimension(), 1,
                Instant.parse("2026-10-22T12:00:00Z"), "synthetic-fixture", brief.site(), cells, Set.of());
    }

    public static void main(String[] args) throws Exception {
        Brief brief = brief(0, 734922, new Pos(0, 64, 0));
        Observation observation = flatObservation(brief);
        Design design = new WorkshopGenerator().generate(brief, observation);
        var report = new StaticValidator().validate(design, observation);
        var budget = new MaterialPlanner().evaluate(design,
                Map.of(Material.COBBLESTONE, 70L, Material.OAK_PLANKS, 20L),
                Map.of(Material.COBBLESTONE, 5L));
        var plan = new SupportCompiler().compile(design, observation);
        StringBuilder text = new StringBuilder("Kindred world-design synthetic pilot\n")
                .append("No world IO, inventory changes, reservations, or completion evidence.\n")
                .append("Design: ").append(design.designId()).append(" revision ").append(design.revision()).append('\n')
                .append("Artifact SHA-256: ").append(plan.designHash()).append('\n')
                .append("Static geometry valid: ").append(report.valid()).append('\n')
                .append("Native execution qualified: ").append(plan.executionQualified()).append('\n')
                .append("Proposed blocks: ").append(design.voxels().size()).append('\n');
        for (Material material : Material.values()) {
            text.append(material.itemId()).append(": required=").append(budget.required().getOrDefault(material, 0L))
                    .append(", unreserved=").append(budget.available().getOrDefault(material, 0L))
                    .append(", deficit=").append(budget.deficits().getOrDefault(material, 0L)).append('\n');
        }
        text.append("Preliminary support steps: ").append(plan.steps().size()).append('\n')
                .append("Missing qualification: ").append(new TreeSet<>(plan.missingQualification())).append('\n');
        text.append("Floor/path overhead (z=-3..4, x=0..4; # block, . empty):\n");
        Set<Pos> occupied = new HashSet<>();
        design.voxels().forEach(v -> occupied.add(v.position()));
        for (int z = -3; z <= 4; z++) {
            for (int x = 0; x <= 4; x++) text.append(occupied.contains(new Pos(x, 64, z)) ? '#' : '.');
            text.append('\n');
        }
        if (args.length == 1) {
            Path output = Path.of(args[0]);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, text, StandardCharsets.UTF_8);
        } else if (args.length > 1) {
            throw new IllegalArgumentException("Usage: DesignDemo [report-path]");
        }
        System.out.print(text);
    }
}
