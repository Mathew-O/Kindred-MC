package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.persistence.StoredNativePlayer;
import dev.kindred.core.match.MatchRegistry;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * A duel between two AI players (M-00a, D7). Players may attack one another only while a match both joined runs, and
 * only where the server allows PvP at all. Before it a strike at the other is refused; with the server's PvP off no duel
 * is proposed; during its countdown neither is hurt; once it runs they fight until one falls, and the other wins; once
 * it is over, a strike is refused again.
 */
public final class NativeMatchGameTests implements FabricGameTest {
    private static final String REFUSED = "No running match allows striking that player";

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchDuel", tickLimit = 2400)
    public void twoAiPlayersFightOnlyWhileTheDuelTheyJoinedRuns(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2200);
        UUID[] ids = new UUID[2], players = new UUID[2];
        UUID[] match = new UUID[1];
        long[] countdown = {-1};
        float[] health = new float[2];
        boolean[] hurt = {false}, refusedInCountdown = {false};
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // A walled ring, so neither wanders off.
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var ada = player(f, players[0]);
                    var bram = player(f, players[1]);
                    context.assertTrue(ada.isOnGround() && bram.isOnGround(), "Both standing");
                    ada.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    ada.getInventory().selectedSlot = 0;
                    bram.getInventory().setStack(0, new ItemStack(Items.WOODEN_SWORD));
                    bram.getInventory().selectedSlot = 0;
                    bram.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
                    BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 3)), b = context.getAbsolutePos(new BlockPos(5, 2, 3));
                    ada.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, -90, 0);
                    bram.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 90, 0);
                    // The server allows PvP from the start, so only the match rule stands between them. Before any
                    // match a strike at another player is refused.
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.nativeFight(f.source, ids[0], players[1], 0) == 1, "Fight requested before any match");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatusLine(ids[0]).contains(REFUSED), "Refused before a match: " + f.runtime.nativeStatusLine(ids[0]));
                    context.assertTrue(player(f, players[1]).getHealth() == 20, "Bram unhurt");
                })
                .createAndAdd(() -> {
                    // Each step acts only once its checks pass, since a failed step is tried again from its start.
                    context.assertTrue(settled(f, ids[0]), "Ada settled: " + f.runtime.nativeStatusLine(ids[0]));
                    // The server's own rule first: with PvP off there is no duel to fight.
                    server.setPvpEnabled(false);
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 0 && f.runtime.matches().isEmpty(),
                            "Refused with the server's PvP off");
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 1, "Duel proposed");
                    var proposed = f.runtime.matches().getFirst();
                    match[0] = proposed.id();
                    context.assertTrue(proposed.joined().size() == 2, "Both entered by their administrator: " + proposed.joined());
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                    countdown[0] = context.getWorld().getServer().getTicks();
                    health[0] = player(f, players[0]).getHealth();
                    health[1] = player(f, players[1]).getHealth();
                })
                .createAndAdd(() -> {
                    // Joined but not yet running: a strike is still refused.
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.COUNTDOWN, "Still counting down");
                    context.assertTrue(f.runtime.nativeFight(f.source, ids[0], players[1], 0) == 1, "Fight requested in the countdown");
                })
                .createAndAdd(() -> {
                    // Through the countdown, nobody is hurt.
                    // A failed step is only tried again, so a hurt seen during the countdown is kept until it ends.
                    var state = f.runtime.matches().getFirst().state();
                    if (state == MatchRegistry.State.COUNTDOWN && (player(f, players[0]).getHealth() != health[0]
                            || player(f, players[1]).getHealth() != health[1])) hurt[0] = true;
                    if (state == MatchRegistry.State.COUNTDOWN && f.runtime.nativeStatusLine(ids[0]).contains(REFUSED)) refusedInCountdown[0] = true;
                    context.assertTrue(state == MatchRegistry.State.RUNNING, "Running after the countdown");
                    context.assertTrue(!hurt[0], "Hurt during the countdown");
                    context.assertTrue(refusedInCountdown[0], "A strike refused during the countdown");
                    context.assertTrue(context.getWorld().getServer().getTicks() - countdown[0] >= MatchRegistry.COUNTDOWN_TICKS - 1, "The whole countdown");
                })
                .createAndAdd(() -> {
                    var duel = f.runtime.matches().getFirst();
                    context.assertTrue(duel.state() == MatchRegistry.State.ENDED, "Fought to a fall: " + duel.state()
                            + "; Ada " + player(f, players[0]).getHealth() + ", Bram " + player(f, players[1]).getHealth());
                })
                .createAndAdd(() -> {
                    var duel = f.runtime.matches().getFirst();
                    KindredMod.LOGGER.info("Duel: {} ({}), the winner {}", duel.state(), duel.result(), duel.winner() == null ? "none" : player(f, duel.winner()).getName().getString());
                    context.assertTrue("fell".equals(duel.result()) && players[0].equals(duel.winner()), "Ada won by Bram's fall: " + duel.result());
                })
                .createAndAdd(() -> {
                    // Over: a strike is refused again, once Bram is back on its feet.
                    var bram = player(f, players[1]);
                    context.assertTrue(bram != null && bram.isAlive() && settled(f, ids[0]), "Bram back, Ada settled: " + f.runtime.nativeStatusLine(ids[0]));
                    context.assertTrue(!f.runtime.nativeStatusLine(ids[0]).contains(REFUSED), "No refusal yet: " + f.runtime.nativeStatusLine(ids[0]));
                    health[1] = bram.getHealth();
                    context.assertTrue(f.runtime.nativeFight(f.source, ids[0], players[1], 0) == 1, "Fight requested after the match");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatusLine(ids[0]).contains(REFUSED), "Refused after the match: " + f.runtime.nativeStatusLine(ids[0]));
                    context.assertTrue(player(f, players[1]).getHealth() >= health[1], "Bram unhurt after the match");
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A player that leaves the game forfeits its duel: here an AI player whose body is taken out mid-fight. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchLeft", tickLimit = 1600)
    public void anAiPlayerTakenOutOfTheGameForfeitsItsDuel(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        UUID[] ids = new UUID[2], players = new UUID[2];
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 1, "Duel proposed");
                    context.assertTrue(f.runtime.startMatch(f.source, f.runtime.matches().getFirst().id()) == 1, "Started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "disconnect", ids[1]) == 1, "Bram taken out"))
                .createAndAdd(() -> {
                    var duel = f.runtime.matches().getFirst();
                    context.assertTrue(duel.state() == MatchRegistry.State.ENDED && "left the game".equals(duel.result())
                            && players[0].equals(duel.winner()), "Ada won by Bram's leaving: " + duel.state() + " " + duel.result());
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Nothing running or still being written for the player, so a request is taken now. */
    private static boolean settled(NativePaidSkillsGameTests.Fixture f, UUID id) {
        String line = f.runtime.nativeStatusLine(id);
        return line.contains("busy=false") && line.contains("workPending=false") && line.contains("actionPending=false")
                && line.contains("activity=IDLE") && line.contains("flushes=true/true") && line.contains("cancelling=false");
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
