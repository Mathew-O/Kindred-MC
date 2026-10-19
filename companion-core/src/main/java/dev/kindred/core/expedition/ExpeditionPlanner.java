package dev.kindred.core.expedition;

import dev.kindred.core.TaskPreferences.Priority;

/**
 * Pure policy deciding expedition readiness, return trigger evaluation, and return route planning.
 */
public final class ExpeditionPlanner {
    public static final double DEFAULT_MAX_EXPEDITION_RADIUS = 64.0;
    public static final int INVENTORY_CAPACITY_RETURN_THRESHOLD = 32; // >= 32 of 36 slots occupied
    public static final float LOW_HEALTH_RETURN_THRESHOLD = 8.0f; // <= 8 HP
    public static final int MIN_EXPEDITION_FOOD_RATIONS = 2; // at least 2 food items to depart

    private ExpeditionPlanner() {}

    public static boolean isReadyForExpedition(ExpeditionObservation obs) {
        // Must have sufficient health, food rations, not be during dusk/night/thunderstorm, and have inventory space
        if (obs.health() < 14.0f) return false;
        if (obs.foodCount() < MIN_EXPEDITION_FOOD_RATIONS) return false;
        if (obs.isNightOrDusk() || obs.isThundering()) return false;
        if (obs.occupiedSlots() >= INVENTORY_CAPACITY_RETURN_THRESHOLD) return false;
        return true;
    }

    public static ReturnReason evaluateReturnTriggers(ExpeditionState state, ExpeditionObservation obs) {
        // 1. Night or dusk approaching or thunderstorm
        if (obs.isNightOrDusk()) {
            long tod = obs.timeOfDay() % 24000;
            return tod >= 12000 ? ReturnReason.NIGHT : ReturnReason.DUSK_APPROACHING;
        }
        if (obs.isThundering()) {
            return ReturnReason.THUNDERSTORM;
        }

        // 2. Critical health
        if (obs.health() <= LOW_HEALTH_RETURN_THRESHOLD) {
            return ReturnReason.LOW_HEALTH;
        }

        // 3. Low rations & hungry
        if (obs.foodCount() == 0 && obs.foodLevel() <= 10) {
            return ReturnReason.LOW_RATIONS;
        }

        // 4. Inventory capacity full
        if (obs.occupiedSlots() >= INVENTORY_CAPACITY_RETURN_THRESHOLD) {
            return ReturnReason.INVENTORY_FULL;
        }

        // 5. Exceeded maximum bounded expedition radius from home anchor
        double distFromHome = state.homeAnchor().horizontalDistanceTo(obs.x(), obs.z());
        if (distFromHome >= state.maxRadius()) {
            return ReturnReason.MAX_RADIUS_REACHED;
        }

        return ReturnReason.NONE;
    }

    /** Computes best cardinal direction from (fromX, fromZ) toward (targetX, targetZ). */
    public static String calculateDirection(int fromX, int fromZ, int targetX, int targetZ) {
        int dx = targetX - fromX;
        int dz = targetZ - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        } else {
            return dz > 0 ? "south" : "north";
        }
    }

    /** Plans next exploration or return step based on state and current observation. */
    public static ExpeditionPlan plan(ExpeditionState state, ExpeditionObservation obs) {
        double distToHome = state.homeAnchor().horizontalDistanceTo(obs.x(), obs.z());

        // Check if arrived at home
        if (state.status() == ExpeditionStatus.RETURNING && distToHome <= 2.0) {
            state.arriveAtBase();
            return ExpeditionPlan.idle();
        }

        // Check if idle at base and ready to explore
        if (state.status() == ExpeditionStatus.IDLE_AT_BASE) {
            if (isReadyForExpedition(obs)) {
                state.startExpedition();
            } else {
                return ExpeditionPlan.idle();
            }
        }

        // If currently exploring outward, evaluate return triggers
        if (state.status() == ExpeditionStatus.EXPLORING_OUTWARD) {
            ReturnReason returnTrigger = evaluateReturnTriggers(state, obs);
            if (returnTrigger != ReturnReason.NONE) {
                state.triggerReturn(returnTrigger);
            }
        }

        // Handle RETURNING
        if (state.status() == ExpeditionStatus.RETURNING) {
            // Determine target: next breadcrumb or homeAnchor directly
            int targetX = state.homeAnchor().x();
            int targetZ = state.homeAnchor().z();

            // If we have breadcrumbs, see if the latest breadcrumb is closer than home
            if (!state.breadcrumbs().isEmpty()) {
                Waypoint targetBc = state.lastBreadcrumb().get();
                if (targetBc.horizontalDistanceTo(obs.x(), obs.z()) <= 2.0) {
                    state.popLastBreadcrumb();
                    if (!state.breadcrumbs().isEmpty()) {
                        targetBc = state.lastBreadcrumb().get();
                    } else {
                        targetBc = state.homeAnchor();
                    }
                }
                targetX = targetBc.x();
                targetZ = targetBc.z();
            }

            String dir = calculateDirection(obs.x(), obs.z(), targetX, targetZ);
            double dist = Math.sqrt((targetX - obs.x()) * (targetX - obs.x()) + (targetZ - obs.z()) * (targetZ - obs.z()));
            int blocks = Math.min(3, Math.max(1, (int) Math.round(dist)));

            // Priority based on urgency of return reason
            Priority priority;
            double need;
            if (state.returnReason() == ReturnReason.NIGHT || state.returnReason() == ReturnReason.THUNDERSTORM || state.returnReason() == ReturnReason.LOW_HEALTH) {
                priority = Priority.URGENT;
                need = 92.0;
            } else if (state.returnReason() == ReturnReason.DUSK_APPROACHING) {
                priority = Priority.PROJECT;
                need = 82.0;
            } else { // INVENTORY_FULL, MAX_RADIUS_REACHED, LOW_RATIONS
                priority = Priority.PROJECT;
                need = 76.0;
            }

            return new ExpeditionPlan("native_traverse", dir, blocks, priority, need, state.returnReason());
        }

        // Handle EXPLORING_OUTWARD
        // Record current position as breadcrumb if far enough from last breadcrumb
        if (state.breadcrumbs().isEmpty() || state.lastBreadcrumb().get().horizontalDistanceTo(obs.x(), obs.z()) >= 4.0) {
            state.recordBreadcrumb(Waypoint.of("crumb_" + state.breadcrumbs().size(), obs.x(), obs.y(), obs.z(), obs.dimension(), WaypointKind.BREADCRUMB, obs.timeOfDay()));
        }

        // Outward direction calculation: pick direction that increases distance from home without exceeding maxRadius
        String outwardDir = calculateDirection(state.homeAnchor().x(), state.homeAnchor().z(), obs.x(), obs.z());
        if (distToHome < 1.0) {
            outwardDir = "east";
        }
        int blocks = Math.min(2, Math.max(1, (int) Math.round(state.maxRadius() - distToHome)));
        if (blocks <= 0) blocks = 1;

        return new ExpeditionPlan("native_traverse", outwardDir, blocks, Priority.ROUTINE, 58.0, ReturnReason.NONE);
    }
}
