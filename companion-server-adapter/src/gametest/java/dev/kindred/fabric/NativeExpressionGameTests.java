package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import dev.kindred.core.Actor;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In plain terms: a GameTest: in a real test world, how an AI player sounds and how a person has asked to be spoken to.
 * A playful player's line to someone who asked for no jokes is the plain one, while the same line to someone who asked
 * for nothing is the playful one; a line heard by two people is put as the stricter of them asked, and a teasing hello
 * between two AI players is said plainly when someone in earshot asked for no teasing; someone who asked for fewer lines
 * is spoken to unasked half as often; and a player's preset and a person's preferences, set by the real commands, are
 * still in force after the server restarts, down to the line the voice is told.
 *
 * Technical: E-18a and E-19a. The first two drive the social lane ({@link CompanionSocials}) with its own clock, as
 * {@link SocialGameTests} does, on the older mob-style body (the lane is the same for both bodies); preferences are set
 * through the method the command calls. The third runs the whole runtime: the commands, the metadata store, a stop and a
 * fresh start on the same save, and the voice (a stub stands in for the model, as in {@link NativeVoiceGameTests}).
 */
public final class NativeExpressionGameTests implements FabricGameTest {
    private static final SocialSnapshot RAIN = new SocialSnapshot(true, true, false, false, null, List.of());
    private static final SocialSnapshot NIGHT = new SocialSnapshot(true, false, true, false, null, List.of());

    /**
     * Sol, playful, notices the rain. Jo asked for nothing and hears the joke; Quiet asked for no jokes and hears the plain
     * line, and the plain fixed answer to a hello, and the voice is told so. Under a server cap of plain, nobody hears the
     * joke at all.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "expression")
    public void aPlayfulLineToSomeoneWhoAskedForNoJokesIsThePlainOne(TestContext context) {
        var jo = player(context, "Jo", new Vec3d(1.5, 2, 1));
        var quiet = player(context, "Quiet", new Vec3d(2.5, 2, 1));
        var body = body(context, new Vec3d(1.5, 2, 2.5));
        var sol = participant(body, "Sol", SocialProfile.defaults(body.companionId()).withPublic(true).withExpression(Expression.PLAYFUL));

        // Control: nobody asked for anything.
        List<String> toJo = new ArrayList<>();
        var lane = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> toJo.add(text.getString()));
        lane.react(sol, RAIN, List.of(jo), 0);
        lane.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals(List.of("[Kindred] Sol: Rain again. I seem to have forgotten my umbrella."), toJo, "The playful line to Jo");
        context.assertTrue(lane.reply(jo, sol, "hi"), "Jo is answered");
        lane.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals("[Kindred] Sol: Hey! Always room for another square head around here.", toJo.getLast(), "The playful hello to Jo");
        context.assertTrue(lane.manner(sol, jo).contains("playful: a light joke"), "The voice may joke with Jo: " + lane.manner(sol, jo));

        // Quiet asked for no jokes: the same player, at the same moment, says the plain line.
        List<String> toQuiet = new ArrayList<>();
        var asked = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> toQuiet.add(text.getString()));
        asked.preferencesChanged(ListenerPreferences.defaults(quiet.getUuid()).withNoJokes(true));
        asked.react(sol, RAIN, List.of(quiet), 0);
        asked.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals(List.of("[Kindred] Sol: It's raining here."), toQuiet, "The plain line to Quiet");
        context.assertTrue(asked.reply(quiet, sol, "hi"), "Quiet is answered");
        asked.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals("[Kindred] Sol: Hello! Nice to have some company.", toQuiet.getLast(), "The plain hello to Quiet");
        context.assertEquals("How you speak to them: plainly, without jokes (they asked for none); clean words only.",
                asked.manner(sol, quiet), "The voice is told");

        // The server's cap bounds every preset: under plain, Jo hears the plain line too.
        List<String> capped = new ArrayList<>();
        var plainServer = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> capped.add(text.getString()));
        plainServer.setExpressionCap(Expression.PLAIN);
        plainServer.react(sol, RAIN, List.of(jo), 0);
        plainServer.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals(List.of("[Kindred] Sol: It's raining here."), capped, "Under a cap of plain");
        context.complete();
    }

    /**
     * A line Jo and Quiet both hear is the plain one, for both. Sol's playful hello to Echo teases when Jo alone hears it,
     * and is the plain hello when Kit, who asked for no teasing, hears it too. And Quiet, who asked for fewer lines, is not
     * spoken to again 31 seconds later, while Jo is.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "expression")
    public void aLineHeardByTwoTakesTheStricterPreference(TestContext context) {
        var jo = player(context, "Jo", new Vec3d(1.5, 2, 1));
        var quiet = player(context, "Quiet", new Vec3d(2.5, 2, 1));
        var kit = player(context, "Kit", new Vec3d(0.5, 2, 1));
        var solBody = body(context, new Vec3d(0.5, 2, 2.5));
        var echoBody = body(context, new Vec3d(2.5, 2, 2.5));
        var sol = participant(solBody, "Sol", SocialProfile.defaults(solBody.companionId()).withPublic(true).withExpression(Expression.PLAYFUL));
        var echo = participant(echoBody, "Echo", SocialProfile.defaults(echoBody.companionId()).withPublic(true));
        Map<UUID, CompanionSocials.Participant> players = new HashMap<>(Map.of(sol.id(), sol, echo.id(), echo));
        var noJokes = ListenerPreferences.defaults(quiet.getUuid()).withNoJokes(true).withUnasked(ListenerPreferences.Unasked.FEWER);
        var noTeasing = ListenerPreferences.defaults(kit.getUuid()).withNoTeasing(true);

        // Two hearers, one who asked for no jokes: the plain line to both.
        List<String> heard = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        var lane = new CompanionSocials(players::get, now::get, (player, text) -> heard.add(player.getName().getString() + " <- " + text.getString()));
        lane.preferencesChanged(noJokes);
        lane.react(sol, RAIN, List.of(jo, quiet), 0);
        lane.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals(List.of("Jo <- [Kindred] Sol: It's raining here.", "Quiet <- [Kindred] Sol: It's raining here."), heard,
                "The stricter preference for both");

        // Echo notices nightfall 31 seconds on: Jo's usual pause is over, Quiet's (twice as long) is not.
        heard.clear();
        now.set(31_000);
        lane.react(echo, NIGHT, List.of(jo, quiet), 31_000);
        lane.deliverDue(echo.id(), 31_000 + SpeechTiming.MAX_MILLIS);
        context.assertEquals(1, heard.size(), "One line, to Jo alone: " + heard);
        context.assertTrue(heard.getFirst().startsWith("Jo <- [Kindred] Echo: "), "To Jo: " + heard);

        // A playful hello between two AI players teases, heard by Jo alone.
        var seesEcho = new SocialSnapshot(true, false, false, false, null,
                List.of(new SocialSnapshot.Contact(echo.id(), echo.name(), SocialSnapshot.Kind.COMPANION)));
        List<String> toJo = new ArrayList<>();
        var alone = new CompanionSocials(players::get, () -> 0, (player, text) -> toJo.add(text.getString()));
        alone.react(sol, seesEcho, List.of(jo), 0);
        alone.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        alone.deliverDue(echo.id(), SpeechTiming.MAX_MILLIS * 2);
        context.assertEquals(List.of("[Kindred] Sol: Hey, Echo. Still here? I thought you'd have the Nether sorted by now.",
                "[Kindred] Echo: Hi, Sol. Good to see you too."), toJo, "The tease, heard by Jo alone");

        // With Kit in earshot, who asked for no teasing: the plain hello, to both.
        List<String> both = new ArrayList<>();
        var withKit = new CompanionSocials(players::get, () -> 0, (player, text) -> both.add(player.getName().getString() + " <- " + text.getString()));
        withKit.preferencesChanged(noTeasing);
        withKit.react(sol, seesEcho, List.of(jo, kit), 0);
        withKit.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        var hello = both.stream().filter(line -> line.contains("Sol: ")).toList();
        context.assertEquals(List.of("Jo <- [Kindred] Sol: Hi, Echo. Good to see another companion around.",
                "Kit <- [Kindred] Sol: Hi, Echo. Good to see another companion around."), hello, "No teasing for either");
        context.complete();
    }

    /**
     * Echo's controller sets it playful, and a person, Quiet, sets no jokes and fewer lines for themselves, all by the real
     * commands; Quiet may not set how Echo sounds, and the console may not set a person's preferences. After a stop and a
     * fresh start on the same save, both still hold: the commands say so, and when Quiet speaks to Echo the voice is told
     * to speak plainly because they asked for no jokes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "expression", tickLimit = 1600)
    public void aPresetAndAPersonsPreferencesSurviveARestart(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] quiet = {null};
        List<String> toOwner = new ArrayList<>(), toQuiet = new ArrayList<>();
        ServerCommandSource[] owner = {null}, person = {null};
        boolean[] done = new boolean[4];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    if (!done[0]) {
                        quiet[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                        owner[0] = t.owner.getCommandSource().withOutput(capture(toOwner));
                        person[0] = quiet[0].getCommandSource().withOutput(capture(toQuiet));
                        context.assertTrue(f.runtime.expression(person[0], f.id, Expression.PLAYFUL, true) == 0, "A person who does not control Echo may not set how it sounds");
                        context.assertTrue(f.runtime.expression(owner[0], f.id, Expression.PLAYFUL, true) == 1, "Its controller may");
                        done[0] = true;
                    }
                    context.assertTrue(toOwner.contains("Saved. Echo sounds playful: its preset."), "Saved: " + toOwner);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on"))
                .createAndAdd(() -> {
                    if (!done[1]) {
                        context.assertTrue(f.runtime.listenerPreference(f.source, "jokes", "off") == 0, "The console sets nobody's preferences");
                        context.assertTrue(f.runtime.listenerPreference(person[0], "jokes", "off") == 1, "Quiet sets their own");
                        done[1] = true;
                    }
                    context.assertTrue(toQuiet.stream().anyMatch(line -> line.startsWith("Saved:") && line.endsWith("and no jokes.")), "Saved: " + toQuiet);
                })
                .createAndAdd(() -> {
                    if (!done[2]) {
                        context.assertTrue(f.runtime.listenerPreference(person[0], "unasked", "fewer") == 1, "And fewer lines");
                        done[2] = true;
                    }
                    context.assertTrue(toQuiet.stream().anyMatch(line -> line.startsWith("Saved: AI players speak to you with fewer lines")), "Saved: " + toQuiet);
                })
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> {
                    f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory);
                    f.runtime.start();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Started again on the same save"))
                .createAndAdd(() -> f.runtime.nativeLifecycle(f.source, "connect", f.id))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).getFirst().connected(), "Echo connected"))
                .createAndAdd(() -> {
                    toOwner.clear();
                    toQuiet.clear();
                    context.assertTrue(f.runtime.expression(owner[0], f.id, null, false) == 1, "Asked how Echo sounds");
                    context.assertTrue(f.runtime.listenerPreference(person[0], null, null) == 1, "Quiet's preferences have been read");
                    context.assertEquals(List.of("Echo sounds playful: its preset."), toOwner, "The preset after the restart");
                    context.assertEquals(List.of("AI players speak to you with fewer lines you did not ask for, teasing between AI players and no jokes."),
                            toQuiet, "Quiet's preferences after the restart");
                })
                .createAndAdd(() -> {
                    if (!done[3]) {
                        f.runtime.nativeLifecycle(f.source, "resume", f.id);
                        t.listen();
                        t.stub.answer = conversation -> NativeVoiceGameTests.Stub.says("Hello there.");
                        f.runtime.chat(quiet[0].getUuid(), "Echo, hi");
                        done[3] = true;
                    }
                    context.assertTrue(t.heardBy(quiet[0]).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: Hello there.")), "Quiet answered: " + t.heard);
                })
                .createAndAdd(() -> {
                    context.assertEquals(1, t.stub.asked.size(), "One conversation");
                    String told = t.stub.asked.getFirst().text();
                    context.assertTrue(told.contains("How you speak to them: plainly, without jokes (they asked for none); clean words only."),
                            "The voice is told Quiet asked for no jokes: " + told);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static CommandOutput capture(List<String> said) {
        return new CommandOutput() {
            @Override public void sendMessage(Text message) { said.add(message.getString()); }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        };
    }

    private static CompanionEntity body(TestContext context, Vec3d relative) {
        CompanionEntity body = KindredMod.COMPANION.create(context.getWorld());
        if (body == null) throw new IllegalStateException("Missing companion factory");
        body.bind(body.getUuid(), UUID.randomUUID());
        Vec3d position = context.getAbsolute(relative);
        body.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return body;
    }

    private static CompanionSocials.Participant participant(CompanionEntity body, String name, SocialProfile profile) {
        return new CompanionSocials.Participant(body.companionId(), body.ownerId(), name, body, profile, true);
    }

    private static ServerPlayerEntity player(TestContext context, String name, Vec3d relative) {
        var player = new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(),
                new GameProfile(UUID.randomUUID(), name), SyncedClientOptions.createDefault());
        Vec3d position = context.getAbsolute(relative);
        player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return player;
    }
}
