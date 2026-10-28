package dev.kindred.persistence;

import dev.kindred.scheduler.BudgetState;
import dev.kindred.scheduler.PeriodTransition;

import java.util.Objects;
import java.util.Optional;

/**
 * In plain terms: the store's answer to which day's play-time tally to use right now: the saved tally to
 * charge, or none yet together with the time one becomes available.
 *
 * Technical: The saved budget activity is charged to now, or none with the transition saying when one opens.
 */
public record ChargeableBudget(PeriodTransition transition, Optional<BudgetState> budget) {
    public ChargeableBudget {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(budget, "budget");
        if (!budget.map(BudgetState::period).equals(transition.period())) {
            throw new IllegalArgumentException("A chargeable budget is the transition's period");
        }
    }
}
