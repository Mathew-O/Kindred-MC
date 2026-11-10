package dev.kindred.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.ui.HudPanel.Line;
import dev.kindred.core.ui.HudPanel.Mode;
import dev.kindred.core.ui.HudPanel.Role;
import dev.kindred.core.ui.HudPanel.Section;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: checks the panel made of sections (the owner's mod-support decision 7): sections stack by their order
 * whoever added them, compact mode shows each section's first lines, hidden shows nothing, and what the server sends the
 * client comes back exactly.
 */
class HudPanelTest {
    private static Section section(String id, int order, int compact, String... texts) {
        return new Section(id, order, compact, java.util.Arrays.stream(texts).map(t -> new Line(t, Role.TEXT, "")).toList());
    }

    private static List<String> texts(List<Line> lines) { return lines.stream().map(Line::text).toList(); }

    @Test
    void sectionsStackByOrderAndAnIntegrationsGoesWhereItsOrderPutsIt() {
        var own = section("factions.own", 100, 1, "[RED] Red", "Power 5 / 10");
        var market = section("factions.market", 400, 0, "Market", "iron +2%");
        var pokedex = section("examplemod.dex", 250, 1, "Dex 12 / 151", "Last caught: a fox");
        var panel = List.of(market, pokedex, own);
        assertEquals(List.of("[RED] Red", "Power 5 / 10", "Dex 12 / 151", "Last caught: a fox", "Market", "iron +2%"), texts(HudPanel.layout(panel, Mode.FULL)));
        assertEquals(List.of("[RED] Red", "Dex 12 / 151"), texts(HudPanel.layout(panel, Mode.COMPACT)));
        assertEquals(List.of(), HudPanel.layout(panel, Mode.HIDDEN));
        assertEquals(Mode.COMPACT, Mode.FULL.next());
        assertEquals(Mode.FULL, Mode.HIDDEN.next());
    }

    @Test
    void thePayloadComesBackExactly() {
        var panel = List.of(new Section("factions.own", 100, 2, List.of(new Line("[RED] Red ★", Role.FACTION, "red"),
                new Line("power 5 · claims 3 / 5 · 1 exposed", Role.RED, ""), new Line("Coins 1.50 = % & ", Role.TEXT, ""))),
                new Section("examplemod.dex", 250, 0, List.of()));
        assertEquals(panel, HudPanel.decode(HudPanel.encode(panel)));
        assertEquals(List.of(), HudPanel.decode(HudPanel.encode(List.of())));
    }

    @Test
    void aSectionIsNamedAndItsCompactLinesAreItsOwn() {
        assertThrows(IllegalArgumentException.class, () -> section("Factions", 1, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> section("factions.own", 1, 2, "x"));
        assertTrue(new Line("a\u001fb\u001dc", Role.TEXT, "").text().equals("a b c"), "the payload's separators never reach a line");
    }
}
