package dev.kindred.core.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BaseSiteTest {
    /** Flat ground at y 63 everywhere in a square, open to the sky, with cells set apart. */
    private static final class Field implements BaseSite.Ground {
        final Map<Long, BaseSite.Column> changed = new HashMap<>();
        final int size;

        Field(int size) { this.size = size; }

        Field set(int x, int z, int top, boolean firm, int open) {
            changed.put(key(x, z), new BaseSite.Column(top, firm, open));
            return this;
        }

        @Override
        public Optional<BaseSite.Column> at(int x, int z) {
            if (Math.abs(x) > size || Math.abs(z) > size) return Optional.empty();
            return Optional.of(changed.getOrDefault(key(x, z), new BaseSite.Column(63, true, 40)));
        }

        private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    }

    @Test
    void onFlatGroundTheSiteIsCentredOnTheAnchorWithItsFloorOnTop() {
        var site = BaseSite.choose(new Field(40), 0, 0, 16, 7, 7, 6, List.of());
        assertEquals(Optional.of(new Pos(-3, 64, -3)), site);
    }

    @Test
    void waterATreeOrADropOfTwoMovesTheSiteToTheNearestGoodGround() {
        var field = new Field(40);
        // A pond under the anchor, a tree beside it, and a step of two to the east.
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) field.set(x, z, 62, false, 40);
        field.set(-5, 0, 66, false, 0);
        for (int x = 5; x <= 40; x++) for (int z = -40; z <= 40; z++) field.set(x, z, 65, true, 40);
        var site = BaseSite.choose(field, 0, 0, 20, 5, 5, 4, List.of()).orElseThrow();
        // No cell of it or its ring on the pond, the tree or the step.
        for (int x = site.x() - 1; x <= site.x() + 5; x++) for (int z = site.z() - 1; z <= site.z() + 5; z++) {
            var column = field.at(x, z).orElseThrow();
            assertTrue(column.firm() && column.top() == 63, "on good ground: " + x + ", " + z);
        }
        assertEquals(64, site.y());
    }

    @Test
    void aDipOfOneIsBridgedButOpenRoomIsNeededOverEveryCell() {
        var field = new Field(40);
        field.set(0, 0, 62, true, 40);
        assertEquals(Optional.of(new Pos(-1, 64, -1)), BaseSite.choose(field, 0, 0, 0, 3, 3, 4, List.of()));
        // A branch overhead leaves too little room.
        field.set(1, 1, 63, true, 3);
        assertTrue(BaseSite.choose(field, 0, 0, 0, 3, 3, 4, List.of()).isEmpty());
    }

    @Test
    void theNextStageGoesBesideTheLastNeverOnIt() {
        var cabin = new BaseSite.Footprint(-3, -3, 3, 3);
        var site = BaseSite.choose(new Field(40), 0, 0, 24, 9, 11, 10, List.of(cabin)).orElseThrow();
        assertTrue(!cabin.overlaps(site.x() - 1 - BaseSite.APART, site.z() - 1 - BaseSite.APART, site.x() + 9 + BaseSite.APART, site.z() + 11 + BaseSite.APART));
        // And unknown ground is never built on.
        assertTrue(BaseSite.choose(new Field(3), 0, 0, 8, 9, 11, 10, List.of()).isEmpty());
    }
}
