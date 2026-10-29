package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.model.Strategist;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asks in chat (E-20b): one who may already command a native player asks it to come, wait, stop or toss them
 * something, and the model's reply carries the ask. The reply is said first; then the ask goes in through the method the
 * matching `/aiplayer native` command calls, with the speaker's own authority, so it is checked as the command is. A
 * refusal there is said in the command's own words. Anyone else's ask refuses the reply, and the fixed reply is said.
 * The stub stands in for the model as in {@link NativeVoiceGameTests}; nothing is given to the player, and the one log
 * it tosses it gathers itself.
 */
public final class NativeAskGameTests implements FabricGameTest {
    private static CompletableFuture<Strategist.Line> says(String text, Strategist.Ask ask, String item) {
        return CompletableFuture.completedFuture(new Strategist.Line(Optional.of(text), ask, Optional.ofNullable(item), "a fixture's line",
                1_180, 0, 22, 5, Optional.empty()));
    }

    private static String work(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.nativeActivityStatus(Actor.console(), f.id).work();
    }

    private static boolean idle(NativePaidSkillsGameTests.Fixture f) {
        String phase = f.runtime.nativeActivityStatus(Actor.console(), f.id).phase();
        return phase.equals("IDLE") || phase.equals("UNLOADED");
    }

    /** The owner, three blocks off, asks Echo to come: "On my way." is said, then the walk starts and ends beside the owner. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "ask", tickLimit = 1200)
    public void comeFromTheOwnerIsSaidThenWalked(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        int[] heardAt = {-1}, walkAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> says("On my way.", Strategist.Ask.COME, null);
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, come here please"))
                .createAndAdd(() -> {
                    int now = context.getWorld().getServer().getTicks();
                    if (heardAt[0] < 0 && !t.heardBy(t.owner).isEmpty()) heardAt[0] = now;
                    if (walkAt[0] < 0 && work(f).contains("native_goto")) walkAt[0] = now;
                    context.assertTrue(heardAt[0] >= 0 && walkAt[0] >= 0, "Said and walked: " + work(f));
                })
                .createAndAdd(() -> context.assertTrue(f.player().squaredDistanceTo(t.owner) <= 2.6 * 2.6 && work(f).startsWith("Completed native_goto"),
                        "Beside the owner: " + Math.sqrt(f.player().squaredDistanceTo(t.owner)) + ", " + work(f)))
                .createAndAdd(() -> {
                    context.assertTrue(t.stub.asked.getFirst().mayAsk(), "The owner may ask");
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: On my way."), "The line: " + t.heard);
                    context.assertTrue(heardAt[0] <= walkAt[0], "Said first: heard at " + heardAt[0] + ", walking at " + walkAt[0]);
                    context.assertTrue("COME: done".equals(f.runtime.voiceAskedForFixtures(f.id)), "Through the goto: " + f.runtime.voiceAskedForFixtures(f.id));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The owner asks Echo to wait: its own wait of four seconds starts, after its look at the speaker. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "ask", tickLimit = 1200)
    public void waitFromTheOwnerStartsItsWait(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> says("Sure, I'll wait here.", Strategist.Ask.WAIT, null);
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, wait there a moment"))
                .createAndAdd(() -> context.assertTrue("WAIT: done".equals(f.runtime.voiceAskedForFixtures(f.id)), "Asked: " + f.runtime.voiceAskedForFixtures(f.id)))
                .createAndAdd(() -> {
                    context.assertTrue(work(f).startsWith("Running native_wait") || work(f).startsWith("Requested native_wait")
                            || f.runtime.nativeActivityStatus(Actor.console(), f.id).phase().equals("ACTIVE"), "Its wait under way: " + work(f));
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: Sure, I'll wait here."), "The line: " + t.heard);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** At work on a gather, Echo is asked by its owner to stop: the gather is revoked as the stop goes in, and it stays stopped. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "ask", tickLimit = 1200)
    public void stopFromTheOwnerRevokesTheGatherAtOnce(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, true);
        var f = t.f;
        BlockPos base = context.getAbsolutePos(new BlockPos(6, 2, 6));
        String[] atOnce = {"", ""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    for (int y = 0; y < 3; y++) context.getWorld().setBlockState(base.up(y), Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, base, base.up(2), "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> says("Stopping.", Strategist.Ask.STOP, null);
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 3, 0) == 1, "Gather of three logs started");
                })
                .createAndAdd(() -> context.assertTrue(work(f).startsWith("Running native_gather"), "At work on the gather: " + work(f)))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, stop"))
                .createAndAdd(() -> {
                    context.assertTrue("STOP: done".equals(f.runtime.voiceAskedForFixtures(f.id)), "Asked: " + f.runtime.voiceAskedForFixtures(f.id));
                    // Taken once, at the first tick the stop is seen to have gone in: no gather running, the stop latched.
                    atOnce[0] = work(f);
                    atOnce[1] = String.valueOf(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped());
                })
                .createAndAdd(() -> context.assertTrue(!atOnce[0].startsWith("Running native_gather") && atOnce[1].equals("true"),
                        "Revoked as the stop went in: " + atOnce[0] + ", stopped " + atOnce[1]))
                .createAndAdd(() -> NativeVoiceGameTests.Talk.waitFor(1_000))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped() && !work(f).startsWith("Running"),
                            "Still stopped, nothing running: " + work(f));
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: Stopping."), "The line: " + t.heard);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Echo gathers a log by hand, then its owner asks for it: it is tossed to the owner. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "ask", tickLimit = 1600)
    public void tossFromTheOwnerHandsOverWhatItGathered(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, true);
        var f = t.f;
        BlockPos base = context.getAbsolutePos(new BlockPos(6, 2, 6));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(base, Blocks.OAK_LOG.getDefaultState());
                    f.runtime.nativePatch(f.source, f.id, base, base, "minecraft:oak_log");
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(Actor.console(), f.id).pending(), "Patch acknowledged"))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> says("Here, take it.", Strategist.Ask.TOSS, "minecraft:oak_log");
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 1, 0) == 1, "Gather of a log started");
                })
                .createAndAdd(() -> context.assertTrue(work(f).startsWith("Completed native_gather")
                        && ItemMoves.count(f.player().getInventory(), Items.OAK_LOG) == 1, "One log gathered: " + work(f)))
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, toss me that log"))
                .createAndAdd(() -> context.assertTrue("TOSS: done".equals(f.runtime.voiceAskedForFixtures(f.id)), "Asked: " + f.runtime.voiceAskedForFixtures(f.id)))
                .createAndAdd(() -> {
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.OAK_LOG) == 0 && work(f).startsWith("Completed native_toss"),
                            "Tossed: " + work(f));
                    boolean held = ItemMoves.count(t.owner.getInventory(), Items.OAK_LOG) == 1;
                    boolean lying = !context.getWorld().getEntitiesByClass(ItemEntity.class, f.player().getBoundingBox().expand(6),
                            item -> item.getStack().isOf(Items.OAK_LOG)).isEmpty();
                    context.assertTrue(held || lying, "The log with the owner or on its way to it");
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: Here, take it."), "The line: " + t.heard);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A bystander's ask, which the stub carries anyway, refuses the reply: the fixed reply is said, and nothing is done. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "ask", tickLimit = 1200)
    public void aBystandersAskIsRefusedAndNothingIsDone(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] bystander = {null};
        Vec3d[] at = {null};
        boolean[] walked = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> says("On my way.", Strategist.Ask.COME, null);
                    bystander[0] = t.player(context, new Vec3d(6.5, 2, 6.5), 135);
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> {
                    at[0] = f.player().getPos();
                    f.runtime.chat(bystander[0].getUuid(), "Echo, come here");
                })
                .createAndAdd(() -> context.assertTrue(!t.heardBy(bystander[0]).isEmpty(), "Answered"))
                .createAndAdd(() -> {
                    if (work(f).contains("native_goto")) walked[0] = true;
                    NativeVoiceGameTests.Talk.waitFor(50);
                    context.assertTrue(t.heard.stream().anyMatch(h -> h.at() + 2_000 <= NativeVoiceGameTests.Talk.now()), "Two seconds after the reply");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!t.stub.asked.getFirst().mayAsk(), "A bystander may not ask");
                    var said = t.heardBy(bystander[0]);
                    context.assertTrue(said.size() == 1 && said.getFirst().text().equals(
                            "[Kindred] Echo: Authorized players can use /aiplayer for controls. Conversation doesn't change my tasks."), "The fixed reply: " + said);
                    context.assertTrue(f.runtime.voiceAskedForFixtures(f.id) == null && !walked[0], "Nothing asked of it: " + f.runtime.voiceAskedForFixtures(f.id));
                    context.assertTrue(f.player().getPos().squaredDistanceTo(at[0]) < 0.25, "It stayed where it stood");
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The owner asks for a diamond Echo does not have: the reply is said, then the toss command's own refusal. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "ask", tickLimit = 1200)
    public void aRefusedAskIsAnsweredInTheCommandsOwnWords(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> says("Here you go.", Strategist.Ask.TOSS, "minecraft:diamond");
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, toss me a diamond"))
                .createAndAdd(() -> context.assertTrue(t.heardBy(t.owner).size() == 2, "Two lines: " + t.heard))
                .createAndAdd(() -> {
                    var said = t.heardBy(t.owner);
                    context.assertTrue(said.get(0).text().equals("[Kindred] Echo: Here you go.")
                            && said.get(1).text().equals("[Kindred] Echo: Echo has no diamond."), "The reply, then the refusal: " + said);
                    context.assertTrue("TOSS: refused".equals(f.runtime.voiceAskedForFixtures(f.id)), "Refused: " + f.runtime.voiceAskedForFixtures(f.id));
                    context.assertTrue(!work(f).contains("native_toss"), "No toss: " + work(f));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
