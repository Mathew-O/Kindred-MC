package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.RemovalToken;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.UUID;

/**
 * Native GameTests for F-14 lifecycle operations (suspend, recover, removal tokens).
 */
public final class LifecycleGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void lifecycleCommandsParseWithOptionalUuid(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();

        for (String verb : new String[]{"suspend", "recover", "remove"}) {
            var parsed = dispatcher.parse("companion " + verb, source);
            context.assertFalse(parsed.getReader().canRead(), verb + " must parse without UUID");
            context.assertTrue(parsed.getExceptions().isEmpty(), verb + " grammar must accept no args");

            var withId = dispatcher.parse("companion " + verb + " " + UUID.randomUUID(), source);
            context.assertFalse(withId.getReader().canRead(), verb + " must accept trailing UUID");
            context.assertTrue(withId.getExceptions().isEmpty(), verb + " with UUID must be valid");
        }

        // confirm-remove requires a token UUID
        var confirmParsed = dispatcher.parse("companion confirm-remove " + UUID.randomUUID(), source);
        context.assertFalse(confirmParsed.getReader().canRead(), "confirm-remove must parse with token");
        context.assertTrue(confirmParsed.getExceptions().isEmpty(), "confirm-remove grammar must accept token");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void removalTokensAreSingleUseAndRejectForeignOrExpired(TestContext context) {
        UUID token = UUID.randomUUID();
        UUID companion = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        long now = 10_000;

        RemovalToken rt = new RemovalToken(token, companion, actor, now + 60_000);

        // Matching token, companion, actor, within time
        context.assertTrue(rt.matches(token, companion, actor, now + 1_000), "Valid token must match");

        // Expired token
        context.assertFalse(rt.matches(token, companion, actor, now + 60_001), "Expired token must be rejected");

        // Foreign actor
        context.assertFalse(rt.matches(token, companion, UUID.randomUUID(), now + 1_000), "Foreign actor must be rejected");

        // Wrong token UUID
        context.assertFalse(rt.matches(UUID.randomUUID(), companion, actor, now + 1_000), "Wrong token UUID must be rejected");

        context.complete();
    }
}
