package dev.kindred.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * In plain terms: one day of play time for one AI player, from midnight to midnight in its timezone. A
 * player's daily allowance (how long it may be active) is counted per period, and a new period starts a fresh
 * count.
 *
 * Technical: The persisted boundaries, not a recalculated timezone offset, identify a budget period.
 */
public record AccountingPeriod(LocalDate localDate, ZoneId zone, Instant startInclusive, Instant endExclusive) {
    public AccountingPeriod {
        Objects.requireNonNull(localDate, "localDate");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Accounting period must have a positive duration");
        }
    }

    /** The period for a given calendar day in a timezone: its local midnight to the next. */
    public static AccountingPeriod on(LocalDate date, ZoneId zone) {
        return new AccountingPeriod(date, zone, date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** The period (local day) that a moment in time falls in. */
    public static AccountingPeriod containing(Instant instant, ZoneId zone) {
        return on(instant.atZone(zone).toLocalDate(), zone);
    }

    /** Whether a moment falls inside this period. */
    public boolean contains(Instant instant) {
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
    }
}
