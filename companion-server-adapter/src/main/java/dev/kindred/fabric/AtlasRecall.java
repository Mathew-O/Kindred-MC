package dev.kindred.fabric;

import dev.kindred.core.atlas.Atlas;

import java.util.ArrayList;
import java.util.List;

/**
 * In plain terms: what a player remembers again after a restart, from its atlas: each ore, lava, water and tree it
 * saw in the world it stands in, where it saw it; nothing the atlas does not hold, nothing of another world.
 *
 * Technical: F-30d (b). The pure choice {@link PortalRunCampaign}'s recall makes from an atlas read back from its file;
 * dimensions as {@link PortalRunCampaign#place} writes them (a registry key's path, {@code overworld}).
 */
final class AtlasRecall {
    private AtlasRecall() { }

    /** A sighting to remember again: what WorldKnowledge keeps it as, and where. */
    record Recalled(WorldKnowledge.Kind kind, int x, int y, int z) { }

    /** What of {@code from} is remembered again in {@code dimension}. */
    static List<Recalled> recallable(Atlas from, String dimension) {
        var out = new ArrayList<Recalled>();
        for (var chunk : from.snapshot().chunks()) {
            if (!dimension.equals(chunk.dimension())) continue;
            for (var s : chunk.sightings()) {
                var kind = kindOf(s.resource());
                if (kind == null || !dimension.equals(s.at().dimension())) continue;
                out.add(new Recalled(kind, s.at().x(), s.at().y(), s.at().z()));
            }
        }
        return out;
    }

    /** What WorldKnowledge keeps a resource as, for those it keeps; none for the rest (a village, a fortress, a stronghold). */
    static WorldKnowledge.Kind kindOf(Atlas.Resource resource) {
        return switch (resource) {
            case IRON -> WorldKnowledge.Kind.IRON_ORE;
            case DIAMOND -> WorldKnowledge.Kind.DIAMOND_ORE;
            case COAL -> WorldKnowledge.Kind.COAL_ORE;
            case LAVA -> WorldKnowledge.Kind.LAVA;
            case WATER -> WorldKnowledge.Kind.WATER;
            case TREES -> WorldKnowledge.Kind.LOG;
            default -> null;
        };
    }
}
