package dev.kindred.core.idle;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks what a player does with free time when it has nothing to do.
 */
class IdleObservationControllerTest {

    @Test
    void eyeContactLockTakesPriorityOverRadiusReturn() {
        var gaze = new IdleObservationController.HumanGazeObservation(
                UUID.randomUUID(),
                "Steve",
                0.0, 1.62, 0.0,
                0.99,
                4.0,
                false
        );
        var input = new IdleObservationController.IdleObservationInput(
                100.0, 64.0, 100.0,
                0.0, 64.0, 0.0,
                40,
                Optional.of(gaze),
                Optional.empty(),
                Optional.empty(),
                12L
        );
        var plan = IdleObservationController.evaluate(input);
        assertEquals(IdleObservationController.IdleSubState.EYE_CONTACT_LOCKED, plan.subState());
        assertTrue(plan.haltMovement());
        assertTrue(plan.lookAtEyeTarget().isPresent());
    }

    @Test
    void returnsToAnchorWhenOutsideDefaultRadius() {
        var input = new IdleObservationController.IdleObservationInput(
                50.0, 64.0, 0.0,
                0.0, 64.0, 0.0,
                40,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1L
        );
        var plan = IdleObservationController.evaluate(input);
        assertEquals(IdleObservationController.IdleSubState.RETURNING_TO_RADIUS, plan.subState());
        assertTrue(plan.walkTowardTarget().isPresent());
    }

    @Test
    void picksUpStickTrinketInsideRadius() {
        var trinket = new IdleObservationController.GroundTrinketObservation(
                "minecraft:stick",
                5.0, 64.0, 5.0,
                9.0
        );
        var input = new IdleObservationController.IdleObservationInput(
                0.0, 64.0, 0.0,
                0.0, 64.0, 0.0,
                40,
                Optional.empty(),
                Optional.of(trinket),
                Optional.empty(),
                1L
        );
        var plan = IdleObservationController.evaluate(input);
        assertEquals(IdleObservationController.IdleSubState.PICKING_UP_TRINKET, plan.subState());
    }
}
