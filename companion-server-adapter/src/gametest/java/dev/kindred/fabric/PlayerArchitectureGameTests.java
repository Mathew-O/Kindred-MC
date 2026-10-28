package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that commands only let
 * people do what they are allowed to.
 *
 * Technical: Native command boundary tests, not autonomous survival or player-body qualification.
 */
public final class PlayerArchitectureGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void playerCommandsAndCompatibilityAliasShareOneTree(TestContext context) {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        PlayerCommands.register(dispatcher);
        var primary = dispatcher.getRoot().getChild("aiplayer");
        var alias = dispatcher.getRoot().getChild("companion");
        context.assertTrue(primary != null && alias.getRedirect() == primary, "Alias must share the authoritative command tree");
        for (String prefix : java.util.List.of("aiplayer", "companion")) {
            for (String suffix : java.util.List.of("status", "stop", "resume", "wait 1", "limit timezone UTC",
                    "native create Ada", "native list", "native status " + java.util.UUID.randomUUID(),
                    "native stop " + java.util.UUID.randomUUID(), "native connect " + java.util.UUID.randomUUID(),
                    "native disconnect " + java.util.UUID.randomUUID(), "native wait 1 " + java.util.UUID.randomUUID(),
                    "native eat 2 " + java.util.UUID.randomUUID(), "native walk north 3 " + java.util.UUID.randomUUID(),
                    "native equip 10 " + java.util.UUID.randomUUID(), "native patch 1 64 1 2 65 2 minecraft:oak_log " + java.util.UUID.randomUUID(),
                    "native mine 1 64 1 0 " + java.util.UUID.randomUUID())) {
                var parsed = dispatcher.parse(prefix + " " + suffix, context.getWorld().getServer().getCommandSource());
                context.assertFalse(parsed.getReader().canRead(), "Command must parse: " + prefix + " " + suffix);
                context.assertTrue(parsed.getExceptions().isEmpty(), "No command parser exceptions");
            }
        }
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void unqualifiedCombatCannotStartThroughTheRuntime(TestContext context) {
        context.addFinalTask(() -> {
            PlayerRuntime runtime = KindredMod.runtime();
            context.assertTrue(runtime != null && runtime.isReady(), "Runtime initialized");
            context.assertTrue(runtime.duel(context.getWorld().getServer().getCommandSource(), null) == 0,
                    "Unqualified combat must be rejected before any body controller starts");
        });
    }
}
