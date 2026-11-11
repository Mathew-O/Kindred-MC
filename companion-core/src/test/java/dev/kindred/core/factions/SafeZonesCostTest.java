package dev.kindred.core.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.record.ClaimLedger;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: the safe zones' chunks are asked for on every block a player in a faction world may work (the
 * territory guard, M-06d/M-06f), inside the goal's wide searches; this measures one such question against the zones'
 * size, and checks the answer is the same set, read-only.
 *
 * Technical: {@code book.chunks().contains(chunk)} timed per call for zones of about 60, 600 and 6,000 chunks, printed
 * (a timing is reported, not asserted: a shared machine's clock is no test). diag-factions303 showed this call 5.7% of
 * the server thread's samples while the goal searched for water.
 */
class SafeZonesCostTest {
    @Test
    void askingWhetherAChunkIsSafeCostsTheSameWhateverTheZonesSize() {
        String probe = ClaimLedger.chunk(Territory.OVERWORLD, 1000, 1000);
        for (int radius : new int[] {64, 200, 640}) {
            var chunks = SafeZones.hub(radius);
            var book = new SafeZones.Book(Map.of(SafeZones.HUB, chunks, "market", Set.of(ClaimLedger.chunk(Territory.OVERWORLD, 900, 900))));
            int calls = 20_000;
            boolean any = false;
            for (int i = 0; i < calls; i++) any |= book.chunks().contains(probe); // warm-up
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) any |= book.chunks().contains(probe);
            long ns = (System.nanoTime() - start) / calls;
            System.out.printf("safe-zone chunks: %d chunks, %d ns a question%n", book.chunks().size(), ns);
            assertTrue(!any, "the probe is in no zone");
            assertEquals(chunks.size() + 1, book.chunks().size(), "every zone's chunks, once each");
            assertTrue(book.chunks().containsAll(chunks));
        }
    }

    @Test
    void theChunksAreEveryZonesChunksAndReadOnly() {
        var book = new SafeZones.Book(Map.of("a", Set.of("x", "y"), "b", Set.of("y", "z")));
        assertEquals(Set.of("x", "y", "z"), book.chunks());
        assertThrows(UnsupportedOperationException.class, () -> book.chunks().add("w"));
        assertEquals(book, new SafeZones.Book(Map.of("b", Set.of("z", "y"), "a", Set.of("y", "x"))), "equal by their zones");
        assertEquals(book.hashCode(), new SafeZones.Book(Map.of("b", Set.of("z", "y"), "a", Set.of("y", "x"))).hashCode());
        assertTrue(SafeZones.Book.EMPTY.chunks().isEmpty());
    }

    @Test
    void aBookIsNotChangedByItsCallerChangingWhatItWasBuiltFrom() {
        // The chunks are gathered once, so a book must hold its own copies: what it was built from may change after.
        var hub = new java.util.HashSet<>(Set.of("x", "y"));
        var zones = new java.util.HashMap<String, Set<String>>();
        zones.put("hub", hub);
        var book = new SafeZones.Book(zones);
        hub.add("z");
        hub.remove("x");
        zones.put("market", Set.of("m"));
        assertEquals(Set.of("x", "y"), book.chunks(), "the chunks as built");
        assertEquals(Map.of("hub", Set.of("x", "y")), book.zones(), "the zones as built");
        assertThrows(UnsupportedOperationException.class, () -> book.zones().put("w", Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> book.zones().get("hub").add("w"));
    }
}
