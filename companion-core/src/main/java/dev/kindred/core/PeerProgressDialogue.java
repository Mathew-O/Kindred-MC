package dev.kindred.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Peer-to-peer progression dialogue between companions (player simulation).
 * Companions privately confer about gathering, tool progression, storage, and milestones,
 * recording mutual facts in memory without spamming public chat.
 */
public final class PeerProgressDialogue {

    public enum Topic {
        GATHERING_COORDINATION,
        MINING_PROGRESSION,
        FARMING_SUPPLIES,
        INDEPENDENT_MILESTONES,
        COMBAT_AWARENESS
    }

    public record PeerState(
            UUID companionId,
            String name,
            OperatingMode.Mode mode,
            Personality personality,
            String activeSkill,
            int logCount,
            boolean hasStorage,
            boolean hasPatch) {
        public PeerState {
            Objects.requireNonNull(companionId, "companionId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(personality, "personality");
        }
    }

    public record Exchange(
            Topic topic,
            String openingRemark,
            String responseRemark,
            String memoryFactInitiator,
            String memoryFactPeer) {
        public Exchange {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(openingRemark, "openingRemark");
            Objects.requireNonNull(responseRemark, "responseRemark");
            Objects.requireNonNull(memoryFactInitiator, "memoryFactInitiator");
            Objects.requireNonNull(memoryFactPeer, "memoryFactPeer");
        }
    }

    private PeerProgressDialogue() { }

    /**
     * Determines whether two nearby companions can confer on progression, and generates
     * a grounded exchange based on their active chores, personality affinities, and modes.
     */
    public static Optional<Exchange> confer(PeerState speaker, PeerState peer, long seed) {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(peer, "peer");
        if (speaker.companionId().equals(peer.companionId())) return Optional.empty();

        int roll = (int) Math.floorMod(seed, 5);

        // 1. Gathering & Storage coordination (if either is working with logs/patch)
        if ("gather_logs".equals(speaker.activeSkill()) || speaker.logCount() > 0 || "gather_logs".equals(peer.activeSkill())) {
            String open = speaker.logCount() > 0
                    ? "I've got " + speaker.logCount() + " logs gathered; heading toward assigned storage soon."
                    : "I'm focusing on clearing logs in our allocated patch.";
            String reply = peer.hasStorage()
                    ? "Understood. The storage container is ready; I'll keep your path clear."
                    : "Good progress. Let's make sure our inventory space stays managed.";
            return Optional.of(new Exchange(
                    Topic.GATHERING_COORDINATION,
                    open,
                    reply,
                    peer.name() + " acknowledged log gathering progress.",
                    speaker.name() + " is gathering/carrying logs for storage."));
        }

        // 2. Mining & Tool Progression
        if (speaker.personality().mining() >= 60 || peer.personality().mining() >= 60 || roll == 1) {
            boolean humorous = speaker.personality().humor() >= 65;
            String open = humorous
                    ? "We'll need more stone and iron if we want decent tools. Remember rule one: never dig straight down."
                    : "We should prioritize stone and iron progression soon so we have proper equipment.";
            String reply = peer.personality().mining() >= 50
                    ? "Agreed. I'll scout exposed veins along our boundary."
                    : "Sounds like a solid plan. Keep some torches handy.";
            return Optional.of(new Exchange(
                    Topic.MINING_PROGRESSION,
                    open,
                    reply,
                    "Agreed with " + peer.name() + " on prioritizing stone and iron tool progression.",
                    speaker.name() + " proposed gathering stone and iron for tool upgrades."));
        }

        // 3. Farming & Sustenance
        if (speaker.personality().farming() >= 60 || peer.personality().farming() >= 60 || roll == 2) {
            String open = "We ought to keep an eye on food supplies and crop yields before long trips.";
            String reply = "Right. Running out of sustenance in the field is an easy mistake to avoid.";
            return Optional.of(new Exchange(
                    Topic.FARMING_SUPPLIES,
                    open,
                    reply,
                    "Discussed keeping crop yields and food stocked with " + peer.name() + ".",
                    speaker.name() + " reminded about food supplies and crop yields."));
        }

        // 4. Independent Mode Milestones
        if (speaker.mode() == OperatingMode.Mode.INDEPENDENT || peer.mode() == OperatingMode.Mode.INDEPENDENT || roll == 3) {
            String open = speaker.mode() == OperatingMode.Mode.INDEPENDENT
                    ? "I'm working on personal milestones—establishing my shelter and stockpiling basics."
                    : "How is your independent setup coming along?";
            String reply = peer.mode() == OperatingMode.Mode.INDEPENDENT
                    ? "Making steady progress. Foundation first, then farming and better gear."
                    : "Owner support is going well; good luck with your build milestones.";
            return Optional.of(new Exchange(
                    Topic.INDEPENDENT_MILESTONES,
                    open,
                    reply,
                    "Shared independent milestone status with " + peer.name() + ".",
                    speaker.name() + " discussed progress milestones and foundations."));
        }

        // 5. Combat & Defense Awareness
        boolean combative = speaker.personality().combativeness() >= 60 || peer.personality().combativeness() >= 60;
        String open = combative
                ? "Night always brings creepers and skeletons. Keep an eye on unlit pockets."
                : "Safe travels around the boundary; stay aware of hostile creatures.";
        String reply = "Understood. Weapon is ready and guard is up.";
        return Optional.of(new Exchange(
                Topic.COMBAT_AWARENESS,
                open,
                reply,
                "Exchanged safety and defense watch with " + peer.name() + ".",
                speaker.name() + " checked in on combat vigilance and safe boundaries."));
    }
}
