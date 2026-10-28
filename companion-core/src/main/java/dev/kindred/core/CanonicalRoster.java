package dev.kindred.core;

import dev.kindred.core.idle.IdleObservationController;
import dev.kindred.core.progression.PlaystyleProfile;
import dev.kindred.core.team.CollaborationMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * In plain terms: the three built-in AI players, Kindred, Sol and Vesp: their names, titles and starting
 * personalities, plus the switches a server can flip for them. Some of those switches are stored for future
 * features and not read by gameplay yet.
 *
 * Technical: Defines the three canonical autonomous AI players (Kindred, Sol, Vesp)
 * and runtime-toggable gameplay settings.
 */
public final class CanonicalRoster {

    public static final String KINDRED_NAME = "Kindred";
    public static final String SOL_NAME = "Sol";
    public static final String VESP_NAME = "Vesp";

    /** One built-in player: name, title and starting personality. */
    public record RosterPreset(
            String name,
            String title,
            Personality defaultPersonality
    ) {
        /**
         * The player's id in a given world, always the same for the same world, so it is recognised again
         * after a restart.
         */
        public UUID deterministicId(UUID worldId) {
            Objects.requireNonNull(worldId, "worldId");
            return UUID.nameUUIDFromBytes(("kindred-canonical-player:" + worldId + ":" + name)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    public static final RosterPreset KINDRED = new RosterPreset(
            KINDRED_NAME,
            "Heart & Homestead Anchor",
            new Personality(75, 60, 65, 80, 60, 65, 85, 80, 70, 0L)
    );

    public static final RosterPreset SOL = new RosterPreset(
            SOL_NAME,
            "Solar Vanguard & Expedition Pioneer",
            new Personality(90, 40, 85, 65, 70, 90, 70, 45, 60, 0L)
    );

    public static final RosterPreset VESP = new RosterPreset(
            VESP_NAME,
            "Evening Star Artisan & Deep Scholar",
            new Personality(80, 75, 95, 55, 50, 55, 75, 50, 95, 0L)
    );

    public static final List<RosterPreset> TRIO = List.of(KINDRED, SOL, VESP);

    /**
     * Global and per-player toggable settings controlled via /aiplayer gui or commands.
     */
    public record RuntimeSettings(
            PlaystyleProfile playstyleProfile,
            CollaborationMode collaborationMode,
            boolean bossProgressionEnabled,
            boolean foreverWorldKaizenEnabled,
            boolean combatHotSwapEnabled,
            boolean lecternAtlasSyncEnabled,
            boolean speedrunCommunityTechEnabled,
            int idleRadiusBlocks,
            String architecturalPresetOverride
    ) {
        public RuntimeSettings {
            idleRadiusBlocks = Math.clamp(
                    idleRadiusBlocks > 0 ? idleRadiusBlocks : IdleObservationController.DEFAULT_IDLE_RADIUS_BLOCKS,
                    IdleObservationController.MIN_IDLE_RADIUS_BLOCKS,
                    IdleObservationController.MAX_IDLE_RADIUS_BLOCKS
            );
        }

        /** The switches a new player starts with. */
        public static RuntimeSettings defaultsForNewPlayer() {
            return new RuntimeSettings(
                    PlaystyleProfile.IDLE_OBSERVING,
                    CollaborationMode.COOP_TEAM,
                    true,
                    true,
                    true,
                    true,
                    true,
                    IdleObservationController.DEFAULT_IDLE_RADIUS_BLOCKS,
                    "AUTO_PERSONALITY"
            );
        }

        /** The same switches with a different playstyle. */
        public RuntimeSettings withPlaystyle(PlaystyleProfile profile) {
            return new RuntimeSettings(
                    Objects.requireNonNull(profile, "profile"),
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        /** Turns boss fights as part of the player's progression on or off. */
        public RuntimeSettings toggleBossProgression() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    !bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        /** Flips the long-lived-world setting (stored for future use). */
        public RuntimeSettings toggleForeverWorldKaizen() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    !foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        /** Turns the speedrunning community's techniques on or off. */
        public RuntimeSettings toggleSpeedrun() {
            PlaystyleProfile next = playstyleProfile == PlaystyleProfile.SPEEDRUN_ANY_PERCENT
                    ? PlaystyleProfile.OPTIMAL_SURVIVAL
                    : PlaystyleProfile.SPEEDRUN_ANY_PERCENT;
            return withPlaystyle(next);
        }

        /** Switches between playing as a team and playing solo. */
        public RuntimeSettings toggleCollaboration() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode == CollaborationMode.COOP_TEAM
                            ? CollaborationMode.SOLO_INDEPENDENT
                            : CollaborationMode.COOP_TEAM,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        /** Flips quick weapon swapping in combat (stored for future use). */
        public RuntimeSettings toggleCombatHotSwap() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    !combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }


        /** The same switches with a different idle wandering radius, kept within limits. */
        public RuntimeSettings withIdleRadiusBlocks(int blocks) {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    speedrunCommunityTechEnabled,
                    blocks,
                    architecturalPresetOverride
            );
        }
    }

    private CanonicalRoster() { }
}
