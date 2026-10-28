package dev.kindred.testkit;

import dev.kindred.persistence.SqliteCompanionStore;
import dev.kindred.scheduler.BudgetState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks that the quick self-check demo passes.
 */
class FoundationDemoTest {
    @TempDir Path directory;

    @Test
    void persistedContractsStillHoldOnAnotherIndependentReopen() throws Exception {
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        FoundationDemo.DemoResult result = FoundationDemo.run(directory.resolve("demo.sqlite"),
                new PrintStream(transcript, true, StandardCharsets.UTF_8));

        SqliteCompanionStore store = SqliteCompanionStore.open(result.database(), 100).join();
        try {
            var companion = store.create(FoundationDemo.REQUEST).join().companion();
            BudgetState budget = store.findBudget(companion.id(), FoundationDemo.PERIOD.startInclusive()).join().orElseThrow();
            assertEquals(result.companionId(), companion.id());
            assertTrue(companion.stopLatched());
            assertEquals(5_000, budget.chargedMillis(), "A second recovery must not charge the slice twice");
            assertEquals(0, budget.observedActiveMillis(), "Conservative accounting is not evidence of world work");
            assertTrue(budget.outstanding().isEmpty());
        } finally {
            store.closeAsync().join();
        }
        String output = transcript.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("no Minecraft world/no native acceptance proof"));
        assertTrue(output.contains("Contract demo passed"));
    }
}
