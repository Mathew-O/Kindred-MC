package dev.kindred.fabric;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import java.util.Optional;

/** Narrow PlayerManager adapter hook. State was read off-thread and validated before registration. */
public interface PreparedNativePlayer {
    Optional<NbtCompound> kindred$loadPreparedState();

    /** The replacement entity vanilla respawn fills in; only valid while the owning body is respawning it. */
    ServerPlayerEntity kindred$respawned(ServerWorld world);
}
