package dev.kindred.fabric;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: a new AI player's spawn tries the ground round it in the order a human joining is tried in: every
 * spot within the spawn radius once, never one whose land is not loaded, the same order each time for one player.
 *
 * Technical: M-06i4a. {@link SpawnColumns} is vanilla 1.21.1's {@code getWorldSpawnPos} order with a seeded start.
 */
class SpawnColumnsTest {
    @Test
    void theRadiusIsVanillas() {
        assertEquals(10, SpawnColumns.radius(10, 4000), "the rule, well inside the border");
        assertEquals(6, SpawnColumns.radius(10, 6.7), "cut to the distance inside the border");
        assertEquals(1, SpawnColumns.radius(10, 0.5), "at least 1");
        assertEquals(0, SpawnColumns.radius(0, 4000), "a rule of 0: the centre column alone, as vanilla's");
        assertEquals(17, SpawnColumns.stride(441));
        assertEquals(8, SpawnColumns.stride(9));
    }

    @Test
    void everyColumnOfTheSquareIsTriedOnce() {
        Set<Long> seen = new HashSet<>();
        int[] tries = {0};
        Object none = SpawnColumns.first(100, -50, 10, 42L, (x, z) -> true, (x, z) -> {
            tries[0]++;
            assertTrue(Math.abs(x - 100) <= 10 && Math.abs(z + 50) <= 10, "within the radius: " + x + ", " + z);
            seen.add(((long) x << 32) ^ (z & 0xffffffffL));
            return null;
        });
        assertNull(none, "nothing accepted, nothing found: no fallback");
        assertEquals(441, tries[0]);
        assertEquals(441, seen.size(), "each of the 21 by 21 once");
    }

    @Test
    void anUnloadedColumnIsNeverJudged() {
        int[] judged = {0};
        String found = SpawnColumns.first(0, 0, 10, 7L, (x, z) -> x < 5, (x, z) -> {
            judged[0]++;
            assertTrue(x < 5, "an unloaded column was judged: " + x);
            return x >= 5 ? "unloaded dry ground" : null;
        });
        assertNull(found, "the only dry ground is unloaded: refused");
        assertEquals(15 * 21, judged[0], "only the loaded columns judged");
    }

    @Test
    void oneCreationTriesTheSameOrderEveryTime() {
        SpawnColumns.Judge<int[]> any = (x, z) -> new int[] {x, z};
        int[] a = SpawnColumns.first(0, 0, 10, 123L, (x, z) -> true, any);
        int[] b = SpawnColumns.first(0, 0, 10, 123L, (x, z) -> true, any);
        assertEquals(a[0], b[0]);
        assertEquals(a[1], b[1]);
    }
}
