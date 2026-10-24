package dev.kindred.core.survival;

import dev.kindred.core.construction.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A field made ready and sown (M-06a's farm ward): water poured into the channel down its middle, every cell of its
 * soil tilled into farmland and a seed sown on each. One pass at a time from what the player sees, so it carries on
 * from wherever the field stands; it remembers only when it poured and which cells it could not work. Pure.
 *
 * <p>The field is {@code size} cells square from its north-west corner, its soil the top layer of the ground at
 * {@code y}, the player's feet one above. The channel runs north to south down its middle column, a cell lower than
 * the field's surface only in that it is open: water there wets farmland four blocks either side, the whole field.
 * The water is poured from the field beside the channel's middle, while that ground is still soil; every cell is
 * then worked from the margin on its side of the channel, west of the field or east of it, never from on it, so no
 * farmland is trampled; from one side to the other it goes round the end of the field.
 */
public final class FieldWork {
    /** A cell of the field's soil: still soil, farmland bare, farmland sown, or something else there. */
    public enum Soil { DRY, FARMLAND, SOWN, OTHER }

    /**
     * What the player sees on a pass.
     *
     * @param feet  the block its feet are in
     * @param soil  each cell of the field but the channel, by its position in the soil's layer
     * @param wet   the channel's cells that hold water
     * @param hoe   it carries a hoe
     * @param seeds how many seeds it carries
     * @param water   how many buckets of water it carries
     * @param footing the places round the field, its margins and the rows past its ends, where its feet can stand
     * @param tick    the server's tick, for how long it has waited
     */
    public record Observation(Pos feet, Map<Pos, Soil> soil, Set<Pos> wet, boolean hoe, int seeds, int water, Set<Pos> footing, long tick) { }

    /** What to do next. */
    public sealed interface Step {
        /** Walk to {@code target}, its feet within {@code range} of it. */
        record Go(Pos target, double range, String why) implements Step { }

        /** Pour a bucket of water into the channel's cell {@code at}, in reach. */
        record Pour(Pos at, String why) implements Step { }

        /** Till the soil at {@code at}, in reach, with a hoe. */
        record Till(Pos at, String why) implements Step { }

        /** Sow a seed on the farmland at {@code at}, in reach. */
        record Sow(Pos at, String why) implements Step { }

        /** Stand a moment. */
        record Wait(String why) implements Step { }

        /** The field is done, or can go no further; the outcome in words, and how many cells stand sown. */
        record Done(String outcome, int sown) implements Step { }
    }

    /**
     * Within this, flat, of a cell its feet are close enough to work it: a player reaches four and a half blocks from
     * its eyes, and its eyes are two and a half above the soil.
     */
    static final double REACH = 4;
    /** Ticks it waits for water poured at the channel's middle to run to both its ends. */
    static final long FLOW = 100;
    /** Times a cell's work may fail before it is left. */
    static final int TRIES = 2;

    private final Pos corner;
    private final int size;
    private long pouredAt = -1;
    private boolean pourFailed;
    private final Map<Pos, Integer> failures = new HashMap<>();
    /** The cell the last step was for, which a failure counts against. */
    private Pos working;

    /** The field {@code size} cells square, its soil's north-west cell {@code corner}; an odd size, for the channel. */
    public FieldWork(Pos corner, int size) {
        if (size < 3 || size > 9 || size % 2 == 0) throw new IllegalArgumentException("A field 3 to 9 cells a side, an odd size so its channel runs down the middle");
        this.corner = corner;
        this.size = size;
    }

    /** The channel's column. */
    public int channelX() { return corner.x() + size / 2; }

    /** The channel's cells, north to south, in the soil's layer. */
    public List<Pos> channel() {
        var cells = new ArrayList<Pos>();
        for (int z = 0; z < size; z++) cells.add(corner.add(size / 2, 0, z));
        return cells;
    }

    /** The field's cells but the channel, in the soil's layer. */
    public List<Pos> cells() {
        var cells = new ArrayList<Pos>();
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) if (x != size / 2) cells.add(corner.add(x, 0, z));
        return cells;
    }

    /** Where it stands to work {@code cell}: on the margin on the cell's side of the channel, level with it. */
    public Pos stand(Pos cell) {
        int x = cell.x() < channelX() ? corner.x() - 1 : corner.x() + size;
        return new Pos(x, corner.y() + 1, cell.z());
    }

    /** The last step did not come off: walking to a cell, tilling or sowing it, or pouring the channel's water. */
    public void failed() {
        if (working != null) failures.merge(working, 1, Integer::sum);
        else pourFailed = true;
    }

    public Step next(Observation seen) {
        working = null;
        int sown = (int) seen.soil().values().stream().filter(s -> s == Soil.SOWN).count();
        // The channel first: tilled soil left dry turns back to dirt.
        if (!seen.wet().containsAll(channel())) {
            // Poured once water is seen in it, not once asked for: a step may be asked for again before it runs.
            if (!seen.wet().isEmpty()) {
                if (pouredAt < 0) pouredAt = seen.tick();
                if (seen.tick() - pouredAt <= FLOW) return new Step.Wait("Water running down the channel");
                return new Step.Done("The water poured did not run the channel's length", sown);
            }
            if (pourFailed) return new Step.Done("Could not pour water into the channel", sown);
            if (seen.water() <= 0) return new Step.Done("No water for the channel", sown);
            Pos middle = corner.add(size / 2, 0, size / 2);
            // From the soil beside the middle, as yet untilled; tilled, from the other side.
            var from = List.of(middle.add(-1, 1, 0), middle.add(1, 1, 0)).stream()
                    .filter(p -> seen.soil().getOrDefault(p.add(0, -1, 0), Soil.OTHER) == Soil.DRY).findFirst();
            if (from.isEmpty()) return new Step.Done("No soil beside the channel to pour its water from", sown);
            if (flat(seen.feet(), middle) <= 1.5) return new Step.Pour(middle, "Water into the channel");
            return new Step.Go(from.get(), 0.5, "Beside the channel's middle, to pour its water");
        }
        var left = seen.soil().entrySet().stream()
                .filter(e -> failures.getOrDefault(e.getKey(), 0) < TRIES && seen.footing().contains(stand(e.getKey())))
                .filter(e -> e.getValue() == Soil.DRY && seen.hoe() || e.getValue() == Soil.FARMLAND && seen.seeds() > 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparingDouble((Pos p) -> flat(seen.feet(), p)).thenComparingInt(Pos::z).thenComparingInt(Pos::x))
                .toList();
        if (left.isEmpty()) return new Step.Done(outcome(seen, sown), sown);
        // In reach from the margin it stands on: worked; else along the margin to the nearest.
        boolean onMargin = seen.feet().x() == corner.x() - 1 || seen.feet().x() == corner.x() + size;
        for (var cell : left) {
            if (!onMargin || flat(seen.feet(), cell) > REACH || (cell.x() < channelX()) != (seen.feet().x() < channelX())) continue;
            working = cell;
            return seen.soil().get(cell) == Soil.DRY ? new Step.Till(cell, "Till the field") : new Step.Sow(cell, "Sow the field");
        }
        // The nearest place on the margins to stand, so it works along one side before it goes round to the other.
        working = left.stream().min(Comparator.comparingDouble((Pos p) -> flat(seen.feet(), stand(p)))).orElseThrow();
        Pos to = stand(working);
        if (onMargin && to.x() != seen.feet().x()) {
            // Round the end of the field, never across it: a jump over the channel lands on farmland and tramples it.
            // Only by an end it can walk all along: past the farm ward's south end runs the stair of its way in.
            var ends = List.of(corner.z() - 1, corner.z() + size).stream().filter(z -> walkable(seen.footing(), z))
                    .sorted(Comparator.comparingInt(z -> Math.abs(seen.feet().z() - z) + Math.abs(to.z() - z))).toList();
            if (ends.isEmpty()) {
                // No way round: the other side is left.
                for (var cell : left) if (stand(cell).x() == to.x()) failures.put(cell, TRIES);
                return next(seen);
            }
            int end = ends.getFirst();
            Pos along = new Pos(seen.feet().z() == end ? to.x() : seen.feet().x(), corner.y() + 1, end);
            return new Step.Go(along, 0.5, "Round the end of the field");
        }
        return new Step.Go(to, 0.5, "Along the field's margin");
    }

    /** The row past an end of the field, {@code z}, stood on all along, from margin to margin. */
    private boolean walkable(Set<Pos> footing, int z) {
        for (int x = corner.x() - 1; x <= corner.x() + size; x++) if (!footing.contains(new Pos(x, corner.y() + 1, z))) return false;
        return true;
    }

    /** The places round the field its feet might stand, which a runner checks: its margins and the rows past its ends. */
    public List<Pos> around() {
        var at = new ArrayList<Pos>();
        int y = corner.y() + 1;
        for (int z = corner.z() - 1; z <= corner.z() + size; z++) {
            at.add(new Pos(corner.x() - 1, y, z));
            at.add(new Pos(corner.x() + size, y, z));
        }
        for (int x = corner.x(); x < corner.x() + size; x++) {
            at.add(new Pos(x, y, corner.z() - 1));
            at.add(new Pos(x, y, corner.z() + size));
        }
        return at;
    }

    private String outcome(Observation seen, int sown) {
        int cells = seen.soil().size();
        if (sown == cells) return "The field tilled and sown, " + sown + " cells";
        var why = new ArrayList<String>();
        if (!seen.hoe() && seen.soil().containsValue(Soil.DRY)) why.add("no hoe to till the rest");
        if (seen.seeds() <= 0 && seen.soil().containsValue(Soil.FARMLAND)) why.add("out of seeds");
        long other = seen.soil().values().stream().filter(s -> s == Soil.OTHER).count();
        if (other > 0) why.add(other + " not soil");
        long given = failures.values().stream().filter(n -> n >= TRIES).count();
        if (given > 0) why.add(given + " it could not work");
        return sown + " of " + cells + " cells sown" + (why.isEmpty() ? "" : ": " + String.join("; ", why));
    }

    private static double flat(Pos a, Pos b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
