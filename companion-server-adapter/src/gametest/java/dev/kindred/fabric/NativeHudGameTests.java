package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.Hud;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a GameTest of the Factions panel's server side: a faction's member is sent her faction with its members
 * online, an outsider is sent no faction's private numbers (only everyone's public power, claims and member counts), and
 * a person crossing into a faction's land is told once on the action bar, not again while they stay, and once more as
 * they leave.
 *
 * Technical: M-06l's native fixture. The stand-in people are ordinary clients (no Kindred channel), so they get the
 * vanilla sidebar and the action bar; the state each was sent is read back from the HUD's own record of it.
 */
public final class NativeHudGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hud", tickLimit = 600)
    public void anOutsiderGetsNoPrivateNumbersAndACrossingIsSaidOnce(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 580);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[3];
        int[] chunk = new int[2];
        FixtureGround.whenDone(context, () -> { for (var x : p) if (x != null && server.getPlayerManager().getPlayer(x.getUuid()) != null) server.getPlayerManager().remove(x); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    p[0] = MockPlayers.survival(context, "Ana", new BlockPos(2, 2, 2));
                    p[1] = MockPlayers.survival(context, "Ben", new BlockPos(2, 2, 2));
                    p[2] = MockPlayers.survival(context, "Cy", new BlockPos(2, 2, 2));
                    f.runtime.factionsTickForTest();
                    context.assertTrue(f.runtime.faction(f.source, p[0].getUuid(), "create", List.of("Red", "RED", "red")).accepted(), "Ana founds Red");
                    context.assertTrue(f.runtime.faction(f.source, p[0].getUuid(), "invite", List.of("Ben")).accepted(), "Invited");
                    context.assertTrue(f.runtime.faction(f.source, p[1].getUuid(), "join", List.of("Red")).accepted(), "Ben joins");
                    var at = context.getAbsolutePos(new BlockPos(2, 2, 2));
                    chunk[0] = at.getX() >> 4;
                    chunk[1] = at.getZ() >> 4;
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, red, ClaimLedger.chunk(W, chunk[0], chunk[1]), Map.of()));
                    // Cy starts in the wilderness, two chunks west.
                    p[2].refreshPositionAndAngles((chunk[0] - 2) * 16 + 8, at.getY(), at.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Written");
                    f.runtime.hud().tick(server.getPlayerManager().getPlayerList());
                    var ana = Hud.decode(f.runtime.hud().lastSent(p[0].getUuid()).orElseThrow());
                    context.assertTrue(ana.own().isPresent() && ana.own().get().online() == 2 && ana.own().get().members() == 2, "Ana sees Red's two online: " + ana.own());
                    context.assertTrue(ana.here().equals("Your land"), "Ana stands on her land: " + ana.here());
                    String cyText = f.runtime.hud().lastSent(p[2].getUuid()).orElseThrow();
                    var cy = Hud.decode(cyText);
                    context.assertTrue(cy.own().isEmpty() && cy.notices().isEmpty() && !cyText.contains("own="), "Cy gets no faction's private numbers: " + cyText);
                    context.assertTrue(cy.standings().size() == 1 && cy.standings().getFirst().members() == 2 && cy.standings().getFirst().power() == 20,
                            "Only the public standings: " + cy.standings());
                    context.assertTrue(cy.here().equals("Wilderness"), "Cy in the wilderness");
                    context.assertTrue(f.runtime.hud().actionBarLines(p[2].getUuid()) == 0, "Nothing said yet");
                    // Into Red's land.
                    var at = context.getAbsolutePos(new BlockPos(2, 2, 2));
                    p[2].refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.runtime.hud().tick(server.getPlayerManager().getPlayerList());
                    f.runtime.hud().tick(server.getPlayerManager().getPlayerList());
                    context.assertTrue(f.runtime.hud().actionBarLines(p[2].getUuid()) == 1, "One line on crossing, none while staying: " + f.runtime.hud().actionBarLines(p[2].getUuid()));
                    context.assertTrue(Hud.decode(f.runtime.hud().lastSent(p[2].getUuid()).orElseThrow()).here().equals("Red (enemy)"), "Red's land, to Cy an enemy's");
                    p[2].refreshPositionAndAngles((chunk[0] - 2) * 16 + 8, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.runtime.hud().tick(server.getPlayerManager().getPlayerList());
                    context.assertTrue(f.runtime.hud().actionBarLines(p[2].getUuid()) == 2, "And one on leaving");
                    context.assertTrue(f.runtime.hud().actionBarLines(p[0].getUuid()) == 0, "Ana, who stayed, told nothing");
                })
                .completeIfSuccessful();
    }
}
