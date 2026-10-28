package dev.kindred.fabric;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import java.util.Optional;

/**
 * In plain terms: marks a player body that belongs to an AI player, so Minecraft loads it from the save the
 * mod already read and checked, and brings it back as an AI player when it respawns.
 *
 * Technical: Narrow PlayerManager adapter hook. State was read off-thread and validated before registration.
 */
public interface PreparedNativePlayer {
    Optional<NbtCompound> kindred$loadPreparedState();

    /** The replacement entity vanilla respawn fills in; only valid while the owning body is respawning it. */
    ServerPlayerEntity kindred$respawned(ServerWorld world);
}
