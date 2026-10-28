package dev.kindred.core;

/**
 * In plain terms: the rhythm of sprint-jumping: one hop after landing and taking a step, then a pause, so it
 * looks like a person running rather than a pogo stick.
 *
 * Technical: When a sprint-jump habit may take one hop. A hop is a single request after the body has landed
 * and taken a step, then not again until the gap has passed. Requesting a jump on every grounded tick is a
 * pogo, not a stride.
 */
public final class SprintStride {
    /** Ground ticks required after a landing before the hop, so the landing reads as a footfall. */
    public static final int SETUP_TICKS = 3;
    /** Ticks from one hop to the next: the air time plus a step. */
    public static final int GAP_TICKS = 12;

    private int grounded;
    private int lastHop = -GAP_TICKS;

    /**
     * @param sprintJump the habit and the world both allow a jump
     * @param striding   actually covering ground, not shuffling, turning a corner, or closing the last few blocks
     * @return true on the one tick the jump should be requested
     */
    public boolean tick(boolean sprintJump, boolean onGround, boolean striding, int age) {
        if (!onGround) {
            grounded = 0;
            return false;
        }
        grounded++;
        if (!sprintJump || !striding) return false;
        if (grounded < SETUP_TICKS) return false;
        if (age - lastHop < GAP_TICKS) return false;
        lastHop = age;
        grounded = 0;
        return true;
    }
}
