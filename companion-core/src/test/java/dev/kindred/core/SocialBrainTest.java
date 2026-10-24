package dev.kindred.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SocialBrainTest {
    private final SocialProfile profile = SocialProfile.defaults(UUID.randomUUID());
    private final SocialSnapshot.Contact owner = new SocialSnapshot.Contact(UUID.randomUUID(), "Owner", SocialSnapshot.Kind.OWNER);

    @Test
    void silenceAndSleepingNeverProduceAmbientSpeechButAllowBoundedDirectReplies() {
        SocialBrain brain = new SocialBrain();
        var active = snapshot(true, true, "Zombie", owner);
        assertTrue(brain.observe(snapshot(false, true, "Zombie", owner), profile, 0).isEmpty());
        assertTrue(brain.observe(active, profile.withAmbient(false), 0).isEmpty());
        assertTrue(brain.observe(active, profile.withTrait(Personality.Trait.SOCIABILITY, 0), 0).isEmpty());
        assertTrue(brain.admitDirectReply(0));
        assertFalse(brain.admitDirectReply(2_999));
        assertTrue(brain.admitDirectReply(3_000));
    }

    @Test
    void privateConversationDoesNotGreetOtherPlayersOrCompanions() {
        for (var kind : List.of(SocialSnapshot.Kind.PLAYER, SocialSnapshot.Kind.COMPANION)) {
            SocialBrain brain = new SocialBrain();
            var other = new SocialSnapshot.Contact(UUID.randomUUID(), "Other", kind);
            assertTrue(brain.observe(snapshot(true, false, null, other), profile, 0).isEmpty());
            assertEquals(other.id(), brain.observe(snapshot(true, false, null, other), profile.withPublic(true), 0)
                    .orElseThrow().subject());
        }
    }

    @Test
    void salientFactsWinAndOldEventsAreNotReplayedAfterCooldown() {
        SocialBrain brain = new SocialBrain();
        var speech = brain.observe(snapshot(true, true, "Zombie", owner), profile, 0).orElseThrow();
        assertEquals(SocialBrain.Topic.THREAT, speech.topic());
        brain.didSpeak(speech, profile, 0);
        assertTrue(brain.observe(snapshot(true, true, null, owner), profile, 119_999).isEmpty());
        assertTrue(brain.observe(snapshot(true, true, null, owner), profile, 120_000).isEmpty());
    }

    @Test
    void pendingObservationsExpireAndDisappearingContactsCannotBeGreeted() {
        SocialBrain brain = new SocialBrain();
        assertTrue(brain.observe(snapshot(true, false, null, owner), profile, 0).isPresent());
        assertTrue(brain.observe(snapshot(true, false, null), profile, 1_000).isEmpty());
        SocialBrain weather = new SocialBrain();
        assertTrue(weather.observe(snapshot(true, true, null), profile, 0).isPresent());
        assertTrue(weather.observe(snapshot(true, true, null), profile, 10_001).isEmpty());
    }

    @Test
    void sociabilityChangesFrequencyWithoutCrossingOneMessagePerMinute() {
        SocialBrain outgoing = new SocialBrain();
        SocialBrain quiet = new SocialBrain();
        var high = profile.withTrait(Personality.Trait.SOCIABILITY, 100);
        var low = profile.withTrait(Personality.Trait.SOCIABILITY, 1);
        var speech = outgoing.observe(snapshot(true, false, null, owner), high, 0).orElseThrow();
        outgoing.didSpeak(speech, high, 0);
        quiet.didSpeak(speech, low, 0);
        assertFalse(outgoing.canSpeak(high, 59_999));
        assertTrue(outgoing.canSpeak(high, 60_000));
        assertFalse(quiet.canSpeak(low, 60_000));
        assertTrue(quiet.canSpeak(low, low.ambientIntervalMillis()));
    }

    @Test
    void stopResumeAndCompanionRepliesCannotCreateAGreetingLoop() {
        SocialBrain brain = new SocialBrain();
        var companion = new SocialSnapshot.Contact(UUID.randomUUID(), "Rowan", SocialSnapshot.Kind.COMPANION);
        var publicProfile = profile.withPublic(true);
        brain.didReplyToCompanion(companion.id(), publicProfile, 0);
        brain.clearObservations();
        assertTrue(brain.observe(snapshot(true, false, null, companion), publicProfile, 120_000).isEmpty());
        assertFalse(brain.canSpeak(publicProfile, -1));
    }

    @Test
    void changingPrivacyDropsPreviouslyQueuedPublicGreetings() {
        SocialBrain brain = new SocialBrain();
        var other = new SocialSnapshot.Contact(UUID.randomUUID(), "Visitor", SocialSnapshot.Kind.PLAYER);
        var publicProfile = profile.withPublic(true);
        assertTrue(brain.observe(snapshot(true, false, null, other), publicProfile, 0).isPresent());
        assertTrue(brain.observe(snapshot(true, false, null, other), publicProfile.withPublic(false), 1).isEmpty());
    }

    @Test
    void noticeRemarksAreLowPriorityPrivateByDefaultAndExpireWithTheContact() {
        SocialBrain brain = new SocialBrain();
        var visitor = new SocialSnapshot.Contact(UUID.randomUUID(), "Alex", SocialSnapshot.Kind.PLAYER);
        // Private companion: a visitor's arrival earns no remark at all; only the owner greeting remains.
        brain.noticed(visitor, "going through this chest", profile, 0);
        var privateSpeech = brain.observe(snapshot(true, false, null, owner, visitor), profile, 0).orElseThrow();
        assertEquals(SocialBrain.Topic.GREETING, privateSpeech.topic());
        assertEquals(owner.id(), privateSpeech.subject());
        SocialBrain open = new SocialBrain();
        var publicProfile = profile.withPublic(true);
        // Prime the contact list so the arrival greeting is not what gets chosen.
        open.observe(snapshot(true, false, null, owner, visitor), publicProfile, 0);
        open.didSpeak(open.observe(snapshot(true, false, null, owner, visitor), publicProfile, 0).orElseThrow(), publicProfile, 0);
        long later = publicProfile.ambientIntervalMillis();
        open.noticed(visitor, "going through this chest", publicProfile, later);
        var remark = open.observe(snapshot(true, false, "Zombie", owner, visitor), publicProfile, later).orElseThrow();
        assertEquals(SocialBrain.Topic.THREAT, remark.topic(), "a visible threat outranks a notice");
        open.didSpeak(remark, publicProfile, later);
        long afterwards = later + publicProfile.ambientIntervalMillis();
        open.noticed(visitor, "going through this chest", publicProfile, afterwards);
        var notice = open.observe(snapshot(true, false, null, owner, visitor), publicProfile, afterwards).orElseThrow();
        assertEquals(SocialBrain.Topic.NOTICE, notice.topic());
        assertTrue(notice.text().contains("Alex") && notice.text().contains("going through this chest"));
        // The person leaves before the companion gets a turn: nothing is said about them.
        SocialBrain gone = new SocialBrain();
        gone.observe(snapshot(true, false, null, owner), publicProfile, 0);
        gone.noticed(visitor, "sorting my inventory", publicProfile, 1);
        assertTrue(gone.observe(snapshot(true, false, null, owner), publicProfile, 2).stream().noneMatch(s -> s.topic() == SocialBrain.Topic.NOTICE));
        // Sociability zero never remarks.
        SocialBrain quiet = new SocialBrain();
        var mute = publicProfile.withTrait(Personality.Trait.SOCIABILITY, 0);
        quiet.noticed(visitor, "waiting", mute, 0);
        assertTrue(quiet.observe(snapshot(true, false, null, owner, visitor), mute, 0).isEmpty());
    }

    @Test
    void hurtSpeechNamesOnlyWhatTheGameSaysHurtItAndDoesNotRepeatWhileTheHurtLasts() {
        var steve = new SocialSnapshot.Contact(UUID.randomUUID(), "Steve", SocialSnapshot.Kind.PLAYER);
        // A fall with a person standing by: the fall is said, and nobody is blamed.
        var fell = new SocialBrain().observe(hurt(new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.WORLD, "a fall"), steve), profile, 0).orElseThrow();
        assertEquals(SocialBrain.Topic.HURT, fell.topic());
        assertTrue(fell.text().contains("a fall"), fell.text());
        assertFalse(fell.text().contains("Steve"), fell.text());
        // Nothing known of it: no one named.
        var unknown = new SocialBrain().observe(hurt(null, steve), profile, 0).orElseThrow();
        assertFalse(unknown.text().contains("Steve"), unknown.text());
        // Struck by a player, as the game recorded it: that player, by name.
        var struck = new SocialBrain().observe(hurt(new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.PLAYER, "Steve"), steve), profile, 0).orElseThrow();
        assertTrue(struck.text().contains("Steve"), struck.text());
        var bitten = new SocialBrain().observe(hurt(new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.MOB, "Zombie"), owner), profile, 0).orElseThrow();
        assertTrue(bitten.text().contains("Zombie") && !bitten.text().contains("Owner"), bitten.text());
        // Burning for a minute: one remark, not one every hit.
        var brain = new SocialBrain();
        var burning = hurt(new SocialSnapshot.Harm(SocialSnapshot.Harm.Source.WORLD, "fire"));
        var first = brain.observe(burning, profile, 0).orElseThrow();
        brain.didSpeak(first, profile, 0);
        for (long t = 1_000; t <= 60_000; t += 1_000) assertTrue(brain.observe(burning, profile, t).isEmpty(), "again at " + t);
    }

    private static SocialSnapshot hurt(SocialSnapshot.Harm harm, SocialSnapshot.Contact... contacts) {
        return new SocialSnapshot(true, false, false, true, null, List.of(contacts), harm);
    }

    private static SocialSnapshot snapshot(boolean active, boolean rain, String threat, SocialSnapshot.Contact... contacts) {
        return new SocialSnapshot(active, rain, false, false, threat, List.of(contacts));
    }
}
