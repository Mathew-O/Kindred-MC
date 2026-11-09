package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.SafeZones;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * In plain terms: GameTests of a faction world's safe zones and border: two people of enemy factions cannot hurt each
 * other in a safe zone (the striker is told why) and can just outside it; a claim 400 blocks from spawn is refused and
 * one 600 blocks away accepted; with the hub set the border is 8,000 across in the overworld and an eighth of that in
 * the Nether, and a Nether portal that would lead beyond it takes nobody and says so.
 *
 * Technical: M-06f's native fixtures. The safe zone of the first test is written over the test's own chunks (the hub at
 * (0, 0) is JUnit's), so the strike happens where the test stands. The border is set through the hub command and put
 * back as the test ends; each test is its own batch.
 */
public final class NativeSafeZoneGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "safeStrike", tickLimit = 600)
    public void aStrikeInASafeZoneDoesNothingAndTheStrikerIsTold(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] people = new ServerPlayerEntity[2];
        float[] before = {0};
        boolean pvp = server.isPvpEnabled();
        FixtureGround.whenDone(context, () -> {
            server.setPvpEnabled(pvp);
            for (var p : people) if (p != null && server.getPlayerManager().getPlayer(p.getUuid()) != null) server.getPlayerManager().remove(p);
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    // The server allows PvP, so only the faction rules and the safe zone stand between them.
                    server.setPvpEnabled(true);
                    people[0] = MockPlayers.survival(context, "Cleo", new BlockPos(2, 2, 2));
                    people[1] = MockPlayers.survival(context, "Dov", new BlockPos(4, 2, 2));
                    f.runtime.factionsSeenLongAgoForTest(List.of(people[0].getUuid(), people[1].getUuid()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, people[0].getUuid(), "create", List.of("Red", "RED")).accepted(), "Cleo founds Red");
                    context.assertTrue(f.runtime.faction(f.source, people[1].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Dov founds Blue");
                    var chunks = new TreeSet<String>();
                    for (var corner : List.of(new BlockPos(0, 0, 0), new BlockPos(8, 0, 0), new BlockPos(0, 0, 8), new BlockPos(8, 0, 8))) {
                        var at = context.getAbsolutePos(corner);
                        chunks.add(SafeZones.chunkAt("minecraft:overworld", at.getX(), at.getZ()));
                    }
                    f.runtime.record(SafeZones.zone("fixture", chunks, null, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The zone written");
                    // Past a newly joined player's three seconds of invulnerability.
                    context.assertTrue(context.getTick() >= 80, "Joined long enough ago");
                    var dov = people[1];
                    before[0] = dov.getHealth();
                    boolean hurt = dov.damage(dov.getDamageSources().playerAttack(people[0]), 4f);
                    context.assertFalse(hurt, "Cleo's hit did nothing");
                    context.assertTrue(dov.getHealth() == before[0], "Dov unhurt: " + dov.getHealth());
                    context.assertTrue(f.runtime.factions().lastTold(people[0].getUuid()).equals("No fighting in a safe zone"),
                            "Cleo told why: " + f.runtime.factions().lastTold(people[0].getUuid()));
                    context.assertFalse(f.runtime.factionsMayAttack(people[0].getUuid(), people[1].getUuid()), "Nor may an AI player strike there");
                    // The control: the zone lifted, the same hit lands.
                    f.runtime.record(SafeZones.zone("fixture", Set.of(), null, server.getTicks(), System.currentTimeMillis()));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "The zone lifted");
                    var dov = people[1];
                    dov.timeUntilRegen = 0;
                    context.assertTrue(dov.damage(dov.getDamageSources().playerAttack(people[0]), 4f) && dov.getHealth() < before[0],
                            "Outside a safe zone, enemies' hits land: " + dov.getHealth());
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "safeClaim", tickLimit = 600)
    public void aClaimAt400BlocksIsRefusedAndAt600Accepted(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] cleo = new ServerPlayerEntity[1];
        BlockPos[] home = new BlockPos[1];
        FixtureGround.whenDone(context, () -> { if (cleo[0] != null && server.getPlayerManager().getPlayer(cleo[0].getUuid()) != null) server.getPlayerManager().remove(cleo[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    cleo[0] = MockPlayers.survival(context, "Cleo", new BlockPos(2, 2, 2));
                    home[0] = cleo[0].getBlockPos();
                })
                .createAndAdd(() -> f.runtime.factionsTickForTest())
                .createAndAdd(() -> context.assertTrue(f.runtime.projected().coins().of(cleo[0].getUuid()) == 50_000, "Cleo starts with 500 coins"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, cleo[0].getUuid(), "create", List.of("Red", "RED")).accepted(), "Cleo founds Red");
                    var cleoNow = cleo[0];
                    cleoNow.refreshPositionAndAngles(400.5, 80, 0.5, 0, 0);
                    var refused = f.runtime.faction(f.source, cleoNow.getUuid(), "claim", List.of());
                    context.assertTrue(!refused.accepted() && refused.lines().getFirst().equals("Within 500 blocks of spawn"), "At 400: " + refused.lines());
                    cleoNow.refreshPositionAndAngles(600.5, 80, 0.5, 0, 0);
                    var accepted = f.runtime.faction(f.source, cleoNow.getUuid(), "claim", List.of());
                    context.assertTrue(accepted.accepted(), "At 600: " + accepted.lines());
                    cleoNow.refreshPositionAndAngles(home[0].getX() + 0.5, home[0].getY(), home[0].getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    var claims = f.runtime.projected().claims();
                    context.assertTrue(claims.size() == 1 && claims.containsKey("minecraft:overworld:37:0"), "One claim, at chunk 37,0: " + claims);
                    context.assertTrue(f.runtime.projected().coins().of(cleo[0].getUuid()) == 40_000, "100 coins paid");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "safeBorder", tickLimit = 600)
    public void theBorderIsSetAndANetherPortalBeyondItIsRefused(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        var border = server.getOverworld().getWorldBorder();
        double size = border.getSize(), cx = border.getCenterX(), cz = border.getCenterZ();
        int warning = border.getWarningBlocks();
        double netherSize = server.getWorld(World.NETHER).getWorldBorder().getSize(), endSize = server.getWorld(World.END).getWorldBorder().getSize();
        ServerPlayerEntity[] cleo = new ServerPlayerEntity[1];
        FixtureGround.whenDone(context, () -> {
            border.setCenter(cx, cz);
            border.setSize(size);
            border.setWarningBlocks(warning);
            server.getWorld(World.NETHER).getWorldBorder().setSize(netherSize);
            server.getWorld(World.END).getWorldBorder().setSize(endSize);
            if (cleo[0] != null && server.getPlayerManager().getPlayer(cleo[0].getUuid()) != null) server.getPlayerManager().remove(cleo[0]);
        });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    context.assertTrue(f.runtime.factionsHub(f.source, false) == 1, "The hub set");
                    cleo[0] = MockPlayers.survival(context, "Cleo", new BlockPos(2, 2, 2));
                })
                .createAndAdd(() -> {
                    var nether = server.getWorld(World.NETHER);
                    context.assertTrue(border.getSize() == 8000 && border.getCenterX() == 0 && border.getCenterZ() == 0 && border.getWarningBlocks() == 20,
                            "The overworld's border 8,000 across round (0, 0), warning at 20: " + border.getSize());
                    var netherBorder = nether.getWorldBorder();
                    context.assertTrue(netherBorder.getSize() == 1000 && netherBorder.contains(499, 0) && !netherBorder.contains(501, 0),
                            "The Nether's border an eighth, 1,000 across: " + netherBorder.getSize());
                    context.assertTrue(server.getWorld(World.END).getWorldBorder().getSize() == 8000, "The End's 8,000");
                    context.assertTrue(f.runtime.projected().safe().chunks().contains("minecraft:overworld:0:0"), "Spawn a safe zone");
                    // A portal at Nether x 600 leads to overworld x 4,800: beyond the border, refused, the traveller told.
                    var beyond = new BlockPos(600, 64, 0);
                    var target = ((net.minecraft.block.NetherPortalBlock) Blocks.NETHER_PORTAL).createTeleportTarget(nether, cleo[0], beyond);
                    context.assertTrue(target == null, "No way through beyond the border");
                    context.assertTrue(f.runtime.factions().lastTold(cleo[0].getUuid()).equals("This portal would lead beyond the border"),
                            "Told: " + f.runtime.factions().lastTold(cleo[0].getUuid()));
                    context.assertFalse(FactionsService.leavesBorder(nether, new BlockPos(100, 64, 0)), "Nether x 100 (overworld 800) is inside");
                })
                .completeIfSuccessful();
    }
}
