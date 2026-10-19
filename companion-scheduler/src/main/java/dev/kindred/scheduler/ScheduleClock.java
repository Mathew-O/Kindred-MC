package dev.kindred.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure, injectable-time calculations. This class grants neither admission nor world authority. */
public final class ScheduleClock {
    private ScheduleClock() { }

    /**
     * The caller durably stores closedThrough and advances it whenever a period closes.
     * Rejected periods cannot reopen on rollback. A zone edit waits for a complete local
     * period whose start does not overlap the prior zone's closed accounting period.
     */
    public static Optional<AccountingPeriod> openPeriod(Instant now, ZoneId zone, Instant closedThrough) {
        Objects.requireNonNull(closedThrough, "closedThrough");
        AccountingPeriod candidate = AccountingPeriod.containing(now, zone);
        return candidate.startInclusive().isBefore(closedThrough) ? Optional.empty() : Optional.of(candidate);
    }

    /** Returns the currently open window, clipped to the persisted accounting period. */
    public static Optional<TimeInterval> activeInterval(
            ActivitySchedule schedule, AccountingPeriod period, Instant now) {
        if (!schedule.zone().equals(period.zone())) {
            throw new IllegalArgumentException("Timezone change must wait for a new accounting period");
        }
        if (!period.contains(now)) {
            return Optional.empty();
        }
        if (schedule.window().isEmpty()) {
            return Optional.of(new TimeInterval(period.startInclusive(), period.endExclusive()));
        }
        ActivityWindow window = schedule.window().orElseThrow();
        LocalDate date = now.atZone(schedule.zone()).toLocalDate();
        for (LocalDate startDate : List.of(date.minusDays(1), date)) {
            Optional<TimeInterval> resolved = windowOn(startDate, window, schedule.zone());
            if (resolved.isPresent() && resolved.orElseThrow().contains(now)) {
                TimeInterval interval = resolved.orElseThrow();
                return Optional.of(new TimeInterval(
                        later(interval.startInclusive(), period.startInclusive()),
                        earlier(interval.endExclusive(), period.endExclusive())));
            }
        }
        return Optional.empty();
    }

    /** Empty when a daylight-saving gap collapses the entire configured window. */
    public static Optional<TimeInterval> windowOn(LocalDate date, ActivityWindow window, ZoneId zone) {
        Instant start = resolveBoundary(date.atTime(window.start()), zone, true);
        LocalDate endDate = window.overnight() ? date.plusDays(1) : date;
        Instant end = resolveBoundary(endDate.atTime(window.end()), zone, false);
        return start.isBefore(end) ? Optional.of(new TimeInterval(start, end)) : Optional.empty();
    }

    /** Gaps move to the first valid instant; overlaps expand to earlier start / later end. */
    public static Instant resolveBoundary(LocalDateTime local, ZoneId zone, boolean start) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return zone.getRules().getTransition(local).getInstant();
        }
        Comparator<Instant> order = Comparator.naturalOrder();
        return offsets.stream().map(offset -> local.toInstant(offset))
                .min(start ? order : order.reversed()).orElseThrow();
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
