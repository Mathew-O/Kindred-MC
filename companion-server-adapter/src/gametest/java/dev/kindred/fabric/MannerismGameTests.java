package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.AttentionPolicy;
import dev.kindred.core.MannerismState;
import dev.kindred.core.Mannerisms;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialProfile;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Native contracts for habits: command grammar, world-probed hard bounds and stop fencing. These do not
 * establish the visual quality of sprint-jumping or a live attention pause; see E-32/E-33 for that evidence.
 */
public final class MannerismGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void quirkCommandsParseEveryHabitAndSettingAndRejectUnknowns(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();
        for (Mannerisms.Quirk quirk : Mannerisms.Quirk.values()) {
            for (Mannerisms.Setting setting : Mannerisms.Setting.values()) {
                String command = "companion quirk " + quirk.commandName() + " " + setting.name().toLowerCase();
                var parsed = dispatcher.parse(command, source);
                context.assertFalse(parsed.getReader().canRead(), "Quirk grammar must parse: " + command);
                context.assertTrue(parsed.getExceptions().isEmpty(), "Quirk grammar must accept: " + command);
                var withId = dispatcher.parse(command + " " + UUID.randomUUID(), source);
                context.assertFalse(withId.getReader().canRead(), "Quirk grammar must accept a trailing UUID");
            }
            var invalid = dispatcher.parse("companion quirk " + quirk.commandName() + " sometimes", source);
            context.assertTrue(invalid.getReader().canRead() || !invalid.getExceptions().isEmpty(),
                    "Only auto, on and off are valid quirk settings");
        }
        var unknown = dispatcher.parse("companion quirk moonwalk on", source);
        context.assertTrue(unknown.getReader().canRead() || !unknown.getExceptions().isEmpty(), "Unknown quirks must be rejected");
        context.assertFalse(dispatcher.parse("companion quirks", source).getReader().canRead(), "Habit inspection must parse");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void sprintJumpIsRefusedUnderLowCeilingsAndWithoutALease(TestContext context) {
        CompanionEntity body = body(context, UUID.randomUUID(), new Vec3d(1.5, 2, 1.5));
        context.setBlockState(1, 1, 1, Blocks.STONE);
        for (int y = 2; y <= 5; y++) context.setBlockState(1, y, 1, Blocks.AIR);
        body.setOnGround(true);
        Personality bouncy = Personality.defaults().withTrait(Personality.Trait.INITIATIVE, 100).withTrait(Personality.Trait.HUMOR, 100);
        MannerismState state = new MannerismState(Mannerisms.defaults(body.companionId()).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON));
        var open = FollowBehavior.movementContext(body, true);
        context.assertFalse(open.lowClearance(), "Open sky above must not read as a low ceiling");
        context.assertTrue(state.gait(bouncy, 0, open) == MannerismState.Gait.SPRINT_JUMP, "Open ground under a lease permits sprint-jumping");
        context.setBlockState(1, 4, 1, Blocks.STONE); // Two blocks above the feet: no headroom for a jump.
        var low = FollowBehavior.movementContext(body, true);
        context.assertTrue(low.lowClearance(), "A ceiling two blocks up must be detected");
        context.assertTrue(state.gait(bouncy, 0, low) == MannerismState.Gait.SPRINT, "A low ceiling falls back to sprinting");
        context.assertTrue(state.gait(bouncy, 0, FollowBehavior.movementContext(body, false)) == MannerismState.Gait.WALK,
                "Without a movement lease no habit is performed at all");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void hardStopClearsSprintAndJumpRequests(TestContext context) {
        CompanionEntity body = body(context, UUID.randomUUID(), new Vec3d(1.5, 2, 1.5));
        body.setSprinting(true);
        body.getJumpControl().setActive();
        context.assertTrue(body.jumpRequested(), "Fixture must queue a jump first");
        body.haltControls();
        context.assertFalse(body.isSprinting(), "Stop must clear the sprint flag");
        context.assertFalse(body.jumpRequested(), "Stop must revoke a queued jump");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void habitFeedbackIsAnsweredWithoutStartingWorkOrChangingSettings(TestContext context) {
        var owner = player(context, "Owner", new Vec3d(1, 2, 1));
        var body = body(context, owner.getUuid(), new Vec3d(1.5, 2, 2.5));
        var profile = SocialProfile.defaults(body.companionId()).withTrait(Personality.Trait.HELPFULNESS, 90);
        MannerismState state = new MannerismState(Mannerisms.defaults(body.companionId()).with(Mannerisms.Quirk.SPRINTJUMP, Mannerisms.Setting.ON));
        List<String> messages = new ArrayList<>();
        CompanionSocials.Habits habits = new CompanionSocials.Habits() {
            @Override public Optional<String> feedback(ServerPlayerEntity speaker, CompanionSocials.Participant companion, String message, long now) {
                var quirk = dev.kindred.core.QuirkFeedback.detect(message);
                if (quirk.isEmpty()) return Optional.empty();
                boolean active = state.active(profile.personality(), now).contains(quirk.orElseThrow());
                var response = dev.kindred.core.QuirkFeedback.respond(quirk.orElseThrow(), profile.personality(),
                        dev.kindred.core.QuirkFeedback.Speaker.OWNER, active);
                if (response.decision() == dev.kindred.core.QuirkFeedback.Decision.ACCOMMODATE) {
                    state.accommodate(quirk.orElseThrow(), now, response.accommodationMillis());
                }
                return Optional.of(response.reply());
            }
            @Override public AttentionPolicy.Phase phase(UUID companionId) { return null; }
            @Override public boolean gazeOwned(UUID companionId) { return true; }
            @Override public Set<Mannerisms.Quirk> activeQuirks(UUID companionId, long now) { return Set.of(); }
            @Override public void glance(UUID companionId, UUID subject, long durationMillis, long now) { }
        };
        var participant = new CompanionSocials.Participant(body.companionId(), body.ownerId(), "Companion", body, profile, false);
        CompanionSocials socials = new CompanionSocials(ignored -> participant, () -> 1_000, (player, text) -> messages.add(text.getString()), habits);
        context.assertTrue(socials.reply(owner, participant, "sprint jumping wastes hunger"), "Habit feedback is ordinary direct chat");
        socials.deliverDue(participant.id(), 1_000 + dev.kindred.core.SpeechTiming.MAX_MILLIS);
        context.assertTrue(messages.getFirst().toLowerCase().contains("walk"), "An agreeable companion says it will walk for a while");
        context.assertFalse(state.active(profile.personality(), 1_000).contains(Mannerisms.Quirk.SPRINTJUMP), "A bounded accommodation is in effect");
        context.assertTrue(state.profile().setting(Mannerisms.Quirk.SPRINTJUMP) == Mannerisms.Setting.ON, "Chat never edits the saved setting");
        context.assertTrue(body.getNavigation().isIdle() && !body.isSprinting(), "Feedback must not start movement");
        context.complete();
    }

    private static CompanionEntity body(TestContext context, UUID owner, Vec3d relative) {
        CompanionEntity body = KindredMod.COMPANION.create(context.getWorld());
        if (body == null) throw new IllegalStateException("Missing companion factory");
        body.bind(body.getUuid(), owner);
        Vec3d position = context.getAbsolute(relative);
        body.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return body;
    }

    private static ServerPlayerEntity player(TestContext context, String name, Vec3d relative) {
        var player = new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(),
                new GameProfile(UUID.randomUUID(), name), SyncedClientOptions.createDefault());
        Vec3d position = context.getAbsolute(relative);
        player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return player;
    }
}
