package dev.kindred.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HorseChoiceTest {
    @Test
    void withItsBaseStandingAndNoHorseItTamesAWildOneInSight() {
        var tame = HorseChoice.next(true, false, 1, true);
        assertTrue(tame.tame(), tame.why());
        assertTrue(tame.why().contains("saddled"), tame.why());
        assertTrue(HorseChoice.next(true, false, 2, false).tame(), "a saddle is not needed to tame");
        assertFalse(HorseChoice.next(false, false, 1, true).tame(), "a base first");
        assertFalse(HorseChoice.next(true, true, 1, true).tame(), "one horse is enough");
        var none = HorseChoice.next(true, false, 0, true);
        assertFalse(none.tame());
        assertTrue(none.why().startsWith("No wild horse"), none.why());
    }
}
