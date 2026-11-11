package dev.kindred.core.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.Capabilities;
import dev.kindred.core.VanillaIds;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * F-38: a mod's raw item, which nothing makes, is worth what the server's recipes turn it into, or else what its kind's
 * vanilla members typically are (the median the table gives them). Vanilla's own items keep the table as before.
 */
class WorthGatheredTest {

    private static final Worth.Holding EMPTY = Worth.Holding.of(Map.of(), Map.of());

    @AfterEach
    void vanillaAgain() { Capabilities.reset(); }

    /** The server's tags: each kind's vanilla members, with these mod items added to the kinds named. */
    private static void install(Map<VanillaIds.Group, Set<String>> added, Capabilities.Recipe... recipes) {
        Map<String, Set<String>> tags = new HashMap<>();
        for (VanillaIds.Group group : VanillaIds.groups()) {
            if (!group.exact()) continue;
            Set<String> members = new HashSet<>(group.ids());
            members.addAll(added.getOrDefault(group, Set.of()));
            tags.put(group.tag(), members);
        }
        Capabilities.install(Capabilities.fromServer(tags, Map.of(), List.of(), List.of(recipes)));
    }

    private static double worth(String item) {
        return Worth.judge(new Worth.Stack(item, 1, 0), EMPTY).worth();
    }

    @Test
    void aModsRawOreIsWorthTheIngotItSmeltsTo() {
        double iron = worth("minecraft:iron_ingot");
        install(Map.of(VanillaIds.INGOTS, Set.of("mymod:tin_ingot"), VanillaIds.RAW_MATERIALS, Set.of("mymod:raw_tin")),
                new Capabilities.Recipe("mymod:tin_ingot", 1, Map.of("mymod:raw_tin", 1)));
        // Ingots: iron and gold 0.6 minutes, netherite 24; the median is iron's.
        assertEquals(iron, worth("mymod:tin_ingot"), 1e-9);
        assertEquals(iron, worth("mymod:raw_tin"), 1e-9);
    }

    @Test
    void aModsGemNothingMakesIsWorthWhatAGemTypicallyIs() {
        double emerald = worth("minecraft:emerald");
        install(Map.of(VanillaIds.GEMS, Set.of("mymod:ruby")));
        // Gems the table names: lapis 0.15, emerald 0.5, diamond 3; the median is the emerald's.
        assertEquals(emerald, worth("mymod:ruby"), 1e-9);
    }

    @Test
    void aModsFoodIsJudgedAsATypicalFood() {
        var apple = Worth.judge(new Worth.Stack("minecraft:apple", 16, 0), EMPTY);
        install(Map.of(VanillaIds.FOODS, Set.of("mymod:rice_ball")));
        var judged = Worth.judge(new Worth.Stack("mymod:rice_ball", 16, 0), EMPTY);
        // Foods the table names: their median is 0.15 minutes, the apple's.
        assertTrue(judged.worth() > 0, "worth something");
        assertEquals(apple.worth(), judged.worth(), 1e-9);
        assertEquals(apple.keep(), judged.keep());
    }

    @Test
    void vanillasOwnItemsAndAModsUntaggedOneStayAsTheyWere() {
        install(Map.of(VanillaIds.GEMS, Set.of("mymod:ruby")));
        // Quartz is a vanilla gem the table leaves out: of no use to it, as before.
        assertEquals("no use to it", Worth.judge(new Worth.Stack("minecraft:quartz", 8, 0), EMPTY).why());
        assertEquals(0, worth("mymod:pebble"));
    }
}
