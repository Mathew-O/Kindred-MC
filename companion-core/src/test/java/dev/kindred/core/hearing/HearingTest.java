package dev.kindred.core.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kindred.core.hearing.HeardSound.Band;
import dev.kindred.core.hearing.HeardSound.Bearing;
import dev.kindred.core.hearing.HeardSound.Kind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** In plain terms: checks how a sound becomes what a player heard: its kind, its direction in eighths, its distance in three bands, and that only the newest 64 are kept. */
class HearingTest {
    @Test
    void bearingsAreEighthsFromWhereItFaces() {
        // Facing south (+z, yaw 0): its right hand is west (-x).
        assertEquals(Bearing.AHEAD, Hearing.bearing(0, 5, 0));
        assertEquals(Bearing.BEHIND, Hearing.bearing(0, -4, 0));
        assertEquals(Bearing.RIGHT, Hearing.bearing(-3, 0, 0));
        assertEquals(Bearing.LEFT, Hearing.bearing(3, 0, 0));
        assertEquals(Bearing.AHEAD_RIGHT, Hearing.bearing(-3, 3, 0));
        assertEquals(Bearing.BEHIND_LEFT, Hearing.bearing(3, -3, 0));
        // Facing west (-x, yaw 90): ahead is -x, its right hand north (-z), behind +x.
        assertEquals(Bearing.AHEAD, Hearing.bearing(-6, 0, 90));
        assertEquals(Bearing.RIGHT, Hearing.bearing(0, -6, 90));
        assertEquals(Bearing.BEHIND, Hearing.bearing(6, 0, 90));
        // Yaw past a turn, or negative, is the same facing.
        assertEquals(Bearing.BEHIND, Hearing.bearing(0, -4, 360));
        // Facing east (+x, yaw -90): its right hand is south (+z).
        assertEquals(Bearing.RIGHT, Hearing.bearing(0, 5, -90));
        // On a sector's edge (22.5 degrees off ahead) it falls to one side or the other, never outside the eight.
        assertTrue(Hearing.bearing(-Math.tan(Math.toRadians(22.5)), 1, 0) == Bearing.AHEAD
                || Hearing.bearing(-Math.tan(Math.toRadians(22.5)), 1, 0) == Bearing.AHEAD_RIGHT);
    }

    @Test
    void distancesAreNearMidOrFar() {
        assertEquals(Band.NEAR, Hearing.band(0));
        assertEquals(Band.NEAR, Hearing.band(4));
        assertEquals(Band.MID, Hearing.band(4.01));
        assertEquals(Band.MID, Hearing.band(8));
        assertEquals(Band.FAR, Hearing.band(8.5));
        assertEquals(Band.FAR, Hearing.band(60));
        var h = Hearing.heard(Kind.CREEPER_HISS, 0, 0, -3.5, 0, 42);
        assertEquals(new HeardSound(Kind.CREEPER_HISS, Bearing.BEHIND, Band.NEAR, 42), h);
    }

    @Test
    void onlyTheSoundsListenedForHaveAKind() {
        assertEquals(Optional.of(Kind.CREEPER_HISS), Hearing.kindOf("sound", "minecraft:entity.creeper.primed", "hostile"));
        assertEquals(Optional.of(Kind.BOW_SHOT), Hearing.kindOf("sound", "minecraft:entity.skeleton.shoot", "hostile"));
        assertEquals(Optional.of(Kind.BOW_SHOT), Hearing.kindOf("sound", "minecraft:entity.arrow.shoot", "players"));
        assertEquals(Optional.of(Kind.HOSTILE), Hearing.kindOf("sound", "minecraft:entity.zombie.ambient", "hostile"));
        assertEquals(Optional.of(Kind.STEP), Hearing.kindOf("sound", "minecraft:entity.zombie.step", "hostile"));
        assertEquals(Optional.of(Kind.STEP), Hearing.kindOf("sound", "minecraft:entity.cow.step", "neutral"));
        assertEquals(Optional.of(Kind.EXPLOSION), Hearing.kindOf("explosion", "minecraft:entity.generic.explode", "explosion"));
        assertEquals(Optional.of(Kind.BLOCK_BROKEN), Hearing.kindOf("world event", "2001", "local"));
        assertEquals(Optional.empty(), Hearing.kindOf("world event", "1000", "local"));
        assertEquals(Optional.of(Kind.DOOR), Hearing.kindOf("sound", "minecraft:block.wooden_door.open", "blocks"));
        assertEquals(Optional.of(Kind.CHEST), Hearing.kindOf("sound", "minecraft:block.chest.open", "blocks"));
        assertEquals(Optional.of(Kind.EATING), Hearing.kindOf("sound", "minecraft:entity.generic.eat", "players"));
        assertEquals(Optional.of(Kind.LAVA), Hearing.kindOf("sound", "minecraft:block.lava.pop", "blocks"));
        assertEquals(Optional.of(Kind.WATER), Hearing.kindOf("sound", "minecraft:block.water.ambient", "blocks"));
        assertEquals(Optional.empty(), Hearing.kindOf("sound", "minecraft:entity.cow.ambient", "neutral"));
        assertEquals(Optional.empty(), Hearing.kindOf("sound", "minecraft:entity.armor_stand.break", "neutral"));
        assertEquals(Optional.empty(), Hearing.kindOf("sound", null, "hostile"));
    }

    @Test
    void theEarshotKeepsTheNewest64AndForgetsOnAStop() {
        var ear = new Earshot();
        for (int i = 0; i < 100; i++) ear.offer(new HeardSound(Kind.STEP, Bearing.AHEAD, Band.FAR, i));
        assertEquals(Earshot.CAPACITY, ear.size());
        assertEquals(36, ear.dropped());
        var drained = ear.drain();
        assertEquals(64, drained.size());
        assertEquals(36, drained.getFirst().tick(), "the oldest kept is the 37th");
        assertEquals(99, drained.getLast().tick());
        assertTrue(ear.drain().isEmpty(), "drained empty");
        ear.offer(new HeardSound(Kind.LAVA, Bearing.LEFT, Band.MID, 1));
        ear.clear();
        assertTrue(ear.drain().isEmpty(), "cleared on a stop");
    }
}
