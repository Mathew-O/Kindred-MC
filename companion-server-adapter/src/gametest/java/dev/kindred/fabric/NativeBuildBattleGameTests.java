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
 * In plain terms: a GameTest: in a real test world, two AI players with empty inventories are entered by an
 * administrator into a build battle with a pyramid theme. The test checks each builds in creative mode on its
 * own plot only, the match ends with a vote won by the better build, both really built a sand pyramid,
 * fireworks go off for the winner, and both leave with nothing in hand and back in survival mode.
 *
 * Technical: A build battle between two AI builders (M-04a). Each arrives with nothing, builds the theme on
 * its own plot in creative, and votes on the other's build when the time is up; the best wins, and both leave
 * as they came, with nothing and in their own game mode.
 */
public final class NativeBuildBattleGameTests implements FabricGameTest {
    private static final int BUILD_TICKS = 1200;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildBattle", tickLimit = 3000)
    public void twoAiBuildersBuildTheThemeInCreativeAndVoteOnEachOther(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2800);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        boolean[] leaked = {false};
        boolean[] rockets = {false};
        String[] heldWhat = {""};
        dev.kindred.core.construction.Pos[] podium = new dev.kindred.core.construction.Pos[1];
        @SuppressWarnings("unchecked") java.util.Map<UUID, dev.kindred.core.construction.Pos>[] spotOf = new java.util.Map[1];
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
                    // The arena round the plots (M-04): a gold mark on the walkway before each plot, the walkway, the lobby pad.
                    var arena = f.runtime.arenaForTest(match[0]);
                    context.assertTrue(arena != null && arena.spots().size() == 2, "An arena with a spot for each build");
                    for (var spot : arena.spots().values()) {
                        context.assertTrue(context.getWorld().getBlockState(new BlockPos(spot.x(), spot.y() - 1, spot.z())).isOf(Blocks.GOLD_BLOCK), "A gold mark at " + spot);
                        context.assertTrue(context.getWorld().getBlockState(new BlockPos(spot.x() - 3, spot.y() - 1, spot.z())).isOf(Blocks.POLISHED_ANDESITE), "The walkway by " + spot);
                    }
                    var pad = arena.lobby();
                    context.assertTrue(context.getWorld().getBlockState(new BlockPos(pad.x(), pad.y() - 1, pad.z())).isOf(Blocks.SMOOTH_QUARTZ), "The lobby pad");
                    podium[0] = arena.podium().getFirst();
                    spotOf[0] = arena.spots();
                })
                .createAndAdd(() -> {
                    // Kept: whether a builder ever held anything outside creative once the vote began. An AI voter keeps
                    // its creative stock for the few ticks its last action takes to settle, still in creative; its hands
                    // are emptied as it leaves creative (a change under an action in flight needs reconciliation).
                    var state = f.runtime.matches().getFirst().state();
                    if (state != MatchRegistry.State.RUNNING && state != MatchRegistry.State.COUNTDOWN) {
                        for (UUID p : players) {
                            var held = player(f, p).getInventory();
                            if (held.isEmpty() || player(f, p).interactionManager.getGameMode() == GameMode.CREATIVE) continue;
                            leaked[0] = true;
                            if (heldWhat[0].isEmpty()) {
                                var what = new StringBuilder(player(f, p).getName().getString() + " in " + state + ":");
                                for (int slot = 0; slot < held.size(); slot++) if (!held.getStack(slot).isEmpty()) what.append(' ').append(slot).append('=').append(held.getStack(slot));
                                heldWhat[0] = what.toString();
                            }
                        }
                    }
                    context.assertTrue(state == MatchRegistry.State.ENDED, "Voted on and decided: " + state + "; " + f.runtime.buildStatus(ids[0]).orElse("")
                            + " / " + f.runtime.buildStatus(ids[1]).orElse(""));
                    // Kept as the round ends: a rocket lives about a second, less than a later step may take to run.
                    var world = context.getWorld();
                    if (!world.getEntitiesByType(EntityType.FIREWORK_ROCKET, new Box(o).expand(48), e -> true).isEmpty()) rockets[0] = true;
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
                    context.assertTrue(!leaked[0], "Nothing held after the build: " + heldWhat[0]);
                    for (UUID p : players) {
                        var player = player(f, p);
                        context.assertTrue(player.getInventory().isEmpty() && player.interactionManager.getGameMode() == GameMode.SURVIVAL,
                                "Left as it came: " + player.getName().getString() + " " + player.interactionManager.getGameMode());
                    }
                    context.assertTrue(rockets[0], "Fireworks for the winner");
                    // The vote as a tour (M-04): each AI voter scored the other's build standing on its gold mark.
                    var tour = f.runtime.tourVotesForTest(match[0]);
                    context.assertTrue(tour.size() == 2 && tour.stream().allMatch(v -> !v.voter().equals(v.builder())), "Each voted on the other's build: " + tour);
                    for (var vote : tour) {
                        var spot = spotOf[0].get(vote.builder());
                        context.assertTrue(vote.stood().getSquaredDistance(spot.x(), spot.y(), spot.z()) <= 9, "Voted from the spot before the build: " + vote);
                    }
                    // The podium: the best first, named on a sign atop a gold step.
                    var names = f.runtime.podiumForTest(match[0]);
                    // First on the podium is a winner (in a tie, either of them).
                    var winnerNames = battle.winners().stream().map(p -> player(f, p).getName().getString()).toList();
                    context.assertTrue(names.size() == 2 && winnerNames.contains(names.getFirst()), "The podium: " + names + ", winners " + winnerNames);
                    var top = new BlockPos(podium[0].x(), podium[0].y(), podium[0].z());
                    context.assertTrue(world.getBlockState(top.down()).isOf(Blocks.GOLD_BLOCK) && world.getBlockState(top).isOf(Blocks.OAK_SIGN), "The first step and its sign");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Designs from the schematic library (M-04, M-05): with a schematic in the operator's library whose name names the
     * round's theme, the AI builders build it, block for block, credited, rather than one of Kindred's own designs. Staged:
     * the fixture saves a small lighthouse with a structure block's own writer into the library, with its license file,
     * and removes it after.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildBattleLibrary", tickLimit = 3000)
    public void aiBuildersBuildTheThemesSchematicFromTheLibrary(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2800);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        String name = "fixture-lighthouse-" + UUID.randomUUID().toString().substring(0, 8);
        java.nio.file.Path[] files = new java.nio.file.Path[2];
        int[] lapis = {0};
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        // Removed however the test ends, so no later round builds it.
        FixtureGround.whenDone(context, () -> {
            try {
                for (var file : files) if (file != null) java.nio.file.Files.deleteIfExists(file);
            } catch (java.io.IOException ignored) {
                // Cleared again at the next run's start.
            }
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -10; x <= 40; x++) for (int z = -4; z <= 26; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A small lighthouse, three by three and five high, banded in lapis, saved as a structure block saves one.
                    BlockPos k = o.add(-8, 2, -3);
                    for (int y = 0; y < 5; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                        boolean wall = x != 1 || z != 1;
                        world.setBlockState(k.add(x, y, z), !wall ? Blocks.AIR.getDefaultState() : y % 2 == 0 ? Blocks.WHITE_CONCRETE.getDefaultState() : Blocks.LAPIS_BLOCK.getDefaultState());
                    }
                    for (BlockPos pos : BlockPos.iterate(k, k.add(2, 4, 2))) if (world.getBlockState(pos).isOf(Blocks.LAPIS_BLOCK)) lapis[0]++;
                    var template = new net.minecraft.structure.StructureTemplate();
                    template.saveFromWorld(world, k, new net.minecraft.util.math.Vec3i(3, 5, 3), false, Blocks.STRUCTURE_VOID);
                    var nbt = template.writeNbt(new net.minecraft.nbt.NbtCompound());
                    for (BlockPos pos : BlockPos.iterate(k, k.add(2, 4, 2))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
                    try {
                        var directory = f.runtime.schematics().directory();
                        java.nio.file.Files.createDirectories(directory);
                        // An earlier run's lighthouse, left by a run that failed before removing it, would be built instead.
                        try (var old = java.nio.file.Files.list(directory)) {
                            for (var file : old.filter(p -> p.getFileName().toString().startsWith("fixture-lighthouse-")).toList()) java.nio.file.Files.deleteIfExists(file);
                        }
                        files[0] = directory.resolve(name + ".nbt");
                        files[1] = directory.resolve(name + ".license");
                        try (var out = java.nio.file.Files.newOutputStream(files[0])) { net.minecraft.nbt.NbtIo.writeCompressed(nbt, out); }
                        java.nio.file.Files.writeString(files[1], "creator=Kindred fixture\nlicense=CC0-1.0\nsource=saved by a structure block in the fixture\n");
                    } catch (java.io.IOException failure) {
                        throw new RuntimeException(failure);
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
                    player(f, players[0]).refreshPositionAndAngles(o.getX() + 9.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    player(f, players[1]).refreshPositionAndAngles(o.getX() + 27.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    for (UUID p : players) player(f, p).getInventory().clear();
                    var operator = f.source.withPosition(net.minecraft.util.math.Vec3d.of(o.add(0, 2, 0)));
                    context.assertTrue(f.runtime.proposeBuildBattle(operator, java.util.List.of(players[0], players[1]), 20 * 60, BuildBattle.Theme.LIGHTHOUSE) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    // The library is read off the game thread; a second's wait for it before the start.
                    context.assertTrue(context.getWorld().getServer().getTicks() % 20 == 0, "A moment for the library");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    String status = f.runtime.buildStatus(ids[0]).orElse("") + " / " + f.runtime.buildStatus(ids[1]).orElse("");
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Building: " + f.runtime.matches().getFirst().state() + "; " + status);
                    context.assertTrue(status.contains(name), "Both build the library's lighthouse: " + status);
                })
                .createAndAdd(() -> {
                    // Each plot holds the lighthouse's lapis band, block for block.
                    var world = context.getWorld();
                    int[] found = new int[2];
                    for (int i = 0; i < 2; i++) {
                        int x0 = 2 + i * (BuildBattle.PLOT + BuildBattle.GAP);
                        for (int x = 0; x < BuildBattle.PLOT; x++) for (int z = 0; z < BuildBattle.PLOT; z++) for (int y = 2; y <= 10; y++) {
                            if (world.getBlockState(o.add(x0 + x, y, 2 + z)).isOf(Blocks.LAPIS_BLOCK)) found[i]++;
                        }
                    }
                    context.assertTrue(found[0] == lapis[0] && found[1] == lapis[0], "The lapis band built on both plots: " + found[0] + " and " + found[1] + " of " + lapis[0]);
                    f.runtime.stopMatch(f.source, match[0]);
                    try {
                        for (var file : files) java.nio.file.Files.deleteIfExists(file);
                    } catch (java.io.IOException failure) {
                        throw new RuntimeException(failure);
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A human builder may build on its own plot's floor (M-04): placing a block against the floor, the target on its plot,
     * is allowed, while the same against another's floor, or outside the plots onto another's, is not. Found on an
     * ordinary client: a human's every block on its own plot was refused, as the floor it clicked was "not on a plot".
     * Staged: a person stands in for a human at an ordinary client.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "buildBattleHuman", tickLimit = 1600)
    public void aHumanBuilderMayPlaceOnItsOwnPlotsFloorOnly(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        UUID[] id = new UUID[1], wren = new UUID[1], match = new UUID[1];
        ServerPlayerEntity[] mira = new ServerPlayerEntity[1];
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -10; x <= 40; x++) for (int z = -4; z <= 26; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Wren");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Wren established");
                    id[0] = statuses.getFirst().id();
                    wren[0] = statuses.getFirst().nativeId();
                })
                .createAndAdd(() -> {
                    player(f, wren[0]).getInventory().clear();
                    mira[0] = MockPlayers.survival(context, "Mira", new BlockPos(1, 2, 1));
                    var operator = f.source.withPosition(net.minecraft.util.math.Vec3d.of(o.add(0, 2, 0)));
                    context.assertTrue(f.runtime.proposeBuildBattle(operator, java.util.List.of(mira[0].getUuid(), wren[0]), 20 * 60, BuildBattle.Theme.COTTAGE) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.joinMatch(mira[0].getCommandSource(), match[0]) == 1, "Mira joined");
                    context.assertTrue(f.runtime.startMatch(mira[0].getCommandSource(), match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Building");
                    // Mira's plot is the first, its floor at y 1 from x 2 and z 2; Wren's the next, eighteen east.
                    BlockPos floor = o.add(8, 1, 8), above = floor.up();
                    BlockPos othersFloor = o.add(8 + BuildBattle.PLOT + BuildBattle.GAP, 1, 8);
                    context.assertTrue(f.runtime.mayPlace(mira[0], floor, above), "On her own plot's floor");
                    context.assertTrue(!f.runtime.mayPlace(mira[0], othersFloor, othersFloor.up()), "Not on Wren's");
                    context.assertTrue(!f.runtime.mayPlace(mira[0], o.add(18, 1, 8), o.add(18, 2, 8)), "Not in the gap between the plots");
                    server(context).getPlayerManager().remove(mira[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static net.minecraft.server.MinecraftServer server(TestContext context) { return context.getWorld().getServer(); }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
