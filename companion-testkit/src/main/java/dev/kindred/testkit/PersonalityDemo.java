package dev.kindred.testkit;

import dev.kindred.core.Personality;
import dev.kindred.core.TaskPreferences;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In plain terms: a quick demo you can run without Minecraft: it shows how turning up one personality trait
 * (helpfulness, farming or mining) changes which job a player picks, and that an urgent job still wins.
 *
 * Technical: Synthetic choice comparison, without a Minecraft world or executable skills.
 */
public final class PersonalityDemo {
    private PersonalityDemo() { }

    /** Prints the demo's choices. */
    public static void main(String[] args) {
        var eligible = new TaskPreferences.Eligibility(true, true, true, true);
        var supplies = new TaskPreferences.Candidate(new UUID(0, 1), TaskPreferences.Priority.PROJECT, 62,
                new TaskPreferences.Affinities(0, 1, 0, 0), eligible);
        var crops = new TaskPreferences.Candidate(new UUID(0, 2), TaskPreferences.Priority.PROJECT, 60,
                new TaskPreferences.Affinities(0, 0, 1, 0), eligible);
        var mining = new TaskPreferences.Candidate(new UUID(0, 3), TaskPreferences.Priority.PROJECT, 61,
                new TaskPreferences.Affinities(0, 0, 0, 1), eligible);
        var names = Map.of(supplies.id(), "deliver supplies", crops.id(), "tend crops", mining.id(), "gather stone");
        var balanced = Personality.defaults();
        System.out.println("Synthetic task preference demo; no Minecraft actions are executed.");
        for (var trait : List.of(Personality.Trait.HELPFULNESS, Personality.Trait.FARMING, Personality.Trait.MINING)) {
            var ranked = TaskPreferences.rankAvailable(List.of(supplies, crops, mining), balanced.withTrait(trait, 100));
            System.out.println(trait + " 100 -> " + names.get(ranked.getFirst().task().id())
                    + " (need " + ranked.getFirst().task().needScore() + ", preference " + ranked.getFirst().personalityBias() + ")");
        }
        var urgentSupplies = new TaskPreferences.Candidate(supplies.id(), TaskPreferences.Priority.URGENT, 40,
                supplies.affinities(), eligible);
        var result = TaskPreferences.rankAvailable(List.of(urgentSupplies, crops, mining),
                balanced.withTrait(Personality.Trait.FARMING, 100)).getFirst();
        System.out.println("FARMING 100 with an urgent delivery -> " + names.get(result.task().id()));
    }
}
