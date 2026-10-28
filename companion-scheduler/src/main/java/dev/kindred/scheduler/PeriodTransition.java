package dev.kindred.scheduler;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Which accounting period activity at an instant is charged to, given the latest saved one. Either a period
 * (the latest, or a new one to open) or none until opensAt. This grants neither admission nor world authority.
 */
public record PeriodTransition(Kind kind, Optional<AccountingPeriod> period, Optional<Instant> opensAt) {
    public enum Kind {
        /** The latest saved period still contains the instant; a timezone edit waits for its end. */
        CURRENT,
        /** A period to open: the first, or the local day containing the instant once the latest has ended. */
        OPEN,
        /** The wall clock is behind the latest saved period, and an earlier period never reopens. */
        CLOCK_BEHIND,
        /** A timezone edit waits for its first local day that starts at or after the latest period's end. */
        ZONE_PENDING
    }

    public PeriodTransition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(opensAt, "opensAt");
        boolean chargeable = kind == Kind.CURRENT || kind == Kind.OPEN;
        if (period.isPresent() != chargeable || opensAt.isPresent() == chargeable) {
            throw new IllegalArgumentException("A chargeable transition names its period; any other names when one opens");
        }
    }

    public static PeriodTransition current(AccountingPeriod period) {
        return new PeriodTransition(Kind.CURRENT, Optional.of(period), Optional.empty());
    }

    public static PeriodTransition open(AccountingPeriod period) {
        return new PeriodTransition(Kind.OPEN, Optional.of(period), Optional.empty());
    }

    public static PeriodTransition clockBehind(Instant opensAt) {
        return new PeriodTransition(Kind.CLOCK_BEHIND, Optional.empty(), Optional.of(opensAt));
    }

    public static PeriodTransition zonePending(Instant opensAt) {
        return new PeriodTransition(Kind.ZONE_PENDING, Optional.empty(), Optional.of(opensAt));
    }

    public boolean chargeable() {
        return period.isPresent();
    }
}
