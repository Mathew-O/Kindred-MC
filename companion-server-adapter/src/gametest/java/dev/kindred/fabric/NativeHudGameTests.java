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
                    var ana = Hud.decode(f.runtime.hud().lastState(p[0].getUuid()).orElseThrow());
                    context.assertTrue(ana.own().isPresent() && ana.own().get().online() == 2 && ana.own().get().members() == 2, "Ana sees Red's two online: " + ana.own());
                    context.assertTrue(ana.here().equals("Your land"), "Ana stands on her land: " + ana.here());
                    // What reached Ana is the panel's sections, Factions' laid out exactly as its state lays out.
                    var anaPanel = dev.kindred.core.ui.HudPanel.decode(f.runtime.hud().lastSent(p[0].getUuid()).orElseThrow());
                    context.assertTrue(dev.kindred.core.ui.HudPanel.layout(anaPanel, dev.kindred.core.ui.HudPanel.Mode.FULL)
                            .equals(Hud.layout(ana, dev.kindred.core.ui.HudPanel.Mode.FULL)), "Ana's panel is Factions' sections: " + anaPanel);
                    context.assertTrue(anaPanel.getFirst().id().equals(Hud.OWN), "Her own section first: " + anaPanel);
                    String cyPanel = f.runtime.hud().lastSent(p[2].getUuid()).orElseThrow();
                    context.assertTrue(!cyPanel.contains("online") && !cyPanel.contains(Hud.NOTICES), "Cy's panel holds no faction's private lines: " + cyPanel);
                    String cyText = f.runtime.hud().lastState(p[2].getUuid()).orElseThrow();
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
                    context.assertTrue(Hud.decode(f.runtime.hud().lastState(p[2].getUuid()).orElseThrow()).here().equals("Red (enemy)"), "Red's land, to Cy an enemy's");
                    p[2].refreshPositionAndAngles((chunk[0] - 2) * 16 + 8, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.runtime.hud().tick(server.getPlayerManager().getPlayerList());
                    context.assertTrue(f.runtime.hud().actionBarLines(p[2].getUuid()) == 2, "And one on leaving");
                    context.assertTrue(f.runtime.hud().actionBarLines(p[0].getUuid()) == 0, "Ana, who stayed, told nothing");
                })
                .completeIfSuccessful();
    }

    /**
     * A section added the way an integration adds one (mod-support decision 7): registered under its owner, asked for each
     * viewer, it goes on the panel where its order puts it, between Factions' own section and the standings; a source that
     * names a section not its own, or fails, is left out and the rest of the panel still sent.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hudSections", tickLimit = 400)
    public void anIntegrationsSectionGoesOnThePanelWhereItsOrderPutsIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[1];
        FixtureGround.whenDone(context, () -> { if (p[0] != null && server.getPlayerManager().getPlayer(p[0].getUuid()) != null) server.getPlayerManager().remove(p[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    p[0] = MockPlayers.survival(context, "Ana", new BlockPos(2, 2, 2));
                    var sections = f.runtime.hudSections();
                    sections.register("examplemod", viewer -> List.of(new dev.kindred.core.ui.HudPanel.Section("examplemod.dex", 150, 1,
                            List.of(new dev.kindred.core.ui.HudPanel.Line("Dex for " + viewer.getName().getString(), dev.kindred.core.ui.HudPanel.Role.TEXT, "")))));
                    sections.register("rogue", viewer -> List.of(new dev.kindred.core.ui.HudPanel.Section("factions.fake", 1, 0, List.of())));
                    sections.register("broken", viewer -> { throw new IllegalStateException("an integration's bug"); });
                    boolean twice = false;
                    try { sections.register("examplemod", viewer -> List.of()); } catch (IllegalStateException refused) { twice = true; }
                    context.assertTrue(twice, "An owner registers once");
                    context.assertTrue(sections.owners().equals(List.of("factions", "examplemod", "rogue", "broken")), "Factions registered the same way: " + sections.owners());
                    f.runtime.factionsTickForTest();
                    f.runtime.hud().tick(server.getPlayerManager().getPlayerList());
                })
                .createAndAdd(() -> {
                    var panel = dev.kindred.core.ui.HudPanel.decode(f.runtime.hud().lastSent(p[0].getUuid()).orElseThrow());
                    var ids = panel.stream().map(dev.kindred.core.ui.HudPanel.Section::id).toList();
                    context.assertTrue(ids.contains("examplemod.dex") && ids.contains(Hud.OWN) && !ids.contains("factions.fake"), "Its own section, not another's: " + ids);
                    var lines = dev.kindred.core.ui.HudPanel.layout(panel, dev.kindred.core.ui.HudPanel.Mode.FULL).stream().map(dev.kindred.core.ui.HudPanel.Line::text).toList();
                    int own = lines.indexOf("No faction"), dex = lines.indexOf("Dex for Ana"), standings = lines.indexOf("Standings");
                    context.assertTrue(own >= 0 && dex > own && (standings < 0 || standings > dex), "Between Factions' own section and the standings: " + lines);
                    var compact = dev.kindred.core.ui.HudPanel.layout(panel, dev.kindred.core.ui.HudPanel.Mode.COMPACT).stream().map(dev.kindred.core.ui.HudPanel.Line::text).toList();
                    context.assertTrue(compact.contains("Dex for Ana"), "Its compact line in compact mode: " + compact);
                })
                .completeIfSuccessful();
    }
}
