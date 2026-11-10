package dev.kindred.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * In plain terms: GameTests of how many AI players a server allows: three unless its operator says otherwise (a fourth
 * is refused, and the refusal names the setting), eight when the operator sets eight (the ninth refused), and never more
 * than twelve whatever the setting says.
 *
 * Technical: {@code maxNativePlayers} in edits.properties, the server's own policy, read at startup
 * ({@link PlayerRuntime#readNativeLimit}): default {@value PlayerRuntime#DEFAULT_NATIVE_PLAYERS}, clamped to 1 to
 * {@value PlayerRuntime#MAX_NATIVE_PLAYERS}. Allocation is counted with the creations still pending, so four asks in one
 * tick are already four.
 */
public final class NativeLimitGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nativeLimitDefault", tickLimit = 400)
    public void threeUnlessTheServerSaysOtherwise(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.maxNativePlayers() == 3, "Three by default: " + f.runtime.maxNativePlayers());
                    int made = 0;
                    for (String name : new String[] {"Ada", "Bram", "Cleo"}) made += f.runtime.createNative(f.source, name);
                    context.assertTrue(made == 3, "Three allowed: " + made);
                    context.assertTrue(f.runtime.createNative(f.source, "Dara") == 0, "A fourth refused");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nativeLimitEight", tickLimit = 400)
    public void eightWhenTheServerSetsEightAndTheNinthRefused(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 380, "maxNativePlayers=8\n");
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.maxNativePlayers() == 8, "Eight as set: " + f.runtime.maxNativePlayers());
                    int made = 0;
                    for (String name : new String[] {"Ada", "Bram", "Cleo", "Dara", "Eli", "Finn", "Gus", "Hal"}) made += f.runtime.createNative(f.source, name);
                    context.assertTrue(made == 8, "Eight allowed: " + made);
                    context.assertTrue(f.runtime.createNative(f.source, "Ivy") == 0, "The ninth refused");
                    // An operator's other change to the policy file keeps the limit in it.
                    context.assertTrue(f.runtime.setWorldEdits(f.source, true) == 1, "Edits set again");
                })
                .createAndAdd(() -> {
                    String file;
                    try {
                        file = Files.readString(f.directory.resolve("edits.properties"));
                    } catch (java.io.IOException unreadable) {
                        throw new net.minecraft.test.GameTestException("No policy file: " + unreadable);
                    }
                    context.assertTrue(file.contains("maxNativePlayers=8") && file.contains("autonomousEdits=true"), "The limit kept in the file: " + file);
                    context.assertTrue(PlayerRuntime.readNativeLimit(f.directory) == 8, "And read back as eight");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nativeLimitRead", tickLimit = 100)
    public void theSettingIsClampedAndAnUnreadableOneIsTheDefault(TestContext context) {
        var server = context.getWorld().getServer();
        Path dir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("kindred-limit-" + java.util.UUID.randomUUID());
        try {
            Files.createDirectories(dir);
            context.assertTrue(PlayerRuntime.readNativeLimit(dir) == 3, "No file: three");
            for (var c : new String[][] {{"99", "12"}, {"0", "1"}, {"many", "3"}, {"", "3"}, {"5", "5"}}) {
                Files.writeString(dir.resolve("edits.properties"), "autonomousEdits=true\nmaxNativePlayers=" + c[0] + "\n");
                int read = PlayerRuntime.readNativeLimit(dir);
                context.assertTrue(read == Integer.parseInt(c[1]), "'" + c[0] + "' reads as " + c[1] + ", not " + read);
            }
        } catch (java.io.IOException failure) {
            throw new net.minecraft.test.GameTestException("Could not write the test's setting: " + failure);
        }
        context.complete();
    }
}
