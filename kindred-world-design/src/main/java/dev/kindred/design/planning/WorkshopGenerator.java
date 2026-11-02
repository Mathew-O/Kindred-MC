package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Local, deterministic 5x5 shell plus three-block path; no inference required. */
public final class WorkshopGenerator {
    public static final String VERSION = "workshop-shell-1";

    public Design generate(Brief brief, Observation observation) {
        if (!brief.worldId().equals(observation.worldId())
                || !brief.dimension().equals(observation.dimension())) {
            throw new IllegalArgumentException("World/dimension mismatch");
        }
        List<Voxel> voxels = new ArrayList<>();
        Set<Pos> air = new HashSet<>();
        // Seed changes the roof palette only. This is variation, not semantic uniqueness.
        Material roof = (brief.seed() & 1) == 0 ? Material.OAK_PLANKS : Material.COBBLESTONE;
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 5; x++) {
                voxels.add(new Voxel(at(brief, x, 0, z), Material.COBBLESTONE, Phase.FLOOR_AND_PATH));
                voxels.add(new Voxel(at(brief, x, 4, z), roof, Phase.ROOF));
                for (int y = 1; y <= 3; y++) {
                    boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                    boolean doorway = x == 2 && z == 0 && y <= 2;
                    if (edge && !doorway) {
                        voxels.add(new Voxel(at(brief, x, y, z), Material.COBBLESTONE, Phase.WALLS));
                    } else {
                        air.add(at(brief, x, y, z));
                    }
                }
            }
        }
        for (int z = -3; z < 0; z++) {
            voxels.add(new Voxel(at(brief, 2, 0, z), Material.COBBLESTONE, Phase.FLOOR_AND_PATH));
            air.add(at(brief, 2, 1, z));
            air.add(at(brief, 2, 2, z));
        }
        voxels.sort(Comparator.comparing(Voxel::position));
        return new Design(brief.projectId() + ".shell", 1, observation.observationId(),
                observation.revision(), VERSION, brief, voxels, air,
                List.of(new Port("entrance", at(brief, 2, 1, 0)),
                        new Port("interior", at(brief, 2, 1, 2))));
    }

    /** Quarter-turn transform around the floor origin; exact arithmetic rejects overflow. */
    public static Pos at(Brief brief, int x, int y, int z) {
        int rx = x, rz = z;
        for (int i = 0; i < brief.rotation(); i++) {
            int nextX = Math.negateExact(rz);
            rz = rx;
            rx = nextX;
        }
        return brief.floorOrigin().add(rx, y, rz);
    }
}
