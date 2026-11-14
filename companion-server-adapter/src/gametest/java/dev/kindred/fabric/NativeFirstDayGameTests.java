package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: the first-day live checks of the month run (M-06k), rehearsed natively so the owner's first day only
 * confirms them (M-06k5). Each goes through the same {@code /faction} method a person's or an AI player's request does:
 * - the 80-chunk cap: a faction of one is held to its power, ten chunks; with ten members (power 100) to the cap, 80;
 * - the 48-hour shield: a new faction's exposed border chunk cannot be captured until 48 hours after it was founded;
 * - +1 power an online hour: a player's next point comes as its hour online completes, and never past the maximum.
 * The Nether portal beyond the border and −2 power a PvP death are already native ({@code NativeSafeZoneGameTests},
 * {@code NativeFactionPowerGameTests}), as are border-first and the 30-minute shield ({@code NativeCaptureGameTests}).
 *
 * Technical: M-06e's native fixture with stand-in people (mock survival players, to Kindred people). What each fixture
 * stages, it says: claims written to the World Record directly (only the claims that test the rule go through the
 * command), a defender's power set, the clock moved, and online seconds written as a {@code power.online} event. The
 * coins are each account's starting coins, from the faction world's own step. The border is at its widest, since test
 * positions lie millions of blocks from spawn.
 */
public final class NativeFirstDayGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));

    /** Ten members, power 100, and the 81st chunk refused for the cap; alone, the 11th refused for power. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "firstDayCap", tickLimit = 600)
    public void aFactionHoldsNoMoreLandThanItsPowerAndNeverMoreThanEightyChunks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        List<ServerPlayerEntity> members = new ArrayList<>();
        int[] base = new int[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    for (int i = 0; i < 10; i++) members.add(MockPlayers.survival(context, "Cap" + i, new BlockPos(2, 2, 2)));
                    f.runtime.factionsTickForTest(); // Each account's starting coins, as for anyone first seen in a faction world.
                    var abs = context.getAbsolutePos(new BlockPos(2, 2, 2));
                    base[0] = abs.getX() >> 4;
                    base[1] = abs.getZ() >> 4;
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Starting coins written");
                    context.assertTrue(f.runtime.faction(f.source, members.getFirst().getUuid(), "create", List.of("Red", "RED")).accepted(), "Cap0 founds Red");
                })
                .createAndAdd(() -> {
                    // Staged: nine claims in a 9-wide grid written to the Record; the tenth and eleventh are asked for.
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    for (int i = 0; i < 9; i++) stageClaim(f, red, base, i);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Nine claims written");
                    var leader = members.getFirst();
                    var tenth = claimAt(f, leader, base, 9);
                    context.assertTrue(tenth.accepted() && tenth.lines().getFirst().endsWith("(10 / 10)"), "The tenth claimed by power 10: " + tenth.lines());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The tenth written");
                    var eleventh = claimAt(f, members.getFirst(), base, 10);
                    context.assertTrue(!eleventh.accepted() && eleventh.lines().getFirst().equals("Over capacity: 10 claimed of 10 (power 10, cap 80)"),
                            "Alone, the eleventh refused for power: " + eleventh.lines());
                    for (int i = 1; i < 10; i++) {
                        var name = members.get(i).getName().getString();
                        context.assertTrue(f.runtime.faction(f.source, members.getFirst().getUuid(), "invite", List.of(name)).accepted(), "Invited " + name);
                        context.assertTrue(f.runtime.faction(f.source, members.get(i).getUuid(), "join", List.of("Red")).accepted(), name + " joins Red");
                    }
                })
                .createAndAdd(() -> {
                    var red = f.runtime.factions().book().named("Red").orElseThrow();
                    context.assertTrue(red.members().size() == 10, "Red has ten members: " + red.members().size());
                    int power = PowerRules.factionPower(f.runtime.factions().book(), f.runtime.factions().powers(), red.id(), WIDE);
                    context.assertTrue(power == 100 && PowerRules.capacity(power, WIDE) == 80, "Power 100, capacity the cap, 80: " + power);
                    // Staged: claims 11 to 79 written; the 80th asked for.
                    for (int i = 10; i < 79; i++) stageClaim(f, red.id().toString(), base, i);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claims to 79 written");
                    var eightieth = claimAt(f, members.getFirst(), base, 79);
                    context.assertTrue(eightieth.accepted() && eightieth.lines().getFirst().endsWith("(80 / 80)"), "The 80th claimed: " + eightieth.lines());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The 80th written");
                    var leader = members.getFirst();
                    var over = claimAt(f, leader, base, 80);
                    context.assertTrue(!over.accepted() && over.lines().getFirst().equals("Over capacity: 80 claimed of 80 (power 100, cap 80)"),
                            "The 81st refused for the cap: " + over.lines());
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    long held = f.runtime.projected().claims().values().stream().filter(red::equals).count();
                    context.assertTrue(held == 80, "Red holds 80: " + held);
                    context.assertTrue(f.runtime.projected().claims().get(chunk(base, 80)) == null, "The 81st chunk nobody's");
                    long coins = f.runtime.projected().coins().of(leader.getUuid());
                    context.assertTrue(coins == 30_000, "Two claims paid, 100 coins each, nothing for the refusals: " + coins);
                })
                .completeIfSuccessful();
    }

    /** Blue, overextended from its founding, cannot lose a chunk until 48 hours after it; then Red takes it. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "firstDayShield", tickLimit = 600)
    public void aNewFactionsExposedBorderIsShieldedForFortyEightHoursAfterItsFounding(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        int[] t = new int[2];
        BlockPos stand = new BlockPos(2, 2, 2);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(WIDE);
                    p[0] = MockPlayers.survival(context, "Shi", stand);
                    p[1] = MockPlayers.survival(context, "Bea", stand);
                    f.runtime.factionsTickForTest(); // Starting coins.
                    f.runtime.factions().clockForTest(0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Starting coins written");
                    context.assertTrue(f.runtime.faction(f.source, p[0].getUuid(), "create", List.of("Red", "RED")).accepted(), "Shi founds Red");
                    context.assertTrue(f.runtime.faction(f.source, p[1].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Bea founds Blue, now");
                })
                .createAndAdd(() -> {
                    var abs = context.getAbsolutePos(stand);
                    t[0] = abs.getX() >> 4;
                    t[1] = abs.getZ() >> 4;
                    String blue = f.runtime.projected().factions().named("Blue").orElseThrow().id().toString();
                    // Staged: Blue's plus of five chunks, the one Shi stands in on its west arm, and Bea's power 1.
                    for (int[] c : new int[][] {{0, 0}, {1, 0}, {2, 0}, {1, 1}, {1, -1}}) {
                        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, ClaimLedger.chunk(W, t[0] + c[0], t[1] + c[1]), Map.of()));
                    }
                    f.runtime.record(PowerRules.set(p[1].getUuid(), 1, null, WIDE, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Blue's land and power written");
                    var fresh = f.runtime.faction(f.source, p[0].getUuid(), "capture", List.of());
                    context.assertTrue(!fresh.accepted() && fresh.lines().getFirst().startsWith("Blue is a new faction, shielded for 28"),
                            "Founded just now: about 2,880 minutes left: " + fresh.lines());
                    // Staged: the clock 47 h 58 min on.
                    f.runtime.factions().clockForTest((47 * 60 + 58) * 60_000L);
                    var nearly = f.runtime.faction(f.source, p[0].getUuid(), "capture", List.of());
                    context.assertTrue(!nearly.accepted() && nearly.lines().getFirst().startsWith("Blue is a new faction, shielded for "),
                            "Two minutes short, still shielded: " + nearly.lines());
                    String blue = f.runtime.projected().factions().named("Blue").orElseThrow().id().toString();
                    context.assertTrue(blue.equals(f.runtime.projected().claims().get(ClaimLedger.chunk(W, t[0], t[1]))), "Still Blue's");
                    // Staged: 48 h 1 min on.
                    f.runtime.factions().clockForTest((48 * 60 + 1) * 60_000L);
                    var taken = f.runtime.faction(f.source, p[0].getUuid(), "capture", List.of());
                    context.assertTrue(taken.accepted(), "Past 48 hours the border chunk taken: " + taken.lines());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The capture written");
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    context.assertTrue(red.equals(f.runtime.projected().claims().get(ClaimLedger.chunk(W, t[0], t[1]))), "The chunk is Red's");
                    f.runtime.factions().clockForTest(0);
                })
                .completeIfSuccessful();
    }

    /**
     * Staged to four seconds short of the hour (online seconds written as a {@code power.online} event), then online in
     * real time: Pia's ninth point comes as the hour completes; Max, at the maximum, gains nothing.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "firstDayPower", tickLimit = 6000)
    public void anHourOnlineGivesAPointOfPowerAndNeverPastTheMaximum(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 5900);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        long[] since = new long[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    p[0] = MockPlayers.survival(context, "Pia", new BlockPos(2, 2, 2));
                    p[1] = MockPlayers.survival(context, "Max", new BlockPos(3, 2, 2));
                    f.runtime.record(PowerRules.set(p[0].getUuid(), 8, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Pia's power set to 8");
                    var powers = f.runtime.factions().powers();
                    for (var player : p) {
                        f.runtime.record(PowerRules.online(powers, player.getUuid(), 3_596, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                    }
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "3,596 s online written");
                    var pia = f.runtime.factions().powers().of(p[0].getUuid(), FactionSettings.DEFAULTS);
                    var max = f.runtime.factions().powers().of(p[1].getUuid(), FactionSettings.DEFAULTS);
                    context.assertTrue(pia.power() == 8 && pia.progressSeconds() == 3_596, "Pia at 8, four seconds short of 9: " + pia);
                    context.assertTrue(max.power() == 10 && max.progressSeconds() == 0, "Max at the maximum, nothing kept toward more: " + max);
                    f.runtime.factionsTickForTest(); // Both counted online from now, by the wall clock.
                    since[0] = System.currentTimeMillis();
                })
                .createAndAdd(() -> {
                    // The faction world's once-a-second step, as the server runs it, until five seconds have passed (the
                    // test server runs its ticks faster than real time, so this waits on the wall clock, not a tick count).
                    f.runtime.factionsTickForTest();
                    context.assertTrue(System.currentTimeMillis() - since[0] >= 5_000 && f.runtime.factions().pendingSeconds(p[0].getUuid()) >= 5,
                            "Five seconds online: " + f.runtime.factions().pendingSeconds(p[0].getUuid()));
                })
                .createAndAdd(() -> f.runtime.stop()) // The stop writes each player's online time, as at a real stop.
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.shutdownComplete(), "Stopped");
                    var pia = f.runtime.factions().powers().of(p[0].getUuid(), FactionSettings.DEFAULTS);
                    var max = f.runtime.factions().powers().of(p[1].getUuid(), FactionSettings.DEFAULTS);
                    context.assertTrue(pia.power() == 9 && pia.progressSeconds() >= 1 && pia.progressSeconds() < 60,
                            "The hour completed: Pia 8 -> 9, the seconds past it kept toward 10: " + pia);
                    context.assertTrue(max.power() == 10 && max.progressSeconds() == 0, "Max still 10: " + max);
                })
                .completeIfSuccessful();
    }

    private static String chunk(int[] base, int cell) {
        return ClaimLedger.chunk(W, base[0] + cell % 9, base[1] + cell / 9);
    }

    private static void stageClaim(NativePaidSkillsGameTests.Fixture f, String faction, int[] base, int cell) {
        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, faction, chunk(base, cell), Map.of()));
    }

    /** {@code leader} stood in the cell's chunk asks {@code /faction claim}, then stood back. */
    private static FactionsService.Reply claimAt(NativePaidSkillsGameTests.Fixture f, ServerPlayerEntity leader, int[] base, int cell) {
        var home = leader.getPos();
        leader.refreshPositionAndAngles((base[0] + cell % 9) * 16 + 8, home.y, (base[1] + cell / 9) * 16 + 8, 0, 0);
        try {
            return f.runtime.faction(f.source, leader.getUuid(), "claim", List.of());
        } finally {
            leader.refreshPositionAndAngles(home.x, home.y, home.z, 0, 0);
        }
    }
}
