package dev.kindred.core.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalScenarioTest {
    @Test
    void theDesignsExampleReadsAsWritten() {
        var s = EvalScenario.parse("starters=Kindred,Sol,Echo; goal=nether; edits=on; ticks=144000; sprint=on; strategist=off; voice=off; keep=none");
        assertEquals(List.of("Kindred", "Sol", "Echo"), s.starters());
        assertEquals(EvalScenario.Goal.NETHER, s.goal());
        assertTrue(s.edits());
        assertEquals(144_000, s.ticks());
        assertTrue(s.sprint());
        assertFalse(s.strategist());
        assertFalse(s.voice());
        assertEquals(EvalScenario.Keep.NONE, s.keep());
        assertTrue(s.earned(), "Nothing handed out or kept: the earned scenario");
    }

    @Test
    void missingKeysTakeTheirDefaultsAndSpacesAndCaseDoNotMatter() {
        var s = EvalScenario.parse(" goal = END ;starters= sol , echo ;");
        assertEquals(List.of("Sol", "Echo"), s.starters());
        assertEquals(EvalScenario.Goal.END, s.goal());
        assertEquals(EvalScenario.DEFAULT_TICKS, s.ticks());
        assertTrue(s.edits() && s.sprint() && !s.strategist() && !s.voice());
        assertTrue(s.lessons(), "Its own lessons are on unless the scenario turns them off (E-46)");
        assertFalse(EvalScenario.parse("lessons=off").lessons());
        assertTrue(EvalScenario.parse("lessons=off").earned(), "Lessons off hand nothing out either");
        assertTrue(s.atlas() && !EvalScenario.parse("atlas=off").atlas(), "The atlas is on unless the scenario turns it off (F-30)");
        assertTrue(s.rescue() && !EvalScenario.parse("rescue=off").rescue(), "Rescue plans are on unless the scenario turns them off (F-29)");
        assertTrue(s.advice() && !EvalScenario.parse("strategist=on; advice=off").advice(), "Advice off asks for rescues alone");
    }

    @Test
    void editsOffOrKeptInventoryIsStaged() {
        assertFalse(EvalScenario.parse("edits=off").earned());
        assertFalse(EvalScenario.parse("keep=inventory").earned());
        assertTrue(EvalScenario.parse("goal=dragon; strategist=on; voice=on").earned(), "The model players hand nothing out");
    }

    @Test
    void whatItCannotSayIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse(""));
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("seed=12"), "an unknown key");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("goal=wither"), "an unknown goal");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("edits=yes"), "on or off only");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("ticks=lots"));
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("ticks=0"));
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("ticks=10000001"));
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("starters=Kindred,Steve"), "only the canonical three");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("starters=Sol,sol"), "a starter twice");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("goal=end; goal=nether"), "a key twice");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("starters="), "no starters");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("goal"), "not a pair");
    }

    @Test
    void itsTextReadsBackAsTheSameScenario() {
        var s = EvalScenario.parse("starters=Echo; goal=none; edits=off; ticks=200; sprint=off; strategist=on; voice=on; keep=inventory; lessons=off; atlas=off;"
                + " rescue=off; advice=off");
        assertEquals("starters=Echo; goal=none; edits=off; ticks=200; sprint=off; strategist=on; voice=on; keep=inventory; lessons=off; atlas=off;"
                + " rescue=off; advice=off", s.asText());
        assertEquals(s, EvalScenario.parse(s.asText()));
    }

    @Test
    void aFactionWorldIsAModeOfItsOwnAndReadsBack() {
        var s = EvalScenario.parse("goal=none; mode=factions; ticks=288000");
        assertEquals(EvalScenario.Mode.FACTIONS, s.mode());
        assertEquals(s, EvalScenario.parse(s.asText()));
        assertTrue(s.asText().endsWith("; mode=factions"), s.asText());
        assertTrue(s.earned(), "nothing handed out: a faction world is earned like any other");
        assertEquals(EvalScenario.Mode.SURVIVAL, EvalScenario.parse("goal=nether").mode(), "survival unless said");
        assertFalse(EvalScenario.parse("goal=nether").asText().contains("mode="), "a survival run's text as before");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("mode=skyblock"));
    }

    @Test
    void morePlayersByNameAndFoundersReadBack() {
        var s = EvalScenario.parse("starters=Kindred; players=Ada,Bram,Cleo; founders=2; mode=factions; goal=none");
        assertEquals(java.util.List.of("Kindred", "Ada", "Bram", "Cleo"), s.everyone());
        assertEquals(2, s.founders());
        assertEquals(s, EvalScenario.parse(s.asText()));
        assertTrue(s.asText().endsWith("; mode=factions; players=Ada,Bram,Cleo; founders=2"), s.asText());
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("players=Sol; mode=factions"), "a starter is named in starters");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("players=Ada,ada; mode=factions"), "a player twice");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("players=A!; mode=factions"), "not a name");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("starters=Kindred; founders=2; mode=factions"), "more founders than players");
        assertThrows(IllegalArgumentException.class, () -> EvalScenario.parse("founders=1"), "founders only in a faction world");
    }
}
