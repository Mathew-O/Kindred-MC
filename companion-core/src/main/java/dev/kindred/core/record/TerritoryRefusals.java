package dev.kindred.core.record;

import java.util.List;

/**
 * In plain terms: the kinds of the World Record for refused attempts on a faction's land, and the bound that keeps them
 * from growing without end.
 *
 * Technical: F-34c2 (Session A's agreement, 2026-11-15). A {@code territory.refused} event is written where a refusal
 * stops a real attempt on land a faction owns: its actor who tried, its subject the owning faction, its place the
 * chunk, {@code data.what} one of {@link #WHAT}. It is never written from a planning query. Past the bound (thirty game
 * days, as settled action history, F-31b1) it is pruned on an agreeing check, with one {@code territory.pruned} tally
 * a game day (F-34c2a).
 */
public final class TerritoryRefusals {
    public static final String REFUSED = "territory.refused";
    public static final String PRUNED = "territory.pruned";
    /** What was tried and refused. */
    public static final List<String> WHAT = List.of("break", "place", "explode", "mob_change", "hit");

    private TerritoryRefusals() { }
}
