package dev.kindred.fabric;

import dev.kindred.core.SocialProfile;
import dev.kindred.core.social.Expression;
import dev.kindred.core.social.ListenerPreferences;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In plain terms: a GameTest: in a real test world, an AI player speaking first. To a hungry person it offers bread it has
 * to spare, and hands it over only once they say "yes please"; it warns a person of a creeper behind them, and not of one in
 * front; it asks for flint once an hour, however often it may speak; it says nothing idle to someone in a fight while it
 * does to someone who is not; and over ten minutes, someone who answers it hears more from it than someone who never does.
 *
 * Technical: E-53. The offer and the warning run through the whole runtime (its lane on the real clock). The other three
 * drive {@link InitiativeLane} with its own clock and a stand-in for the runtime's side, on the older mob-style body, as
 * {@link SocialGameTests} drives the social lane; the people are mock players logged in to the test server. Staged, and
 * said where: Echo's bread and a person's hunger; a creeper with its AI off; a person's fight (their last strike set).
 */
public final class NativeInitiativeGameTests implements FabricGameTest {
    /**
     * Staged: Echo carries sixteen bread; a bystander, who does not control Echo, is hungry (food 4). Echo offers them bread;
     * nothing moves until they say "yes please", and then four go through the toss method.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "initiative", tickLimit = 1600)
    public void anOfferToAHungryPersonIsTossedOnlyOnYesPlease(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] hungry = {null};
        boolean[] said = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on"))
                .createAndAdd(() -> {
                    f.runtime.heardForFixtures((player, text) -> t.heard.add(new NativeVoiceGameTests.Heard(player.getUuid(), text.getString(), NativeVoiceGameTests.Talk.now())));
                    f.player().getInventory().setStack(0, new ItemStack(Items.BREAD, 16)); // Staged: its bread.
                    hungry[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                    hungry[0].getHungerManager().setFoodLevel(4); // Staged: their hunger.
                })
                .createAndAdd(() -> {
                    context.assertTrue(t.heardBy(hungry[0]).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: Need food? I've got bread.")),
                            "Offered: " + t.heardBy(hungry[0]));
                    context.assertTrue(f.runtime.offeringForFixtures(f.id, hungry[0].getUuid()), "The offer waits");
                })
                .createAndAdd(() -> {
                    NativeVoiceGameTests.Talk.waitFor(1_500);
                    context.assertTrue(f.player().getInventory().count(Items.BREAD) == 16, "Nothing moved before the yes: " + f.player().getInventory().count(Items.BREAD));
                })
                .createAndAdd(() -> {
                    if (!said[0]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(hungry[0].getUuid(), "Echo, yes please");
                        said[0] = true;
                    }
                    context.assertTrue(t.heardBy(hungry[0]).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: Here you go.")), "Answered: " + t.heardBy(hungry[0]));
                    context.assertTrue(f.player().getInventory().count(Items.BREAD) == 12, "Four tossed: " + f.player().getInventory().count(Items.BREAD));
                    context.assertTrue(!f.runtime.offeringForFixtures(f.id, hungry[0].getUuid()), "The offer taken");
                })
                .createAndAdd(() -> t.cleanup(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Staged: a creeper with its AI off. In front of its owner, no warning; behind them, a warning. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "initiative", tickLimit = 1600)
    public void aWarningAboutACreeperBehindAPerson(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        net.minecraft.entity.mob.CreeperEntity[] creeper = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.runtime.heardForFixtures((player, text) -> t.heard.add(new NativeVoiceGameTests.Heard(player.getUuid(), text.getString(), NativeVoiceGameTests.Talk.now())));
                    // Its owner stands south of Echo, facing north toward it; the creeper first between them, in front of the owner.
                    creeper[0] = context.spawnMob(EntityType.CREEPER, new Vec3d(5.5, 2, 5.0));
                    creeper[0].setAiDisabled(true);
                })
                .createAndAdd(() -> {
                    NativeVoiceGameTests.Talk.waitFor(2_000);
                    context.assertTrue(t.heardBy(t.owner).stream().noneMatch(h -> h.text().contains("Behind you")), "No warning for one in front: " + t.heardBy(t.owner));
                    Vec3d behind = context.getAbsolute(new Vec3d(3.5, 2, 7.5));
                    creeper[0].refreshPositionAndAngles(behind.x, behind.y, behind.z, 0, 0);
                })
                .createAndAdd(() -> context.assertTrue(t.heardBy(t.owner).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: Behind you, test-mock-player: a creeper!")),
                        "Warned: " + t.heardBy(t.owner)))
                .createAndAdd(() -> {
                    creeper[0].discard();
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A stand-in for the runtime's side: one player, idle or not, needing what it is told, with nothing to spare. */
    private static final class Stand implements InitiativeLane.Source {
        final List<CompanionSocials.Participant> players = new ArrayList<>();
        List<String> needs = List.of();
        boolean idle;

        @Override public List<CompanionSocials.Participant> players() { return players; }
        @Override public Optional<Map.Entry<String, Integer>> spareFood(UUID id) { return Optional.empty(); }
        @Override public List<String> needs(UUID id) { return needs; }
        @Override public boolean idle(UUID id) { return idle; }
        @Override public boolean inCountdown(UUID person) { return false; }
        @Override public int offerToss(UUID id, ServerPlayerEntity to, String item, int count, java.util.function.Consumer<String> refused) { return 0; }
        @Override public Expression sounding(CompanionSocials.Participant player) { return Expression.PLAIN; }
        @Override public ListenerPreferences preferences(UUID person) { return ListenerPreferences.defaults(person); }
    }

    /** The lane with its own clock, one player talking in public at the centre of the plot, and the lines it says, by hearer. */
    private static final class Lane {
        final AtomicLong now = new AtomicLong();
        final Stand stand = new Stand();
        final List<String> heard = new ArrayList<>();
        // By id: every mock player has the same name.
        final CompanionSocials socials = new CompanionSocials(id -> null, now::get, (to, text) -> heard.add(to.getUuid() + " <- " + text.getString()));
        final InitiativeLane lane = new InitiativeLane(stand, socials, now::get);
        final CompanionSocials.Participant player;
        final List<ServerPlayerEntity> people = new ArrayList<>();

        Lane(TestContext context) {
            var body = KindredMod.COMPANION.create(context.getWorld());
            if (body == null) throw new IllegalStateException("Missing companion factory");
            body.bind(body.getUuid(), UUID.randomUUID());
            Vec3d at = context.getAbsolute(new Vec3d(3.5, 2, 3.5));
            body.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
            player = new CompanionSocials.Participant(body.companionId(), body.ownerId(), "Sol", body,
                    SocialProfile.defaults(body.companionId()).withPublic(true), true);
            stand.players.add(player);
        }

        ServerPlayerEntity person(TestContext context, Vec3d at) {
            var person = MockPlayers.creative(context);
            Vec3d stand = context.getAbsolute(at);
            person.refreshPositionAndAngles(stand.x, stand.y, stand.z, 0, 0);
            people.add(person);
            return person;
        }

        /** The lane's turn at each step of {@code stepMillis} until {@code untilMillis}, its lines delivered. */
        void run(long untilMillis, long stepMillis, java.util.function.LongConsumer each) {
            for (; now.get() <= untilMillis; now.addAndGet(stepMillis)) {
                // People who stand still for five minutes are away: each shifts its weight a little, as people do.
                double shift = (now.get() / stepMillis) % 2 == 0 ? 0.2 : -0.2;
                for (var person : people) person.refreshPositionAndAngles(person.getX() + shift, person.getY(), person.getZ(), person.getYaw(), 0);
                lane.tick();
                socials.deliverDue(player.id(), now.get() + 60_000);
                each.accept(now.get());
            }
        }

        long count(ServerPlayerEntity person, String text) {
            String prefix = person.getUuid() + " <- [Kindred] Sol: ";
            return heard.stream().filter(h -> h.startsWith(prefix) && (text == null || h.equals(prefix + text))).count();
        }

        void cleanup(TestContext context) {
            var manager = context.getWorld().getServer().getPlayerManager();
            for (var person : people) manager.remove(person);
        }
    }

    /** It needs flint (and later string): asked once, not again within the hour however often it may speak, then again. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "initiative")
    public void aRequestForFlintSpokenOnceInAnHour(TestContext context) {
        var l = new Lane(context);
        var jo = l.person(context, new Vec3d(3.5, 2, 6.5));
        l.stand.needs = List.of("flint");
        List<Long> asked = new ArrayList<>();
        l.run(70 * 60_000L, 10_000, now -> {
            long before = asked.size();
            if (l.count(jo, "Anyone got flint?") > before) asked.add(now);
        });
        context.assertTrue(asked.size() == 2, "Asked twice in seventy minutes: at " + asked);
        context.assertTrue(asked.get(0) == 0 && asked.get(1) >= 60 * 60_000L, "Once, then again an hour later: " + asked);
        // Another thing it needs is its own ask, within the budget.
        l.stand.needs = List.of("flint", "string");
        l.run(l.now.get() + 10 * 60_000L, 10_000, now -> { });
        context.assertTrue(l.count(jo, "Anyone got string?") == 1, "String asked once: " + l.heard);
        l.cleanup(context);
        context.complete();
    }

    /** Staged: Kit struck last moments ago (still in a fight). Idle together, Jo hears a word and Kit none. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "initiative")
    public void aPersonFightingGetsNoBanter(TestContext context) {
        var l = new Lane(context);
        var jo = l.person(context, new Vec3d(2.5, 2, 6.5));
        var kit = l.person(context, new Vec3d(4.5, 2, 6.5));
        var zombie = context.spawnMob(EntityType.ZOMBIE, new Vec3d(5.5, 2, 7.5));
        zombie.setAiDisabled(true);
        kit.age = 500;
        kit.onAttacking(zombie); // Staged: Kit's strike, the tick of it.
        context.assertTrue(InitiativeLane.fighting(kit) && !InitiativeLane.fighting(jo), "Kit in a fight, Jo not");
        l.stand.idle = true;
        l.run(5 * 60_000L, 10_000, now -> { });
        context.assertTrue(l.count(jo, "Quiet out here.") >= 1, "Jo hears a word: " + l.heard);
        context.assertTrue(l.count(kit, null) == 0, "Kit, fighting, hears nothing: " + l.heard);
        zombie.discard();
        l.cleanup(context);
        context.complete();
    }

    /** Ten minutes idle together: Jo answers every line, Kit never does; Jo hears more, counted. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "initiative")
    public void aPersonWhoNeverAnswersGetsFewerLinesThanOneWhoDoes(TestContext context) {
        var l = new Lane(context);
        var jo = l.person(context, new Vec3d(2.5, 2, 6.5));
        var kit = l.person(context, new Vec3d(4.5, 2, 6.5));
        l.stand.idle = true;
        long[] answered = {0};
        l.run(10 * 60_000L, 5_000, now -> {
            long toJo = l.count(jo, null);
            if (toJo > answered[0]) { // A new line to Jo: answered within the minute.
                answered[0] = toJo;
                l.lane.heard(jo, l.player, "ha, yes");
            }
        });
        long toJo = l.count(jo, null), toKit = l.count(kit, null);
        KindredMod.LOGGER.info("Ten minutes idle together: {} lines to the one who answers, {} to the one who never does", toJo, toKit);
        context.assertTrue(toJo > toKit, "Jo " + toJo + " lines, Kit " + toKit + ": " + l.heard);
        context.assertTrue(toKit >= 1, "Kit still spoken to: " + toKit);
        l.cleanup(context);
        context.complete();
    }
}
