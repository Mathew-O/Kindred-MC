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

    /**
     * Where activity at now is charged, given the latest saved period: that period while it lasts, even after a
     * zone edit; once it has ended, the local day in zone containing now, unless that day would start before the
     * latest period's end, when a zone edit waits for its next whole local day. A clock behind the latest period
     * charges nothing until it catches up.
     */
    public static PeriodTransition transition(Instant now, ZoneId zone, Optional<AccountingPeriod> latest) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(latest, "latest");
        if (latest.isEmpty()) {
            return PeriodTransition.open(AccountingPeriod.containing(now, zone));
        }
        AccountingPeriod last = latest.orElseThrow();
        if (last.contains(now)) {
            return PeriodTransition.current(last);
        }
        if (now.isBefore(last.startInclusive())) {
            return PeriodTransition.clockBehind(last.startInclusive());
        }
        return openPeriod(now, zone, last.endExclusive()).map(PeriodTransition::open)
                .orElseGet(() -> PeriodTransition.zonePending(firstPeriodFrom(zone, last.endExclusive()).startInclusive()));
    }

    /** The first local day in zone that starts at or after closedThrough. */
    public static AccountingPeriod firstPeriodFrom(ZoneId zone, Instant closedThrough) {
        Objects.requireNonNull(closedThrough, "closedThrough");
        AccountingPeriod containing = AccountingPeriod.containing(closedThrough, zone);
        return containing.startInclusive().isBefore(closedThrough)
                ? AccountingPeriod.on(containing.localDate().plusDays(1), zone) : containing;
    }

    /** Returns the currently open window, clipped to the persisted accounting period. */
    public static Optional<TimeInterval> activeInterval(
            ActivitySchedule schedule, AccountingPeriod period, Instant now) {
        if (!schedule.zone().equals(period.zone())) {
            throw new IllegalArgumentException("Timezone change must wait for a new accounting period");
        }
        return windowInterval(schedule.window(), period, now);
    }

    /**
     * The open stretch of window containing now, clipped to the period, its local times read in the zone the period
     * is kept in: after a zone edit the current period keeps its own zone to its end (F-11f). No window is the whole
     * period; empty outside the window or the period.
     */
    public static Optional<TimeInterval> windowInterval(Optional<ActivityWindow> window, AccountingPeriod period, Instant now) {
        Objects.requireNonNull(window, "window");
        if (!period.contains(now)) {
            return Optional.empty();
        }
        if (window.isEmpty()) {
            return Optional.of(new TimeInterval(period.startInclusive(), period.endExclusive()));
        }
        LocalDate date = now.atZone(period.zone()).toLocalDate();
        for (LocalDate startDate : List.of(date.minusDays(1), date)) {
            Optional<TimeInterval> resolved = windowOn(startDate, window.orElseThrow(), period.zone());
            if (resolved.isPresent() && resolved.orElseThrow().contains(now)) {
                TimeInterval interval = resolved.orElseThrow();
                return Optional.of(new TimeInterval(
                        later(interval.startInclusive(), period.startInclusive()),
                        earlier(interval.endExclusive(), period.endExclusive())));
            }
        }
        return Optional.empty();
    }

    /** The first start of window after now in zone; a day whose window a daylight-saving gap collapses is passed over. */
    public static Instant nextWindowStart(ActivityWindow window, ZoneId zone, Instant now) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(now, "now");
        LocalDate date = now.atZone(zone).toLocalDate();
        for (int day = -1; day <= 2; day++) {
            Optional<TimeInterval> resolved = windowOn(date.plusDays(day), window, zone);
            if (resolved.isPresent() && resolved.orElseThrow().startInclusive().isAfter(now)) {
                return resolved.orElseThrow().startInclusive();
            }
        }
        throw new IllegalStateException("No activity window opens within two days");
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
