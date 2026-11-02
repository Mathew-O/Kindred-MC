package dev.kindred.design.planning;

import dev.kindred.design.model.DesignModel;
import dev.kindred.design.model.DesignModel.*;
import java.util.*;

/** Bounded, deterministic flat-site candidate search. Unknown, protected, and hazardous cells are excluded. */
public final class LayoutPlanner {
    public record Request(String requestId, Box site, Pos accessAnchor, int width, int depth, int candidates,
                          Set<Pos> preserved, Set<Pos> reservedCorridor) {
        public Request {
            requestId = DesignModel.id(requestId); Objects.requireNonNull(site); Objects.requireNonNull(accessAnchor);
            preserved = Set.copyOf(preserved); reservedCorridor = Set.copyOf(reservedCorridor);
            if (width < 3 || depth < 3 || width > 32 || depth > 32 || candidates < 1 || candidates > 3)
                throw new IllegalArgumentException("Invalid bounded layout request");
            if (!site.contains(accessAnchor) || !preserved.stream().allMatch(site::contains)
                    || !reservedCorridor.stream().allMatch(site::contains)) throw new IllegalArgumentException("Layout outside site");
        }
    }
    public record Candidate(Box footprint, Pos entrance, List<Pos> route, int score) {
        public Candidate { route = List.copyOf(route); }
    }
    public record Result(List<Candidate> candidates, List<String> conflicts) {
        public Result { candidates = List.copyOf(candidates); conflicts = List.copyOf(conflicts); }
    }

    public Result plan(Request request, Observation observation) {
        requireObservation(request, observation);
        List<Candidate> candidates = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (int z = request.site().min().z(); z <= request.site().max().z() - request.depth() + 1; z++) {
            for (int x = request.site().min().x(); x <= request.site().max().x() - request.width() + 1; x++) {
                Box footprint = new Box(new Pos(x, request.accessAnchor().y() - 1, z),
                        new Pos(x + request.width() - 1, request.accessAnchor().y() + 3, z + request.depth() - 1));
                if (!request.site().contains(footprint.min()) || !request.site().contains(footprint.max())) continue;
                if (intersects(footprint, request.preserved()) || intersects(footprint, request.reservedCorridor())) continue;
                Pos entrance = new Pos(x + request.width() / 2, request.accessAnchor().y(), z);
                List<Pos> route = route(request.accessAnchor(), entrance, request.site(), observation, footprint);
                if (route.isEmpty()) { conflicts.add("no-known-safe-route:" + footprint.min().key()); continue; }
                if (!flatAndClear(footprint, observation)) { conflicts.add("unknown-or-blocked-footprint:" + footprint.min().key()); continue; }
                candidates.add(new Candidate(footprint, entrance, route, route.size()));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).thenComparing(c -> c.footprint().min()));
        if (candidates.size() > request.candidates()) candidates = new ArrayList<>(candidates.subList(0, request.candidates()));
        return new Result(candidates, candidates.isEmpty() ? List.copyOf(conflicts) : List.of());
    }
    private static void requireObservation(Request request, Observation observation) {
        if (!request.site().equals(observation.bounds())) throw new IllegalArgumentException("Observation coverage must equal layout site");
        if (!request.site().contains(request.accessAnchor())) throw new IllegalArgumentException("Anchor outside observation");
    }
    private static boolean intersects(Box box, Set<Pos> points) { return points.stream().anyMatch(box::contains); }
    private static boolean flatAndClear(Box box, Observation o) {
        for (Pos p : box.cells()) {
            if (p.y() == box.min().y() - 1) continue;
            if (p.y() == box.min().y() && o.cell(p).orElse(null) != Cell.SOLID) return false;
            if (p.y() > box.min().y() && o.cell(p).orElse(null) != Cell.AIR) return false;
            if (o.protectedCells().contains(p)) return false;
        }
        return true;
    }
    private static List<Pos> route(Pos start, Pos goal, Box site, Observation o, Box blocked) {
        if (blocked.contains(start) || !walkable(start, o, blocked)) return List.of();
        Map<Pos, Pos> previous = new HashMap<>(); ArrayDeque<Pos> todo = new ArrayDeque<>(); todo.add(start); previous.put(start, null);
        while (!todo.isEmpty()) {
            Pos current = todo.removeFirst(); if (current.equals(goal)) break;
            for (Pos next : current.horizontalNeighbors()) if (site.contains(next) && !previous.containsKey(next)
                    && (next.equals(goal) ? walkable(next, o, null) : walkable(next, o, blocked))) { previous.put(next, current); todo.add(next); }
        }
        if (!previous.containsKey(goal)) return List.of();
        List<Pos> path = new ArrayList<>(); for (Pos p = goal; p != null; p = previous.get(p)) path.add(p); Collections.reverse(path); return path;
    }
    private static boolean walkable(Pos p, Observation o, Box blocked) {
        return (blocked == null || !blocked.contains(p)) && !o.protectedCells().contains(p) && o.cell(p).orElse(null) == Cell.AIR
                && o.cell(p.above()).orElse(null) == Cell.AIR && o.cell(p.below()).orElse(null) == Cell.SOLID;
    }
}
