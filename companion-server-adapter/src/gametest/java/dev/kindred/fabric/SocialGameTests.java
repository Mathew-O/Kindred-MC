package dev.kindred.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.Personality;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SpeechTiming;
import dev.kindred.core.SocialSnapshot;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Native perception and output routing; these do not replace multiplayer visual/play testing. */
public final class SocialGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void personalityCommandsAcceptPreferencesAndAliasesWithBounds(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();
        for (var trait : Personality.Trait.values()) {
            for (String name : trait.commandNames()) {
                var parsed = dispatcher.parse("companion personality " + name + " 70", source);
                context.assertFalse(parsed.getReader().canRead(), "Trait name must parse: " + name);
                context.assertTrue(parsed.getExceptions().isEmpty(), "Trait grammar must accept a bounded value");
                var invalid = dispatcher.parse("companion personality " + name + " 101", source);
                context.assertTrue(invalid.getReader().canRead() || !invalid.getExceptions().isEmpty(),
                        "Trait values outside 0..100 must be rejected");
            }
        }
        context.assertFalse(dispatcher.parse("companion personality", source).getReader().canRead(),
                "Owner profile inspection must parse");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "socialThreatBatch")
    public void perceptionDoesNotSeeThreatsThroughWalls(TestContext context) {
        CompanionEntity body = body(context, UUID.randomUUID(), new Vec3d(1.5, 2, 0.5));
        var zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(1.5, 2, 2.5));
        zombie.setAiDisabled(true);
        var profile = SocialProfile.defaults(body.companionId());
        context.assertTrue(SocialPerception.observe(body, profile, true, ignored -> false).visibleThreat() != null,
                "A nearby unobstructed hostile must be observed");
        context.setBlockState(1, 2, 1, Blocks.STONE);
        context.setBlockState(1, 3, 1, Blocks.STONE);
        context.assertTrue(SocialPerception.observe(body, profile, true, ignored -> false).visibleThreat() == null,
                "The brain must not discover a hostile through an opaque wall");
        context.setBlockState(1, 2, 1, Blocks.AIR);
        context.setBlockState(1, 3, 1, Blocks.AIR);
        zombie.refreshPositionAndAngles(body.getX() + 9, body.getY(), body.getZ(), 0, 0);
        context.assertFalse(SocialPerception.visible(body, zombie), "Social perception ends at eight blocks");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void publicConversationIsOptInAndRepliesStayPrivate(TestContext context) {
        var owner = player(context, "Owner", new Vec3d(1, 2, 1));
        var visitor = player(context, "Visitor", new Vec3d(2, 2, 1));
        var body = body(context, owner.getUuid(), new Vec3d(1.5, 2, 2.5));
        var profile = SocialProfile.defaults(body.companionId());
        var stopped = participant(body, profile, false);
        List<UUID> recipients = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        CompanionSocials socials = new CompanionSocials(ignored -> stopped, now::get, (recipient, text) -> {
            recipients.add(recipient.getUuid());
            messages.add(text.getString());
        });
        context.assertFalse(socials.reply(visitor, stopped, "hello"), "Non-owner conversation must require opt-in");
        context.assertTrue(socials.reply(owner, stopped, "what are you doing"), "Owner may talk while stopped");
        context.assertTrue(messages.isEmpty(), "A reply is not heard on the same tick it is decided");
        socials.deliverDue(stopped.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals(List.of(owner.getUuid()), recipients, "Direct reply is visible only to its speaker");
        context.assertTrue(messages.getFirst().contains("staying put"), "Reply must reflect the stopped state");
        context.assertTrue(body.getNavigation().isIdle(), "Chat must not start body work");
        now.set(3_000);
        context.assertTrue(socials.reply(visitor, participant(body, profile.withPublic(true), false), "hello"),
                "Owner opt-in permits nearby visitors to talk");
        context.assertFalse(socials.reply(visitor, participant(body, profile.withPublic(true), false), "follow me"),
                "Repeated direct requests must be throttled");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void companionExchangeTerminatesAndConsumesBothBudgets(TestContext context) {
        var audience = player(context, "Audience", new Vec3d(1.5, 2, 1));
        var first = body(context, audience.getUuid(), new Vec3d(0.5, 2, 2));
        var second = body(context, UUID.randomUUID(), new Vec3d(2.5, 2, 2));
        var a = participant(first, SocialProfile.defaults(first.companionId()).withPublic(true), true);
        var b = participant(second, SocialProfile.defaults(second.companionId()).withPublic(true), true);
        Map<UUID, CompanionSocials.Participant> participants = new HashMap<>(Map.of(a.id(), a, b.id(), b));
        List<String> messages = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        CompanionSocials socials = new CompanionSocials(participants::get, now::get, (player, text) -> messages.add(text.getString()));
        var seesB = new SocialSnapshot(true, false, false, false, null,
                List.of(new SocialSnapshot.Contact(b.id(), b.name(), SocialSnapshot.Kind.COMPANION)));
        var seesA = new SocialSnapshot(true, false, false, false, null,
                List.of(new SocialSnapshot.Contact(a.id(), a.name(), SocialSnapshot.Kind.COMPANION)));
        socials.react(a, seesB, List.of(audience), 0);
        socials.deliverDue(a.id(), SpeechTiming.MAX_MILLIS);
        socials.deliverDue(b.id(), SpeechTiming.MAX_MILLIS * 2);
        context.assertEquals(2, messages.size(), "One greeting and one reply, with no open reply chain");
        socials.react(b, seesA, List.of(audience), 1_000);
        socials.react(a, seesB, List.of(audience), 120_000);
        socials.react(b, seesA, List.of(audience), 120_000);
        context.assertEquals(2, messages.size(), "Replies must not become fresh greetings or a bot loop");
        var privateB = participant(second, b.profile().withPublic(false), true);
        context.assertFalse(CompanionSocials.eligiblePartner(a, privateB), "Both owners must enable public conversation");
        context.assertFalse(CompanionSocials.eligiblePartner(a, participant(second, b.profile(), false)),
                "A stopped companion must not be recruited into an ambient exchange");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void ambientChatterIsLocalMutedAndLimitedAcrossCompanions(TestContext context) {
        var owner = player(context, "Owner", new Vec3d(1.5, 2, 1));
        var far = player(context, "Distant", new Vec3d(30, 2, 1));
        var body = body(context, owner.getUuid(), new Vec3d(1.5, 2, 2.5));
        var profile = SocialProfile.defaults(body.companionId());
        var active = participant(body, profile, true);
        List<UUID> received = new ArrayList<>();
        CompanionSocials socials = new CompanionSocials(ignored -> null, () -> 0, (player, text) -> received.add(player.getUuid()));
        var rain = new SocialSnapshot(true, true, false, false, null, List.of());
        socials.react(participant(body, profile.withAmbient(false), true), rain, List.of(owner), 0);
        socials.react(participant(body, profile, false), rain, List.of(owner), 0);
        context.assertTrue(received.isEmpty(), "Mute and stopped state must suppress ambient speech");
        socials.react(active, rain, List.of(owner, far), 0);
        socials.deliverDue(active.id(), SpeechTiming.MAX_MILLIS);
        context.assertEquals(List.of(owner.getUuid()), received, "Only the permitted nearby listener hears ambient speech");
        var otherBody = body(context, owner.getUuid(), new Vec3d(2.5, 2, 2.5));
        socials.react(participant(otherBody, SocialProfile.defaults(otherBody.companionId()), true), rain, List.of(owner), 11_000);
        context.assertEquals(1, received.size(), "A second companion must honor the listener's shared cooldown");
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

    private static CompanionSocials.Participant participant(CompanionEntity body, SocialProfile profile, boolean active) {
        return new CompanionSocials.Participant(body.companionId(), body.ownerId(), "Companion", body, profile, active);
    }

    private static ServerPlayerEntity player(TestContext context, String name, Vec3d relative) {
        var player = new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(),
                new GameProfile(UUID.randomUUID(), name), SyncedClientOptions.createDefault());
        Vec3d position = context.getAbsolute(relative);
        player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return player;
    }
}
