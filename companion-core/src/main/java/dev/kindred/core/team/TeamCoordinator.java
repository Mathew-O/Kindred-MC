package dev.kindred.core.team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In plain terms: the team's shared job board: projects broken into tasks, which player has taken which, and
 * the materials set aside for each. A task waits until the tasks before it are done, only one player can hold
 * it, and it is taken back from a player who dies, stops or takes too long. It also keeps the places the team
 * has shared.
 *
 * Technical: Coordinates shared multi-player projects, task dependency evaluation,
 * atomic single-assignee ownership, material reservations, and fail-safe
 * revocation upon player death, stop, or lease expiration.
 */
public class TeamCoordinator {
    private final Map<ProjectId, SharedProject> projects = new ConcurrentHashMap<>();
    private final Map<String, dev.kindred.core.expedition.Waypoint> sharedWaypoints = new ConcurrentHashMap<>();
    private final StockLedger stockLedger;

    /** A job board keeping its materials count in the given ledger. */
    public TeamCoordinator(StockLedger stockLedger) {
        this.stockLedger = Objects.requireNonNull(stockLedger, "stockLedger cannot be null");
    }

    /** A job board with an empty materials count of its own. */
    public TeamCoordinator() {
        this(new StockLedger());
    }

    /** Shares a named place with the whole team, replacing any of the same name. */
    public void shareWaypoint(dev.kindred.core.expedition.Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint cannot be null");
        sharedWaypoints.put(waypoint.name(), waypoint);
    }

    /** A place the team shares, by name. */
    public Optional<dev.kindred.core.expedition.Waypoint> getSharedWaypoint(String name) {
        return Optional.ofNullable(sharedWaypoints.get(name));
    }

    /** Every place the team shares. */
    public Collection<dev.kindred.core.expedition.Waypoint> listSharedWaypoints() {
        return Collections.unmodifiableCollection(new ArrayList<>(sharedWaypoints.values()));
    }

    public StockLedger stockLedger() {
        return stockLedger;
    }

    /**
     * Each change to a shared project, for the runtime to save in order through its one writer: the project as it was
     * and as it is, and the reservations of the tasks that changed, as they stand now.
     */
    public interface Changes {
        void changed(SharedProject before, SharedProject after, List<StockReservation> reservations);
    }

    private Changes changes = (before, after, reservations) -> { };

    /** Sets who hears of each change to a project, so it can be saved. */
    public synchronized void onChange(Changes listener) {
        this.changes = Objects.requireNonNull(listener, "listener");
    }

    /**
     * A project as it stands, loaded or new; not a change. A reservation its tasks still hold, held across a restart
     * for a player whose last action is uncertain, is active in the stock ledger again.
     */
    public synchronized void registerProject(SharedProject project) {
        Objects.requireNonNull(project, "project");
        projects.put(project.id(), project);
        for (SharedTask task : project.tasks().values()) task.stockReservation().ifPresent(stockLedger::restore);
    }

    /** Records a change and reports it. */
    private void put(SharedProject before, SharedProject after) {
        projects.put(after.id(), after);
        var touched = new java.util.LinkedHashMap<UUID, StockReservation>();
        for (SharedTask task : after.tasks().values()) {
            SharedTask was = before.tasks().get(task.id());
            if (task.equals(was)) continue;
            task.stockReservation().ifPresent(r -> touched.putIfAbsent(r.reservationId(), r));
            if (was != null) was.stockReservation().ifPresent(r -> touched.putIfAbsent(r.reservationId(), r));
        }
        var now = new ArrayList<StockReservation>();
        for (StockReservation r : touched.values()) now.add(stockLedger.getReservation(r.reservationId()).orElse(r));
        changes.changed(before, after, List.copyOf(now));
    }

    /** A project by its id, if there is one. */
    public synchronized Optional<SharedProject> getProject(ProjectId id) {
        return Optional.ofNullable(projects.get(id));
    }

    /** Every project the team has. */
    public synchronized Collection<SharedProject> listProjects() {
        return Collections.unmodifiableCollection(new ArrayList<>(projects.values()));
    }

    /** Adds a task to a project the team already has. */
    public synchronized void addTask(ProjectId projectId, SharedTask task) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(task, "task");
        SharedProject proj = projects.get(projectId);
        if (proj == null) {
            throw new IllegalArgumentException("Unknown project " + projectId);
        }
        put(proj, proj.withTask(task));
    }

    /** A task by its id, in whichever project it is. */
    public synchronized Optional<SharedTask> findTask(TaskId taskId) {
        for (SharedProject proj : projects.values()) {
            Optional<SharedTask> t = proj.getTask(taskId);
            if (t.isPresent()) return t;
        }
        return Optional.empty();
    }

    /**
     * Discovers all open tasks across active projects whose dependencies
     * are completely satisfied and whose material requirements can be reserved.
     */
    public synchronized List<SharedTask> findEligibleTasks(UUID playerId) {
        List<SharedTask> eligible = new ArrayList<>();
        for (SharedProject proj : projects.values()) {
            if (proj.state() == SharedProject.ProjectState.COMPLETED
                    || proj.state() == SharedProject.ProjectState.CANCELLED) {
                continue;
            }
            for (SharedTask task : proj.tasks().values()) {
                if (task.state() != SharedTask.TaskState.OPEN) {
                    continue;
                }
                // Verify all dependencies are completed
                boolean depsSatisfied = true;
                for (TaskId depId : task.dependencies()) {
                    SharedTask dep = proj.tasks().get(depId);
                    if (dep == null || dep.state() != SharedTask.TaskState.COMPLETED) {
                        depsSatisfied = false;
                        break;
                    }
                }
                if (!depsSatisfied) continue;

                // Check required stock reservation if specified in arguments
                if (task.arguments().containsKey("reserved_item")) {
                    String item = task.arguments().get("reserved_item");
                    int count = Integer.parseInt(task.arguments().getOrDefault("reserved_count", "1"));
                    if (stockLedger.unreservedCount(item) < count) {
                        continue; // Insufficient stock: would double-spend
                    }
                }

                eligible.add(task);
            }
        }
        return List.copyOf(eligible);
    }

    /**
     * Atomically claims a task for a player, establishing sole assignment ownership
     * and reserving required physical materials. Contending claims are rejected.
     */
    public synchronized Optional<SharedTask> claimTask(TaskId taskId, UUID playerId, long currentTick, long leaseTicks) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(playerId, "playerId");

        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            SharedTask task = proj.tasks().get(taskId);
            if (task == null) continue;

            // A finished or cancelled project's tasks are not to be taken up, as discovery already holds.
            if (proj.state() == SharedProject.ProjectState.COMPLETED || proj.state() == SharedProject.ProjectState.CANCELLED) {
                return Optional.empty();
            }

            // Atomic state check
            if (task.state() != SharedTask.TaskState.OPEN && task.state() != SharedTask.TaskState.REVOKED) {
                return Optional.empty(); // Contention: already claimed
            }

            // Verify dependencies
            for (TaskId depId : task.dependencies()) {
                SharedTask dep = proj.tasks().get(depId);
                if (dep == null || dep.state() != SharedTask.TaskState.COMPLETED) {
                    return Optional.empty(); // Dependencies not yet completed
                }
            }

            // Reserve material if needed
            Optional<StockReservation> reservation = Optional.empty();
            if (task.arguments().containsKey("reserved_item")) {
                String item = task.arguments().get("reserved_item");
                int count = Integer.parseInt(task.arguments().getOrDefault("reserved_count", "1"));
                String dest = task.arguments().get("destination");
                UUID resId = UUID.randomUUID();
                reservation = stockLedger.tryReserve(resId, taskId, proj.id(), playerId, item, count, dest);
                if (reservation.isEmpty()) {
                    return Optional.empty(); // Insufficient unreserved stock; prevented double spending
                }
            }

            SharedTask claimed = task.claim(playerId, currentTick, leaseTicks, reservation);
            SharedProject updatedProj = proj.withTask(claimed);
            if (updatedProj.state() == SharedProject.ProjectState.OPEN) {
                updatedProj = updatedProj.withState(SharedProject.ProjectState.IN_PROGRESS);
            }
            put(proj, updatedProj);
            return Optional.of(claimed);
        }
        return Optional.empty();
    }

    /** Marks a task as begun; only the player that took it can begin it. */
    public synchronized Optional<SharedTask> startTask(TaskId taskId, UUID playerId) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(playerId, "playerId");

        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            SharedTask task = proj.tasks().get(taskId);
            if (task == null) continue;

            if (!playerId.equals(task.assignedPlayerId()) || task.state() != SharedTask.TaskState.CLAIMED) {
                return Optional.empty();
            }

            SharedTask started = task.start();
            put(proj, proj.withTask(started));
            return Optional.of(started);
        }
        return Optional.empty();
    }

    /** Completes a task with nothing observed of its output: the task is done, and no stock is credited for it. */
    public synchronized boolean completeTask(TaskId taskId, UUID playerId, String completionEvidence) {
        return completeTask(taskId, playerId, completionEvidence, java.util.Map.of());
    }

    /**
     * Completes a task, crediting to stock what was observed of its output, never more than it declares it produces:
     * a declared {@code produces_count} is a plan, not an item. Partial output credits what was seen; none, nothing.
     */
    public synchronized boolean completeTask(TaskId taskId, UUID playerId, String completionEvidence, java.util.Map<String, Integer> observed) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(observed, "observed");

        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            SharedTask task = proj.tasks().get(taskId);
            if (task == null) continue;

            if (!playerId.equals(task.assignedPlayerId())) {
                return false; // Wrong assignee
            }
            if (task.state() != SharedTask.TaskState.CLAIMED && task.state() != SharedTask.TaskState.IN_PROGRESS) {
                return false;
            }

            // Fulfill reservation if present
            task.stockReservation().ifPresent(res -> stockLedger.fulfill(res.reservationId()));

            // What it produced, as observed, up to what it declares
            if (task.arguments().containsKey("produces_item")) {
                String producedItem = task.arguments().get("produces_item");
                int declared = Integer.parseInt(task.arguments().getOrDefault("produces_count", "1"));
                int seen = Math.max(0, Math.min(declared, observed.getOrDefault(producedItem, 0)));
                if (seen > 0) stockLedger.addStock(producedItem, seen);
            }

            SharedTask completed = task.complete(completionEvidence);
            SharedProject updatedProj = proj.withTask(completed);
            if (updatedProj.areAllTasksCompleted()) {
                updatedProj = updatedProj.withState(SharedProject.ProjectState.COMPLETED);
            }
            put(proj, updatedProj);
            return true;
        }
        return false;
    }

    /**
     * Takes a task back from whoever has it, frees its materials and opens it to anyone again; a finished
     * task cannot be taken back.
     */
    public synchronized boolean revokeTask(TaskId taskId, String reason) {
        Objects.requireNonNull(taskId, "taskId");

        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            SharedTask task = proj.tasks().get(taskId);
            if (task == null) continue;

            if (task.state() == SharedTask.TaskState.COMPLETED) {
                return false; // Cannot revoke completed task
            }

            // Release any active reservation
            task.stockReservation().ifPresent(res -> stockLedger.release(res.reservationId()));

            SharedTask revoked = task.reopen();
            put(proj, proj.withTask(revoked));
            return true;
        }
        return false;
    }

    /**
     * Handles player death: revokes any held assignments and releases all
     * stock reservations immediately, preventing double spending and permanent lockouts.
     */
    public synchronized void handlePlayerDeath(UUID playerId) {
        if (playerId == null) return;
        stockLedger.releaseAllForPlayer(playerId);
        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            boolean changed = false;
            SharedProject current = proj;
            for (SharedTask task : proj.tasks().values()) {
                if (playerId.equals(task.assignedPlayerId())
                        && (task.state() == SharedTask.TaskState.CLAIMED || task.state() == SharedTask.TaskState.IN_PROGRESS)) {
                    SharedTask revoked = task.revoke("Player died in field; assignment revoked");
                    current = current.withTask(revoked);
                    changed = true;
                }
            }
            if (changed) {
                put(proj, current);
            }
        }
    }

    /**
     * Handles player stop: cleanly revokes active assignments and releases reserved stock.
     */
    public synchronized void handlePlayerStop(UUID playerId) {
        if (playerId == null) return;
        stockLedger.releaseAllForPlayer(playerId);
        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            boolean changed = false;
            SharedProject current = proj;
            for (SharedTask task : proj.tasks().values()) {
                if (playerId.equals(task.assignedPlayerId())
                        && (task.state() == SharedTask.TaskState.CLAIMED || task.state() == SharedTask.TaskState.IN_PROGRESS)) {
                    SharedTask revoked = task.revoke("Player stopped; assignment revoked");
                    current = current.withTask(revoked);
                    changed = true;
                }
            }
            if (changed) {
                put(proj, current);
            }
        }
    }

    /**
     * Checks all in-progress tasks for lease expiry, releasing abandoned tasks.
     */
    public synchronized void handleTickExpiry(long currentTick) {
        for (var entry : projects.entrySet()) {
            SharedProject proj = entry.getValue();
            boolean changed = false;
            SharedProject current = proj;
            for (SharedTask task : proj.tasks().values()) {
                if (task.isExpired(currentTick)) {
                    task.stockReservation().ifPresent(res -> stockLedger.release(res.reservationId()));
                    SharedTask reopened = task.reopen();
                    current = current.withTask(reopened);
                    changed = true;
                }
            }
            if (changed) {
                put(proj, current);
            }
        }
    }

    /** The task a player has taken and not yet finished, if any. */
    public synchronized Optional<SharedTask> getPlayerCurrentTask(UUID playerId) {
        if (playerId == null) return Optional.empty();
        for (SharedProject proj : projects.values()) {
            for (SharedTask task : proj.tasks().values()) {
                if (playerId.equals(task.assignedPlayerId())
                        && (task.state() == SharedTask.TaskState.CLAIMED || task.state() == SharedTask.TaskState.IN_PROGRESS)) {
                    return Optional.of(task);
                }
            }
        }
        return Optional.empty();
    }
}
