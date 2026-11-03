package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.social.Rapport;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * In plain terms: a GameTest: gifts and friendship, in a real test world. Someone tells Echo "I need iron"; Echo, with
 * iron ingots and nothing else to do, offers half of them and hands them over only on their "yes please"; the gift counts
 * as given only once they pick it up, and then Echo's friendship with them rises. A toss nobody picks up is noted as left,
 * not given, and is not tossed again. A blow from someone is held against them, and its controller can strike it out as
 * a mistake; both, and the friendship worked out from them, are still there after the server restarts.
 *
 * Technical: E-21a (the offer, through E-53's lane and budget), E-22a ({@link GiftWatch}: the recipient's own
 * {@code picked_up} statistic) and E-24a ({@link Rapport}, kept beside the ledger in {@code <uuid>.rapport.json}, struck
 * out with {@code /aiplayer rapport}). The whole runtime, the stub voice as {@link NativeVoiceGameTests} has it. Staged,
 * and said where: Echo's iron, the pickup (a mock player does not walk over items, so the fixture hands each thrown stack
 * to it as vanilla's pickup does when a player touches one), a shorter wait for the left toss, the blow.
 */
public final class NativeGiftGameTests implements FabricGameTest {
    /** Each iron ingot stack thrown near Echo, picked up by {@code person} as vanilla's pickup does on touching it (staged). */
    private static int pickUp(TestContext context, NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity person) {
        var near = f.player().getBoundingBox().expand(8);
        int picked = 0;
        for (ItemEntity item : context.getWorld().getEntitiesByClass(ItemEntity.class, near, e -> e.getStack().isOf(Items.IRON_INGOT))) {
            item.setPickupDelay(0);
            item.onPlayerCollision(person);
            picked++;
        }
        return picked;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gifts", tickLimit = 2000)
    public void someoneWhoSaysTheyNeedIronIsOfferedHalfAndItCountsOncePickedUp(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] steve = {null};
        boolean[] done = new boolean[4];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.peersForFixtures(f.id) != null && f.runtime.rapportForFixtures(f.id).isPresent(),
                        "Its ledger and friendships read"))
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on"))
                .createAndAdd(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    t.listen();
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 8)); // Staged: its iron.
                    f.player().getInventory().setStack(1, new ItemStack(Items.IRON_PICKAXE)); // Never given.
                    steve[0] = t.player(context, new Vec3d(6.5, 2, 3.5), 90);
                })
                .createAndAdd(() -> {
                    if (!done[3]) {
                        done[3] = true;
                        f.runtime.chat(steve[0].getUuid(), "Echo, I need iron");
                    }
                    context.assertTrue(f.runtime.peersForFixtures(f.id).claimsKept(steve[0].getUuid()).size() == 1, "Steve's need noted, as said");
                })
                .createAndAdd(() -> context.assertTrue(t.heardBy(steve[0]).stream().anyMatch(h ->
                                h.text().equals("[Kindred] Echo: You said you need iron? I've got 4 iron ingot spare. Want some?")),
                        "Offered half its spare iron: " + t.heardBy(steve[0])))
                .createAndAdd(() -> {
                    NativeVoiceGameTests.Talk.waitFor(1_500);
                    context.assertTrue(f.player().getInventory().count(Items.IRON_INGOT) == 8, "Nothing moved before the yes");
                })
                .createAndAdd(() -> {
                    if (!done[1]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(steve[0].getUuid(), "Echo, yes please");
                        done[1] = true;
                    }
                    context.assertTrue(f.player().getInventory().count(Items.IRON_INGOT) == 4, "Four tossed: " + f.player().getInventory().count(Items.IRON_INGOT));
                    context.assertTrue(f.player().getInventory().count(Items.IRON_PICKAXE) == 1, "Its pickaxe kept");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.giftsForFixtures().watchingForFixtures() == 1, "Watched for its pickup");
                    var rapport = f.runtime.rapportForFixtures(f.id).orElseThrow();
                    context.assertTrue(rapport.standing(steve[0].getUuid()).counted() == 0, "Not a gift until picked up");
                })
                .createAndAdd(() -> {
                    if (!done[2]) {
                        done[2] = true;
                        context.assertTrue(pickUp(context, f, steve[0]) > 0, "Staged: Steve picks the iron up");
                    }
                    context.assertTrue(f.runtime.giftsForFixtures().receivedForFixtures() == 1, "Received: " + f.runtime.giftsForFixtures().receivedForFixtures());
                })
                .createAndAdd(() -> {
                    var standing = f.runtime.rapportForFixtures(f.id).orElseThrow().standing(steve[0].getUuid());
                    context.assertTrue(standing.counted() == 1 && standing.friendship() == Rapport.Kind.GAVE.worth,
                            "Its friendship with Steve, from the gift picked up: " + standing);
                    context.assertTrue(steve[0].getInventory().count(Items.IRON_INGOT) == 4, "Steve has the iron: " + steve[0].getInventory().count(Items.IRON_INGOT));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "giftsLeft", tickLimit = 1600)
    public void aTossNobodyPicksUpIsLeftNotGivenAndNotTossedAgain(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 8)); // Staged.
                    f.runtime.giftsForFixtures().deadlineForFixtures(100); // Staged: five seconds, not ninety.
                    context.assertTrue(f.runtime.nativeToss(t.owner.getCommandSource(), f.id, "iron_ingot", 2) == 1, "Its owner's toss");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getInventory().count(Items.IRON_INGOT) == 6, "Two tossed"))
                // Retried each tick until its five seconds of game time are over.
                .createAndAdd(() -> context.assertTrue(f.runtime.giftsForFixtures().leftForFixtures() == 1, "Left: " + f.runtime.giftsForFixtures().leftForFixtures()))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.giftsForFixtures().receivedForFixtures() == 0, "Not given");
                    context.assertTrue(f.runtime.rapportForFixtures(f.id).orElseThrow().standing(t.owner.getUuid()).counted() == 0, "Nothing counted");
                    context.assertTrue(f.player().getInventory().count(Items.IRON_INGOT) == 6, "Not tossed again");
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A toss lands at the feet of whoever it is for, whichever way the thrower faces (E-22b). The toss meant to aim the
     * thrown stack at its recipient, but it looked the stack up by its thrower, which a drop that does not keep its owner
     * never has: every toss flew where the thrower faced, two blocks or so, and a delivery to an AI player failed whenever
     * that was not at the receiver's feet (Wren left holding nothing, in CI and alone on main's code). Staged: Echo facing
     * west with its owner 3.8 blocks east of it; Echo's iron. The owner, a mock player, does not pick it up; where the stack
     * comes to rest is what is checked.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tossAimed", tickLimit = 800)
    public void aTossLandsAtTheFeetOfWhoeverItIsForWhicheverWayTheThrowerFaces(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ItemEntity[] thrown = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var from = context.getAbsolute(new Vec3d(2.5, 2, 3.5));
                    f.player().refreshPositionAndAngles(from.x, from.y, from.z, 90, 0); // Staged: facing west.
                    t.owner.refreshPositionAndAngles(from.x + 3.8, from.y, from.z, 90, 0); // East of it, behind its back.
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_INGOT, 8)); // Staged.
                })
                .createAndAdd(() -> {
                    f.player().setYaw(90);
                    f.player().setHeadYaw(90);
                    context.assertTrue(f.runtime.nativeToss(t.owner.getCommandSource(), f.id, "iron_ingot", 2) == 1, "Its owner's toss");
                })
                .createAndAdd(() -> {
                    var items = context.getWorld().getEntitiesByClass(ItemEntity.class, f.player().getBoundingBox().expand(12), e -> e.getStack().isOf(Items.IRON_INGOT));
                    context.assertTrue(!items.isEmpty(), "Tossed: " + f.player().getInventory().count(Items.IRON_INGOT) + " left");
                    thrown[0] = items.getFirst();
                })
                .createAndAdd(() -> context.assertTrue(thrown[0].isOnGround() && thrown[0].getVelocity().horizontalLengthSquared() < 1.0E-5, "Still moving"))
                .createAndAdd(() -> KindredMod.LOGGER.info("Aimed toss fixture: the stack came to rest {} blocks from its recipient",
                        Math.round(Math.hypot(thrown[0].getX() - t.owner.getX(), thrown[0].getZ() - t.owner.getZ()) * 10) / 10.0))
                .createAndAdd(() -> {
                    double off = Math.hypot(thrown[0].getX() - t.owner.getX(), thrown[0].getZ() - t.owner.getZ());
                    context.assertTrue(off <= 1.2, "Landed " + Math.round(off * 10) / 10.0 + " blocks from its owner, at "
                            + thrown[0].getPos().subtract(f.player().getPos()) + " from the thrower");
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rapport", tickLimit = 2000)
    public void aBlowIsHeldAgainstThemStruckOutByItsControllerAndBothSurviveARestart(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        ServerPlayerEntity[] steve = {null};
        boolean[] pvpWas = {false};
        List<String> said = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var server = context.getWorld().getServer();
                    pvpWas[0] = server.isPvpEnabled();
                    server.setPvpEnabled(true); // Staged: a server with hits between players.
                    steve[0] = t.player(context, new Vec3d(5.5, 2, 3.5), 90);
                    var player = f.player();
                    context.assertTrue(player.damage(player.getServerWorld().getDamageSources().playerAttack(steve[0]), 1.0f), "The blow landed");
                })
                .createAndAdd(() -> {
                    var standing = f.runtime.rapportForFixtures(f.id).orElseThrow().standing(steve[0].getUuid());
                    context.assertTrue(standing.friendship() == Rapport.Kind.HARMED.worth && standing.band().equals("cool"), "Held against Steve: " + standing);
                })
                .createAndAdd(() -> {
                    var source = t.owner.getCommandSource().withOutput(capture(said));
                    context.assertTrue(f.runtime.rapport(steve[0].getCommandSource(), f.id, null, 0) == 0, "Steve may not look at Echo's record");
                    context.assertTrue(f.runtime.rapport(source, f.id, steve[0].getName().getString(), 1) == 1, "Its controller strikes it out");
                    context.assertTrue(said.stream().anyMatch(line -> line.startsWith("Struck out. Echo and ")), "Said: " + said);
                    context.assertTrue(f.runtime.rapportForFixtures(f.id).orElseThrow().standing(steve[0].getUuid()).friendship() == 0, "Worked out again");
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
                    var rapport = f.runtime.rapportForFixtures(f.id).orElse(null);
                    context.assertTrue(rapport != null, "Read back");
                    var events = rapport.events(steve[0].getUuid());
                    context.assertTrue(events.size() == 1 && events.getFirst().corrected() && events.getFirst().kind() == Rapport.Kind.HARMED,
                            "The blow and its strike-out kept: " + events);
                    context.assertTrue(rapport.standing(steve[0].getUuid()).friendship() == 0, "And the friendship worked out from them");
                    context.getWorld().getServer().setPvpEnabled(pvpWas[0]);
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
}
