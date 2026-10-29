package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.ConversationAudience;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.model.Strategist;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The player's voice (E-20a): spoken to, a native player has the model phrase its reply from what it knows that this
 * listener may know, through the social lane's own checks, and says its fixed reply whenever that fails. A stub stands
 * in for the model: it records each conversation it is given and answers as the fixture sets, as a provider would
 * before its own checks, so the refusals seen here are the runtime's. The owner is a mock player who created the
 * player (its controller, and an operator so that it may create one); the others are mock players who did not. Since
 * F-18a its controller is talked with in private; the fixtures with others in them turn public chat on, and each turns
 * the player's ambient remarks off so that only replies are heard.
 */
public final class NativeVoiceGameTests implements FabricGameTest {
    /** A strategist that records each conversation and answers as set; it gives no advice and answers no question. */
    static final class Stub implements Strategist {
        final List<Conversation> asked = new ArrayList<>();
        Function<Conversation, CompletableFuture<Line>> answer = conversation -> says("Hello.");

        static CompletableFuture<Line> says(String text) {
            return CompletableFuture.completedFuture(new Line(Optional.of(text), "a fixture's line", 1_180, 0, 22, 5, Optional.empty()));
        }

        static CompletableFuture<Line> fails(String why) {
            return CompletableFuture.completedFuture(new Line(Optional.empty(), "", 0, 0, 0, 5, Optional.of(why)));
        }

        @Override
        public CompletableFuture<Line> converse(Conversation conversation) {
            asked.add(conversation);
            return answer.apply(conversation);
        }

        @Override
        public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
            return CompletableFuture.completedFuture(new Outcome(Optional.empty(), 0, 0, 0, 0, Optional.of("a fixture's stub gives no advice")));
        }

        @Override public String provider() { return "Fixture"; }
        @Override public String model() { return "stub"; }
        @Override public String pace() { return "at once"; }
        @Override public void close() { }
    }

    /** A line the social lane said: to whom, what, and when (milliseconds, as the lane's clock). */
    record Heard(UUID to, String text, long at) { }

    /** The paid-skills fixture with an owner who is a mock player, the stub given and the lane's lines heard. */
    static final class Talk {
        final NativePaidSkillsGameTests.Fixture f;
        final Stub stub = new Stub();
        final List<Heard> heard = new ArrayList<>();
        final List<ServerPlayerEntity> others = new ArrayList<>();
        ServerPlayerEntity owner;

        Talk(TestContext context, boolean edits) {
            f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, edits);
        }

        static long now() { return System.nanoTime() / 1_000_000; }

        /**
         * Waits {@code millis} of real time on the server thread. The social lane keeps real time (a player's three
         * seconds between direct replies, the typing delay) while the test server runs up to ten times faster, so a
         * gap in real time is waited out here rather than counted in ticks.
         */
        static void waitFor(long millis) {
            long until = System.nanoTime() + millis * 1_000_000;
            long left;
            while ((left = until - System.nanoTime()) > 0) java.util.concurrent.locks.LockSupport.parkNanos(left);
        }

        /** A mock player at {@code at} in the test's own coordinates, facing {@code yaw}. */
        ServerPlayerEntity player(TestContext context, Vec3d at, float yaw) {
            var player = context.createMockCreativeServerPlayerInWorld();
            Vec3d stand = context.getAbsolute(at);
            player.refreshPositionAndAngles(stand.x, stand.y, stand.z, yaw, 0);
            player.setHeadYaw(yaw);
            others.add(player);
            return player;
        }

        /** The owner, three blocks south of where the player stands, an operator so that it may create one. */
        void createOwned(TestContext context) {
            owner = player(context, new Vec3d(3.5, 2, 6.5), 180);
            var server = context.getWorld().getServer();
            server.getPlayerManager().getOpList().add(new OperatorEntry(owner.getGameProfile(), 4, false));
            ServerCommandSource as = owner.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(3.5, 2, 3.5)));
            f.runtime.createNative(as, "Echo");
        }

        private boolean publicSet, quietSet;

        /**
         * Public chat on, then ambient remarks off, each once (a profile change waits for the last to be saved, so this
         * step is retried until both are): open to talk, and only replies said.
         */
        void open(TestContext context) {
            if (!publicSet) {
                context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on");
                publicSet = true;
            }
            quiet(context);
        }

        /** Ambient remarks off alone, public chat left as it is. */
        void quiet(TestContext context) {
            if (!quietSet) {
                context.assertTrue(f.runtime.socialSetting(f.source, f.id, "ambient", false, null, 0) == 1, "Ambient remarks off");
                quietSet = true;
            }
        }

        void listen() {
            f.runtime.strategistForFixtures(stub);
            f.runtime.heardForFixtures((player, text) -> heard.add(new Heard(player.getUuid(), text.getString(), now())));
        }

        List<Heard> heardBy(ServerPlayerEntity player) {
            return heard.stream().filter(h -> h.to().equals(player.getUuid())).toList();
        }

        void cleanup(TestContext context) {
            var manager = context.getWorld().getServer().getPlayerManager();
            if (owner != null) manager.getOpList().remove(owner.getGameProfile());
            for (var player : others) manager.remove(player);
            f.runtime.stop();
        }
    }

    private static boolean idle(NativePaidSkillsGameTests.Fixture f) {
        String phase = f.runtime.nativeActivityStatus(Actor.console(), f.id).phase();
        return phase.equals("IDLE") || phase.equals("UNLOADED");
    }

    /**
     * The owner asks Echo, at work on a scoped gather of three logs, what it is doing. The model is told the task and
     * who is asking; its line comes to the owner after at least the typing delay and within the chat lane's deadline, as
     * the lane writes it; a player forty blocks off hears nothing; and the gather goes on as the same task.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void theOwnerAsksWhatItIsDoingAndHearsTheModelsLineInTime(TestContext context) {
        var t = new Talk(context, true);
        var f = t.f;
        BlockPos base = context.getAbsolutePos(new BlockPos(6, 2, 6));
        ServerPlayerEntity[] far = {null};
        UUID[] task = {null};
        long[] asked = {0};
        String line = "Chopping logs for a table.";
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
                    t.stub.answer = conversation -> Stub.says(line);
                    far[0] = t.player(context, new Vec3d(43.5, 2, 3.5), 270);
                    context.assertTrue(f.runtime.nativeGather(f.source, f.id, 3, 0) == 1, "Gather of three logs started");
                })
                .createAndAdd(() -> {
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).work().startsWith("Running native_gather") && action != null,
                            "At work on the gather: " + f.runtime.nativeActivityStatus(Actor.console(), f.id).work());
                    task[0] = action.taskId();
                })
                .createAndAdd(() -> {
                    asked[0] = Talk.now();
                    f.runtime.chat(t.owner.getUuid(), "Echo, what are you doing?");
                })
                .createAndAdd(() -> context.assertTrue(!t.heardBy(t.owner).isEmpty(), "The owner heard a reply"))
                .createAndAdd(() -> {
                    context.assertTrue(t.stub.asked.size() == 1, "One conversation: " + t.stub.asked.size());
                    var conversation = t.stub.asked.getFirst();
                    context.assertTrue(conversation.text().contains("What you are doing: gathering logs"), "Told the task: " + conversation.text());
                    context.assertTrue(conversation.text().contains("(your owner, who may give you commands)"), "Told who asks: " + conversation.text());
                    context.assertTrue(conversation.text().contains("Your position: ") && conversation.mayGivePlaces(), "The owner may know where it is");
                    var reply = t.heardBy(t.owner).getFirst();
                    context.assertTrue(reply.text().equals("[Kindred] Echo: " + line), "The model's line, as the lane writes it: " + reply.text());
                    long took = reply.at() - asked[0];
                    context.assertTrue(took >= SpeechTiming.delayMillis(line) - 5 && took <= Strategist.CHAT_DEADLINE.toMillis(),
                            "After the typing delay (" + SpeechTiming.delayMillis(line) + " ms), within the deadline: " + took + " ms");
                    context.assertTrue(t.heardBy(far[0]).isEmpty(), "Nothing to a player forty blocks off: " + t.heard);
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(f.runtime.nativeActivityStatus(Actor.console(), f.id).work().startsWith("Running native_gather")
                            && action != null && action.taskId().equals(task[0]), "The same gather goes on: " + (action == null ? null : action.taskId()));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A bystander who is not its owner speaks while Echo talks only with its owner: no reply, and the model is not asked.
     * With public chat on, the bystander is answered, and the model was told neither where Echo is nor what it carries.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void aBystanderIsAnsweredOnlyInPublicAndNeverToldWhereOrWhat(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] bystander = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.quiet(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> Stub.says("Just keeping busy out here.");
                    bystander[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> {
                    f.runtime.chat(bystander[0].getUuid(), "Echo, what are you doing?");
                    Talk.waitFor(3_000);
                })
                .createAndAdd(() -> {
                    context.assertTrue(t.heardBy(bystander[0]).isEmpty(), "No reply to a bystander in private: " + t.heard);
                    context.assertTrue(t.stub.asked.isEmpty(), "And no request: " + t.stub.asked.size());
                    context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on");
                })
                .createAndAdd(() -> f.runtime.chat(bystander[0].getUuid(), "Echo, what are you doing?"))
                .createAndAdd(() -> context.assertTrue(!t.heardBy(bystander[0]).isEmpty(), "Answered in public"))
                .createAndAdd(() -> {
                    context.assertTrue(t.stub.asked.size() == 1, "One conversation");
                    var conversation = t.stub.asked.getFirst();
                    String told = conversation.text();
                    context.assertTrue(told.contains("(another player, who may not give you commands)"), "Told who asks: " + told);
                    context.assertTrue(!told.contains("Your position") && !ConversationAudience.givesPlace(told) && !conversation.mayGivePlaces(),
                            "Not told where it is: " + told);
                    context.assertTrue(!told.contains("What you carry:") && told.contains("What you carry, roughly:"), "Not told what it carries: " + told);
                    context.assertTrue(t.heardBy(bystander[0]).getFirst().text().equals("[Kindred] Echo: Just keeping busy out here."),
                            "The line said: " + t.heardBy(bystander[0]));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * An opponent in a running duel asks Echo where it is: the model is told neither its position nor its goal, answers
     * with coordinates anyway, and the line is refused; the opponent hears the fixed reply instead.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void anOpponentsAskForCoordinatesIsRefusedAndTheFixedReplySaid(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] rival = {null};
        UUID[] match = {null};
        var server = context.getWorld().getServer();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> Stub.says("I'm at 120, 64, -35, come find me.");
                    rival[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.proposeDuel(f.source, f.nativeId, rival[0].getUuid()) == 1, "Duel proposed");
                })
                .createAndAdd(() -> {
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.joinMatch(rival[0].getCommandSource(), match[0]) == 1, "The rival joins for itself");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == dev.kindred.core.match.MatchRegistry.State.RUNNING, "Running");
                    // Echo keeps still for this fixture: a stopped player still answers.
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Echo held");
                })
                .createAndAdd(() -> f.runtime.chat(rival[0].getUuid(), "Echo, where are you?"))
                .createAndAdd(() -> context.assertTrue(!t.heardBy(rival[0]).isEmpty(), "The rival heard a reply"))
                .createAndAdd(() -> {
                    context.assertTrue(t.stub.asked.size() == 1, "One conversation");
                    var conversation = t.stub.asked.getFirst();
                    String told = conversation.text();
                    context.assertTrue(told.contains("(your opponent in a match, who may not give you commands)"), "Told who asks: " + told);
                    context.assertTrue(!told.contains("Your position") && !told.contains("Your goal") && !ConversationAudience.givesPlace(told),
                            "Neither position nor goal: " + told);
                    var said = t.heardBy(rival[0]);
                    context.assertTrue(said.size() == 1 && said.getFirst().text().equals("[Kindred] Echo: I can chat about what's nearby. Try hello, how are you, "
                            + "what are you doing, weather, or what do you see."), "The fixed reply, not the coordinates: " + said);
                    t.cleanup(context);
                    server.setPvpEnabled(false);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The model gives no line (a failure, as a timeout or an outage would): the owner hears the fixed reply. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void noLineFromTheModelMeansTheFixedReply(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> Stub.fails("service error 503");
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, what are you doing?"))
                .createAndAdd(() -> context.assertTrue(!t.heardBy(t.owner).isEmpty(), "The owner heard a reply"))
                .createAndAdd(() -> {
                    context.assertTrue(t.stub.asked.size() == 1, "The model was asked");
                    var said = t.heardBy(t.owner);
                    context.assertTrue(said.size() == 1 && said.getFirst().text().equals("[Kindred] Echo: I'm watching what's nearby."),
                            "The fixed reply: " + said);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Two messages while a line is being phrased: the second replaces the first in waiting. When the line comes, it is
     * said, then the waiting message is asked and answered: two replies to the owner, in order, and the replaced one is
     * never asked of the model. The bystander it replaced is not left unanswered: it is given the fixed reply as it is
     * replaced (E-20c). Each message comes past the player's three seconds between direct replies.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void messagesDuringAFlightWaitTheNewestInPlaceOfTheRest(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] bystander = {null};
        var held = new CompletableFuture<Strategist.Line>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    // The first line is held until the fixture lets it go; the rest come at once.
                    t.stub.answer = conversation -> t.stub.asked.size() == 1 ? held : Stub.says("And now I'm answering your second question.");
                    bystander[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> {
                    f.runtime.chat(t.owner.getUuid(), "Echo, what are you doing?");
                    Talk.waitFor(3_100); // Past the player's three seconds between direct replies.
                })
                .createAndAdd(() -> {
                    f.runtime.chat(bystander[0].getUuid(), "Echo, nice day?");
                    Talk.waitFor(3_100);
                })
                .createAndAdd(() -> {
                    context.assertTrue(t.heard.isEmpty() && t.stub.asked.size() == 1, "Nothing said yet, one conversation out: " + t.heard);
                    f.runtime.chat(t.owner.getUuid(), "Echo, and after that?");
                })
                .createAndAdd(() -> held.complete(new Strategist.Line(Optional.of("Waiting on you, mostly."), "a fixture's line", 1_180, 0, 22, 6_500,
                        Optional.empty())))
                .createAndAdd(() -> context.assertTrue(t.heardBy(t.owner).size() == 2, "Two replies to the owner: " + t.heard))
                .createAndAdd(() -> {
                    var said = t.heardBy(t.owner);
                    context.assertTrue(said.get(0).text().equals("[Kindred] Echo: Waiting on you, mostly.")
                            && said.get(1).text().equals("[Kindred] Echo: And now I'm answering your second question."), "In order: " + said);
                    var replaced = t.heardBy(bystander[0]);
                    context.assertTrue(replaced.size() == 1 && replaced.getFirst().text().equals("[Kindred] Echo: I can chat about what's nearby. "
                            + "Try hello, how are you, what are you doing, weather, or what do you see."), "The replaced speaker given the fixed reply: " + t.heard);
                    context.assertTrue(t.stub.asked.size() == 2 && t.stub.asked.get(1).text().endsWith("\"test-mock-player: and after that?\""),
                            "The newest asked next: " + t.stub.asked.size());
                    context.assertTrue(t.stub.asked.stream().noneMatch(c -> c.text().contains("nice day")), "The replaced message never asked");
                    context.assertTrue(t.stub.asked.get(1).text().contains("Earlier in this talk: test-mock-player: what are you doing?"),
                            "The talk so far goes with it: " + t.stub.asked.get(1).text());
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A stopped Echo still answers its owner, is told it is stopped, and nothing starts. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void aStoppedPlayerAnswersAndNothingStarts(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    t.stub.answer = conversation -> Stub.says("I'm stopped for now, so I'm staying put.");
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Stopped"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Stop latched"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, come on, let's go mining"))
                .createAndAdd(() -> context.assertTrue(!t.heardBy(t.owner).isEmpty(), "The owner heard a reply"))
                .createAndAdd(() -> Talk.waitFor(2_000))
                .createAndAdd(() -> {
                    var conversation = t.stub.asked.getFirst();
                    context.assertTrue(conversation.text().contains("You are stopped"), "Told it is stopped: " + conversation.text());
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: I'm stopped for now, so I'm staying put."),
                            "The line said: " + t.heard);
                    context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().stopped(), "Still stopped");
                    context.assertTrue(idle(f), "Nothing started: " + f.runtime.nativeActivityStatus(Actor.console(), f.id).phase());
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action == null || action.state().terminal(), "No action under way: " + (action == null ? null : action.state()));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Talking to your own player in private (F-18a): with public chat off and no model at all, the player who created
     * Echo (its controller) is answered with the fixed reply; a bystander who may not command it is not, and nobody
     * else hears either. Before F-18a a native player's own record was its only "owner", so even its creator went
     * unanswered until public chat was turned on.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void itsControllerIsAnsweredWithPublicChatOff(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] bystander = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.quiet(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    // No strategist: only what the lane says is heard.
                    f.runtime.heardForFixtures((player, text) -> t.heard.add(new Heard(player.getUuid(), text.getString(), Talk.now())));
                    bystander[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> {
                    f.runtime.chat(bystander[0].getUuid(), "Echo, what are you doing?");
                    Talk.waitFor(3_100); // Past the player's three seconds between direct replies.
                })
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, what are you doing?"))
                .createAndAdd(() -> context.assertTrue(!t.heardBy(t.owner).isEmpty(), "Its controller is answered in private: " + t.heard))
                .createAndAdd(() -> {
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: I'm watching what's nearby."), "The fixed reply: " + t.heard);
                    context.assertTrue(t.heardBy(bystander[0]).isEmpty(), "Nothing to a bystander in private: " + t.heard);
                    context.assertTrue(t.heard.size() == 1, "Said to no one else: " + t.heard);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Its own remarks in private (F-18a): with public chat off and its ambient remarks on, what the player says of itself
     * unasked (here, greeting whoever it notices) reaches its controller, and not a bystander beside them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 2400)
    public void itsControllerHearsItsRemarksWithPublicChatOff(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] bystander = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.heardForFixtures((player, text) -> t.heard.add(new Heard(player.getUuid(), text.getString(), Talk.now())));
                    bystander[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    Talk.waitFor(20); // Real time for the lane's own clock.
                    context.assertTrue(!t.heardBy(t.owner).isEmpty(), "A remark to its controller: " + t.heard);
                })
                .createAndAdd(() -> Talk.waitFor(1_000))
                .createAndAdd(() -> {
                    context.assertTrue(t.heardBy(bystander[0]).isEmpty(), "Nothing to a bystander in private: " + t.heard);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** With the voice switched off ({@code KINDRED_VOICE=off}), the model is never asked and the fixed reply is said. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "voice", tickLimit = 1200)
    public void withTheVoiceOffTheModelIsNeverAsked(TestContext context) {
        var t = new Talk(context, false);
        var f = t.f;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> t.open(context))
                .createAndAdd(() -> {
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    t.listen();
                    f.runtime.voiceForFixtures(false);
                    t.stub.answer = conversation -> Stub.says("This is never said.");
                })
                .createAndAdd(() -> context.assertTrue(idle(f), "Settled"))
                .createAndAdd(() -> f.runtime.chat(t.owner.getUuid(), "Echo, what are you doing?"))
                .createAndAdd(() -> context.assertTrue(!t.heardBy(t.owner).isEmpty(), "The owner heard a reply"))
                .createAndAdd(() -> {
                    context.assertTrue(t.stub.asked.isEmpty(), "The model was not asked");
                    context.assertTrue(t.heardBy(t.owner).getFirst().text().equals("[Kindred] Echo: I'm watching what's nearby."), "The fixed reply: " + t.heard);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
