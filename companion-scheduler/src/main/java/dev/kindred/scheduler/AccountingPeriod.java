package dev.kindred.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** The persisted boundaries, not a recalculated timezone offset, identify a budget period. */
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

    public static AccountingPeriod on(LocalDate date, ZoneId zone) {
        return new AccountingPeriod(date, zone, date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public static AccountingPeriod containing(Instant instant, ZoneId zone) {
        return on(instant.atZone(zone).toLocalDate(), zone);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
    }
}
