package dev.kindred.core.guide;

import dev.kindred.core.guide.NeedsAssessment.Chunk;
import dev.kindred.core.guide.NeedsAssessment.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NeedsAssessmentTest {
    private static final Set<Objective> END = Set.of(Objective.END_FIGHT);

    private static Chunk find(List<Chunk> chunks, Need need) {
        return chunks.stream().filter(c -> c.need() == need).findFirst().orElse(null);
    }

    @Test
    void theEndFightWithNothingInHandBreaksIntoGatheringThenCrafting() {
        var chunks = TaskQueue.EMPTY.chunks(END, Map.of());
        // Bow: 3 string, 3 sticks. Arrows: 32 is 8 crafts of flint, stick and feather. Sticks: 11 is 3 crafts.
        assertEquals(3, find(chunks, Need.STRING).count());
        assertEquals(8, find(chunks, Need.FEATHERS).count());
        assertEquals(8, find(chunks, Need.FLINT).count());
        assertEquals(12, find(chunks, Need.STICKS).count());
        assertEquals(Kind.CRAFT, find(chunks, Need.STICKS).kind());
        assertEquals(32, find(chunks, Need.ARROWS).count());
        assertEquals(1, find(chunks, Need.BOW).count());
        assertEquals(64, find(chunks, Need.BLOCKS).count());
        assertEquals(1, find(chunks, Need.WATER_BUCKET).count());
        assertEquals(16, find(chunks, Need.FOOD).count());
        assertEquals("for the bow and arrows", find(chunks, Need.STICKS).why(), "the sticks serve both");
        assertEquals(Objective.END_FIGHT, find(chunks, Need.STRING).objective());
        // Inputs before what they make: every gather first, sticks before the bow and the arrows.
        int sticks = chunks.indexOf(find(chunks, Need.STICKS));
        assertTrue(sticks < chunks.indexOf(find(chunks, Need.BOW)) && sticks < chunks.indexOf(find(chunks, Need.ARROWS)));
        assertTrue(chunks.indexOf(find(chunks, Need.STRING)) < sticks);
    }

    @Test
    void whatIsHeldChangesTheChunksAtOnce() {
        // A bow looted from a skeleton: no string wanted; 20 arrows picked up: three crafts for the other twelve.
        var held = Map.of(Need.BOW, 1, Need.ARROWS, 20, Need.STICKS, 1, Need.FOOD, 20, Need.BLOCKS, 70, Need.WATER_BUCKET, 1);
        var chunks = TaskQueue.EMPTY.chunks(END, held);
        assertNull(find(chunks, Need.STRING));
        assertNull(find(chunks, Need.BOW));
        assertEquals(12, find(chunks, Need.ARROWS).count());
        assertEquals(3, find(chunks, Need.FLINT).count());
        assertEquals(3, find(chunks, Need.FEATHERS).count());
        assertEquals(4, find(chunks, Need.STICKS).count(), "three wanted, one held: one craft of four");
        assertNull(find(chunks, Need.FOOD));
        // The bed for the respawn point by the End portal: three wool from sheep, then the bed at a table.
        assertEquals(3, find(chunks, Need.WOOL).count());
        assertEquals(1, find(chunks, Need.BED).count());
        assertNull(find(TaskQueue.EMPTY.chunks(END, Map.of(Need.BED, 1)), Need.WOOL), "a bed in hand wants no wool");
        // Everything in hand: nothing to do.
        var all = Map.of(Need.BOW, 1, Need.ARROWS, 40, Need.FOOD, 16, Need.BLOCKS, 64, Need.WATER_BUCKET, 1, Need.BED, 1);
        assertTrue(TaskQueue.EMPTY.chunks(END, all).isEmpty());
    }

    @Test
    void aCraftIsReadyOnlyWithItsInputsInHand() {
        var bow = new Chunk(Need.BOW, Kind.CRAFT, 1, Objective.END_FIGHT, "");
        assertFalse(NeedsAssessment.ready(bow, Map.of(Need.STRING, 2, Need.STICKS, 3)));
        assertTrue(NeedsAssessment.ready(bow, Map.of(Need.STRING, 3, Need.STICKS, 3)));
        var sticks = new Chunk(Need.STICKS, Kind.CRAFT, 4, Objective.END_FIGHT, "");
        assertFalse(NeedsAssessment.ready(sticks, Map.of()), "sticks come from planks, which only the adapter can see");
        assertFalse(NeedsAssessment.ready(new Chunk(Need.FLINT, Kind.GATHER, 1, Objective.END_FIGHT, ""), Map.of(Need.FLINT, 5)));
    }

    @Test
    void aPieceOfGoldArmourIsFiveIngotsAndOneCraft() {
        var wants = List.of(new Want(Need.GOLD_ARMOR, 1, Objective.SURVIVAL, "piglins leave a player in gold alone"));
        assertEquals(List.of(new Chunk(Need.GOLD, Kind.GATHER, 3, Objective.SURVIVAL, "for the piece of gold armour"),
                new Chunk(Need.GOLD_ARMOR, Kind.CRAFT, 1, Objective.SURVIVAL, "piglins leave a player in gold alone")),
                NeedsAssessment.chunks(wants, Map.of(Need.GOLD, 2)), "two ingots in hand: three to go, then the helmet");
        assertTrue(NeedsAssessment.chunks(wants, Map.of(Need.GOLD_ARMOR, 1)).isEmpty(), "one piece is enough");
        assertTrue(NeedsAssessment.ready(new Chunk(Need.GOLD_ARMOR, Kind.CRAFT, 1, Objective.SURVIVAL, ""), Map.of(Need.GOLD, 5)));
    }

    @Test
    void productsAreDeclaredBeforeTheirInputs() {
        for (Need need : Need.values()) {
            MinecraftGuide.recipe(need).ifPresent(recipe -> recipe.inputs().keySet().forEach(input ->
                    assertTrue(input.ordinal() > need.ordinal(), input + " must come after " + need)));
            assertFalse(MinecraftGuide.source(need).isEmpty(), "every need says where it comes from: " + need);
        }
    }
}
