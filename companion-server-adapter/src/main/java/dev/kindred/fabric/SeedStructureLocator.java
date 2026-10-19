package dev.kindred.fabric;

import dev.kindred.core.CanonicalRoster;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.Structure;

import java.util.Optional;
import java.util.UUID;

/**
 * Optional seed-assisted structure locate (SPEED-01). Never runs unless {@code seedLocatorEnabled} is true.
 */
final class SeedStructureLocator {

    private static final long MIN_TICKS_BETWEEN_LOCATES = 600L;

    private SeedStructureLocator() { }

    /** Seed-assisted locate is opt-in and disabled while idle-observing (SPEED-01 / no omniscience). */
    static boolean locatorAllowed(CanonicalRoster.RuntimeSettings settings) {
        return settings != null
                && settings.seedLocatorEnabled()
                && settings.playstyleProfile() != null
                && !settings.playstyleProfile().isIdleObserving();
    }

    record LocateResult(BlockPos pos, String structureId, boolean seedLocatorUsed) { }

    static Optional<LocateResult> locateNearest(
            ServerWorld world,
            CanonicalRoster.RuntimeSettings settings,
            UUID playerId,
            long serverTick,
            long lastLocateTick,
            StructureKind kind,
            BlockPos origin,
            int searchRadiusChunks
    ) {
        if (!locatorAllowed(settings)) {
            return Optional.empty();
        }
        if (serverTick - lastLocateTick < MIN_TICKS_BETWEEN_LOCATES) {
            return Optional.empty();
        }
        TagKey<Structure> tag = structureTag(kind);
        if (tag == null) {
            return Optional.empty();
        }
        var found = world.locateStructure(tag, origin, searchRadiusChunks, false);
        if (found == null) {
            return Optional.empty();
        }
        return Optional.of(new LocateResult(found, kind.id(), true));
    }

    private static TagKey<Structure> structureTag(StructureKind kind) {
        return switch (kind) {
            case VILLAGE -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "village"));
            case RUINED_PORTAL -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "ruined_portal"));
            case SHIPWRECK -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "shipwreck"));
            case BURIED_TREASURE -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "buried_treasure"));
            case NETHER_FORTRESS -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "fortress"));
            case BASTION -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "bastion_remnant"));
            case STRONGHOLD -> TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "stronghold"));
        };
    }

    enum StructureKind {
        VILLAGE("minecraft:village"),
        RUINED_PORTAL("minecraft:ruined_portal"),
        SHIPWRECK("minecraft:shipwreck"),
        BURIED_TREASURE("minecraft:buried_treasure"),
        NETHER_FORTRESS("minecraft:fortress"),
        BASTION("minecraft:bastion_remnant"),
        STRONGHOLD("minecraft:stronghold");

        private final String id;

        StructureKind(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }
}
