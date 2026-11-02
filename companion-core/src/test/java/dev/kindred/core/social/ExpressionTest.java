package dev.kindred.core.social;

import dev.kindred.core.Personality;
import dev.kindred.core.SocialBrain;
import dev.kindred.core.SocialProfile;
import dev.kindred.core.SocialSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player sounds (E-18a) and what a listener can ask for (E-19a): the server's cap bounds every
 * preset; a player with none set sounds by its humour as before; a joke or a tease goes out plainly when anyone who will
 * hear it asked for none, the strictest of them deciding; a person is never teased; and the voice is told so in one line.
 */
class ExpressionTest {
    private static final UUID ME = UUID.randomUUID();

    @Test
    void theServersCapBoundsEveryPresetToItsOwnRank() {
        assertEquals(Expression.PLAIN, Expression.PLAYFUL.capped(Expression.PLAIN));
        assertEquals(Expression.DRY, Expression.PLAYFUL.capped(Expression.DRY));
        assertEquals(Expression.DRY, Expression.DRY.capped(Expression.PLAYFUL));
        assertEquals(Expression.PLAIN, Expression.DRY.capped(Expression.PLAIN));
        assertEquals(Expression.PLAYFUL, Expression.PLAYFUL.capped(null));
        assertEquals(Expression.PLAYFUL, Expression.parse(" Playful ").orElseThrow());
        assertTrue(Expression.parse("loud").isEmpty());
        assertEquals("dry", Expression.DRY.id());
    }

    @Test
    void aPlayerWithNoPresetSoundsByItsHumourAsBeforeAndOneSetOverridesIt() {
        var witty = SocialProfile.defaults(ME).withTrait(Personality.Trait.HUMOR, 70);
        assertNull(witty.expression());
        assertEquals(Expression.PLAYFUL, witty.effectiveExpression());
        assertEquals(Expression.PLAIN, SocialProfile.defaults(ME).withTrait(Personality.Trait.HUMOR, 64).effectiveExpression());
        var plain = witty.withExpression(Expression.PLAIN);
        assertEquals(Expression.PLAIN, plain.effectiveExpression());
        assertEquals(witty.revision() + 1, plain.revision());
        // Under the cap, at the same revision: nothing waiting to be said is dropped for it.
        var capped = witty.capped(Expression.DRY);
        assertEquals(Expression.DRY, capped.effectiveExpression());
        assertEquals(witty.revision(), capped.revision());
        assertSame(plain, plain.capped(Expression.PLAYFUL));
    }

    @Test
    void theStrictestOfEveryoneWhoWillHearALineApplies() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var easy = ListenerPreferences.defaults(a).withUnasked(ListenerPreferences.Unasked.MORE);
        var strict = ListenerPreferences.defaults(b).withNoJokes(true).withUnasked(ListenerPreferences.Unasked.FEWER);
        var both = ListenerPreferences.strictest(List.of(easy, strict));
        assertTrue(both.noJokes());
        assertFalse(both.noTeasing());
        assertEquals(ListenerPreferences.Unasked.FEWER, both.unasked());
        assertEquals(ListenerPreferences.Unasked.MORE, ListenerPreferences.strictest(List.of(easy)).unasked());
        var nobody = ListenerPreferences.strictest(List.of());
        assertTrue(nobody.isDefault());
        assertEquals(60_000, strict.spacing(30_000));
        assertEquals(15_000, easy.spacing(30_000));
        assertEquals(30_000, ListenerPreferences.defaults(a).spacing(30_000));
        assertTrue(ListenerPreferences.defaults(a).isDefault());
        assertFalse(ListenerPreferences.defaults(a).withNoTeasing(true).isDefault());
    }

    @Test
    void aPlayfulLineGoesOutPlainlyToAnyoneWhoAskedForNoJokes() {
        var playful = SocialProfile.defaults(ME).withExpression(Expression.PLAYFUL);
        var brain = new SocialBrain();
        var rain = brain.observe(new SocialSnapshot(true, true, false, false, null, List.of()), playful, 0).orElseThrow();
        assertEquals(SocialBrain.Topic.RAIN, rain.topic());
        assertEquals(SocialBrain.Tone.JOKE, rain.tone());
        assertNotEquals(rain.plain(), rain.text());
        var noJokes = ListenerPreferences.defaults(UUID.randomUUID()).withNoJokes(true);
        assertEquals(rain.plain(), rain.textFor(noJokes));
        assertEquals(rain.text(), rain.textFor(ListenerPreferences.defaults(UUID.randomUUID())));
        assertEquals(rain.text(), rain.textFor(null));
        // A plain player says the plain line to everyone; a dry one its own, which no preference changes.
        var plainRain = new SocialBrain().observe(new SocialSnapshot(true, true, false, false, null, List.of()),
                playful.withExpression(Expression.PLAIN), 0).orElseThrow();
        assertEquals(SocialBrain.Tone.PLAIN, plainRain.tone());
        assertEquals(plainRain.text(), plainRain.textFor(noJokes));
        var dryRain = new SocialBrain().observe(new SocialSnapshot(true, true, false, false, null, List.of()),
                playful.withExpression(Expression.DRY), 0).orElseThrow();
        assertEquals("Rain. Lovely.", dryRain.textFor(noJokes));
    }

    @Test
    void onlyAnotherAiPlayerIsTeasedAndNotWithAnyoneInEarshotWhoAskedForNone() {
        var playful = SocialProfile.defaults(ME).withExpression(Expression.PLAYFUL).withPublic(true);
        var mate = new SocialSnapshot.Contact(UUID.randomUUID(), "Sol", SocialSnapshot.Kind.COMPANION);
        var person = new SocialSnapshot.Contact(UUID.randomUUID(), "Steve", SocialSnapshot.Kind.PLAYER);
        var toMate = new SocialBrain().observe(new SocialSnapshot(true, false, false, false, null, List.of(mate)), playful, 0).orElseThrow();
        assertEquals(SocialBrain.Tone.TEASE, toMate.tone());
        assertEquals(toMate.plain(), toMate.textFor(ListenerPreferences.defaults(person.id()).withNoTeasing(true)));
        assertEquals(toMate.plain(), toMate.textFor(ListenerPreferences.defaults(person.id()).withNoJokes(true)));
        var toPerson = new SocialBrain().observe(new SocialSnapshot(true, false, false, false, null, List.of(person)), playful, 0).orElseThrow();
        assertNotEquals(SocialBrain.Tone.TEASE, toPerson.tone());
    }

    @Test
    void theVoiceIsToldInOneLineHowToSpeakToThisListener() {
        var none = ListenerPreferences.defaults(UUID.randomUUID());
        var noJokes = none.withNoJokes(true);
        assertTrue(Expression.voiceLine(Expression.PLAYFUL, none).contains("never teasing a person"));
        assertTrue(Expression.voiceLine(Expression.PLAYFUL, noJokes).contains("without jokes"));
        assertTrue(Expression.voiceLine(Expression.PLAIN, none).contains("without jokes"));
        assertTrue(Expression.voiceLine(Expression.DRY, none).contains("dry"));
        for (var e : Expression.values()) {
            String line = Expression.voiceLine(e, none);
            assertTrue(line.startsWith("How you speak to them:") && line.endsWith("clean words only."), line);
            assertFalse(line.contains("\n"));
        }
    }
}
