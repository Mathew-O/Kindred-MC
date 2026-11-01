package dev.kindred.fabric.mixin;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * In plain terms: who really typed a command. A command run "as" someone else still answers to whoever ran it, and
 * this lets a person's own say (whether the AI players may learn from them) be taken only from that person.
 *
 * Technical: the source's output, which {@code /execute as} leaves as the one who ran it while the entity changes
 * (E-47, D10).
 */
@Mixin(ServerCommandSource.class)
public interface ServerCommandSourceAccessor {
    @Accessor("output") CommandOutput kindred$output();
}
