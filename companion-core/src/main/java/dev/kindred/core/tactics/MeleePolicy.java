package dev.kindred.core.tactics;

/**
 * What chooses a melee fight's move each tick (E-41b, E-41e): the hand-set {@link Melee}, or a {@link LearnedMelee}
 * running a model trained offline, which falls back to the hand-set one.
 */
public interface MeleePolicy {
    /** The move to make at {@code tick} from {@code sight}. */
    Melee.Choice next(Melee.Sight sight, long tick);

    /** The moves leaned by a strategist's proposal from now (E-41f), or not with null; a learned policy takes no lean. */
    default void lean(CombatProposals.Tactic tactic) { }
}
