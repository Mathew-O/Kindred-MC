package dev.kindred.core;

import dev.kindred.core.KitNames.Kind;
import dev.kindred.core.KitNames.Piece;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a player names its best gear: only the best piece of each kind that is worth a
 * name, weapons first, nothing named twice and only as far as its experience levels go; its own names come
 * from what the piece is and how it is enchanted, and a name suggested by an optional adviser must pass the
 * same rules.
 */
class KitNamesTest {
    private static final UUID WREN = new UUID(7, 11);
    private static final Personality NEUTRAL = Personality.defaults();

    private static Piece piece(int slot, String item, Map<String, Integer> enchantments) {
        return new Piece(slot, item, enchantments, Optional.empty(), 0);
    }

    @Test
    void kindsAreReadFromTheItemAndAPickaxeIsNoAxeNorACrossbowABow() {
        assertEquals(Optional.of(Kind.PICKAXE), Kind.of("minecraft:diamond_pickaxe"));
        assertEquals(Optional.of(Kind.AXE), Kind.of("minecraft:netherite_axe"));
        assertEquals(Optional.of(Kind.CROSSBOW), Kind.of("minecraft:crossbow"));
        assertEquals(Optional.of(Kind.BOW), Kind.of("minecraft:bow"));
        assertTrue(Kind.of("minecraft:diamond_hoe").isEmpty());
        assertTrue(Kind.of("minecraft:bowl").isEmpty());
        assertEquals("diamond", piece(0, "minecraft:diamond_pickaxe", Map.of()).material());
        assertEquals("", piece(0, "minecraft:bow", Map.of()).material());
        assertThrows(IllegalArgumentException.class, () -> piece(0, "minecraft:bread", Map.of()));
    }

    @Test
    void theBestOfEachKindIsNamedWhenItIsWorthAName_weaponsFirst_andNothingNamedTwice() {
        var sword = piece(0, "minecraft:diamond_sword", Map.of("minecraft:sharpness", 3));
        var spare = piece(1, "minecraft:iron_sword", Map.of());
        var pickaxe = new Piece(2, "minecraft:diamond_pickaxe", Map.of("minecraft:efficiency", 4), Optional.of("Old Faithful"), 1);
        var stonePick = piece(3, "minecraft:stone_pickaxe", Map.of());
        var axe = piece(4, "minecraft:iron_axe", Map.of());
        var shovel = piece(5, "minecraft:iron_shovel", Map.of("minecraft:unbreaking", 1));
        var bow = piece(6, "minecraft:bow", Map.of("minecraft:power", 2));
        assertEquals(List.of(sword, bow, shovel), KitNames.toName(List.of(shovel, pickaxe, stonePick, spare, sword, axe, bow)),
                "The diamond sword, the enchanted bow and shovel; not the named pickaxe nor the lesser one; not a plain iron axe");
        assertEquals(List.of(), KitNames.toName(List.of(spare, axe)), "Plain iron is not worth a name");
        assertEquals(1, KitNames.cost(sword));
        assertEquals(3, KitNames.cost(new Piece(0, "minecraft:diamond_sword", Map.of(), Optional.empty(), 2)), "Its prior work and one");
        assertEquals(List.of(sword, bow), KitNames.affordable(List.of(sword, bow, shovel), 2), "A level a name, as far as its levels go");
        assertEquals(List.of(), KitNames.affordable(List.of(sword), 0));
    }

    @Test
    void itsOwnNamesAreCoolDrawnFromWhatThePieceIsAndAlwaysPassTheRules() {
        var fiery = piece(0, "minecraft:diamond_sword", Map.of("minecraft:fire_aspect", 2, "minecraft:sharpness", 3));
        var name = KitNames.own(fiery, WREN, NEUTRAL, Set.of()).orElseThrow();
        assertEquals(name, KitNames.own(fiery, WREN, NEUTRAL, Set.of()).orElseThrow(), "The same player and piece, the same name");
        assertEquals(NameForge.Source.FORGE, name.source());
        assertTrue(List.of("Ember", "Cinder", "Blaze", "Pyre", "Scorch", "Flame").stream().anyMatch(name.text()::startsWith),
                "Fire Aspect, its most telling enchantment, first: " + name.text());
        assertTrue(name.text().matches("[A-Z][a-z]+"), "One word of a root and an ending: " + name.text());
        var next = KitNames.own(fiery, WREN, NEUTRAL, Set.of(name.text().toUpperCase())).orElseThrow();
        assertNotEquals(name.text(), next.text(), "A name it has used is never used again");
        var names = new HashSet<String>();
        for (int i = 0; i < 24; i++) names.add(KitNames.own(fiery, new UUID(i, 99), NEUTRAL, Set.of()).orElseThrow().text());
        assertTrue(names.size() > 6, "Players of one temper still name differently: " + names);
        var curious = NEUTRAL.withTrait(Personality.Trait.CURIOSITY, 80);
        var titled = KitNames.own(piece(0, "minecraft:netherite_pickaxe", Map.of()), WREN, curious, Set.of()).orElseThrow().text();
        assertTrue(titled.contains(" of the "), titled);
        var playful = NEUTRAL.withTrait(Personality.Trait.HUMOR, 80);
        for (Kind kind : Kind.values()) {
            String item = "minecraft:" + (List.of(Kind.BOW, Kind.CROSSBOW, Kind.TRIDENT, Kind.MACE).contains(kind) ? "" : "diamond_") + kind.noun;
            for (var temper : List.of(NEUTRAL, curious, playful)) {
                for (int attempt = 0; attempt < NameForge.MAX_ATTEMPTS; attempt++) {
                    for (var enchanted : List.of(Map.<String, Integer>of(), Map.of("minecraft:mending", 1), Map.of("minecraft:fortune", 3))) {
                        String text = KitNames.candidate(piece(0, item, enchanted), new UUID(attempt, kind.ordinal()), NameForge.Flavor.of(temper), attempt);
                        assertEquals(text, new NameForge.Name(text, NameForge.Subject.TOOL, NameForge.Source.FORGE, attempt).text(), "Valid: " + text);
                    }
                }
            }
        }
    }

    @Test
    void theModelIsAskedWhatThePieceIsAndItsNamePassesTheSameRules() {
        var fierce = NEUTRAL.withTrait(Personality.Trait.HUMOR, 70).withTrait(Personality.Trait.COMBATIVENESS, 80);
        var sword = piece(0, "minecraft:diamond_sword", Map.of("minecraft:sharpness", 3, "minecraft:fire_aspect", 2));
        assertEquals("You are Wren, playful and fierce. Name your diamond sword, with Fire Aspect II and Sharpness III. "
                + "You have named Emberdelver, your diamond pickaxe.", KitNames.question("Wren", fierce, sword, Map.of("Emberdelver", "diamond pickaxe")));
        assertEquals("You are Wren, even-tempered. Name your bow, with Infinity. You have named nothing yet.",
                KitNames.question("Wren", NEUTRAL, piece(0, "minecraft:bow", Map.of("minecraft:infinity", 1)), Map.of()));
        assertTrue(KitNames.SYSTEM.contains("2 to 32 characters"));
        assertEquals("Dawnsplitter", KitNames.fromModel(" Dawnsplitter ", Set.of()).orElseThrow().text());
        assertEquals(NameForge.Source.MODEL, KitNames.fromModel("Dawnsplitter", Set.of()).orElseThrow().source());
        assertTrue(KitNames.fromModel("dawnsplitter", Set.of()).isEmpty(), "A capital first");
        assertTrue(KitNames.fromModel("Splitter 3000", Set.of()).isEmpty(), "Letters only");
        assertTrue(KitNames.fromModel("Dawnsplitter", Set.of("dawnsplitter")).isEmpty(), "Not one it has used");
        assertTrue(KitNames.fromModel("The Exceedingly Long Name Of A Blade", Set.of()).isEmpty(), "Thirty-two characters at most");
        assertEquals("I named my diamond sword Emberfang.", KitNames.told(sword, "Emberfang"));
        assertEquals("Luck of the Sea III and Unbreaking I", KitNames.enchantments(new Piece(0, "minecraft:trident",
                Map.of("minecraft:unbreaking", 1, "minecraft:luck_of_the_sea", 3), Optional.empty(), 0)));
    }
}
