package dev.kindred.core.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F-38: needs as data keep the enum's names, order, caps and words, so saves and the planner see no change. */
class NeedRecordTest {

    @Test
    void theVanillaNeedsResourceHoldsTheEnumsNamesInItsOrder() {
        assertEquals(List.of("BOW", "ARROWS", "SHIELD", "WATER_BUCKET", "BED", "STRING", "FEATHERS", "WOOL", "FLINT", "STICKS",
                        "BLOCKS", "FOOD", "GOLD_ARMOR", "GOLD", "IRON_SWORD"),
                Arrays.stream(Need.values()).map(Need::name).toList());
        for (int i = 0; i < Need.values().length; i++) assertEquals(i, Need.values()[i].ordinal());
    }

    @Test
    void capsAndWordsAreAsTheEnumHadThem() {
        assertEquals(64, Need.ARROWS.cap());
        assertEquals(128, Need.BLOCKS.cap());
        assertEquals("8 arrows", 8 + " " + Need.ARROWS.words(8));
        assertEquals("string", Need.STRING.words(3));
        assertEquals("flint", Need.FLINT.words(2));
        assertEquals("food", Need.FOOD.words(5));
        assertEquals("wool", Need.WOOL.words(4));
        assertEquals("piece of gold armour", Need.GOLD_ARMOR.words(1));
        assertEquals("gold ingots", Need.GOLD.words(2));
    }

    @Test
    void namesParseAndLookUpAsBefore() {
        assertSame(Need.ARROWS, Need.parse(" arrows ").orElseThrow());
        assertSame(Need.WATER_BUCKET, Need.parse("water bucket").orElseThrow());
        assertTrue(Need.parse("diamonds").isEmpty());
        assertSame(Need.BOW, Need.valueOf("BOW"));
        assertThrows(IllegalArgumentException.class, () -> Need.valueOf("bow"));
    }
}
