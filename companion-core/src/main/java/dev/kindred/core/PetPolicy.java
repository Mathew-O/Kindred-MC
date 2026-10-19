package dev.kindred.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Who may be tamed, with what, and when. Every fact here comes from trusted checks on the server thread;
 * the policy only combines them. Personality supplies the usual bounded bias toward a species, never
 * permission. A pet is a world entity: this class never records or replaces one.
 */
public final class PetPolicy {
    public static final int DEFAULT_CAP = 2;
    public static final int MAX_CAP = 4;

    public enum Support { FIRST, SECOND, LATER, UNSUPPORTED }

    public enum Species {
        WOLF("minecraft:wolf", Support.FIRST, "bone", 1),
        CAT("minecraft:cat", Support.FIRST, "raw cod or raw salmon", 1),
        PARROT("minecraft:parrot", Support.SECOND, "seeds", 1),
        HORSE("minecraft:horse", Support.LATER, "repeated mounting", 0),
        DONKEY("minecraft:donkey", Support.LATER, "repeated mounting", 0),
        MULE("minecraft:mule", Support.LATER, "repeated mounting", 0);

        public final String entityId;
        public final Support support;
        public final String tamingInput;
        /** Minimum count of the taming item to begin an attempt; zero means no item is involved. */
        public final int minimumItems;

        Species(String entityId, Support support, String tamingInput, int minimumItems) {
            this.entityId = entityId;
            this.support = support;
            this.tamingInput = tamingInput;
            this.minimumItems = minimumItems;
        }
    }

    /** Trusted facts gathered immediately before a taming attempt. */
    public record Request(Species species, boolean adapterInstalled, boolean petsEnabled, int cap, int ownedPets,
                          int ownedOfSpecies, int unreservedTamingItems, boolean candidateWild, boolean candidateVisible,
                          boolean insidePermittedArea, boolean candidatePlayerOwned, boolean candidatePenned,
                          boolean candidateFarmPopulation, boolean affordable, boolean active, boolean ownerOfflineWorkAllowed,
                          boolean ownerOnline) {
        public Request {
            Objects.requireNonNull(species);
            if (cap < 0 || cap > MAX_CAP) throw new IllegalArgumentException("Pet cap must be 0.." + MAX_CAP);
            if (ownedPets < 0 || ownedOfSpecies < 0 || unreservedTamingItems < 0) throw new IllegalArgumentException("Counts cannot be negative");
        }
    }

    public enum Blocker {
        UNSUPPORTED_SPECIES, ADAPTER_MISSING, PETS_DISABLED, CAP_REACHED, SPECIES_CAP_REACHED, INSUFFICIENT_ITEMS,
        CANDIDATE_NOT_WILD, CANDIDATE_NOT_VISIBLE, OUTSIDE_PERMITTED_AREA, CANDIDATE_PLAYER_OWNED, CANDIDATE_PENNED,
        CANDIDATE_FARM_POPULATION, UNAFFORDABLE, COMPANION_INACTIVE, OWNER_OFFLINE_WORK_DISABLED
    }

    public record Decision(List<Blocker> blockers) {
        public Decision { blockers = List.copyOf(blockers); }
        public boolean eligible() { return blockers.isEmpty(); }
    }

    private PetPolicy() { }

    public static Decision evaluate(Request request) {
        Objects.requireNonNull(request, "request");
        List<Blocker> blockers = new ArrayList<>();
        Species species = request.species();
        if (species.support == Support.LATER || species.support == Support.UNSUPPORTED) blockers.add(Blocker.UNSUPPORTED_SPECIES);
        if (!request.adapterInstalled()) blockers.add(Blocker.ADAPTER_MISSING);
        if (!request.petsEnabled()) blockers.add(Blocker.PETS_DISABLED);
        if (request.ownedPets() >= request.cap()) blockers.add(Blocker.CAP_REACHED);
        if (request.ownedOfSpecies() >= 1) blockers.add(Blocker.SPECIES_CAP_REACHED);
        if (request.unreservedTamingItems() < species.minimumItems) blockers.add(Blocker.INSUFFICIENT_ITEMS);
        if (!request.candidateWild()) blockers.add(Blocker.CANDIDATE_NOT_WILD);
        if (!request.candidateVisible()) blockers.add(Blocker.CANDIDATE_NOT_VISIBLE);
        if (!request.insidePermittedArea()) blockers.add(Blocker.OUTSIDE_PERMITTED_AREA);
        if (request.candidatePlayerOwned()) blockers.add(Blocker.CANDIDATE_PLAYER_OWNED);
        if (request.candidatePenned()) blockers.add(Blocker.CANDIDATE_PENNED);
        if (request.candidateFarmPopulation()) blockers.add(Blocker.CANDIDATE_FARM_POPULATION);
        if (!request.affordable()) blockers.add(Blocker.UNAFFORDABLE);
        if (!request.active()) blockers.add(Blocker.COMPANION_INACTIVE);
        if (!request.ownerOnline() && !request.ownerOfflineWorkAllowed()) blockers.add(Blocker.OWNER_OFFLINE_WORK_DISABLED);
        return new Decision(blockers);
    }

    /**
     * Species affinity for {@link TaskPreferences}: combativeness and caution lean toward a wolf, sociability and
     * curiosity toward a cat or parrot. The result feeds the existing ±5 envelope and is never a permission.
     */
    public static TaskPreferences.Affinities affinities(Species species) {
        Objects.requireNonNull(species);
        return switch (species) {
            case WOLF -> new TaskPreferences.Affinities(0.6, 0, 0, 0);
            case CAT, PARROT -> new TaskPreferences.Affinities(0, 0.3, 0, 0);
            case HORSE, DONKEY, MULE -> TaskPreferences.Affinities.none();
        };
    }

    /** Extra lean from the social traits, bounded so combined with the activity bias it stays inside ±5. */
    public static double socialLean(Species species, Personality personality) {
        Objects.requireNonNull(species);
        Objects.requireNonNull(personality);
        double lean = switch (species) {
            case WOLF -> 0.02 * (personality.caution() - 50);
            case CAT, PARROT -> 0.015 * (personality.sociability() - 50) + 0.015 * (personality.curiosity() - 50);
            case HORSE, DONKEY, MULE -> 0;
        };
        return Math.max(-1.5, Math.min(1.5, lean));
    }

    /** Combined preference for a species, clamped to the shared discretionary ceiling. */
    public static double preference(Species species, Personality personality) {
        double combined = TaskPreferences.bias(personality, affinities(species)) + socialLean(species, personality);
        return Math.max(-TaskPreferences.MAX_PERSONALITY_BIAS, Math.min(TaskPreferences.MAX_PERSONALITY_BIAS, combined));
    }

    /** A readable summary for commands and journals; counts are supplied by trusted callers. */
    public static Map<Species, Support> catalog() {
        EnumMap<Species, Support> map = new EnumMap<>(Species.class);
        for (Species species : Species.values()) map.put(species, species.support);
        return Map.copyOf(map);
    }
}
