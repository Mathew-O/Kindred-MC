package dev.kindred.core.expedition;

import dev.kindred.core.TaskPreferences.Priority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionPlannerTest {

    private final Waypoint home = Waypoint.of("home_base", 100, 64, 200, "minecraft:overworld", WaypointKind.HOME_BED, 0);

    @Test
    @DisplayName("Readiness checks: requires minimum health, food rations, daytime and inventory space")
    void readinessChecks() {
        // Ready state
        ExpeditionObservation ready = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 1000, false, false, 20.0f, 20.0f, 5, 20, 10, 36, true);
        assertTrue(ExpeditionPlanner.isReadyForExpedition(ready));

        // Low health (< 14 HP)
        ExpeditionObservation lowHealth = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 1000, false, false, 12.0f, 20.0f, 5, 20, 10, 36, true);
        assertFalse(ExpeditionPlanner.isReadyForExpedition(lowHealth));

        // Insufficient food rations (< 2 items)
        ExpeditionObservation lowFood = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 1000, false, false, 20.0f, 20.0f, 1, 20, 10, 36, true);
        assertFalse(ExpeditionPlanner.isReadyForExpedition(lowFood));

        // Night / dusk time
        ExpeditionObservation night = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 13000, false, false, 20.0f, 20.0f, 5, 20, 10, 36, true);
        assertFalse(ExpeditionPlanner.isReadyForExpedition(night));

        // Thunderstorm
        ExpeditionObservation storm = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 1000, true, true, 20.0f, 20.0f, 5, 20, 10, 36, true);
        assertFalse(ExpeditionPlanner.isReadyForExpedition(storm));

        // Full inventory
        ExpeditionObservation fullInv = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 1000, false, false, 20.0f, 20.0f, 5, 20, 33, 36, true);
        assertFalse(ExpeditionPlanner.isReadyForExpedition(fullInv));
    }

    @Test
    @DisplayName("Return triggers: night, dusk, thunderstorm, inventory full, low health, and max radius")
    void returnTriggers() {
        ExpeditionState state = new ExpeditionState(home, 64.0);
        state.startExpedition();

        // 1. Daytime, healthy, empty inventory: no return
        ExpeditionObservation normal = new ExpeditionObservation(120, 64, 200, "minecraft:overworld", 4000, false, false, 20.0f, 20.0f, 4, 20, 10, 36, true);
        assertEquals(ReturnReason.NONE, ExpeditionPlanner.evaluateReturnTriggers(state, normal));

        // 2. Dusk approaching (11800)
        ExpeditionObservation dusk = new ExpeditionObservation(120, 64, 200, "minecraft:overworld", 11800, false, false, 20.0f, 20.0f, 4, 20, 10, 36, true);
        assertEquals(ReturnReason.DUSK_APPROACHING, ExpeditionPlanner.evaluateReturnTriggers(state, dusk));

        // 3. Nighttime (13000)
        ExpeditionObservation night = new ExpeditionObservation(120, 64, 200, "minecraft:overworld", 13000, false, false, 20.0f, 20.0f, 4, 20, 10, 36, true);
        assertEquals(ReturnReason.NIGHT, ExpeditionPlanner.evaluateReturnTriggers(state, night));

        // 4. Thunderstorm
        ExpeditionObservation storm = new ExpeditionObservation(120, 64, 200, "minecraft:overworld", 2000, true, true, 20.0f, 20.0f, 4, 20, 10, 36, true);
        assertEquals(ReturnReason.THUNDERSTORM, ExpeditionPlanner.evaluateReturnTriggers(state, storm));

        // 5. Critical low health (<= 8.0f)
        ExpeditionObservation lowHealth = new ExpeditionObservation(120, 64, 200, "minecraft:overworld", 2000, false, false, 6.0f, 20.0f, 4, 20, 10, 36, true);
        assertEquals(ReturnReason.LOW_HEALTH, ExpeditionPlanner.evaluateReturnTriggers(state, lowHealth));

        // 6. Inventory capacity full (>= 32 slots occupied)
        ExpeditionObservation fullInv = new ExpeditionObservation(120, 64, 200, "minecraft:overworld", 2000, false, false, 20.0f, 20.0f, 4, 20, 33, 36, true);
        assertEquals(ReturnReason.INVENTORY_FULL, ExpeditionPlanner.evaluateReturnTriggers(state, fullInv));

        // 7. Exceeded max radius (distance >= 64 blocks)
        ExpeditionObservation farAway = new ExpeditionObservation(170, 64, 200, "minecraft:overworld", 2000, false, false, 20.0f, 20.0f, 4, 20, 10, 36, true);
        assertEquals(ReturnReason.MAX_RADIUS_REACHED, ExpeditionPlanner.evaluateReturnTriggers(state, farAway));
    }

    @Test
    @DisplayName("Outward exploration records breadcrumbs and bounds motion within max radius")
    void outwardExplorationAndBreadcrumbs() {
        ExpeditionState state = new ExpeditionState(home, 50.0);
        ExpeditionObservation atHome = new ExpeditionObservation(100, 64, 200, "minecraft:overworld", 1000, false, false, 20.0f, 20.0f, 5, 20, 5, 36, true);

        ExpeditionPlan plan1 = ExpeditionPlanner.plan(state, atHome);
        assertEquals(ExpeditionStatus.EXPLORING_OUTWARD, state.status());
        assertEquals("native_traverse", plan1.action());
        assertEquals(Priority.ROUTINE, plan1.priority());

        // Player moves to (110, 64, 200)
        ExpeditionObservation step1 = new ExpeditionObservation(110, 64, 200, "minecraft:overworld", 1050, false, false, 20.0f, 20.0f, 5, 20, 5, 36, true);
        ExpeditionPlan plan2 = ExpeditionPlanner.plan(state, step1);
        assertEquals("native_traverse", plan2.action());
        assertFalse(state.breadcrumbs().isEmpty());
    }

    @Test
    @DisplayName("Return trigger routes player toward home with urgent priority on nightfall")
    void nightfallTriggerPlansUrgentReturn() {
        ExpeditionState state = new ExpeditionState(home, 64.0);
        state.startExpedition();
        // Add a breadcrumb at (115, 64, 200)
        state.recordBreadcrumb(Waypoint.of("crumb_1", 115, 64, 200, "minecraft:overworld", WaypointKind.BREADCRUMB, 1000));

        // Player is at (130, 64, 200) when night falls (time 13000)
        ExpeditionObservation nightObs = new ExpeditionObservation(130, 64, 200, "minecraft:overworld", 13000, false, false, 20.0f, 20.0f, 5, 20, 10, 36, true);
        ExpeditionPlan plan = ExpeditionPlanner.plan(state, nightObs);

        assertEquals(ExpeditionStatus.RETURNING, state.status());
        assertEquals(ReturnReason.NIGHT, state.returnReason());
        assertEquals("native_traverse", plan.action());
        // Heading west back toward home at x=100
        assertEquals("west", plan.direction());
        assertEquals(Priority.URGENT, plan.priority());
        assertTrue(plan.need() >= 90.0);
    }

    @Test
    @DisplayName("Arrival at home resets expedition state to IDLE_AT_BASE")
    void arrivalAtHomeResetsState() {
        ExpeditionState state = new ExpeditionState(home, 64.0);
        state.startExpedition();
        state.triggerReturn(ReturnReason.INVENTORY_FULL);

        // Player arrives back at home (101, 64, 200) <= 2.0 blocks away
        ExpeditionObservation homeArrival = new ExpeditionObservation(101, 64, 200, "minecraft:overworld", 3000, false, false, 20.0f, 20.0f, 5, 20, 32, 36, true);
        ExpeditionPlan plan = ExpeditionPlanner.plan(state, homeArrival);

        assertEquals(ExpeditionStatus.IDLE_AT_BASE, state.status());
        assertEquals(ReturnReason.NONE, state.returnReason());
        assertEquals("none", plan.action());
    }

    @Test
    @DisplayName("Direction calculation chooses dominant cardinal axis")
    void directionCalculation() {
        assertEquals("east", ExpeditionPlanner.calculateDirection(0, 0, 10, 2));
        assertEquals("west", ExpeditionPlanner.calculateDirection(10, 0, 0, 2));
        assertEquals("south", ExpeditionPlanner.calculateDirection(0, 0, 2, 10));
        assertEquals("north", ExpeditionPlanner.calculateDirection(0, 10, 2, 0));
    }
}
