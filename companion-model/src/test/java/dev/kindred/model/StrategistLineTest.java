package dev.kindred.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** A conversation's reply is checked before anything of it is said (E-20a): every refusal is a failure, nothing used. */
class StrategistLineTest {
    private static final Strategist.Conversation PLAYER = new Strategist.Conversation("s", "t", false);
    private static final Strategist.Conversation CONTROLLER = new Strategist.Conversation("s", "t", true);

    private static Optional<String> refused(Strategist.Conversation to, String say) {
        var line = Strategist.Line.checked(to, say, "why", 100, 0, 10, 50);
        assertEquals(line.failure().isPresent(), line.say().isEmpty(), "a line is said or refused, never both");
        assertEquals(100, line.inputTokens(), "its cost is kept either way");
        return line.failure();
    }

    @Test
    void aGoodLineIsSaidTrimmed() {
        var line = Strategist.Line.checked(PLAYER, "  Chopping logs for a table.  ", " Logs. ", 1, 0, 1, 1);
        assertEquals(Optional.of("Chopping logs for a table."), line.say());
        assertEquals("Logs.", line.reason());
        assertTrue(line.failure().isEmpty());
    }

    @Test
    void emptyOrTooLongIsRefused() {
        assertEquals(Optional.of("nothing to say"), refused(PLAYER, "   "));
        assertEquals(Optional.of("nothing to say"), refused(PLAYER, null));
        assertTrue(refused(PLAYER, "a".repeat(Strategist.MAX_SAID)).isEmpty(), "240 characters is allowed");
        assertEquals(Optional.of("too long"), refused(PLAYER, "a".repeat(Strategist.MAX_SAID + 1)));
    }

    @Test
    void aCommandIsRefusedForEveryone() {
        assertEquals(Optional.of("a command"), refused(CONTROLLER, "/op Steve"));
        assertEquals(Optional.of("a command"), refused(PLAYER, " /aiplayer stop"));
        assertTrue(refused(PLAYER, "Use /aiplayer stop if you need me to stop.").isEmpty(), "naming a command is not giving one");
    }

    @Test
    void formattingCodesControlCharactersAndSecondLinesAreRefused() {
        assertEquals(Optional.of("formatting codes"), refused(CONTROLLER, "§cDanger§r ahead."));
        assertEquals(Optional.of("more than one line"), refused(CONTROLLER, "Hi.\nI'm chopping."));
        assertEquals(Optional.of("control characters"), refused(CONTROLLER, "Hi\u0007 there."));
    }

    @Test
    void unCleanSpeechIsRefusedForEveryone() {
        assertEquals(Optional.of("not clean"), refused(CONTROLLER, "Damn creepers blew up my wall."));
        assertEquals(Optional.of("not clean"), refused(PLAYER, "That was a SHITTY fight."));
        assertTrue(refused(PLAYER, "I'll pass the classic scrap heap, then head home.").isEmpty(), "words inside words are fine");
    }

    @Test
    void coordinatesAreForControllersOnly() {
        assertEquals(Optional.of("a place this listener may not know"), refused(PLAYER, "I'm at 120, 64, -35."));
        assertEquals(Optional.of("a place this listener may not know"), refused(PLAYER, "Around x 120 z -35."));
        assertTrue(refused(CONTROLLER, "I'm at 120, 64, -35.").isEmpty());
        assertTrue(refused(PLAYER, "I've got 3 logs and 2 apples.").isEmpty());
    }
}
