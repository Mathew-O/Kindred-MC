package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PeerProgressDialogueTest {

    @Test
    void cannotConferWithSelf() {
        UUID id = UUID.randomUUID();
        PeerProgressDialogue.PeerState state = new PeerProgressDialogue.PeerState(
                id, "Alice", OperatingMode.Mode.COMPANION, Personality.defaults(), "idle", 0, false, false);

        Optional<PeerProgressDialogue.Exchange> exchange = PeerProgressDialogue.confer(state, state, 42L);
        assertFalse(exchange.isPresent(), "Companion cannot confer with itself");
    }

    @Test
    void coordinatesOnActiveGatheringLogs() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PeerProgressDialogue.PeerState speaker = new PeerProgressDialogue.PeerState(
                a, "Alice", OperatingMode.Mode.COMPANION, Personality.defaults(), "gather_logs", 8, true, true);
        PeerProgressDialogue.PeerState peer = new PeerProgressDialogue.PeerState(
                b, "Bob", OperatingMode.Mode.COMPANION, Personality.defaults(), "idle", 0, true, false);

        Optional<PeerProgressDialogue.Exchange> exchange = PeerProgressDialogue.confer(speaker, peer, 0L);
        assertTrue(exchange.isPresent());
        PeerProgressDialogue.Exchange ex = exchange.orElseThrow();

        assertEquals(PeerProgressDialogue.Topic.GATHERING_COORDINATION, ex.topic());
        assertTrue(ex.openingRemark().contains("8 logs gathered"));
        assertTrue(ex.responseRemark().contains("Understood"));
        assertTrue(ex.memoryFactInitiator().contains("Bob acknowledged"));
        assertTrue(ex.memoryFactPeer().contains("Alice is gathering/carrying logs"));
    }

    @Test
    void progressionConferencesReflectMiningAffinities() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Personality miner = Personality.defaults().withTrait(Personality.Trait.MINING, 75).withTrait(Personality.Trait.HUMOR, 70);
        PeerProgressDialogue.PeerState speaker = new PeerProgressDialogue.PeerState(
                a, "Gimli", OperatingMode.Mode.COMPANION, miner, "idle", 0, false, false);
        PeerProgressDialogue.PeerState peer = new PeerProgressDialogue.PeerState(
                b, "Bob", OperatingMode.Mode.COMPANION, Personality.defaults(), "idle", 0, false, false);

        Optional<PeerProgressDialogue.Exchange> exchange = PeerProgressDialogue.confer(speaker, peer, 1L);
        assertTrue(exchange.isPresent());
        PeerProgressDialogue.Exchange ex = exchange.orElseThrow();

        assertEquals(PeerProgressDialogue.Topic.MINING_PROGRESSION, ex.topic());
        assertTrue(ex.openingRemark().contains("never dig straight down"));
    }

    @Test
    void independentModeMilestoneCoordination() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        PeerProgressDialogue.PeerState speaker = new PeerProgressDialogue.PeerState(
                a, "Loner", OperatingMode.Mode.INDEPENDENT, Personality.defaults(), "idle", 0, false, false);
        PeerProgressDialogue.PeerState peer = new PeerProgressDialogue.PeerState(
                b, "Helper", OperatingMode.Mode.COMPANION, Personality.defaults(), "idle", 0, false, false);

        Optional<PeerProgressDialogue.Exchange> exchange = PeerProgressDialogue.confer(speaker, peer, 3L);
        assertTrue(exchange.isPresent());
        PeerProgressDialogue.Exchange ex = exchange.orElseThrow();

        assertEquals(PeerProgressDialogue.Topic.INDEPENDENT_MILESTONES, ex.topic());
        assertTrue(ex.openingRemark().contains("personal milestones"));
        assertTrue(ex.responseRemark().contains("Owner support"));
    }
}
