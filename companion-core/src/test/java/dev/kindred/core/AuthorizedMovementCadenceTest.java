package dev.kindred.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In plain terms: checks when a moving player sprints and hops: always over terrain, and only now and then
 * for show.
 */
class AuthorizedMovementCadenceTest {
    private final SprintStride stride = new SprintStride();

    @Test
    void terrainJumpBypassesStrideTiming() {
        assertTrue(AuthorizedMovementCadence.requestJump(stride, MannerismState.Gait.WALK, true, true, 0, true));
    }

    @Test
    void habitHopUsesStrideWhenSprintJumping() {
        assertFalse(AuthorizedMovementCadence.requestJump(stride, MannerismState.Gait.SPRINT_JUMP, true, true, 0, false));
        assertFalse(AuthorizedMovementCadence.requestJump(stride, MannerismState.Gait.SPRINT_JUMP, true, true, 1, false));
        assertTrue(AuthorizedMovementCadence.requestJump(stride, MannerismState.Gait.SPRINT_JUMP, true, true, 2, false));
    }

    @Test
    void walkOrShuffleNeverSprints() {
        assertFalse(AuthorizedMovementCadence.sprint(MannerismState.Gait.SPRINT, false));
        assertFalse(AuthorizedMovementCadence.sprint(MannerismState.Gait.WALK, true));
    }
}
