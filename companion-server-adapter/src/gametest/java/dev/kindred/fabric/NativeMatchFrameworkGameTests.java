package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.BuildBattle;
import dev.kindred.core.match.GameModes;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.UUID;

/**
 * In plain terms: GameTests for the match framework (M-00) in a real test world. A person (a stand-in for someone
 * at an ordinary client) is invited to a duel with an AI player: nobody can enter them into it but themselves, the
 * AI player cannot strike them until they have joined and it runs, and they see the countdown, the sidebar and
 * their result, as does someone watching nearby; their forfeit ends it at once. An operator's stop takes back
 * every rule a match gave in the same tick: the leave to strike, and a build battle's creative mode. A duel whose
 * time runs out is a draw, and one a player leaves before its start is called off. A stopped manhunt takes its
 * compass back at once, and a player is in one match at a time. The world's mode sets the AI
 * players' pace and which matches may be played, and is kept across a restart.
 *
 * Technical: M-00's lifecycle and rules in native fixtures: lobby, join, countdown, running, forfeit, stop, time-out
 * and call-off; D7 for a human (a stand-in player with no runtime entry); revocation on stop checked in the same
 * step as the stop; the world mode's playstyle mapping, match gating and save.
 */
public final class NativeMatchFrameworkGameTests implements FabricGameTest {
    private static final String REFUSED = "No running match allows striking that player";

    /**
     * A human joins a duel only for itself (D7): proposed, the AI player is entered by its administrator, the human
     * not; the console cannot join for it; the AI player's strike is refused until the human has joined and it runs.
     * Then the human is struck, sees the countdown, the fight and its defeat on forfeiting, and an onlooker nearby sees
     * the bar, the sidebar and who won. The forfeit takes the leave to strike away in the same step.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchHuman", tickLimit = 2000)
    public void aHumanJoinsADuelOnlyForItselfAndIsStruckOnlyOnceItRuns(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1800);
        UUID[] id = new UUID[1], ada = new UUID[1], match = new UUID[1];
        ServerPlayerEntity[] people = new ServerPlayerEntity[3];
        float[] health = new float[1];
        long[] at = {0};
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 12; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 12) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    for (int x = 0; x < 8; x++) for (int z = 8; z < 12; z++) context.setBlockState(x, 1, z, Blocks.STONE);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Ada established");
                    id[0] = statuses.getFirst().id();
                    ada[0] = statuses.getFirst().nativeId();
                })
                .createAndAdd(() -> {
                    var a = player(f, ada[0]);
                    context.assertTrue(a.isOnGround(), "Ada standing");
                    a.getInventory().setStack(0, new ItemStack(Items.WOODEN_SWORD));
                    a.getInventory().selectedSlot = 0;
                    BlockPos p = context.getAbsolutePos(new BlockPos(2, 2, 3));
                    a.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, -90, 0);
                    // Staged: two people stand in for humans at ordinary clients; no runtime entry is theirs.
                    people[0] = MockPlayers.survival(context, "Mira", new BlockPos(5, 2, 3));
                    people[1] = MockPlayers.survival(context, "Owen", new BlockPos(4, 2, 10));
                    // Staged: a bystander in no match within sight, as another test's player stood beside this one in CI
                    // (suite of 2026-11-05 on f2791ea): an onlooker as much as Owen.
                    people[2] = MockPlayers.survival(context, "Pip", new BlockPos(6, 2, 10));
                    server.setPvpEnabled(true);
                    // Before any match, a strike at the human is refused.
                    context.assertTrue(f.runtime.nativeFight(f.source, id[0], people[0].getUuid(), 0) == 1, "Fight requested before any match");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatusLine(id[0]).contains(REFUSED), "Refused before a match: " + f.runtime.nativeStatusLine(id[0]));
                    context.assertTrue(people[0].getHealth() == 20, "Mira unhurt");
                })
                .createAndAdd(() -> {
                    context.assertTrue(settled(f, id[0]), "Ada settled: " + f.runtime.nativeStatusLine(id[0]));
                    context.assertTrue(f.runtime.proposeDuel(f.source, ada[0], people[0].getUuid()) == 1, "Duel proposed");
                    var proposed = f.runtime.matches().getFirst();
                    match[0] = proposed.id();
                    // The proposer enters the AI player, never the human.
                    context.assertTrue(proposed.joined().equals(java.util.Set.of(ada[0])), "Only Ada entered: " + proposed.joined());
                    context.assertTrue(f.runtime.joinMatch(f.source, match[0]) == 0, "The console cannot join for anyone");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 0, "Not started before Mira joins");
                    context.assertTrue(f.runtime.nativeFight(f.source, id[0], people[0].getUuid(), 0) == 1, "Fight requested in the lobby");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatusLine(id[0]).contains(REFUSED), "Refused in the lobby: " + f.runtime.nativeStatusLine(id[0]));
                    context.assertTrue(people[0].getHealth() == 20, "Mira unhurt in the lobby");
                    context.assertTrue(f.runtime.presenter().sidebarLines(people[0].getUuid()).isEmpty()
                            || f.runtime.presenter().sidebarLines(people[0].getUuid()).getFirst().equals("Joined: 1 of 2"), "The lobby's sidebar: " + f.runtime.presenter().sidebarLines(people[0].getUuid()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(settled(f, id[0]), "Ada settled again: " + f.runtime.nativeStatusLine(id[0]));
                    // Mira joins for herself, and starts it as a player in it.
                    context.assertTrue(f.runtime.joinMatch(people[0].getCommandSource(), match[0]) == 1, "Mira joined");
                    context.assertTrue(f.runtime.startMatch(people[0].getCommandSource(), match[0]) == 1, "Mira started it");
                })
                .createAndAdd(() -> {
                    var duel = f.runtime.matches().getFirst();
                    context.assertTrue(duel.state() == MatchRegistry.State.RUNNING, "Running: " + duel.state());
                    var presenter = f.runtime.presenter();
                    var lines = presenter.sidebarLines(people[0].getUuid());
                    context.assertTrue(lines.size() == 3 && lines.get(0).startsWith("Time left: ") && lines.contains("Ada") && lines.contains("Mira"),
                            "Mira's sidebar: " + lines);
                    // Everyone near in no match looks on, Owen and the bystander alike; neither player in it does.
                    var onlookers = presenter.onlookers(match[0]);
                    context.assertTrue(onlookers.containsAll(List.of(people[1].getUuid(), people[2].getUuid()))
                            && !onlookers.contains(ada[0]) && !onlookers.contains(people[0].getUuid()), "Owen and Pip watching: " + onlookers);
                    context.assertTrue(presenter.sidebarLines(people[1].getUuid()).equals(lines), "Owen's sidebar: " + presenter.sidebarLines(people[1].getUuid()));
                    context.assertTrue(presenter.barPlayers(match[0]).containsAll(List.of(ada[0], people[0].getUuid(), people[1].getUuid())),
                            "The bar to all three: " + presenter.barPlayers(match[0]));
                })
                .createAndAdd(() -> {
                    // Struck once it runs; then Mira forfeits, and the leave to strike is gone in the same step.
                    context.assertTrue(people[0].getHealth() < 20, "Mira struck in the duel: " + people[0].getHealth());
                    context.assertTrue(f.runtime.mayAttackForTest(ada[0], people[0].getUuid()), "Ada may strike while it runs");
                    context.assertTrue(f.runtime.stopMatch(people[0].getCommandSource(), match[0]) == 1, "Mira forfeits");
                    var duel = f.runtime.matches().getFirst();
                    context.assertTrue(duel.state() == MatchRegistry.State.ENDED && "forfeited".equals(duel.result()) && ada[0].equals(duel.winner()),
                            "Ada won by forfeit: " + duel.state() + " " + duel.result());
                    context.assertTrue(!f.runtime.mayAttackForTest(ada[0], people[0].getUuid()), "No leave to strike once it ends");
                    context.assertTrue(f.runtime.presenter().barPlayers(match[0]).isEmpty(), "The bar taken down at once");
                    health[0] = people[0].getHealth();
                    at[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    context.assertTrue(server.getTicks() - at[0] >= 40, "Two seconds on");
                    context.assertTrue(people[0].getHealth() >= health[0], "Mira not struck after the end: " + people[0].getHealth() + " from " + health[0]);
                    var presenter = f.runtime.presenter();
                    context.assertTrue(presenter.shown(people[0].getUuid()).equals(List.of("3", "2", "1", "Fight!", "Defeat")), "Mira shown " + presenter.shown(people[0].getUuid()));
                    context.assertTrue(presenter.shown(ada[0]).getLast().equals("Victory"), "Ada shown " + presenter.shown(ada[0]));
                    // F-34c: Ada, who has no screen, was told the same titles by the server, to her alone; no countdown seconds.
                    var told = f.runtime.wire().inbox(id[0], dev.kindred.core.wire.MatchTitles.KIND).reversed().stream()
                            .map(t -> t.message().facts().get("title").value()).toList();
                    context.assertTrue(told.equals(List.of("Fight!", "Victory")), "Ada told " + told);
                    context.assertTrue(f.runtime.wire().inbox(id[0], dev.kindred.core.wire.MatchTitles.KIND).stream()
                            .allMatch(t -> t.message().fromServer() && t.message().scope().key().equals(id[0].toString())), "From the server, to her alone");
                    context.assertTrue(presenter.shown(people[1].getUuid()).equals(List.of("Ada wins")), "Owen shown " + presenter.shown(people[1].getUuid()));
                    context.assertTrue(presenter.bursts(ada[0]).equals(List.of("start", "win")) && presenter.bursts(people[0].getUuid()).equals(List.of("start")),
                            "Particles: Ada " + presenter.bursts(ada[0]) + ", Mira " + presenter.bursts(people[0].getUuid()));
                    context.assertTrue(presenter.sidebarLines(people[0].getUuid()).isEmpty() && presenter.sidebarLines(people[1].getUuid()).isEmpty(), "The sidebars taken down");
                    for (var person : people) server.getPlayerManager().remove(person);
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A player in a match of its own is not an onlooker of another nearby: its sidebar is its own match's, and the other's
     * result is not shown to it (found on an ordinary client in a live manhunt, where a runner's sidebar showed the
     * other hunt beside it).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchTwo", tickLimit = 1600)
    public void aPlayerInItsOwnMatchSeesItsOwnSidebarNotANearbyMatchs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        UUID[] ids = new UUID[3], players = new UUID[3], match = new UUID[2];
        ServerPlayerEntity[] mira = new ServerPlayerEntity[1];
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 2, "Bram allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cy"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 3 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "All three established");
                    for (var status : statuses) {
                        String name = player(f, status.nativeId()).getName().getString();
                        int i = name.equals("Ada") ? 0 : name.equals("Bram") ? 1 : 2;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    for (UUID id : ids) f.runtime.meleeStyle(id, "idle");
                    server.setPvpEnabled(true);
                    // Staged: a person stands in for a human at an ordinary client, beside the first duel.
                    mira[0] = MockPlayers.survival(context, "Mira", new BlockPos(3, 2, 5));
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 1, "Ada and Bram's duel proposed first");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[2], mira[0].getUuid()) == 1, "Cy and Mira's duel proposed");
                    match[1] = f.runtime.matches().getLast().id();
                    context.assertTrue(f.runtime.joinMatch(mira[0].getCommandSource(), match[1]) == 1, "Mira joined her own");
                    context.assertTrue(f.runtime.startMatch(mira[0].getCommandSource(), match[1]) == 1, "Mira started her own");
                })
                .createAndAdd(() -> {
                    var presenter = f.runtime.presenter();
                    var lines = presenter.sidebarLines(mira[0].getUuid());
                    context.assertTrue(f.runtime.matches().stream().allMatch(m -> m.state() == MatchRegistry.State.RUNNING), "Both running");
                    context.assertTrue(lines.contains("Cy") && lines.contains("Mira"), "Mira's sidebar is her own duel's: " + lines);
                    context.assertTrue(!presenter.onlookers(match[0]).contains(mira[0].getUuid()), "Mira is no onlooker of the other: " + presenter.onlookers(match[0]));
                    f.runtime.stopMatch(f.source, match[0]);
                    f.runtime.stopMatch(f.source, match[1]);
                })
                .createAndAdd(() -> {
                    var shown = f.runtime.presenter().shown(mira[0].getUuid());
                    context.assertTrue(!shown.isEmpty() && shown.getLast().equals("Stopped") && !shown.contains("Ada wins") && shown.stream().filter("Stopped"::equals).count() == 1,
                            "Mira shown her own result only: " + shown);
                    server.getPlayerManager().remove(mira[0]);
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * An operator's stop takes back every rule the match gave, in the same tick: mid-fight, the leave to strike and the
     * bar; and no strike lands after it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchStop", tickLimit = 1800)
    public void anOperatorsStopTakesBackTheLeaveToStrikeAtOnce(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1600);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        float[] health = new float[2];
        long[] at = {0};
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++) {
                        if (x > -1 && x < 8 && z > -1 && z < 8) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> both(context, f, ids, players))
                .createAndAdd(() -> {
                    for (int i = 0; i < 2; i++) {
                        var p = player(f, players[i]);
                        p.getInventory().setStack(0, new ItemStack(Items.WOODEN_SWORD));
                        p.getInventory().selectedSlot = 0;
                    }
                    BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 3)), b = context.getAbsolutePos(new BlockPos(5, 2, 3));
                    player(f, players[0]).refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, -90, 0);
                    player(f, players[1]).refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 90, 0);
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 1, "Duel proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    // Mid-fight: a strike has landed on one of them.
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(player(f, players[0]).getHealth() < 20 || player(f, players[1]).getHealth() < 20, "A strike landed");
                    context.assertTrue(f.runtime.mayAttackForTest(players[0], players[1]), "Leave to strike while it runs");
                    context.assertTrue(f.runtime.stopMatch(f.source, match[0]) == 1, "Stopped by the operator");
                    // In the same tick: ended, no leave to strike either way, the bar down.
                    var duel = f.runtime.matches().getFirst();
                    context.assertTrue(duel.state() == MatchRegistry.State.ENDED && "Stopped".equals(duel.result()) && duel.winners().isEmpty(),
                            "Stopped with no winner: " + duel.state() + " " + duel.result());
                    context.assertTrue(!f.runtime.mayAttackForTest(players[0], players[1]) && !f.runtime.mayAttackForTest(players[1], players[0]),
                            "No leave to strike in the stop's own tick");
                    context.assertTrue(f.runtime.presenter().barPlayers(match[0]).isEmpty(), "The bar down in the stop's own tick");
                    health[0] = player(f, players[0]).getHealth();
                    health[1] = player(f, players[1]).getHealth();
                    at[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    context.assertTrue(server.getTicks() - at[0] >= 60, "Three seconds on");
                    context.assertTrue(player(f, players[0]).getHealth() >= health[0] && player(f, players[1]).getHealth() >= health[1],
                            "No strike after the stop: Ada " + player(f, players[0]).getHealth() + ", Bram " + player(f, players[1]).getHealth());
                    var presenter = f.runtime.presenter();
                    context.assertTrue(presenter.shown(players[0]).getLast().equals("Stopped") && presenter.shown(players[1]).getLast().equals("Stopped"),
                            "Both shown the stop: " + presenter.shown(players[0]) + " " + presenter.shown(players[1]));
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A build battle stopped mid-build takes back what it gave in the same tick: each builder out of creative, its
     * creative blocks cleared and its build stopped. The plots are then ordinary ground under the world's own rules.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchStopBuild", tickLimit = 1600)
    public void aStoppedBuildBattleTakesBackCreativeAtOnce(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        boolean[] stopped = {false};
        String[] atStop = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (int x = -4; x <= 40; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Wren");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Wren allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pip"))
                .createAndAdd(() -> both(context, f, ids, players))
                .createAndAdd(() -> {
                    player(f, players[0]).refreshPositionAndAngles(o.getX() + 9.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    player(f, players[1]).refreshPositionAndAngles(o.getX() + 27.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                    for (UUID p : players) player(f, p).getInventory().clear();
                    var operator = f.source.withPosition(net.minecraft.util.math.Vec3d.of(o.add(0, 2, 0)));
                    context.assertTrue(f.runtime.proposeBuildBattle(operator, List.of(players[0], players[1]), 20 * 120, BuildBattle.Theme.PYRAMID) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    var wren = player(f, players[0]);
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Building");
                    context.assertTrue(wren.interactionManager.getGameMode() == GameMode.CREATIVE && f.runtime.mayEditAt(wren, o.add(9, 3, 9)), "Wren in creative on its plot");
                    context.assertTrue(!wren.getInventory().isEmpty(), "Stocked from creative");
                    stopped[0] = f.runtime.stopMatch(f.source, match[0]) == 1;
                    // In the same tick: both back in survival with nothing, their builds stopped. What was seen is
                    // kept and checked in the next step, since a failed step is tried again from its start.
                    var seen = new StringBuilder();
                    for (UUID p : players) {
                        var builder = player(f, p);
                        seen.append(builder.getName().getString()).append(' ').append(builder.interactionManager.getGameMode())
                                .append(builder.getInventory().isEmpty() ? " empty; " : " carrying; ");
                    }
                    seen.append(f.runtime.buildStatus(ids[0]).isPresent() || f.runtime.buildStatus(ids[1]).isPresent() ? "still building" : "builds stopped");
                    atStop[0] = seen.toString();
                })
                .createAndAdd(() -> {
                    context.assertTrue(stopped[0], "Stopped by the operator");
                    context.assertTrue(atStop[0].equals("Wren SURVIVAL empty; Pip SURVIVAL empty; builds stopped"), "In the stop's own tick: " + atStop[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A manhunt stopped mid-hunt takes back the one item it handed out, in the same tick: the hunter's tracking compass
     * (D6), and with it the leave to strike across the sides.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchStopHunt", tickLimit = 1600)
    public void aStoppedManhuntTakesBackTheCompassAtOnce(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        String[] atStop = {""};
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> both(context, f, ids, players))
                .createAndAdd(() -> {
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                    for (UUID p : players) player(f, p).getInventory().clear();
                    // Bram runs, Ada hunts.
                    context.assertTrue(f.runtime.proposeManhunt(f.source, List.of(players[1]), List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    // One match at a time a player: neither can be put in another while this one stands.
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 0 && f.runtime.matches().size() == 1, "No second match");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Hunting");
                    context.assertTrue(compass(player(f, players[0]), match[0]) != null, "Ada handed the compass");
                    context.assertTrue(compass(player(f, players[1]), match[0]) == null, "The runner has none");
                    context.assertTrue(f.runtime.mayAttackForTest(players[0], players[1]), "Leave to strike across the sides");
                    boolean stopped = f.runtime.stopMatch(f.source, match[0]) == 1;
                    // What the stop's own tick shows, checked in the next step (a failed step is tried again from its start).
                    atStop[0] = stopped + " " + (compass(player(f, players[0]), match[0]) == null ? "compass gone" : "compass kept")
                            + " " + (f.runtime.mayAttackForTest(players[0], players[1]) ? "may strike" : "no strike");
                })
                .createAndAdd(() -> {
                    context.assertTrue(atStop[0].equals("true compass gone no strike"), "In the stop's own tick: " + atStop[0]);
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ItemStack compass(ServerPlayerEntity player, UUID match) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) if (HuntCompass.of(inventory.getStack(slot), match)) return inventory.getStack(slot);
        return null;
    }

    /**
     * A duel whose own time runs out is a draw, shown to both; a duel a player leaves before its start is called off,
     * with no winner. Both players here stand rather than fight (a declared opponent's style), so the clock decides.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchDraw", tickLimit = 2000)
    public void aDuelOutOfTimeIsADrawAndOneLeftBeforeItsStartIsCalledOff(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1800);
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[2];
        long[] started = {0};
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> both(context, f, ids, players))
                .createAndAdd(() -> {
                    f.runtime.meleeStyle(ids[0], "idle");
                    f.runtime.meleeStyle(ids[1], "idle");
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1], java.util.Set.of(), 20) == 0, "A second's duel refused");
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1], java.util.Set.of(), 20 * 30) == 1, "A thirty-second duel proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                    started[0] = server.getTicks();
                })
                .createAndAdd(() -> {
                    var duel = f.runtime.matches().getFirst();
                    context.assertTrue(duel.state() == MatchRegistry.State.ENDED, "Over: " + duel.state());
                    long took = server.getTicks() - started[0];
                    context.assertTrue("Time ran out: a draw".equals(duel.result()) && duel.winners().isEmpty(), "A draw: " + duel.result());
                    context.assertTrue(took >= MatchRegistry.COUNTDOWN_TICKS + 20 * 30 && took <= MatchRegistry.COUNTDOWN_TICKS + 20 * 30 + 5, "At its time: " + took);
                    context.assertTrue(f.runtime.presenter().shown(players[0]).getLast().equals("Draw") && f.runtime.presenter().shown(players[1]).getLast().equals("Draw"),
                            "Both shown the draw");
                    // Another, which Bram leaves before it starts: called off.
                    context.assertTrue(f.runtime.proposeDuel(f.source, players[0], players[1]) == 1, "Another proposed");
                    match[1] = f.runtime.matches().getLast().id();
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "disconnect", ids[1]) == 1, "Bram taken out before the start");
                })
                .createAndAdd(() -> {
                    var duel = f.runtime.matches().stream().filter(m -> m.id().equals(match[1])).findFirst().orElseThrow();
                    context.assertTrue(duel.state() == MatchRegistry.State.ENDED && duel.result().equals("A player left before the start") && duel.winners().isEmpty(),
                            "Called off: " + duel.state() + " " + duel.result());
                    context.assertTrue(f.runtime.presenter().shown(players[0]).getLast().equals("Called off"), "Ada shown " + f.runtime.presenter().shown(players[0]));
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The world's mode (M-00): chosen by an operator only; each AI player's playstyle follows it; only its matches and
     * duels may be proposed in it; and it is kept across a restart of the runtime.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "matchWorldMode", tickLimit = 1600)
    public void theWorldsModeSetsThePaceAndWhichMatchesMayBePlayed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1400);
        UUID[] ids = new UUID[2], players = new UUID[2];
        PlayerRuntime[] again = new PlayerRuntime[1];
        String[] gate = new String[6];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> both(context, f, ids, players))
                .createAndAdd(() -> {
                    server.setPvpEnabled(true);
                    context.assertTrue(f.runtime.worldMode() == null, "No mode chosen at first");
                    // Staged: a person with no operator rights stands in for a player at an ordinary client.
                    person[0] = MockPlayers.survival(context, "Nell", new BlockPos(1, 2, 1));
                    context.assertTrue(f.runtime.setWorldMode(person[0].getCommandSource(), GameModes.WorldMode.SPEEDRUN) == 0 && f.runtime.worldMode() == null,
                            "Not chosen by a player without operator rights");
                    // Factions is an operator's choice too (its own fixture: NativeFactionsModeGameTests).
                    context.assertTrue(f.runtime.setWorldMode(person[0].getCommandSource(), GameModes.WorldMode.FACTIONS) == 0 && f.runtime.worldMode() == null,
                            "Factions not chosen by a player without operator rights");
                    // Every other condition of each match met (fresh starts, edits, the dragon fight's leave, PvP), so
                    // the world's mode is the only reason left to refuse one.
                    f.runtime.setDragonFightForTest(true);
                    for (UUID p : players) player(f, p).getInventory().clear();
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.SPEEDRUN) == 1, "Speedrun chosen");
                    for (UUID id : ids) context.assertTrue(bot(f, id).playstyle().equals("SPEEDRUN_ANY_PERCENT"), "At speedrun pace: " + bot(f, id).playstyle());
                    gate[0] = f.runtime.proposeManhunt(f.source, List.of(players[0]), List.of(players[1])) + "," + f.runtime.matches().size();
                    gate[1] = String.valueOf(f.runtime.proposeSpeedrun(f.source, List.of(players[0])));
                    f.runtime.matches().stream().filter(m -> m.state() != MatchRegistry.State.ENDED).forEach(m -> f.runtime.stopMatch(f.source, m.id()));
                    gate[2] = String.valueOf(f.runtime.proposeDuel(f.source, players[0], players[1]));
                    f.runtime.matches().stream().filter(m -> m.state() != MatchRegistry.State.ENDED).forEach(m -> f.runtime.stopMatch(f.source, m.id()));
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.MANHUNT) == 1, "Manhunt chosen");
                    gate[3] = String.valueOf(f.runtime.proposeSpeedrun(f.source, List.of(players[0])));
                    gate[4] = String.valueOf(f.runtime.proposeManhunt(f.source, List.of(players[0]), List.of(players[1])));
                    f.runtime.matches().stream().filter(m -> m.state() != MatchRegistry.State.ENDED).forEach(m -> f.runtime.stopMatch(f.source, m.id()));
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.BUILDER) == 1, "Builder chosen");
                    for (UUID id : ids) context.assertTrue(bot(f, id).playstyle().equals("IDLE_OBSERVING"), "Watching in a builder world: " + bot(f, id).playstyle());
                    gate[5] = String.valueOf(f.runtime.proposeSpeedrun(f.source, List.of(players[0])));
                })
                .createAndAdd(() -> {
                    // In a speedrun world: no manhunt, a race, a duel; in a manhunt world: no race, a manhunt; in a
                    // builder world: no race.
                    context.assertTrue(java.util.Arrays.equals(gate, new String[]{"0,0", "1", "1", "0", "1", "0"}), "Proposals by mode: " + java.util.Arrays.toString(gate));
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
                    context.assertTrue(saved.contains("mode=builder"), "Saved: " + saved);
                    server.getPlayerManager().remove(person[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                    again[0] = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer);
                    again[0].start();
                })
                .createAndAdd(() -> {
                    context.assertTrue(again[0].isReady(), "Restarted");
                    context.assertTrue(again[0].worldMode() == GameModes.WorldMode.BUILDER, "Kept across the restart: " + again[0].worldMode());
                    server.setPvpEnabled(pvp);
                    again[0].stop();
                })
                .createAndAdd(() -> context.assertTrue(again[0].shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void both(TestContext context, NativePaidSkillsGameTests.Fixture f, UUID[] ids, UUID[] players) {
        var statuses = f.runtime.nativeStatuses(Actor.console());
        context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                "Both established");
        for (var status : statuses) {
            String name = player(f, status.nativeId()).getName().getString();
            int i = name.equals("Ada") || name.equals("Wren") ? 0 : 1;
            ids[i] = status.id();
            players[i] = status.nativeId();
        }
    }

    private static PlayerRuntime.PanelBot bot(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    private static boolean settled(NativePaidSkillsGameTests.Fixture f, UUID id) {
        String line = f.runtime.nativeStatusLine(id);
        return line.contains("busy=false") && line.contains("workPending=false") && line.contains("actionPending=false")
                && line.contains("activity=IDLE") && line.contains("flushes=true/true") && line.contains("cancelling=false");
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
