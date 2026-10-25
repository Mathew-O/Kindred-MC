package dev.kindred.core.match;

import dev.kindred.core.match.Raid.Choice;
import dev.kindred.core.match.Raid.Facts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RaidTest {
    private static final UUID STEVE = new UUID(0, 1), WREN = new UUID(0, 2), MASON = new UUID(0, 3), ADA = new UUID(0, 4);

    private static Facts hunter(int distance, OptionalInt opponent, float health) {
        return new Facts("Wren", MatchRegistry.Mode.MANHUNT, "hunter", "Steve", "runner", 40, distance, opponent, health,
                List.of("an iron sword", "an iron chestplate", "12 bread"), List.of("ender pearls"));
    }

    @Test
    void onlyAPlayersOpponentInARunningMatchCountsAndNeverOutsideOne() {
        var registry = new MatchRegistry();
        assertFalse(registry.opponents(WREN, STEVE), "No match: nobody is anybody's opponent");
        var hunt = registry.proposeManhunt(List.of(STEVE), List.of(WREN, MASON)).match().orElseThrow();
        for (UUID player : List.of(STEVE, WREN, MASON)) registry.join(hunt.id(), player);
        assertFalse(registry.opponents(WREN, STEVE), "Not before it starts");
        assertTrue(registry.start(hunt.id(), 0).accepted());
        assertFalse(registry.opponents(WREN, STEVE), "Nor while it counts down");
        registry.tick(10_000);
        assertTrue(registry.opponents(WREN, STEVE), "A hunter and the runner");
        assertTrue(registry.opponents(STEVE, WREN));
        assertFalse(registry.opponents(WREN, MASON), "Two hunters are on one side");
        assertFalse(registry.opponents(WREN, ADA), "Nobody outside the match");
        assertFalse(registry.opponents(WREN, WREN));
        var race = new MatchRegistry();
        var run = race.propose(MatchRegistry.Mode.SPEEDRUN, List.of(STEVE, ADA)).match().orElseThrow();
        race.join(run.id(), STEVE);
        race.join(run.id(), ADA);
        race.start(run.id(), 0);
        race.tick(10_000);
        assertEquals(MatchRegistry.State.RUNNING, race.get(run.id()).orElseThrow().state());
        assertFalse(race.opponents(STEVE, ADA), "Racers are not opponents to raid");
    }

    @Test
    void theQuestionSaysWhatThePlayerKnowsAndNothingOfWhatIsInTheChest() {
        String question = Raid.question(hunter(18, OptionalInt.empty(), 18));
        assertEquals("You are Wren, a hunter in a Manhunt match. You saw Steve, a runner and your opponent, use a chest 40 seconds ago; "
                + "it is 18 blocks from you. No opponent is in sight. Your health is 18 of 20. You hold an iron sword, an iron chestplate, "
                + "12 bread. Your run still wants ender pearls. What is in the chest you have not seen. Raid it now, leave it, or watch it?",
                question);
        assertTrue(Raid.question(hunter(18, OptionalInt.of(12), 18)).contains("An opponent is in sight 12 blocks away."));
        assertEquals(List.of("RAID", "LEAVE", "WATCH"), Raid.choices());
        assertTrue(Raid.SYSTEM.contains("never suppose what is in the chest"));
    }

    @Test
    void itsOwnRuleRaidsAnUnwatchedChestCloseByAndNeverWithAnOpponentInSightOrHurt() {
        assertEquals(Choice.RAID, Raid.local(hunter(18, OptionalInt.empty(), 18)));
        assertEquals("Its opponent's supplies, and nobody watching them.", Raid.why(Choice.RAID, hunter(18, OptionalInt.empty(), 18)));
        assertEquals(Choice.WATCH, Raid.local(hunter(18, OptionalInt.of(12), 18)), "An opponent in sight");
        assertEquals(Choice.RAID, Raid.local(hunter(18, OptionalInt.of(40), 18)), "One far off does not see it");
        assertEquals(Choice.LEAVE, Raid.local(hunter(18, OptionalInt.empty(), 6)), "Too hurt");
        assertEquals(Choice.WATCH, Raid.local(hunter(60, OptionalInt.empty(), 18)), "Too far for now");
        var runner = new Facts("Steve", MatchRegistry.Mode.MANHUNT, "runner", "Wren", "hunter", 10, 30, OptionalInt.empty(), 20,
                List.of(), List.of("blaze rods"));
        assertEquals(Choice.LEAVE, Raid.local(runner), "A runner's run comes first");
        assertEquals("The run comes first.", Raid.why(Choice.LEAVE, runner));
        var onTheWay = new Facts("Steve", MatchRegistry.Mode.MANHUNT, "runner", "Wren", "hunter", 10, 9, OptionalInt.empty(), 20,
                List.of(), List.of());
        assertEquals(Choice.RAID, Raid.local(onTheWay));
    }

    @Test
    void anAnswerIsOneOfTheChoicesAndWhatItTellsItsTeamIsWhatItTook() {
        assertEquals(java.util.Optional.of(Choice.WATCH), Raid.parse(" watch "));
        assertTrue(Raid.parse("burn it").isEmpty());
        assertTrue(Raid.parse(null).isEmpty());
        assertEquals("I raided Steve's chest: 4 diamonds and 10 gold ingots.",
                Raid.told("Steve", Map.of("minecraft:diamond", 4, "minecraft:gold_ingot", 10)));
        assertEquals("I raided Steve's chest: nothing in it worth taking.", Raid.told("Steve", Map.of()));
    }
}
