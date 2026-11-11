package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.GameModes;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.UUID;

/**
 * Factions chosen by an operator as the world's mode (M-06i, for the month run M-06k): not only an evaluation run's or a
 * fixture's world. The operator's {@code /kindred mode factions} sets it, the territory guard is on from then, the
 * world's rules say who may fight (the faction world is a standing match its members joined by joining a faction; safe
 * zones are absolute), it is saved, and a restart comes back to it with the guard on again. Another mode turns the guard
 * off.
 */
public final class NativeFactionsModeGameTests implements FabricGameTest {
    /** D7 in a faction world, as told on joining: the owner may loosen it later. */
    static final String PVP = "only between members of different factions, never in safe zones; joining a faction is joining the match; leave it any time";

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionsMode", tickLimit = 1200)
    public void anOperatorChoosesFactionsAndItIsKeptAcrossARestart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 1000);
        var server = context.getWorld().getServer();
        PlayerRuntime[] again = new PlayerRuntime[1];
        var shared = Shared.isolate(context, () -> java.util.Arrays.asList(f.runtime, again[0]));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.worldMode() == null && !f.runtime.factionsWorld(), "No mode chosen at first");
                    context.assertTrue(!TerritoryGuard.activeForTest(f.runtime.factions()), "No territory guard before Factions");
                    var rules = GameModes.rules(GameModes.WorldMode.FACTIONS);
                    context.assertTrue(!rules.toString().contains("not playable") && !rules.toString().contains("not yet defined"),
                            "Factions' rules are set: " + rules);
                    context.assertTrue(rules.lines().contains("Fighting: " + PVP), "The fighting rule told on joining: " + rules.lines());
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.FACTIONS) == 1, "Factions chosen by an operator");
                    context.assertTrue(f.runtime.worldMode() == GameModes.WorldMode.FACTIONS && f.runtime.factionsWorld(), "The world plays Factions");
                    context.assertTrue(TerritoryGuard.activeForTest(f.runtime.factions()), "The territory guard is on");
                })
                .createAndAdd(() -> {
                    // Another mode: the guard off with it.
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.COOP_SURVIVAL) == 1, "Co-op chosen");
                    context.assertTrue(!f.runtime.factionsWorld() && !TerritoryGuard.activeForTest(f.runtime.factions()), "No guard outside Factions");
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.FACTIONS) == 1, "Factions chosen again");
                    context.assertTrue(TerritoryGuard.activeForTest(f.runtime.factions()), "The guard on again");
                })
                .createAndAdd(() -> {
                    // Saved off the server thread; once it is, a restart reads it back.
                    var file = f.directory.resolve("mode.properties");
                    String saved;
                    try {
                        saved = java.nio.file.Files.exists(file) ? java.nio.file.Files.readString(file) : "";
                    } catch (java.io.IOException failure) {
                        saved = "";
                    }
                    context.assertTrue(saved.contains("mode=factions"), "Saved: " + saved);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                    context.assertTrue(!TerritoryGuard.activeForTest(f.runtime.factions()), "The stopped runtime's guard is off");
                    again[0] = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer);
                    again[0].start();
                })
                .createAndAdd(() -> {
                    context.assertTrue(again[0].isReady(), "Restarted");
                    context.assertTrue(again[0].worldMode() == GameModes.WorldMode.FACTIONS && again[0].factionsWorld(),
                            "Factions kept across the restart: " + again[0].worldMode());
                    context.assertTrue(TerritoryGuard.activeForTest(again[0].factions()), "The guard on after the restart");
                    again[0].stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(again[0].shutdownComplete(), "Cleanup");
                    shared.leftAsFound(context);
                })
                .completeIfSuccessful();
    }
    /**
     * D7 in an operator's faction world: joining a faction is joining the match. A person in no faction is never struck,
     * however near a hostile faction's AI player; once they join one, a member of another faction may strike them outside
     * a safe zone; once they leave, no more. The same rule binds the AI players: one in no faction strikes no person.
     * Checked at the strike gate every attack passes ({@code TerritoryGuard.strikeRefusal}, no running match's leave).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "factionsModeStrike", tickLimit = 1600)
    public void aPersonIsStruckOnlyWhileInAFactionOtherThanTheStrikers(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        UUID[] players = new UUID[2];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        FixtureGround.whenDone(context, () -> { if (person[0] != null && server.getPlayerManager().getPlayer(person[0].getUuid()) != null) server.getPlayerManager().remove(person[0]); });
        var shared = Shared.isolate(context, () -> java.util.Arrays.asList(f.runtime));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.FACTIONS) == 1, "Factions chosen by an operator"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) players[player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1] = status.nativeId();
                    // Staged: a person at an ordinary client, in the wilderness beside Ada.
                    person[0] = MockPlayers.survival(context, "Nell", new BlockPos(1, 2, 1));
                    context.assertTrue(f.runtime.faction(f.source, players[0], "create", List.of("Red", "RED", "red")).accepted(), "Ada founds Red");
                })
                .createAndAdd(() -> {
                    var ada = player(f, players[0]);
                    var bram = player(f, players[1]);
                    var nell = person[0];
                    context.assertTrue(f.runtime.factions().book().of(players[0]).isPresent(), "Ada in Red");
                    context.assertTrue(TerritoryGuard.strikeRefusal(ada, nell, false) != null, "Nell, in no faction, is not struck by Red's Ada");
                    context.assertTrue(TerritoryGuard.strikeRefusal(nell, ada, false) != null, "Nor does she strike Ada");
                    context.assertTrue(TerritoryGuard.strikeRefusal(bram, nell, false) != null, "Bram, in no faction, strikes no person");
                    context.assertTrue(f.runtime.faction(f.source, players[1], "create", List.of("Blue", "BLU", "blue")).accepted(), "Bram founds Blue");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.faction(f.source, players[1], "invite", List.of("Nell")).accepted(), "Bram invites Nell"))
                .createAndAdd(() -> context.assertTrue(f.runtime.faction(f.source, person[0].getUuid(), "join", List.of("Blue")).accepted(), "Nell joins Blue"))
                .createAndAdd(() -> {
                    var ada = player(f, players[0]);
                    var bram = player(f, players[1]);
                    var nell = person[0];
                    context.assertTrue(f.runtime.factions().book().of(nell.getUuid()).map(x -> x.name().equals("Blue")).orElse(false), "Nell in Blue");
                    context.assertTrue(TerritoryGuard.strikeRefusal(ada, nell, false) == null, "In Blue, outside a safe zone, Red's Ada may strike Nell");
                    context.assertTrue(TerritoryGuard.strikeRefusal(bram, nell, false) != null, "Never her own faction's Bram");
                    context.assertTrue(f.runtime.faction(f.source, nell.getUuid(), "leave", List.of()).accepted(), "Nell leaves Blue");
                })
                .createAndAdd(() -> {
                    var ada = player(f, players[0]);
                    var nell = person[0];
                    context.assertTrue(f.runtime.factions().book().of(nell.getUuid()).isEmpty(), "Nell in no faction");
                    context.assertTrue(TerritoryGuard.strikeRefusal(ada, nell, false) != null, "Left: Nell is not struck from then on");
                    server.getPlayerManager().remove(nell);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Cleanup");
                    shared.leftAsFound(context);
                })
                .completeIfSuccessful();
    }

    /**
     * What a faction world could change in the gametest server that other fixtures share: the territory guard (one static
     * set for the whole server) and the borders. The world's mode and mode.properties are each runtime's own (its own
     * directory), so not shared. {@link #isolate} records the borders and, on every way the test ends (passed, failed,
     * timed out, retried), stops the test's runtimes still running, turns their guards off and puts the borders back;
     * {@link #leftAsFound} asserts it on the passing path.
     */
    record Shared(java.util.function.Supplier<List<PlayerRuntime>> runtimes, net.minecraft.server.MinecraftServer server, double[] borders) {
        static Shared isolate(TestContext context, java.util.function.Supplier<List<PlayerRuntime>> runtimes) {
            var server = context.getWorld().getServer();
            var shared = new Shared(() -> runtimes.get().stream().filter(java.util.Objects::nonNull).toList(), server, sizes(server));
            FixtureGround.whenDone(context, () -> {
                // Never thrown into the server's tick: a clean-up that failed is logged and the test's own result stands.
                try {
                    shared.restore();
                } catch (RuntimeException failed) {
                    KindredMod.LOGGER.error("Factions-mode fixture clean-up failed", failed);
                }
            });
            return shared;
        }

        private static double[] sizes(net.minecraft.server.MinecraftServer server) {
            var out = new double[3];
            int i = 0;
            for (var key : List.of(net.minecraft.world.World.OVERWORLD, net.minecraft.world.World.NETHER, net.minecraft.world.World.END)) {
                var world = server.getWorld(key);
                out[i++] = world == null ? -1 : world.getWorldBorder().getSize();
            }
            return out;
        }

        private void restore() {
            for (var runtime : runtimes.get()) {
                if (runtime.running()) runtime.stop();
                TerritoryGuard.deactivate(runtime.factions());
            }
            int i = 0;
            for (var key : List.of(net.minecraft.world.World.OVERWORLD, net.minecraft.world.World.NETHER, net.minecraft.world.World.END)) {
                var world = server.getWorld(key);
                double size = borders[i++];
                if (world != null && size >= 0 && world.getWorldBorder().getSize() != size) world.getWorldBorder().setSize(size);
            }
        }

        void leftAsFound(TestContext context) {
            for (var runtime : runtimes.get()) context.assertTrue(!TerritoryGuard.activeForTest(runtime.factions()), "The guard is off as the test ends");
            context.assertTrue(java.util.Arrays.equals(sizes(server), borders), "The borders as they were: " + java.util.Arrays.toString(sizes(server)));
        }
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
