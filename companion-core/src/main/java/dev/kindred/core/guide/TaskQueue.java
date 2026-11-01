package dev.kindred.core.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In plain terms: the player's list of things to get ready for the tasks ahead, such as a bow and arrows
 * before the End, worked on as chances come up while it gets on with its main job. It holds each task's own
 * needs and a few more that an optional AI adviser added; nothing on it is ever handed out.
 *
 * Technical: The follow-up queue: what a player means to have for the objectives ahead, worked on while it
 * gets on with what it is doing. Two kinds of entry: each objective's own requirements ({@link
 * MinecraftGuide#requirements}), assessed afresh on every pass from what the player holds, and wants the
 * optional strategist added, kept until they are met or their objective is behind the player. The planner
 * breaks them into chunks ({@link NeedsAssessment}) and does each when the chance comes (a chicken in sight
 * while feathers are wanted) or when the objective is next. A want is never a grant: everything still has to
 * be gathered or crafted. Immutable.
 *
 * @param advised the strategist's wants, oldest first
 */
public record TaskQueue(List<Want> advised) {
    /** Wants the strategist may keep queued at once, and add in one reply. */
    public static final int MAX_ADVISED = 6, MAX_PER_ADVICE = 3;

    public static final TaskQueue EMPTY = new TaskQueue(List.of());

    public TaskQueue {
        var kept = new ArrayList<>(List.copyOf(advised));
        while (kept.size() > MAX_ADVISED) kept.removeFirst();
        advised = List.copyOf(kept);
    }

    /**
     * With up to {@link #MAX_PER_ADVICE} of {@code wants} added. A need already queued is replaced by the newer want,
     * which goes to the back; past {@link #MAX_ADVISED} the oldest go.
     */
    public TaskQueue advise(List<Want> wants) {
        Objects.requireNonNull(wants);
        var kept = new ArrayList<>(advised);
        int taken = 0;
        for (Want want : wants) {
            if (want == null || taken >= MAX_PER_ADVICE) continue;
            kept.removeIf(old -> old.need() == want.need());
            kept.add(want);
            taken++;
        }
        return new TaskQueue(kept);
    }

    /** Without the advised wants now met in hand, or whose objective is no longer ahead. */
    public TaskQueue settle(Map<Need, Integer> held, Set<Objective> ahead) {
        var kept = new ArrayList<Want>();
        for (Want want : advised) {
            if (!ahead.contains(want.objective())) continue;
            if (held.getOrDefault(want.need(), 0) >= want.count()) continue;
            kept.add(want);
        }
        return kept.size() == advised.size() ? this : new TaskQueue(kept);
    }

    /** Every want for the objectives ahead: their own requirements first, in order, then what was advised. */
    public List<Want> wants(Set<Objective> ahead) {
        var out = new ArrayList<Want>();
        for (Objective objective : Objective.values()) {
            if (!ahead.contains(objective)) continue;
            for (var requirement : MinecraftGuide.requirements(objective)) {
                out.add(new Want(requirement.need(), requirement.count(), objective, requirement.why()));
            }
        }
        for (Want want : advised) if (ahead.contains(want.objective())) out.add(want);
        return List.copyOf(out);
    }

    /** The chunks of work still to do for the objectives ahead, from what is held. */
    public List<NeedsAssessment.Chunk> chunks(Set<Objective> ahead, Map<Need, Integer> held) {
        return NeedsAssessment.chunks(wants(ahead), held);
    }

    /** The queue in words, for the strategist's report: each want with what is held of it, then the work left. */
    public String describe(Set<Objective> ahead, Map<Need, Integer> held) {
        var wants = wants(ahead);
        if (wants.isEmpty()) return "Nothing queued for what is ahead.";
        var out = new StringBuilder("Wanted for what is ahead:");
        for (Want want : wants) {
            out.append(' ').append(want.need().word()).append(' ').append(Math.min(want.count(), held.getOrDefault(want.need(), 0)))
                    .append(" of ").append(want.count()).append(" (").append(want.objective().words());
            if (!want.why().isEmpty()) out.append(": ").append(want.why());
            out.append(advised.contains(want) ? "; queued by you)" : ")").append(';');
        }
        out.setLength(out.length() - 1);
        var chunks = chunks(ahead, held);
        if (chunks.isEmpty()) return out.append(". All in hand.").toString();
        out.append(". Still to do:");
        for (var chunk : chunks) {
            out.append(' ').append(chunk.kind() == NeedsAssessment.Kind.GATHER ? "gather " : "craft ")
                    .append(chunk.need().word()).append(' ').append(chunk.count()).append(',');
        }
        out.setLength(out.length() - 1);
        return out.append('.').toString();
    }
}
