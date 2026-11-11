package dev.kindred.core.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kindred.core.Capabilities;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F-38: an item vanilla's worth table does not name, from another namespace, is worth what its recipe tree is made of;
 * vanilla's items keep their written worth, and a vanilla item the table leaves out stays of no use, as before.
 */
class WorthDerivedTest {

    @AfterEach
    void vanillaAgain() { Capabilities.reset(); }

    private static void install(Capabilities.Recipe... recipes) {
        Capabilities.install(Capabilities.fromServer(Map.of(), Map.of(), List.of(), List.of(recipes)));
    }

    @Test
    void aModsItemIsWorthWhatItIsMadeOf() {
        var empty = Worth.Holding.of(Map.of(), Map.of());
        double ingot = Worth.judge(new Worth.Stack("minecraft:iron_ingot", 1, 0), empty).worth();
        install(new Capabilities.Recipe("mymod:steel_block", 1, Map.of("minecraft:iron_ingot", 9)),
                new Capabilities.Recipe("mymod:steel_plate", 2, Map.of("mymod:steel_block", 1)));
        assertEquals(9 * ingot, Worth.judge(new Worth.Stack("mymod:steel_block", 1, 0), empty).worth(), 1e-9);
        assertEquals(9 * ingot / 2, Worth.judge(new Worth.Stack("mymod:steel_plate", 1, 0), empty).worth(), 1e-9);
    }

    @Test
    void anItemWithNoKnownInputsAndVanillasOwnStayAsTheyWere() {
        var empty = Worth.Holding.of(Map.of(), Map.of());
        install(new Capabilities.Recipe("mymod:odd", 1, Map.of("mymod:unknown", 1)));
        assertEquals(Worth.Keep.LEAVE, Worth.judge(new Worth.Stack("mymod:odd", 1, 0), empty).keep());
        assertEquals(0, Worth.judge(new Worth.Stack("mymod:odd", 1, 0), empty).worth());
        assertEquals("no use to it", Worth.judge(new Worth.Stack("minecraft:iron_bars", 8, 0), empty).why());
    }
}
