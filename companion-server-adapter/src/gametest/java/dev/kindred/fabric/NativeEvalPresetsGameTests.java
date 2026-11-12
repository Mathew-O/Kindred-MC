package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialProfile;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * In plain terms: an evaluation run's preset traits for a player (M-06i's founders and joiners) are saved, and kept
 * after a restart, and the player's profile can still be changed after them. Before, three traits were saved as three
 * changes at once; the store refused the save, and the player's profile stayed ahead of its saved one, so every later
 * change to it was refused as well, and a run continued from its database (keepdb) lost the presets.
 *
 * Technical: {@link PlayerRuntime#personalityForEval} with three traits, its write awaited: it completes, one revision
 * on (profile and personality); an ordinary trait change after it ({@link PlayerRuntime#socialSetting}) is saved too;
 * after a restart on the same database the profile read back has all four traits at the runtime's revision.
 */
public final class NativeEvalPresetsGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "evalPresets", tickLimit = 900)
    public void anEvaluationRunsPresetTraitsAreSavedAndKeptAndLaterChangesStillSave(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 800);
        var server = context.getWorld().getServer();
        UUID[] id = new UUID[1];
        SocialProfile[] profiles = new SocialProfile[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED, "Ada established");
                    id[0] = statuses.getFirst().id();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.profileWriteForTest(id[0]).isDone(), "Her profile saved as made"))
                .createAndAdd(() -> {
                    profiles[0] = f.runtime.profileForTest(id[0]);
                    f.runtime.personalityForEval(id[0], Map.of(Personality.Trait.INITIATIVE, 80, Personality.Trait.CAUTION, 20,
                            Personality.Trait.SOCIABILITY, 70));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.profileWriteForTest(id[0]).isDone(), "The presets' write done"))
                .createAndAdd(() -> {
                    var write = f.runtime.profileWriteForTest(id[0]);
                    context.assertTrue(!write.isCompletedExceptionally(), "The presets saved: " + failure(write));
                    var now = f.runtime.profileForTest(id[0]);
                    context.assertTrue(now.revision() == profiles[0].revision() + 1
                                    && now.personality().revision() == profiles[0].personality().revision() + 1,
                            "One revision on: " + profiles[0].revision() + " to " + now.revision());
                    var p = now.personality();
                    context.assertTrue(p.initiative() == 80 && p.caution() == 20 && p.sociability() == 70, "The presets in effect: " + p);
                    // An ordinary change after them, as an administrator makes one.
                    context.assertTrue(f.runtime.socialSetting(f.source, id[0], null, false, Personality.Trait.COMBATIVENESS, 60) == 1, "A later change asked");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.profileWriteForTest(id[0]).isDone(), "The later change's write done"))
                .createAndAdd(() -> {
                    var write = f.runtime.profileWriteForTest(id[0]);
                    context.assertTrue(!write.isCompletedExceptionally(), "The later change saved too: " + failure(write));
                    profiles[1] = f.runtime.profileForTest(id[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                    f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer);
                    f.runtime.start();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> {
                    var read = f.runtime.profileForTest(id[0]);
                    var p = read.personality();
                    context.assertTrue(p.initiative() == 80 && p.caution() == 20 && p.sociability() == 70 && p.combativeness() == 60,
                            "Kept across the restart: " + p);
                    context.assertTrue(read.revision() == profiles[1].revision(), "At the runtime's revision: " + read.revision() + " and " + profiles[1].revision());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static String failure(java.util.concurrent.CompletableFuture<?> write) {
        try {
            write.join();
            return "none";
        } catch (RuntimeException e) {
            return String.valueOf(e.getCause() == null ? e : e.getCause());
        }
    }
}
