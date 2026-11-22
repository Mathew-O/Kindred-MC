package dev.kindred.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * In plain terms: places set aside as open water it saw (F-35j1) begin the run its faction walks wait on (F-35i) with
 * where it stood and whether it was at the surface; at the surface is the sky's light at its feet (F-35i2). Set aside from
 * under a roof open at its sides, or from the sea it swam in, it was at the surface: its roof's going, or its climbing
 * out onto land, is no coming up, and the wait holds. Set aside from a tunnel with no light of the sky, coming up ends it.
 *
 * Technical: {@code openWater}'s own record of the run (where, at the surface or not) and {@code paused}, with the reading
 * {@code FactionsPlayer.atSurface(skyLightAtFeet)}. Each spot also carries its column's top as the world's height map
 * keeps it ({@code MOTION_BLOCKING_NO_LEAVES}, which counts water), the reading before F-35i2, only so that the evidence's
 * diagnostic can put that reading in its place; nothing here reads it otherwise. The swimming case is the one the Factions
 * seed runs met: of the seven times the two readings differed at {@code openWater} (M-06i2c5's seeds 20261030 and 109,
 * both builds), all seven were at sea level, the sky's light at its feet and the water's surface over its head.
 */
class FactionsPlayerSurfaceTest {
    /** Where it stands, the sky's light at its feet, and the height map's top of its column (the first block over it). */
    private record Spot(BlockPos at, int skyLight, int columnTop) {}

    private static boolean atSurface(Spot spot) {
        return FactionsPlayer.atSurface(spot.skyLight());
    }

    /** Two places set aside as open water from {@code from}: every faction walk waits. */
    private static FactionsPlayer setAsideFrom(Spot from, long tick) {
        var play = new FactionsPlayer();
        play.openWater("minecraft:overworld:9:5", tick, from.at(), atSurface(from));
        play.openWater("minecraft:overworld:10:5", tick + 600, from.at(), atSurface(from));
        assertTrue(play.paused(tick + 601, from.at(), atSurface(from)), "two places set aside as water: every faction walk waits");
        return play;
    }

    @Test
    void setAsideUnderARoofOpenAtItsSidesTheWaitHoldsWhenTheRoofGoes() {
        // On its island under a roof six above it: the sky's light from the open sides, 13 at its feet; the roof the top.
        var under = new Spot(new BlockPos(0, 100, 0), 13, 107);
        var play = setAsideFrom(under, 1000);
        var open = new Spot(under.at(), 15, 100);
        assertTrue(play.paused(1700, open.at(), atSurface(open)), "its roof gone: it was at the surface all along, the wait holds");
    }

    @Test
    void setAsideFromTheSeaItSwamInTheWaitHoldsWhenItClimbsOntoLand() {
        // In the sea, two below its surface at 62 (the field case): the sky's light 13 at its feet; the water the top.
        var swimming = new Spot(new BlockPos(37, 56, 64), 13, 63);
        var play = setAsideFrom(swimming, 1000);
        var shore = new Spot(new BlockPos(40, 63, 64), 15, 63);
        assertTrue(play.paused(1700, shore.at(), atSurface(shore)), "out onto the shore: no coming up from below, the wait holds");
    }

    @Test
    void setAsideFromATunnelWithNoSkyComingUpEndsTheWait() {
        var tunnel = new Spot(new BlockPos(105, 19, 64), 0, 70);
        var play = setAsideFrom(tunnel, 1000);
        var up = new Spot(new BlockPos(111, 69, 64), 15, 69);
        assertFalse(play.paused(1700, up.at(), atSurface(up)), "up at the surface from below: the wait ends");
    }
}
