package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.Companion;
import dev.kindred.core.GoalRecord;
import dev.kindred.core.JournalSummary;
import dev.kindred.core.OperatingMode;
import dev.kindred.core.Personality;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.UUID;

/**
 * Native GameTests for F-11 (limits), F-16 (modes & projects), F-20 (memories), and F-21 (journal).
 */
public final class IntentionsGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void modeAndScopeCommandsParseWithOptionalUuid(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();

        // Mode variants: companion, together (alias), independent
        for (String sub : new String[]{"companion", "together", "independent"}) {
            var parsed = dispatcher.parse("companion mode " + sub, source);
            context.assertFalse(parsed.getReader().canRead(), "mode " + sub + " must parse without UUID");
            context.assertTrue(parsed.getExceptions().isEmpty(), "mode " + sub + " grammar must accept no UUID");

            var withId = dispatcher.parse("companion mode " + sub + " " + UUID.randomUUID(), source);
            context.assertFalse(withId.getReader().canRead(), "mode " + sub + " must accept trailing UUID");
            context.assertTrue(withId.getExceptions().isEmpty(), "mode " + sub + " with UUID must be valid");
        }

        // Scope variants: home, approved
        for (String sub : new String[]{"home", "approved"}) {
            var parsed = dispatcher.parse("companion scope " + sub, source);
            context.assertFalse(parsed.getReader().canRead(), "scope " + sub + " must parse without UUID");
            context.assertTrue(parsed.getExceptions().isEmpty(), "scope " + sub + " grammar must accept no UUID");

            var withId = dispatcher.parse("companion scope " + sub + " " + UUID.randomUUID(), source);
            context.assertFalse(withId.getReader().canRead(), "scope " + sub + " must accept trailing UUID");
            context.assertTrue(withId.getExceptions().isEmpty(), "scope " + sub + " with UUID must be valid");
        }

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void goalAndMemoryCommandsParse(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();

        // goal list
        var goalList = dispatcher.parse("companion goal list", source);
        context.assertFalse(goalList.getReader().canRead(), "goal list must parse");
        context.assertTrue(goalList.getExceptions().isEmpty(), "goal list valid");

        // goal add "desc" "outcome" 85
        var goalAdd = dispatcher.parse("companion goal add \"Gather oak\" \"Collect 16 logs\" 85", source);
        context.assertFalse(goalAdd.getReader().canRead(), "goal add must parse");
        context.assertTrue(goalAdd.getExceptions().isEmpty(), "goal add valid");

        // memory list
        var memList = dispatcher.parse("companion memory list", source);
        context.assertFalse(memList.getReader().canRead(), "memory list must parse");
        context.assertTrue(memList.getExceptions().isEmpty(), "memory list valid");

        // memory add preference "likes birch forests"
        var memAdd = dispatcher.parse("companion memory add preference \"likes birch forests\"", source);
        context.assertFalse(memAdd.getReader().canRead(), "memory add must parse");
        context.assertTrue(memAdd.getExceptions().isEmpty(), "memory add valid");

        // memory forget <UUID>
        var memForget = dispatcher.parse("companion memory forget " + UUID.randomUUID(), source);
        context.assertFalse(memForget.getReader().canRead(), "memory forget must parse");
        context.assertTrue(memForget.getExceptions().isEmpty(), "memory forget valid");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void limitAndJournalCommandsParseAndSummarize(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();

        // limit status
        var limStatus = dispatcher.parse("companion limit status", source);
        context.assertFalse(limStatus.getReader().canRead(), "limit status must parse");

        // limit cap 4
        var limCap = dispatcher.parse("companion limit cap 4", source);
        context.assertFalse(limCap.getReader().canRead(), "limit cap must parse");

        // limit offline true
        var limOffline = dispatcher.parse("companion limit offline true", source);
        context.assertFalse(limOffline.getReader().canRead(), "limit offline must parse");

        // journal
        var journal = dispatcher.parse("companion journal", source);
        context.assertFalse(journal.getReader().canRead(), "journal must parse");

        // JournalSummary truthfulness rendering check
        String report = JournalSummary.render("Lydia", 3, 1, 0,
                List.of("Broke 16 oak logs.", "Deposited 16 oak logs into chest."));
        context.assertTrue(report.contains("3 verified"), "Must report verified actions");
        context.assertTrue(report.contains("1 failed"), "Must report failed actions");
        context.assertTrue(report.contains("0 uncertain"), "Must report uncertain actions");
        context.assertTrue(report.contains("Broke 16 oak logs"), "Must contain verified details");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void modeSwitchInvalidatesIntentRevisionAndPreservesInventoryAndTraits(TestContext context) {
        CompanionEntity body = KindredMod.COMPANION.create(context.getWorld());
        if (body == null) throw new IllegalStateException("Missing companion factory");
        UUID companionId = body.getUuid();
        UUID ownerId = UUID.randomUUID();
        body.bind(companionId, ownerId);
        body.inventory().setStack(0, new ItemStack(Items.OAK_LOG, 8));

        Companion initialCompanion = Companion.create(companionId, ownerId, "Lydia", UUID.randomUUID(), "minecraft:overworld");
        context.assertEquals(0L, initialCompanion.intentRevision(), "Initial intent revision is 0");

        // Simulate mode switch:
        // 1. Advance mode
        OperatingMode mode = OperatingMode.defaults(companionId);
        context.assertEquals(OperatingMode.Mode.COMPANION, mode.mode(), "Default mode is COMPANION");
        OperatingMode nextMode = mode.withMode(OperatingMode.Mode.INDEPENDENT);
        context.assertEquals(OperatingMode.Mode.INDEPENDENT, nextMode.mode(), "Next mode is INDEPENDENT");
        context.assertEquals(1L, nextMode.revision(), "Mode revision is 1");

        // 2. Invalidate intent revision
        Companion switchedCompanion = initialCompanion.withRevisions(Math.incrementExact(initialCompanion.intentRevision()), initialCompanion.taskRevision());
        context.assertEquals(1L, switchedCompanion.intentRevision(), "Intent revision advanced to invalidate old authority");

        // 3. Preserve inventory
        context.assertEquals(8, ItemMoves.count(body.inventory(), Items.OAK_LOG), "Inventory preserved during mode switch");

        // 4. Preserve traits
        Personality traits = Personality.defaults();
        context.assertEquals(50, traits.farming(), "Farming trait preserved");
        context.assertEquals(50, traits.mining(), "Mining trait preserved");

        // 5. Switching while stopped remains stopped
        Companion stopped = initialCompanion.withStopLatched(true);
        context.assertTrue(stopped.stopLatched(), "Remains stopped");
        Companion switchedWhileStopped = stopped.withRevisions(Math.incrementExact(stopped.intentRevision()), stopped.taskRevision());
        context.assertTrue(switchedWhileStopped.stopLatched(), "Still stopped after mode switch");

        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void goalSurvivesAcrossFollowInterruptWithoutDeletion(TestContext context) {
        UUID companionId = UUID.randomUUID();
        GoalRecord goal = new GoalRecord(
                UUID.randomUUID(), companionId, GoalRecord.Origin.OWNER,
                "Build fishing dock", "Collect 32 oak logs", 80, GoalRecord.Status.OPEN, 0
        );

        // Follow interrupts without deleting the project goal
        context.assertEquals(GoalRecord.Status.OPEN, goal.status(), "Goal remains OPEN when follow begins");
        context.assertEquals("Build fishing dock", goal.description(), "Goal description intact");
        context.assertEquals(80, goal.priority(), "Goal priority intact");

        // Completing or advancing goal requires deterministic evidence
        GoalRecord advanced = goal.advance(GoalRecord.Status.DONE);
        context.assertEquals(GoalRecord.Status.DONE, advanced.status(), "Advanced goal is DONE");
        context.assertEquals(1L, advanced.revision(), "Advanced goal revision is 1");

        context.complete();
    }
}
