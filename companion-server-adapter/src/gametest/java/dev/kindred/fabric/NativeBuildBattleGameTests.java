package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.BuildBattle;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.util.UUID;

/**
 * A build battle between two AI builders (M-04a). Each arrives with nothing, builds the theme on its own plot in
 * creative, and votes on the other's build when the time is up; the best wins, and both leave as they came, with nothing
 * and in their own game mode.
 */
public final class NativeBuildBattleGameTests implements FabricGameTest {
    private static final int BUILD_TICKS = 1200;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildBattle", tickLimit = 3000)
    public void twoAiBuildersBuildTheThemeInCreativeAndVoteOnEachOther(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2800);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        boolean[] leaked = {false};
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Level ground for two plots east of where the operator stands.
                    var world = context.getWorld();
                    for (int x = -4; x <= 40; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Wren");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Wren allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pip"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Wren") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var wren = player(f, players[0]);
                    var pip = player(f, players[1]);
                    wren.refreshPositionAndAngles(o.getX() + 9.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    pip.refreshPositionAndAngles(o.getX() + 27.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    wren.getInventory().clear();
                    pip.getInventory().clear();
                    context.assertTrue(wren.interactionManager.getGameMode() == GameMode.SURVIVAL && pip.interactionManager.getGameMode() == GameMode.SURVIVAL,
                            "Both in survival");
                    var operator = f.source.withPosition(net.minecraft.util.math.Vec3d.of(o.add(0, 2, 0)));
                    context.assertTrue(f.runtime.proposeBuildBattle(operator, java.util.List.of(players[0], players[1]), BUILD_TICKS, BuildBattle.Theme.PYRAMID) == 1,
                            "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(world.getBlockState(o.add(2, 1, 2)).isOf(Blocks.LIGHT_GRAY_CONCRETE) && world.getBlockState(o.add(9, 1, 9)).isOf(Blocks.WHITE_CONCRETE),
                            "The first plot marked out");
                    context.assertTrue(f.runtime.matches().getFirst().joined().size() == 2, "Both entered by their administrator");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Building");
                    var wren = player(f, players[0]);
                    context.assertTrue(wren.interactionManager.getGameMode() == GameMode.CREATIVE, "In creative to build");
                    context.assertTrue(f.runtime.buildStatus(ids[0]).isPresent() && f.runtime.buildStatus(ids[1]).isPresent(), "Both at work");
                    // Its own plot only: never the other's.
                    context.assertTrue(f.runtime.mayEditAt(wren, o.add(9, 3, 9)) && !f.runtime.mayEditAt(wren, o.add(27, 3, 9)), "Each to its own plot");
                })
                .createAndAdd(() -> {
                    // Kept: whether a builder ever held anything after the vote began.
                    var state = f.runtime.matches().getFirst().state();
                    if (state != MatchRegistry.State.RUNNING && state != MatchRegistry.State.COUNTDOWN) {
                        for (UUID p : players) if (!player(f, p).getInventory().isEmpty()) leaked[0] = true;
                    }
                    context.assertTrue(state == MatchRegistry.State.ENDED, "Voted on and decided: " + state + "; " + f.runtime.buildStatus(ids[0]).orElse("")
                            + " / " + f.runtime.buildStatus(ids[1]).orElse(""));
                })
                .createAndAdd(() -> {
                    var battle = f.runtime.matches().getFirst();
                    var world = context.getWorld();
                    int[] built = new int[2];
                    for (int i = 0; i < 2; i++) {
                        int x0 = 2 + i * (BuildBattle.PLOT + BuildBattle.GAP);
                        for (int x = 0; x < BuildBattle.PLOT; x++) for (int z = 0; z < BuildBattle.PLOT; z++) for (int y = 2; y <= 10; y++) {
                            if (world.getBlockState(o.add(x0 + x, y, 2 + z)).isIn(net.minecraft.registry.tag.BlockTags.SAND)
                                    || world.getBlockState(o.add(x0 + x, y, 2 + z)).getBlock().getTranslationKey().contains("sandstone")) built[i]++;
                        }
                    }
                    KindredMod.LOGGER.info("Build battle: {} ({}), winners {}; sandstone built {} and {}", battle.state(), battle.result(),
                            battle.winners().stream().map(p -> player(f, p).getName().getString()).toList(), built[0], built[1]);
                    context.assertTrue(built[0] > 20 && built[1] > 20, "Both built a pyramid: " + built[0] + ", " + built[1]);
                    context.assertTrue("the best mean vote".equals(battle.result()) && !battle.winners().isEmpty(), "Decided by the votes: " + battle.result());
                    context.assertTrue(!leaked[0], "Nothing held after the build");
                    for (UUID p : players) {
                        var player = player(f, p);
                        context.assertTrue(player.getInventory().isEmpty() && player.interactionManager.getGameMode() == GameMode.SURVIVAL,
                                "Left as it came: " + player.getName().getString() + " " + player.interactionManager.getGameMode());
                    }
                    var rockets = world.getEntitiesByType(EntityType.FIREWORK_ROCKET, new Box(o).expand(48), e -> true);
                    context.assertTrue(!rockets.isEmpty(), "Fireworks for the winner");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
