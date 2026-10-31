package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustTest {
    private static final UUID RUE = UUID.nameUUIDFromBytes("Rue".getBytes()), SAM = UUID.nameUUIDFromBytes("Sam".getBytes());
    private static final long DAY = Trust.DAY;

    @Test
    void eachEventAddsItsWeightAndTwoBlowsMakeItWaryOfThatPersonAlone() {
        var trust = new Trust();
        assertEquals(0, trust.wariness(RUE, 100));
        trust.note(RUE, Trust.Event.HURT, 100);
        assertEquals(40, trust.wariness(RUE, 100));
        assertFalse(trust.wary(RUE, 100), "One blow is not enough to hold back");
        trust.note(RUE, Trust.Event.HURT, 130);
        assertEquals(80, trust.wariness(RUE, 130));
        assertTrue(trust.wary(RUE, 130));
        assertEquals(Optional.of(Trust.Event.HURT), trust.why(RUE, 130));
        assertEquals(0, trust.wariness(SAM, 130), "Separate for each person");
        assertEquals(Optional.empty(), trust.why(SAM, 130));
        trust.note(SAM, Trust.Event.LED_INTO_HARM, 200);
        trust.note(SAM, Trust.Event.TOOK_FROM_CHEST, 210);
        assertEquals(60, trust.wariness(SAM, 210));
        assertEquals(Optional.of(Trust.Event.TOOK_FROM_CHEST), trust.why(SAM, 210), "The last thing they did");
        for (int i = 0; i < 10; i++) trust.note(RUE, Trust.Event.HURT, 300 + i * 20);
        assertEquals(Trust.MOST, trust.wariness(RUE, 500), "Bounded");
    }

    @Test
    void aQuietGameDayTakesTenAwayAndAHarmStartsTheCountAgain() {
        var trust = new Trust();
        trust.note(RUE, Trust.Event.HURT, 1_000);
        trust.note(RUE, Trust.Event.HURT, 1_000);
        assertEquals(80, trust.wariness(RUE, 1_000 + DAY - 1), "Not a whole day yet");
        assertEquals(70, trust.wariness(RUE, 1_000 + DAY));
        assertEquals(50, trust.wariness(RUE, 1_000 + 3 * DAY));
        assertTrue(trust.wary(RUE, 1_000 + 3 * DAY));
        assertEquals(40, trust.wariness(RUE, 1_000 + 4 * DAY));
        assertFalse(trust.wary(RUE, 1_000 + 4 * DAY), "Four quiet days and it no longer holds back");
        trust.note(RUE, Trust.Event.TOSS_PAST_CAP, 1_000 + 4 * DAY + 5);
        assertEquals(50, trust.wariness(RUE, 1_000 + 4 * DAY + 5), "From where it had fallen to");
        assertEquals(50, trust.wariness(RUE, 1_000 + 5 * DAY), "The quiet days count from the last harm");
        assertEquals(40, trust.wariness(RUE, 1_000 + 5 * DAY + 5));
        assertEquals(0, trust.wariness(RUE, 1_000 + 40 * DAY), "Never below nothing");
    }

    @Test
    void aGiftTakesTwentyAwayAtOnceWithoutRestartingTheQuietDays() {
        var trust = new Trust();
        trust.note(RUE, Trust.Event.HURT, 0);
        trust.note(RUE, Trust.Event.HURT, 0);
        trust.gift(RUE, DAY / 2);
        assertEquals(60, trust.wariness(RUE, DAY / 2));
        assertEquals(50, trust.wariness(RUE, DAY), "The first quiet day still counted from the harm");
        trust.gift(RUE, DAY + 1);
        trust.gift(RUE, DAY + 2);
        trust.gift(RUE, DAY + 3);
        assertEquals(0, trust.wariness(RUE, DAY + 3), "Never below nothing");
        trust.gift(SAM, 10);
        assertEquals(0, trust.wariness(SAM, 10), "A gift from someone it holds nothing against changes nothing");
    }

    @Test
    void tossesToOnePersonAreCappedEachGameDayAndNoneWhileWary() {
        var trust = new Trust();
        long morning = 5 * DAY + 100;
        assertEquals(Trust.TOSS_CAP, trust.tossAllowance(RUE, morning));
        trust.tossed(RUE, 8, morning);
        assertEquals(8, trust.tossAllowance(RUE, morning + 10));
        trust.tossed(RUE, 8, morning + 20);
        assertEquals(0, trust.tossAllowance(RUE, morning + 30), "Sixteen a day");
        assertEquals(Trust.TOSS_CAP, trust.tossAllowance(SAM, morning + 30), "Each person's own");
        assertEquals(Trust.TOSS_CAP, trust.tossAllowance(RUE, 6 * DAY), "A new day, a new allowance");
        trust.note(RUE, Trust.Event.HURT, 6 * DAY + 1);
        trust.note(RUE, Trust.Event.TOSS_PAST_CAP, 6 * DAY + 2);
        assertTrue(trust.wary(RUE, 6 * DAY + 2));
        assertEquals(0, trust.tossAllowance(RUE, 6 * DAY + 3), "None while wary");
    }

    @Test
    void itKeepsAwayAMinuteAfterABlowAndOnlyAfterABlow() {
        var trust = new Trust();
        trust.note(SAM, Trust.Event.TOOK_FROM_CHEST, 0);
        assertFalse(trust.struckLately(SAM, 10), "Only a blow");
        trust.note(RUE, Trust.Event.HURT, 100);
        assertTrue(trust.struckLately(RUE, 100 + Trust.KEEP_AWAY));
        assertFalse(trust.struckLately(RUE, 101 + Trust.KEEP_AWAY));
        trust.note(RUE, Trust.Event.TOSS_PAST_CAP, 2_000);
        assertFalse(trust.struckLately(RUE, 2_000), "The blow's time kept, not the last event's");
    }

    @Test
    void aClockThatGoesBackNeitherRestoresNorExtendsWariness() {
        var trust = new Trust();
        trust.note(RUE, Trust.Event.HURT, 3 * DAY);
        trust.note(RUE, Trust.Event.HURT, 3 * DAY);
        assertEquals(60, trust.wariness(RUE, 5 * DAY));
        assertEquals(60, trust.wariness(RUE, DAY), "A tick earlier than one seen counts as the latest seen");
        assertEquals(Trust.TOSS_CAP, trust.tossAllowance(SAM, 0));
    }
}
