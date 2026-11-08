package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest: in a faction world, an AI player and a stand-in person found a faction together, a second
 * AI player founds an enemy one, the second kills the first in the wilderness, and the victim loses two power and its
 * faction two chunks of capacity; then the server's runtime is stopped and started again, and the victim's progress
 * toward its next point of power comes back to the second.
 *
 * Technical: M-06c's native fixture. The person is a mock survival player with no runtime entry, so to Kindred a human;
 * its requests go through the same {@code /faction} method as the AI players' (entered by an operator). The world is put
 * in Factions mode by the fixture only (the mode is not yet playable for operators: M-06d and M-06f come first).
 */
public final class NativeFactionPowerGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionPower", tickLimit = 1600)
    public void aPvpDeathCostsTwoPowerAndARestartKeepsRegenerationToTheSecond(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] players = new UUID[2];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        boolean[] dead = {false};
        int[] progress = {-1};
        FixtureGround.whenDone(context, () -> { if (person[0] != null && server.getPlayerManager().getPlayer(person[0].getUuid()) != null) server.getPlayerManager().remove(person[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) players[player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1] = status.nativeId();
                    person[0] = MockPlayers.survival(context, "Cleo", new BlockPos(1, 2, 1));
                })
                .createAndAdd(() -> {
                    // Accounts seen long enough ago that a kill counts (the new-account rule is JUnit's).
                    f.runtime.factionsSeenLongAgoForTest(List.of(players[0], players[1], person[0].getUuid()));
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "create", List.of("Red", "RED", "red")).accepted(), "Cleo founds Red");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "invite", List.of("Ada")).accepted(), "Cleo invites Ada");
                    context.assertTrue(f.runtime.faction(f.source, players[0], "join", List.of("Red")).accepted(), "Ada joins Red");
                    context.assertTrue(f.runtime.faction(f.source, players[1], "create", List.of("Blue", "BLU", "blue")).accepted(), "Bram founds Blue");
                    context.assertFalse(f.runtime.faction(f.source, players[1], "join", List.of("Red")).accepted(), "Bram is in Blue and not invited");
                })
                .createAndAdd(() -> {
                    var red = f.runtime.factions().book().named("Red").orElseThrow();
                    context.assertTrue(red.members().size() == 2, "Red: Cleo and Ada");
                    int power = PowerRules.factionPower(f.runtime.factions().book(), f.runtime.factions().powers(), red.id(), FactionSettings.DEFAULTS);
                    context.assertTrue(power == 20 && PowerRules.capacity(power, FactionSettings.DEFAULTS) == 20, "Red's power 20, capacity 20: " + power);
                    context.assertTrue(f.runtime.factionsMayAttack(players[1], players[0]), "Bram may strike Ada: enemies in the wilderness");
                    context.assertFalse(f.runtime.factionsMayAttack(players[0], person[0].getUuid()), "Ada never strikes Cleo, her faction-mate");
                    server.setPvpEnabled(true);
                })
                .createAndAdd(() -> {
                    var ada = player(f, players[0]);
                    var bram = player(f, players[1]);
                    if (!dead[0]) {
                        ada.damage(ada.getDamageSources().playerAttack(bram), 1000f);
                        dead[0] = ada.isDead();
                    }
                    context.assertTrue(dead[0], "Ada killed by Bram");
                })
                .createAndAdd(() -> {
                    var factions = f.runtime.factions();
                    context.assertTrue(factions.powers().of(players[0], FactionSettings.DEFAULTS).power() == 8, "Ada 10 -> 8: " + factions.powerLine(players[0]));
                    var red = factions.book().named("Red").orElseThrow();
                    int power = PowerRules.factionPower(factions.book(), factions.powers(), red.id(), FactionSettings.DEFAULTS);
                    context.assertTrue(power == 18 && PowerRules.capacity(power, FactionSettings.DEFAULTS) == 18, "Red's power and capacity 18: " + power);
                    context.assertTrue(factions.powers().of(players[1], FactionSettings.DEFAULTS).power() == 10, "Bram unchanged");
                })
                // Some seconds online, then a stop: Ada's progress toward her ninth point written to the second.
                .createAndAdd(() -> context.assertTrue(f.runtime.factions().pendingSeconds(players[0]) >= 3, "Three seconds online since"))
                .createAndAdd(() -> {
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                    progress[0] = f.runtime.factions().powers().of(players[0], FactionSettings.DEFAULTS).progressSeconds();
                    context.assertTrue(progress[0] >= 3, "Ada's progress written at the stop: " + progress[0]);
                })
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Restarted"))
                .createAndAdd(() -> {
                    var after = f.runtime.factions().powers().of(players[0], FactionSettings.DEFAULTS);
                    context.assertTrue(after.power() == 8 && after.progressSeconds() == progress[0],
                            "Ada at 8 with " + progress[0] + " s toward 9 after the restart: " + after);
                    context.assertTrue(f.runtime.factions().book().named("Red").orElseThrow().members().containsKey(person[0].getUuid()), "Cleo still in Red");
                    f.runtime.stop();
                    server.setPvpEnabled(pvp);
                    server.getPlayerManager().remove(person[0]);
                })
                .completeIfSuccessful();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
