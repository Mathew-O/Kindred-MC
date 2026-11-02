package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import dev.kindred.core.Actor;
import dev.kindred.core.CanonicalRoster;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.social.ChatStyle;
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

/**
 * In plain terms: a GameTest: in a real test world, the rough stuff (E-18b). A rough player's swear word is heard only by
 * someone who said yes to swearing, softened for everyone else, and by everyone who did not say no once the operator says
 * yes for the server; a dark joke and flirting reach only people who said yes to them, and the strictest person in earshot
 * decides for a line they both hear; each player types in its own chat style (Sol like a gamer, Echo properly), which its
 * controller can change; and the chat style, the rough preset and a person's yes to swearing, set by the real commands,
 * are still in force after the server restarts, down to what the voice is told and how its line is typed.
 *
 * Technical: E-18b. The first three drive the social lane ({@link CompanionSocials}) with its own clock, as
 * {@link NativeExpressionGameTests} does, on the older mob-style body (the lane is the same for both bodies). The fourth runs
 * the whole runtime: the commands, the metadata store, a stop and a fresh start on the same save, and the voice (a stub
 * stands in for the model, as in {@link NativeVoiceGameTests}).
 */
public final class NativeRoughTalkGameTests implements FabricGameTest {
    private static final SocialSnapshot RAIN = new SocialSnapshot(true, true, false, false, null, List.of());

    /**
     * Sol, rough and typing properly, notices the rain: "Rain again? Damn it." Yes, who said yes to swearing, hears that;
     * Jo, who said nothing, hears "Dang it."; and once the operator says yes for the server, Jo hears it too, but No, who
     * said no, still does not. Under a server cap of playful, nobody hears a rough line.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rough")
    public void aSwearWordIsHeardOnlyByWhoSaidYesAndSoftenedForEveryoneElse(TestContext context) {
        var jo = player(context, "Jo", new Vec3d(1.5, 2, 1));
        var yes = player(context, "Yes", new Vec3d(2.5, 2, 1));
        var no = player(context, "No", new Vec3d(0.5, 2, 1));
        var body = body(context, new Vec3d(1.5, 2, 2.5));
        var sol = participant(body, "Sol", SocialProfile.defaults(body.companionId()).withPublic(true).withExpression(Expression.ROUGH)
                .withChatStyle(ChatStyle.PROPER));
        var saidYes = ListenerPreferences.defaults(yes.getUuid()).withSwearing(ListenerPreferences.Opt.ON);
        var saidNo = ListenerPreferences.defaults(no.getUuid()).withSwearing(ListenerPreferences.Opt.OFF);

        Map<String, String> heard = new HashMap<>();
        // Each heard alone, so that the strictest of them does not decide for the others.
        for (var person : List.of(jo, yes, no)) {
            var own = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> heard.put(player.getName().getString(), text.getString()));
            own.preferencesChanged(saidYes);
            own.preferencesChanged(saidNo);
            own.react(sol, RAIN, List.of(person), 0);
            own.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        }
        context.assertEquals("[Kindred] Sol: Rain again? Damn it.", heard.get("Yes"), "Yes said yes to swearing");
        context.assertEquals("[Kindred] Sol: Rain again? Dang it.", heard.get("Jo"), "Jo said nothing: softened");
        context.assertEquals("[Kindred] Sol: Rain again? Dang it.", heard.get("No"), "No said no: softened");

        // The operator says yes for everyone who said nothing.
        heard.clear();
        for (var person : List.of(jo, no)) {
            var server = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> heard.put(player.getName().getString(), text.getString()));
            server.setRoughAudience(true);
            server.preferencesChanged(saidNo);
            server.react(sol, RAIN, List.of(person), 0);
            server.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        }
        context.assertEquals("[Kindred] Sol: Rain again? Damn it.", heard.get("Jo"), "Jo, with the server's yes");
        context.assertEquals("[Kindred] Sol: Rain again? Dang it.", heard.get("No"), "No's own no beats the server's yes");

        // A server capped at playful: the playful line, to Yes as well.
        heard.clear();
        var capped = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> heard.put(player.getName().getString(), text.getString()));
        capped.setExpressionCap(Expression.PLAYFUL);
        capped.preferencesChanged(saidYes);
        capped.react(sol, RAIN, List.of(yes), 0);
        capped.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals("[Kindred] Sol: Rain again. I seem to have forgotten my umbrella.", heard.get("Yes"), "Under a cap of playful");
        context.complete();
    }

    /**
     * Kindred, flirty, meets Echo: Yes (who said yes to flirting) hears it flirt, Jo hears a plain hello, and with both in
     * earshot both hear the plain one. Sol, rough, is hurt by a zombie after a line about the rain: Dark (yes to dark jokes)
     * hears the dark joke, Jo the plain line.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rough")
    public void darkJokesAndFlirtingReachOnlyWhoSaidYesAndTheStrictestInEarshotDecides(TestContext context) {
        var jo = player(context, "Jo", new Vec3d(1.5, 2, 1));
        var yes = player(context, "Yes", new Vec3d(2.5, 2, 1));
        var dark = player(context, "Dark", new Vec3d(0.5, 2, 1));
        var kindredBody = body(context, new Vec3d(0.5, 2, 2.5));
        var echoBody = body(context, new Vec3d(2.5, 2, 2.5));
        var kindred = participant(kindredBody, "Kindred", SocialProfile.defaults(kindredBody.companionId()).withPersonality(CanonicalRoster.KINDRED.defaultPersonality())
                .withPublic(true).withExpression(Expression.PLAYFUL).withChatStyle(ChatStyle.PROPER));
        var echo = participant(echoBody, "Echo", SocialProfile.defaults(echoBody.companionId()).withPublic(true));
        Map<UUID, CompanionSocials.Participant> players = new HashMap<>(Map.of(kindred.id(), kindred, echo.id(), echo));
        var flirting = ListenerPreferences.defaults(yes.getUuid()).withFlirting(ListenerPreferences.Opt.ON);
        var darkJokes = ListenerPreferences.defaults(dark.getUuid()).withDarkHumor(ListenerPreferences.Opt.ON);
        var seesEcho = new SocialSnapshot(true, false, false, false, null,
                List.of(new SocialSnapshot.Contact(echo.id(), echo.name(), SocialSnapshot.Kind.COMPANION)));
        String plainHello = "[Kindred] Kindred: Hi, Echo. Good to see another companion around.";

        for (var who : List.of(List.of(yes), List.of(jo), List.of(yes, jo))) {
            List<String> heard = new ArrayList<>();
            var lane = new CompanionSocials(players::get, () -> 0, (player, text) -> heard.add(player.getName().getString() + " <- " + text.getString()));
            lane.preferencesChanged(flirting);
            lane.react(kindred, seesEcho, who, 0);
            lane.deliverDue(kindred.id(), SpeechTiming.MAX_MILLIS);
            List<String> first = heard.stream().filter(h -> h.contains("Kindred: ")).toList();
            context.assertEquals(who.size(), first.size(), "One hello to each: " + heard);
            for (String line : first) {
                boolean flirted = line.endsWith("Looking good in that armor, not gonna lie.");
                boolean expected = who.size() == 1 && who.getFirst() == yes;
                context.assertTrue(flirted == expected, (expected ? "Flirts with Yes alone: " : "The plain hello: ") + heard);
                if (!flirted) context.assertTrue(line.endsWith(plainHello), "Plain: " + line);
            }
        }

        var solBody = body(context, new Vec3d(1.5, 2, 2.5));
        var sol = participant(solBody, "Sol", SocialProfile.defaults(solBody.companionId()).withPublic(true).withExpression(Expression.ROUGH)
                .withChatStyle(ChatStyle.PROPER));
        var bitten = new SocialSnapshot(true, false, false, true, null, List.of(), new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.MOB, "zombie"));
        Map<String, String> heard = new HashMap<>();
        for (var person : List.of(dark, jo)) {
            var lane = new CompanionSocials(ignored -> sol, () -> 0, (player, text) -> heard.put(player.getName().getString(), text.getString()));
            lane.preferencesChanged(darkJokes);
            lane.react(sol, RAIN, List.of(person), 0); // One line said first, so that the next is the dark one.
            lane.deliverDue(sol.id(), SpeechTiming.MAX_MILLIS);
            lane.react(sol, bitten, List.of(person), 400_000);
            lane.deliverDue(sol.id(), 400_000 + SpeechTiming.MAX_MILLIS);
        }
        context.assertEquals("[Kindred] Sol: That zombie almost sent me back to spawn lol.", heard.get("Dark"), "Dark said yes to dark jokes");
        context.assertEquals("[Kindred] Sol: That zombie hit me. Careful.", heard.get("Jo"), "Jo hears it plainly");
        context.complete();
    }

    /**
     * Sol (by its personality) types like a gamer: lower case, no punctuation; Echo types properly; and Sol set to proper
     * types as Echo does.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rough")
    public void eachPlayerTypesInItsOwnChatStyle(TestContext context) {
        var jo = player(context, "Jo", new Vec3d(1.5, 2, 1));
        var solBody = body(context, new Vec3d(0.5, 2, 2.5));
        var echoBody = body(context, new Vec3d(2.5, 2, 2.5));
        Personality solPersonality = CanonicalRoster.SOL.defaultPersonality();
        var sol = participant(solBody, "Sol", SocialProfile.defaults(solBody.companionId()).withPersonality(solPersonality).withPublic(true));
        var echo = participant(echoBody, "Echo", SocialProfile.defaults(echoBody.companionId()).withPersonality(CanonicalRoster.ECHO.defaultPersonality())
                .withPublic(true));
        context.assertEquals(ChatStyle.GAMER, sol.profile().effectiveChatStyle(), "Sol types like a gamer");
        context.assertEquals(Expression.ROUGH, sol.profile().effectiveExpression(), "Sol sounds rough by its personality");

        for (var who : List.of(sol, echo, participant(solBody, "Sol", sol.profile().withChatStyle(ChatStyle.PROPER)))) {
            List<String> heard = new ArrayList<>();
            var lane = new CompanionSocials(ignored -> who, () -> 0, (player, text) -> heard.add(text.getString()));
            lane.react(who, RAIN, List.of(jo), 0);
            lane.deliverDue(who.id(), SpeechTiming.MAX_MILLIS);
            context.assertEquals(1, heard.size(), "One line: " + heard);
            String said = heard.getFirst().substring(("[Kindred] " + who.name() + ": ").length());
            if (who.profile().effectiveChatStyle() == ChatStyle.GAMER) {
                context.assertTrue(said.equals(said.toLowerCase()) && said.chars().noneMatch(c -> ".,?!'".indexOf(c) >= 0),
                        "Lower case, no punctuation: " + said);
                context.assertTrue(said.startsWith("rain again dang it"), "Sol's rough line, softened for Jo, as a gamer types it: " + said);
            } else {
                context.assertTrue(Character.isUpperCase(said.charAt(0)) && (said.endsWith(".") || said.endsWith("!")), "Typed properly: " + said);
            }
        }
        context.complete();
    }

    /**
     * Echo's controller sets it rough and typing like a gamer; a person, Yes, says yes to swearing, by the real commands.
     * After a restart on the same save all three hold: the settings read back, the voice is told it may swear with Yes and
     * to type like a Minecraft player, and its line reaches Yes typed that way.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rough", tickLimit = 1600)
    public void aChatStyleARoughPresetAndAPersonsYesSurviveARestart(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] person = {null};
        List<String> toOwner = new ArrayList<>(), toYes = new ArrayList<>();
        ServerCommandSource[] owner = {null}, yes = {null};
        boolean[] done = new boolean[4];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    if (!done[0]) {
                        person[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                        owner[0] = t.owner.getCommandSource().withOutput(capture(toOwner));
                        yes[0] = person[0].getCommandSource().withOutput(capture(toYes));
                        context.assertTrue(f.runtime.chatStyle(yes[0], f.id, ChatStyle.GAMER, true) == 0, "A person who does not control Echo may not set how it types");
                        context.assertTrue(f.runtime.chatStyle(owner[0], f.id, ChatStyle.GAMER, true) == 1, "Its controller may");
                        done[0] = true;
                    }
                    context.assertTrue(toOwner.stream().anyMatch(line -> line.startsWith("Saved.") && line.contains("It types gamer (set)")), "Saved: " + toOwner);
                })
                .createAndAdd(() -> {
                    if (!done[1]) {
                        context.assertTrue(f.runtime.expression(owner[0], f.id, Expression.ROUGH, true) == 1, "Rough, by its controller");
                        done[1] = true;
                    }
                    context.assertTrue(toOwner.stream().anyMatch(line -> line.startsWith("Saved. Echo sounds rough: its preset.")), "Saved: " + toOwner);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on"))
                .createAndAdd(() -> {
                    if (!done[2]) {
                        context.assertTrue(f.runtime.listenerPreference(f.source, "swearing", "on") == 0, "The console says yes for nobody");
                        context.assertTrue(f.runtime.listenerPreference(yes[0], "swearing", "on") == 1, "Yes says yes for themselves");
                        done[2] = true;
                    }
                    context.assertTrue(toYes.stream().anyMatch(line -> line.startsWith("Saved:") && line.contains("; swearing")), "Saved: " + toYes);
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
                    toYes.clear();
                    context.assertTrue(f.runtime.chatStyle(owner[0], f.id, null, false) == 1, "Asked how Echo types");
                    context.assertTrue(f.runtime.listenerPreference(yes[0], null, null) == 1, "Yes's preferences have been read");
                    context.assertTrue(toOwner.stream().anyMatch(line -> line.startsWith("Echo sounds rough: its preset.") && line.contains("It types gamer (set)")),
                            "Both after the restart: " + toOwner);
                    context.assertTrue(toYes.stream().anyMatch(line -> line.contains("; swearing")), "Yes's yes after the restart: " + toYes);
                })
                .createAndAdd(() -> {
                    if (!done[3]) {
                        f.runtime.nativeLifecycle(f.source, "resume", f.id);
                        t.listen();
                        t.stub.answer = conversation -> NativeVoiceGameTests.Stub.says("Hell yeah, I'm just mining.");
                        f.runtime.chat(person[0].getUuid(), "Echo, hi");
                        done[3] = true;
                    }
                    context.assertTrue(t.heardBy(person[0]).stream().anyMatch(h -> h.text().startsWith("[Kindred] Echo: hell yeah im just mining")),
                            "Yes answered, typed like a gamer, the swear word kept: " + t.heard);
                })
                .createAndAdd(() -> {
                    context.assertEquals(1, t.stub.asked.size(), "One conversation");
                    var asked = t.stub.asked.getFirst();
                    context.assertTrue(asked.maySwear(), "Its reply may swear with Yes");
                    context.assertTrue(asked.text().contains("casual swearing is fine") && asked.text().contains("Type like a Minecraft player"),
                            "The voice is told: " + asked.text());
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
