package dev.kindred.core.survival;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LessonsTest {
    private static final long DAY = Lessons.DAY;
    private static final PlanPreferences.Habit MORE_FOOD = PlanPreferences.Habit.MORE_FOOD;

    private static Lessons.Death death(long time, int y, boolean night, boolean sky, int food, Lessons.How how) {
        return new Lessons.Death(time, y, night, sky, true, food, how, false);
    }

    /** A death that counts toward no rule: by day, under cover, fed, at y 64, to a mob. */
    private static Lessons.Death plain(long time) {
        return death(time, 64, false, false, 20, Lessons.How.MOB);
    }

    private static List<Lessons.Lesson> review(List<Lessons.Death> deaths, long now) {
        return Lessons.review(deaths, List.of(), Set.of(), Map.of(), Map.of(), now);
    }

    private static Set<PlanPreferences.Habit> taught(List<Lessons.Lesson> lessons) {
        return lessons.stream().filter(l -> !l.retire()).map(Lessons.Lesson::habit).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void twoHungryDeathsWithinTwoDaysTeachMoreFoodWithItsNote() {
        var lessons = review(List.of(death(1_000, 64, false, false, 6, Lessons.How.MOB), death(1_000 + 2 * DAY - 1, 64, false, false, 3, Lessons.How.MOB)),
                1_000 + 2 * DAY);
        assertEquals(List.of(new Lessons.Lesson(MORE_FOOD, "Twice dead hungry; carry more food.", false)), lessons);
    }

    @Test
    void twoHungryDeathsLongAgoTeachNothingNow() {
        assertEquals(List.of(), review(List.of(death(0, 64, false, false, 2, Lessons.How.MOB), death(10, 64, false, false, 2, Lessons.How.MOB)),
                10 + 2 * DAY));
    }

    @Test
    void hungryDeathsFurtherApartThanTwoDaysOrWellFedTeachNothing() {
        assertEquals(List.of(), review(List.of(death(0, 64, false, false, 2, Lessons.How.MOB), death(2 * DAY + 1, 64, false, false, 2, Lessons.How.MOB)),
                2 * DAY + 1));
        assertEquals(List.of(), review(List.of(death(0, 64, false, false, 7, Lessons.How.MOB), death(10, 64, false, false, 2, Lessons.How.MOB)), 20));
    }

    @Test
    void twoNightDeathsOnTheSurfaceTeachShelterEarlyButNotUnderCoverByDayOrInTheNether() {
        assertEquals(Set.of(PlanPreferences.Habit.SHELTER_EARLY),
                taught(review(List.of(death(0, 70, true, true, 20, Lessons.How.MOB), death(DAY, 70, true, true, 20, Lessons.How.MOB)), DAY)));
        assertEquals(Set.of(), taught(review(List.of(death(0, 70, true, false, 20, Lessons.How.MOB), death(DAY, 70, true, true, 20, Lessons.How.MOB)), DAY)));
        assertEquals(Set.of(), taught(review(List.of(death(0, 70, false, true, 20, Lessons.How.MOB), death(DAY, 70, true, true, 20, Lessons.How.MOB)), DAY)));
        var nether = new Lessons.Death(0, 70, true, true, false, 20, Lessons.How.MOB, false);
        assertEquals(Set.of(), taught(review(List.of(nether, death(DAY, 70, true, true, 20, Lessons.How.MOB)), DAY)));
    }

    @Test
    void threeDeathsBelowFortyInOneDayTeachNoCaves() {
        var below = List.of(death(0, 39, false, false, 20, Lessons.How.MOB), death(100, 12, false, false, 20, Lessons.How.MOB),
                death(DAY - 1, -40, false, false, 20, Lessons.How.MOB));
        assertEquals(List.of(new Lessons.Lesson(PlanPreferences.Habit.NO_CAVES, "The caves took three lives today; dig for ore instead.", false)),
                review(below, DAY - 1));
        // Spread over more than a day, at y 40 itself, or in the Nether: not the caves.
        assertEquals(Set.of(), taught(review(List.of(below.get(0), below.get(1), death(DAY + 1, 12, false, false, 20, Lessons.How.MOB)), DAY + 1)));
        assertEquals(Set.of(), taught(review(List.of(death(0, 40, false, false, 20, Lessons.How.MOB), below.get(1), below.get(2)), DAY - 1)));
        var nether = new Lessons.Death(0, 30, false, false, false, 20, Lessons.How.MOB, false);
        assertEquals(Set.of(), taught(review(List.of(nether, below.get(1), below.get(2)), DAY - 1)));
    }

    @Test
    void aPickaxeBrokenBelowGroundWithNoneSpareTeachesASpareWithinTheDay() {
        assertEquals(List.of(new Lessons.Lesson(PlanPreferences.Habit.SPARE_PICKAXE, "Stranded without a pickaxe; pack a spare.", false)),
                Lessons.review(List.of(), List.of(new Lessons.Stranded(500)), Set.of(), Map.of(), Map.of(), 600));
        assertEquals(List.of(), Lessons.review(List.of(), List.of(new Lessons.Stranded(500)), Set.of(), Map.of(), Map.of(), 500 + DAY));
    }

    @Test
    void aDeathToAMobInItsOwnTunnelTeachesTorchesAsItGoes() {
        var tunnel = new Lessons.Death(100, 20, false, false, true, 20, Lessons.How.MOB, true);
        assertEquals(List.of(new Lessons.Lesson(PlanPreferences.Habit.TORCH_AS_YOU_GO, "Something spawned in my own tunnel; torches as I go.", false)),
                review(List.of(tunnel), 200));
        assertEquals(List.of(), review(List.of(plain(100)), 200));
    }

    @Test
    void twoFallDeathsWithinTwoDaysTeachTheBucketToHand() {
        var falls = List.of(death(0, 64, false, true, 20, Lessons.How.FALL), death(DAY, 64, false, true, 20, Lessons.How.FALL));
        assertEquals(List.of(new Lessons.Lesson(PlanPreferences.Habit.BUCKET_ON_HOTBAR, "Falls have cost two lives; keep the bucket to hand.", false)),
                review(falls, DAY));
        assertEquals(List.of(), review(List.of(falls.get(0), plain(DAY)), DAY));
    }

    @Test
    void aHabitAlreadyKeptIsNotTaughtAgain() {
        var hungry = List.of(death(0, 64, false, false, 2, Lessons.How.MOB), death(10, 64, false, false, 2, Lessons.How.MOB));
        assertEquals(List.of(), Lessons.review(hungry, List.of(), Set.of(MORE_FOOD), Map.of(), Map.of(), 20));
    }

    @Test
    void aLearnedHabitThreeDaysWithoutItsTriggerIsDroppedAndOneWhoseTriggerCameAgainIsKept() {
        long learned = 1_000;
        var none = Lessons.review(List.of(), List.of(), Set.of(MORE_FOOD), Map.of(MORE_FOOD, learned), Map.of(), learned + Lessons.LAPSE);
        assertEquals(List.of(new Lessons.Lesson(MORE_FOOD, "Three days without dying hungry; dropping the habit of carrying more food.", true)), none);
        assertEquals(List.of(), Lessons.review(List.of(), List.of(), Set.of(MORE_FOOD), Map.of(MORE_FOOD, learned), Map.of(), learned + Lessons.LAPSE - 1));
        // Dead hungry again a day after it learned: the three days run from then.
        var again = List.of(death(learned + DAY, 64, false, false, 4, Lessons.How.MOB));
        assertEquals(List.of(), Lessons.review(again, List.of(), Set.of(MORE_FOOD), Map.of(MORE_FOOD, learned), Map.of(), learned + Lessons.LAPSE));
        assertTrue(Lessons.review(again, List.of(), Set.of(MORE_FOOD), Map.of(MORE_FOOD, learned), Map.of(), learned + DAY + Lessons.LAPSE)
                .getFirst().retire());
    }

    @Test
    void aHabitTheStrategistTaughtIsNeverDroppedByLessons() {
        assertEquals(List.of(), Lessons.review(List.of(), List.of(), Set.of(PlanPreferences.Habit.NO_CAVES), Map.of(), Map.of(), 100 * DAY));
    }

    @Test
    void aRetiredHabitIsTaughtAgainOnlyByTroubleAfterItsRetirement() {
        var hungry = List.of(death(0, 64, false, false, 2, Lessons.How.MOB), death(10, 64, false, false, 2, Lessons.How.MOB));
        // The strategist retired it at 20: the two deaths before that teach nothing again.
        assertEquals(List.of(), Lessons.review(hungry, List.of(), Set.of(), Map.of(), Map.of(MORE_FOOD, 20L), 30));
        var later = new java.util.ArrayList<>(hungry);
        later.add(death(40, 64, false, false, 2, Lessons.How.MOB));
        later.add(death(50, 64, false, false, 2, Lessons.How.MOB));
        assertEquals(Set.of(MORE_FOOD), taught(Lessons.review(later, List.of(), Set.of(), Map.of(), Map.of(MORE_FOOD, 20L), 60)));
    }

    @Test
    void eachRuleNamesItsOwnHabitAndEveryHabitButNoneHasARule() {
        for (PlanPreferences.Habit habit : PlanPreferences.Habit.values()) {
            assertEquals(habit != PlanPreferences.Habit.NONE, Lessons.note(habit).isPresent(), habit.name());
        }
    }
}
