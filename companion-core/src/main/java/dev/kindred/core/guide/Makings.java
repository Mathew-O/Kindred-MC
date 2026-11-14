package dev.kindred.core.guide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: before a player sets a crafting table down to make something, whether what it carries makes that
 * thing there: yes, yes once its logs are cut into planks, or no. A table is put down only for a yes.
 *
 * Technical: F-25c7a. A recipe as its slots, each the set of item ids that fill it, against what is held by item id.
 * {@link Choice#HELD}: every slot filled from what is held. {@link Choice#PLANKS_FIRST}: not so, but filled once the
 * logs held are made into planks (four a log, by the 2x2 grid, which needs no table). {@link Choice#NONE}: neither. On
 * seed 303 Sol, three white wool and logs but no planks, put a table down for a bed it could not make there, and its
 * "take workstations along" picked it straight up, 35 times in one stuck spell. Pure: no game objects.
 */
public final class Makings {
    private Makings() { }

    /** What a recipe asks of the player before a table is any use to it. */
    public enum Choice {
        /** Made from what it holds: a table is worth setting down for it. */
        HELD,
        /** Made once its logs are planks: those first, in the 2x2 grid; no table yet. */
        PLANKS_FIRST,
        /** Not made from what it holds, nor with its logs as planks: no table for it. */
        NONE
    }

    /**
     * {@code slots}: one set of acceptable item ids per filled slot of the recipe. {@code held}: item id to count.
     * {@code planksOf}: a log's id to the id of the planks it makes (four a log); a log not in it makes none.
     */
    public static Choice of(List<Set<String>> slots, Map<String, Integer> held, Map<String, String> planksOf) {
        Objects.requireNonNull(slots);
        Objects.requireNonNull(held);
        Objects.requireNonNull(planksOf);
        if (fills(slots, held)) return Choice.HELD;
        var withPlanks = new HashMap<String, Integer>(held);
        boolean any = false;
        for (var entry : held.entrySet()) {
            String planks = planksOf.get(entry.getKey());
            if (planks == null || entry.getValue() <= 0) continue;
            withPlanks.remove(entry.getKey());
            withPlanks.merge(planks, 4 * entry.getValue(), Integer::sum);
            any = true;
        }
        return any && fills(slots, withPlanks) ? Choice.PLANKS_FIRST : Choice.NONE;
    }

    /**
     * Whether every slot can be given one item from {@code held}, each item used once a count. The most particular
     * slots are filled first, from the item that the fewest other slots could take: a recipe's slots are few (nine at
     * most) and their sets either one item or one tag, so this is exact for every vanilla crafting recipe.
     */
    static boolean fills(List<Set<String>> slots, Map<String, Integer> held) {
        var left = new HashMap<String, Integer>(held);
        var order = new ArrayList<Set<String>>(slots);
        order.removeIf(Set::isEmpty);
        order.sort(Comparator.comparingInt(Set::size));
        for (var slot : order) {
            String best = null;
            int demand = Integer.MAX_VALUE;
            for (String item : slot) {
                if (left.getOrDefault(item, 0) <= 0) continue;
                int wanted = 0;
                for (var other : order) if (other.contains(item)) wanted++;
                if (wanted < demand) {
                    demand = wanted;
                    best = item;
                }
            }
            if (best == null) return false;
            left.merge(best, -1, Integer::sum);
        }
        return true;
    }
}
