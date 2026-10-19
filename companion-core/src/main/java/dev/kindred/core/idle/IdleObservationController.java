package dev.kindred.core.idle;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure decision controller for IDLE_OBSERVING mode and in-game waiting states.
 */
public final class IdleObservationController {

    public static final int DEFAULT_IDLE_RADIUS_BLOCKS = 40;
    public static final int MIN_IDLE_RADIUS_BLOCKS = 8;
    public static final int MAX_IDLE_RADIUS_BLOCKS = 128;

    /** Cosine of ~10 degrees: dot product threshold for human looking at bot's eyes. */
    public static final double EYE_CONTACT_DOT_THRESHOLD = 0.985;
    public static final double EYE_CONTACT_MAX_DISTANCE_SQ = 16.0 * 16.0;

    public static final Set<String> SCAVENGEABLE_TRINKETS = Set.of(
            "minecraft:stick",
            "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
            "minecraft:cherry_sapling", "minecraft:dark_oak_sapling", "minecraft:acacia_sapling",
            "minecraft:feather", "minecraft:flint", "minecraft:wheat_seeds",
            "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:beetroot_seeds",
            "minecraft:sweet_berries", "minecraft:apple", "minecraft:bone",
            "minecraft:string", "minecraft:nautilus_shell", "minecraft:dandelion",
            "minecraft:poppy", "minecraft:cornflower", "minecraft:lily_of_the_valley"
    );

    public enum IdleSubState {
        EYE_CONTACT_LOCKED,
        PICKING_UP_TRINKET,
        ACKNOWLEDGING_FEATURE,
        STROLLING_IN_RADIUS,
        RETURNING_TO_RADIUS
    }

    public record IdleObservationInput(
            double botX, double botY, double botZ,
            double anchorX, double anchorY, double anchorZ,
            int configuredRadiusBlocks,
            Optional<HumanGazeObservation> gazingHuman,
            Optional<GroundTrinketObservation> nearbyTrinket,
            Optional<LandmarkSighting> newlySpottedLandmark,
            long currentTick
    ) {
        public IdleObservationInput {
            Objects.requireNonNull(gazingHuman, "gazingHuman");
            Objects.requireNonNull(nearbyTrinket, "nearbyTrinket");
            Objects.requireNonNull(newlySpottedLandmark, "newlySpottedLandmark");
        }
    }

    public record HumanGazeObservation(
            UUID humanId,
            String humanName,
            double eyeX, double eyeY, double eyeZ,
            double lookDotProduct,
            double distanceSq,
            boolean humanIsSneaking
    ) { }

    public record GroundTrinketObservation(
            String itemId,
            double x, double y, double z,
            double distanceSq
    ) { }

    public record LandmarkSighting(
            String kind,
            int x, int y, int z,
            String description
    ) { }

    public record IdleActionPlan(
            IdleSubState subState,
            boolean haltMovement,
            Optional<double[]> lookAtEyeTarget,
            boolean shouldSneakPulse,
            Optional<double[]> walkTowardTarget,
            Optional<String> ambientChatRemark
    ) { }

    public static IdleActionPlan evaluate(IdleObservationInput input) {
        Objects.requireNonNull(input, "input");
        int radius = Math.clamp(
                input.configuredRadiusBlocks() > 0 ? input.configuredRadiusBlocks() : DEFAULT_IDLE_RADIUS_BLOCKS,
                MIN_IDLE_RADIUS_BLOCKS,
                MAX_IDLE_RADIUS_BLOCKS
        );

        if (input.gazingHuman().isPresent()) {
            HumanGazeObservation gaze = input.gazingHuman().get();
            if (gaze.distanceSq() <= EYE_CONTACT_MAX_DISTANCE_SQ
                    && gaze.lookDotProduct() >= EYE_CONTACT_DOT_THRESHOLD) {
                boolean sneakPulse = gaze.humanIsSneaking() || (input.currentTick() % 40L < 5L);
                Optional<String> remark = input.newlySpottedLandmark()
                        .map(lm -> "Hey " + gaze.humanName() + "! I noticed a " + lm.description()
                                + " over at [" + lm.x() + ", " + lm.y() + ", " + lm.z() + "].");
                return new IdleActionPlan(
                        IdleSubState.EYE_CONTACT_LOCKED,
                        true,
                        Optional.of(new double[]{gaze.eyeX(), gaze.eyeY(), gaze.eyeZ()}),
                        sneakPulse,
                        Optional.empty(),
                        remark
                );
            }
        }

        double dxAnchor = input.anchorX() - input.botX();
        double dzAnchor = input.anchorZ() - input.botZ();
        double distSqFromAnchor = dxAnchor * dxAnchor + dzAnchor * dzAnchor;
        if (distSqFromAnchor > (double) (radius * radius)) {
            return new IdleActionPlan(
                    IdleSubState.RETURNING_TO_RADIUS,
                    false,
                    Optional.of(new double[]{input.anchorX(), input.botY() + 1.62, input.anchorZ()}),
                    false,
                    Optional.of(new double[]{input.anchorX(), input.anchorY(), input.anchorZ()}),
                    Optional.empty()
            );
        }

        if (input.nearbyTrinket().isPresent()) {
            GroundTrinketObservation trinket = input.nearbyTrinket().get();
            if (SCAVENGEABLE_TRINKETS.contains(trinket.itemId())) {
                double tx = trinket.x() - input.anchorX();
                double tz = trinket.z() - input.anchorZ();
                if (tx * tx + tz * tz <= (double) (radius * radius)) {
                    return new IdleActionPlan(
                            IdleSubState.PICKING_UP_TRINKET,
                            false,
                            Optional.of(new double[]{trinket.x(), trinket.y() + 0.25, trinket.z()}),
                            false,
                            Optional.of(new double[]{trinket.x(), trinket.y(), trinket.z()}),
                            Optional.empty()
                    );
                }
            }
        }

        if (input.newlySpottedLandmark().isPresent()) {
            LandmarkSighting lm = input.newlySpottedLandmark().get();
            return new IdleActionPlan(
                    IdleSubState.ACKNOWLEDGING_FEATURE,
                    true,
                    Optional.of(new double[]{lm.x() + 0.5, lm.y() + 0.5, lm.z() + 0.5}),
                    false,
                    Optional.empty(),
                    Optional.of("Noted " + lm.description() + " at [" + lm.x() + ", " + lm.y() + ", " + lm.z() + "].")
            );
        }

        return new IdleActionPlan(
                IdleSubState.STROLLING_IN_RADIUS,
                false,
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty()
        );
    }

    private IdleObservationController() { }
}
