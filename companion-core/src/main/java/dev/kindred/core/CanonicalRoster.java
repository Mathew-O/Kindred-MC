package dev.kindred.core;

import dev.kindred.core.idle.IdleObservationController;
import dev.kindred.core.progression.PlaystyleProfile;
import dev.kindred.core.team.CollaborationMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Defines the three canonical autonomous AI players (Kindred, Sol, Vesp)
 * and runtime-toggable gameplay settings.
 */
public final class CanonicalRoster {

    public static final String KINDRED_NAME = "Kindred";
    public static final String SOL_NAME = "Sol";
    public static final String VESP_NAME = "Vesp";

    public record RosterPreset(
            String name,
            String title,
            Personality defaultPersonality
    ) {
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
            boolean seedLocatorEnabled,
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

        public static RuntimeSettings defaultsForNewPlayer() {
            return new RuntimeSettings(
                    PlaystyleProfile.IDLE_OBSERVING,
                    CollaborationMode.COOP_TEAM,
                    true,
                    true,
                    true,
                    true,
                    false,
                    true,
                    IdleObservationController.DEFAULT_IDLE_RADIUS_BLOCKS,
                    "AUTO_PERSONALITY"
            );
        }

        public RuntimeSettings withPlaystyle(PlaystyleProfile profile) {
            return new RuntimeSettings(
                    Objects.requireNonNull(profile, "profile"),
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleBossProgression() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    !bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleForeverWorldKaizen() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    !foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleSpeedrun() {
            PlaystyleProfile next = playstyleProfile == PlaystyleProfile.SPEEDRUN_ANY_PERCENT
                    ? PlaystyleProfile.OPTIMAL_SURVIVAL
                    : PlaystyleProfile.SPEEDRUN_ANY_PERCENT;
            return withPlaystyle(next);
        }

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
                    seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleCombatHotSwap() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    !combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings toggleSeedLocator() {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    !seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    idleRadiusBlocks,
                    architecturalPresetOverride
            );
        }

        public RuntimeSettings withIdleRadiusBlocks(int blocks) {
            return new RuntimeSettings(
                    playstyleProfile,
                    collaborationMode,
                    bossProgressionEnabled,
                    foreverWorldKaizenEnabled,
                    combatHotSwapEnabled,
                    lecternAtlasSyncEnabled,
                    seedLocatorEnabled,
                    speedrunCommunityTechEnabled,
                    blocks,
                    architecturalPresetOverride
            );
        }
    }

    private CanonicalRoster() { }
}
