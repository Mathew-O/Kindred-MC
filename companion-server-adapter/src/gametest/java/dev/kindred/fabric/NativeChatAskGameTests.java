package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * In plain terms: a GameTest: in a real test world, people ask an AI player things in chat with no model to help, and it
 * does them exactly as the commands would, answering aloud. Its owner sets it a goal, a way of playing and working alone;
 * someone else's race only happens once that person says "I'm in" and the player itself says yes (and not while it is
 * hungry); a build goes up where its owner stands; someone who does not control it is told no in its own words, and
 * nothing of theirs moves; "let's race" is asked back once and then dropped; and an operator's switch asked by someone who
 * is not one is answered with the command an operator would use.
 *
 * Technical: E-52 through the whole runtime, with no strategist: the local grammar reads each ask and the runtime method
 * its command calls runs it, as the speaker, with the speaker's own command source. The owner is a mock player who created
 * Echo and an operator; the bystander is a mock player who did not, talking with Echo once its owner turns public chat on.
 * One fixture stages Echo's hunger (said where it is done) to see it say no to a race. Each turns Echo's ambient remarks
 * off, as the voice fixtures do, so that only replies are heard.
 */
public final class NativeChatAskGameTests implements FabricGameTest {
    /** The Talk fixture without a model: lines heard by everyone, Echo made by its owner, public chat on when asked. */
    private static final class Ask {
        final NativeVoiceGameTests.Talk t;
        ServerPlayerEntity bystander;

        Ask(TestContext context, boolean edits) {
            t = new NativeVoiceGameTests.Talk(context, edits);
        }

        void hear() {
            t.f.runtime.heardForFixtures((player, text) -> t.heard.add(new NativeVoiceGameTests.Heard(player.getUuid(), text.getString(),
                    NativeVoiceGameTests.Talk.now())));
        }

        /** Public chat on and ambient remarks off (each profile change waits for the last to be saved: retried until both are). */
        void open(TestContext context) {
            t.open(context);
            if (bystander == null) bystander = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
        }

        /** The last thing Echo said to {@code player}, or empty. */
        Optional<String> lastTo(ServerPlayerEntity player) {
            var lines = t.heardBy(player).stream().map(NativeVoiceGameTests.Heard::text).filter(h -> h.startsWith("[Kindred] Echo: ")).toList();
            return lines.isEmpty() ? Optional.empty() : Optional.of(lines.getLast().substring("[Kindred] Echo: ".length()));
        }

        PlayerRuntime.PanelBot echo() {
            return t.f.runtime.panelBots(Actor.console()).getFirst();
        }
    }

    /** Runs {@code say} once, then waits (retried) until Echo has said something new to {@code to}, which {@code check} looks at. */
    private static Runnable says(Ask a, ServerPlayerEntity[] to, String message, boolean[] sent, int heardBefore[], Consumer<String> check) {
        return () -> {
            if (!sent[0]) {
                NativeVoiceGameTests.Talk.waitFor(3_100); // The lane's three seconds between one speaker's replies.
                heardBefore[0] = a.t.heardBy(to[0]).size();
                a.t.f.runtime.chat(to[0].getUuid(), message);
                sent[0] = true;
            }
            var lines = a.t.heardBy(to[0]).stream().skip(heardBefore[0]).map(NativeVoiceGameTests.Heard::text)
                    .filter(h -> h.startsWith("[Kindred] Echo: ")).toList();
            if (lines.isEmpty()) throw new net.minecraft.test.GameTestException("Waiting for Echo's answer to \"" + message + "\"");
            check.accept(lines.getLast().substring("[Kindred] Echo: ".length()));
        };
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1200)
    public void aGoalByChatStartsAndIsAnswered(TestContext context) {
        var a = new Ask(context, true);
        var f = a.t.f;
        ServerPlayerEntity[] owner = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> a.t.quiet(context))
                .createAndAdd(() -> {
                    a.hear();
                    owner[0] = a.t.owner;
                    context.assertTrue(a.echo().goal() == SurvivalGoal.NONE, "No goal yet");
                })
                .createAndAdd(says(a, owner, "Echo, go for the Nether!", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("On it: build a nether portal."), "Answered: " + said);
                    context.assertTrue(a.echo().goal() == SurvivalGoal.NETHER_PORTAL, "The goal started: " + a.echo().goal());
                    context.assertTrue(f.runtime.chatAskedForFixtures(f.id).startsWith("GOAL: done"), f.runtime.chatAskedForFixtures(f.id));
                }))
                .createAndAdd(() -> a.t.cleanup(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1200)
    public void aModeAndATeamChangeByChat(TestContext context) {
        var a = new Ask(context, false);
        var f = a.t.f;
        ServerPlayerEntity[] owner = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> a.t.quiet(context))
                .createAndAdd(() -> {
                    a.hear();
                    owner[0] = a.t.owner;
                    context.assertTrue(!a.echo().playstyle().equals("SPEEDRUN_ANY_PERCENT") && a.echo().collaboration().equals("COOP_TEAM"),
                            "Before: " + a.echo().playstyle() + ", " + a.echo().collaboration());
                })
                .createAndAdd(says(a, owner, "Echo, speed it up", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("Speedrunning, then."), "Answered: " + said);
                    context.assertTrue(a.echo().playstyle().equals("SPEEDRUN_ANY_PERCENT"), "The mode: " + a.echo().playstyle());
                }))
                .createAndAdd(says(a, owner, "Echo, go solo", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("I'll go it alone."), "Answered: " + said);
                    context.assertTrue(a.echo().collaboration().equals("SOLO_INDEPENDENT"), "The team: " + a.echo().collaboration());
                }))
                .createAndAdd(says(a, owner, "Echo, go solo", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("I'll go it alone."), "Asked again: " + said);
                    context.assertTrue(a.echo().collaboration().equals("SOLO_INDEPENDENT"), "Not toggled back: " + a.echo().collaboration());
                }))
                .createAndAdd(() -> a.t.cleanup(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Staged: Echo's hunger set to 5, then back to 20. A bystander, who does not control Echo, asks "Echo, race me": while
     * Echo is hungry it says no by its own rule and nothing is proposed; fed, it says yes, and the race is proposed with
     * Echo in it and the bystander invited but not in. Only the bystander's own "I'm in" puts them in, and the race starts.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1600)
    public void aRaceByChatRunsOnlyAfterTheirImInAndTheAisYes(TestContext context) {
        var a = new Ask(context, true);
        var f = a.t.f;
        ServerPlayerEntity[] bystander = {null};
        MatchRegistry.Match[] race = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.setDragonFight(f.source, true) == 1, "The operator allows the dragon fight"))
                .createAndAdd(() -> {
                    a.open(context);
                    a.hear();
                    bystander[0] = a.bystander;
                    f.player().getHungerManager().setFoodLevel(5); // Staged: hungry.
                })
                .createAndAdd(says(a, bystander, "Echo, race me", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("Not now: I'm hungry."), "Its own no: " + said);
                    context.assertTrue(f.runtime.matchRegistry().list().isEmpty(), "Nothing proposed");
                }))
                .createAndAdd(() -> f.player().getHungerManager().setFoodLevel(20)) // Staged: fed.
                .createAndAdd(says(a, bystander, "Echo, race me", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.startsWith("You're on."), "Its own yes: " + said);
                    var matches = f.runtime.matchRegistry().list();
                    context.assertTrue(matches.size() == 1 && matches.getFirst().mode() == MatchRegistry.Mode.SPEEDRUN, "A race proposed: " + matches);
                    race[0] = matches.getFirst();
                    context.assertTrue(race[0].joined().contains(f.player().getUuid()), "Echo in by its own yes");
                    context.assertTrue(race[0].invited().contains(bystander[0].getUuid()) && !race[0].joined().contains(bystander[0].getUuid()),
                            "The bystander invited, not in");
                    context.assertTrue(race[0].state() == MatchRegistry.State.LOBBY, "Not started: " + race[0].state());
                }))
                .createAndAdd(says(a, bystander, "Echo, I'm in", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("You're in."), "Answered: " + said);
                    var now = f.runtime.matchRegistry().get(race[0].id()).orElseThrow();
                    context.assertTrue(now.joined().contains(bystander[0].getUuid()), "In by their own word");
                    context.assertTrue(now.state() != MatchRegistry.State.LOBBY, "Started once everyone was in: " + now.state());
                }))
                .createAndAdd(() -> {
                    f.runtime.stopMatch(f.source, race[0].id());
                    a.t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1200)
    public void aBuildByChatGoesUpWhereItsOwnerStands(TestContext context) {
        var a = new Ask(context, true);
        var f = a.t.f;
        ServerPlayerEntity[] owner = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> a.t.quiet(context))
                .createAndAdd(() -> {
                    a.hear();
                    owner[0] = a.t.owner;
                })
                .createAndAdd(says(a, owner, "Echo, build a hut here", new boolean[1], new int[1], said -> {
                    context.assertTrue(said.equals("Building a hut here."), "Answered: " + said);
                    var at = owner[0].getBlockPos();
                    String how = f.runtime.chatAskedForFixtures(f.id);
                    context.assertTrue(how.startsWith("BUILD: done") && how.contains(at.getX() + ", " + at.getY() + ", " + at.getZ()),
                            "Built where its owner stands (" + at.toShortString() + "): " + how);
                }))
                .createAndAdd(() -> a.t.cleanup(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A bystander, who does not control Echo, asks it to go for the Nether, build a hut where they stand and wear the
     * knight skin, and to change how it plays: each is refused aloud in Echo's own words, and nothing moves: no goal, no
     * build, the same skin, mode and team.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1600)
    public void aNonControllersAsksAreRefusedAloudAndNothingMoves(TestContext context) {
        var a = new Ask(context, true);
        var f = a.t.f;
        ServerPlayerEntity[] bystander = {null};
        PlayerRuntime.PanelBot[] before = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    a.open(context);
                    a.hear();
                    bystander[0] = a.bystander;
                    before[0] = a.echo();
                })
                .createAndAdd(says(a, bystander, "Echo, go for the Nether", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals("Only an operator or whoever looks after me can ask me that."), "Refused aloud: " + said)))
                .createAndAdd(says(a, bystander, "Echo, build a hut here", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals("Only an operator or whoever looks after me can ask me that."), "Refused aloud: " + said)))
                .createAndAdd(says(a, bystander, "Echo, speed it up", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals("Only an operator or whoever looks after me may change my settings."), "Refused aloud: " + said)))
                .createAndAdd(says(a, bystander, "Echo, wear the knight skin", new boolean[1], new int[1], said ->
                        context.assertTrue(!said.equals("How do I look?"), "Refused aloud: " + said)))
                .createAndAdd(() -> {
                    var now = a.echo();
                    context.assertTrue(now.goal() == SurvivalGoal.NONE, "No goal: " + now.goal());
                    context.assertTrue(now.playstyle().equals(before[0].playstyle()) && now.collaboration().equals(before[0].collaboration()),
                            "The same mode and team: " + now.playstyle() + ", " + now.collaboration());
                    context.assertTrue(java.util.Objects.equals(now.skin(), before[0].skin()), "The same skin: " + now.skin());
                    context.assertTrue(!f.runtime.nativeActivityStatus(Actor.console(), f.id).work().contains("build"),
                            "No build: " + f.runtime.nativeActivityStatus(Actor.console(), f.id).work());
                    context.assertTrue(f.runtime.chatAskedForFixtures(f.id).startsWith("SKIN: refused"), f.runtime.chatAskedForFixtures(f.id));
                    a.t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** "Let's race" names nobody: asked back once; an answer that names nobody drops it, and the next "me" is ordinary talk. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1600)
    public void anAmbiguousMatchAskIsAskedBackOnceThenDropped(TestContext context) {
        var a = new Ask(context, true);
        var f = a.t.f;
        ServerPlayerEntity[] owner = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> a.t.quiet(context))
                .createAndAdd(() -> {
                    a.hear();
                    owner[0] = a.t.owner;
                })
                .createAndAdd(says(a, owner, "Echo, let's race", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals("Race whom?"), "Asked back: " + said)))
                .createAndAdd(says(a, owner, "Echo, nice weather today", new boolean[1], new int[1], said -> {
                    context.assertTrue(!said.equals("Race whom?") && !said.startsWith("You're on"), "Not asked again, no race: " + said);
                    context.assertTrue(f.runtime.matchRegistry().list().isEmpty(), "Nothing proposed");
                }))
                .createAndAdd(says(a, owner, "Echo, me", new boolean[1], new int[1], said -> {
                    context.assertTrue(!said.startsWith("You're on"), "Dropped: 'me' is no answer now: " + said);
                    context.assertTrue(f.runtime.matchRegistry().list().isEmpty(), "Still nothing proposed");
                }))
                .createAndAdd(() -> a.t.cleanup(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A bystander who is no operator asks Echo to allow the dragon fight: told, in character, the command an operator would use; it stays off. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1200)
    public void anOperatorOnlyAskFromANonOperatorIsRefusedInCharacter(TestContext context) {
        var a = new Ask(context, true);
        var f = a.t.f;
        ServerPlayerEntity[] bystander = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    a.open(context);
                    a.hear();
                    bystander[0] = a.bystander;
                    context.assertTrue(!bystander[0].hasPermissionLevel(2), "Not an operator");
                })
                .createAndAdd(says(a, bystander, "Echo, allow the dragon fight", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals("That's not mine to change; an operator can, with /aiplayer dragonfight on."), "In character: " + said)))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.startGoal(a.t.owner.getCommandSource(), f.id, SurvivalGoal.DRAGON) == 0, "The dragon fight is still off");
                    a.t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * With world edits off, its owner's own asks that the commands refuse: a goal, a build where they stand, a cow ranch, a
     * field. Each is refused aloud with the command's reason, and nothing moves.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "chatAsks", tickLimit = 1600)
    public void nothingMovesOnAnAskTheCommandWouldRefuse(TestContext context) {
        var a = new Ask(context, false);
        var f = a.t.f;
        ServerPlayerEntity[] owner = {null};
        String off = "AI world edits are disabled on this server; an operator must enable them first.";
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> a.t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> a.t.quiet(context))
                .createAndAdd(() -> {
                    a.hear();
                    owner[0] = a.t.owner;
                })
                .createAndAdd(says(a, owner, "Echo, go for the Nether", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals(off), "The command's reason: " + said)))
                .createAndAdd(says(a, owner, "Echo, build a hut here", new boolean[1], new int[1], said ->
                        context.assertTrue(said.equals(off), "The command's reason: " + said)))
                .createAndAdd(says(a, owner, "Echo, make a cow ranch here", new boolean[1], new int[1], said ->
                        context.assertTrue(!said.startsWith("A cow ranch"), "Refused: " + said)))
                .createAndAdd(says(a, owner, "Echo, plant a field here", new boolean[1], new int[1], said ->
                        context.assertTrue(!said.startsWith("I'll plant"), "Refused: " + said)))
                .createAndAdd(() -> {
                    context.assertTrue(a.echo().goal() == SurvivalGoal.NONE, "No goal");
                    String work = f.runtime.nativeActivityStatus(Actor.console(), f.id).work();
                    context.assertTrue(!work.contains("build") && !work.contains("ranch") && !work.contains("field"), "Nothing started: " + work);
                    a.t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
