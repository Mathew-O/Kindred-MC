package dev.kindred.fabric;

import net.minecraft.nbt.NbtCompound;
import java.util.Optional;

/** Narrow PlayerManager adapter hook. State was read off-thread and validated before registration. */
public interface PreparedNativePlayer {
    Optional<NbtCompound> kindred$loadPreparedState();
}
