package dev.kindred.persistence;

import dev.kindred.scheduler.BudgetState;
import dev.kindred.scheduler.PeriodTransition;

import java.util.Objects;
import java.util.Optional;

/** The saved budget activity is charged to now, or none with the transition saying when one opens. */
public record ChargeableBudget(PeriodTransition transition, Optional<BudgetState> budget) {
    public ChargeableBudget {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(budget, "budget");
        if (!budget.map(BudgetState::period).equals(transition.period())) {
            throw new IllegalArgumentException("A chargeable budget is the transition's period");
        }
    }
}
