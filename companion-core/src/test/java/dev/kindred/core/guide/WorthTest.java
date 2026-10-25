package dev.kindred.core.guide;

import dev.kindred.core.guide.Worth.Container;
import dev.kindred.core.guide.Worth.Holding;
import dev.kindred.core.guide.Worth.Keep;
import dev.kindred.core.guide.Worth.Piece;
import dev.kindred.core.guide.Worth.Stack;
import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorthTest {
    /** Early on: stone tools, leather boots, a few logs and some bread. */
    private static final Holding EARLY = Holding.of(
            Map.of("minecraft:stone_pickaxe", 1, "minecraft:stone_sword", 1, "minecraft:oak_log", 6, "minecraft:bread", 5),
            Map.of(Slot.BOOTS, new Piece(Material.LEATHER, 0)));

    private static Keep keep(String item, int count, Holding holding) {
        return Worth.judge(new Stack(item, count), holding).keep();
    }

    @Test
    void kitBetterThanItsOwnIsTakenUpAndNoBetterIsASpareOrLeft() {
        var chest = Worth.judge(new Stack("minecraft:iron_chestplate", 1), EARLY);
        assertEquals(Keep.UPGRADE, chest.keep());
        assertEquals("it has no chestplate", chest.why());
        assertEquals("better than its leather boots", Worth.judge(new Stack("minecraft:iron_boots", 1), EARLY).why());
        // The pickaxe it has is stone: an iron one is the step that opens up diamonds.
        assertEquals(Keep.UPGRADE, keep("minecraft:iron_pickaxe", 1, EARLY));
        var ironed = Holding.of(Map.of(), Map.of(Slot.PICKAXE, new Piece(Material.IRON, 0), Slot.SWORD, new Piece(Material.IRON, 0),
                Slot.BOOTS, new Piece(Material.IRON, 0)));
        assertEquals(Keep.STORE, keep("minecraft:iron_pickaxe", 1, ironed), "A spare pickaxe to fall back on");
        assertEquals(Keep.LEAVE, keep("minecraft:leather_boots", 1, ironed), "Leather when it wears iron");
        assertEquals(Keep.LEAVE, keep("minecraft:golden_sword", 1, ironed));
        // The same iron, enchanted: better than its own.
        var enchanted = Worth.judge(new Stack("minecraft:iron_boots", 1, 3), ironed);
        assertEquals(Keep.UPGRADE, enchanted.keep());
        assertEquals("better than its iron boots", enchanted.why());
    }

    @Test
    void whatItsRunIsShortOfIsCarriedAndTheRestKeptForLaterOrLeft() {
        var run = new Holding(Map.of("minecraft:iron_ingot", 1, "minecraft:bread", 2), Map.of(),
                Map.of("minecraft:iron_ingot", 3, Worth.FOOD, 6, "minecraft:obsidian", 10));
        var iron = Worth.judge(new Stack("minecraft:raw_iron", 4), run);
        assertEquals(Keep.CARRY, iron.keep());
        assertEquals("its run is short of 2 iron ingots", iron.why());
        assertEquals("its run is short of 4 food", Worth.judge(new Stack("minecraft:cooked_porkchop", 3), run).why());
        assertEquals(Keep.CARRY, keep("minecraft:golden_apple", 1, run), "Food too");
        assertEquals(Keep.CARRY, keep("minecraft:glow_berries", 6, run), "Even berries, short of food");
        assertEquals(Keep.LEAVE, keep("minecraft:glow_berries", 6, EARLY), "Not worth a slot with bread enough");
        assertEquals(Keep.CARRY, keep("minecraft:obsidian", 2, run));
        // Not wanted now, but a player keeps them: gold before the Nether, a book, a saddle, a run of rails.
        for (var later : List.of(new Stack("minecraft:gold_block", 2), new Stack("minecraft:enchanted_book", 1, 1),
                new Stack("minecraft:saddle", 1), new Stack("minecraft:rail", 16), new Stack("minecraft:diamond", 1),
                new Stack("minecraft:name_tag", 1))) {
            assertEquals(Keep.STORE, Worth.judge(later, run).keep(), later.item());
            assertEquals("worth keeping for later", Worth.judge(later, run).why());
        }
        // Not worth a slot: a rail or two, rotten flesh, cobwebs, blocks it has no want of, a disc it has no use for.
        assertEquals(Keep.LEAVE, keep("minecraft:rail", 2, run));
        assertEquals(Keep.LEAVE, keep("minecraft:rotten_flesh", 9, run));
        assertEquals(Keep.LEAVE, keep("minecraft:cobweb", 3, run));
        assertEquals(Keep.LEAVE, keep("minecraft:cobblestone", 30, run));
        var disc = Worth.judge(new Stack("minecraft:music_disc_13", 1), run);
        assertEquals(Keep.LEAVE, disc.keep());
        assertEquals("no use to it", disc.why());
        // Short of blocks to build up with, the same cobblestone is carried.
        var bridging = new Holding(Map.of(), Map.of(), Map.of(Worth.BLOCKS, 64));
        assertEquals(Keep.CARRY, keep("minecraft:cobblestone", 30, bridging));
    }

    @Test
    void worthIsWhatItIsMadeOfAndFallsOffPastWhatItCouldUse() {
        double ingot = Worth.judge(new Stack("minecraft:gold_ingot", 1), EARLY).worth();
        assertEquals(9 * ingot, Worth.judge(new Stack("minecraft:gold_block", 1), EARLY).worth(), 1e-9, "Nine ingots");
        assertEquals(ingot, Worth.judge(new Stack("minecraft:raw_gold", 1), EARLY).worth(), 1e-9, "What it smelts to");
        double first = Worth.judge(new Stack("minecraft:diamond", 1), EARLY).worth();
        var rich = Holding.of(Map.of("minecraft:diamond_block", 3, "minecraft:diamond", 2), Map.of());
        double thirtieth = Worth.judge(new Stack("minecraft:diamond", 1), rich).worth();
        assertEquals(first / 10, thirtieth, 1e-9, "Twenty-nine is all it could use: the thirtieth is worth a tenth");
        // Enchantments are worth the levels and lapis they save.
        assertTrue(Worth.judge(new Stack("minecraft:enchanted_book", 1, 3), EARLY).worth()
                > Worth.judge(new Stack("minecraft:enchanted_book", 1, 1), EARLY).worth());
    }

    @Test
    void aPieceOfGoldIsCarriedForThePiglinsUntilItWearsOne() {
        var nether = new Holding(Map.of(), Map.of(Slot.HELMET, new Piece(Material.IRON, 0), Slot.BOOTS, new Piece(Material.IRON, 0)),
                Map.of(Worth.GOLD_ARMOUR, 1));
        var boots = Worth.judge(new Stack("minecraft:golden_boots", 1), nether);
        assertEquals(Keep.CARRY, boots.keep(), "No better than iron boots, but gold keeps the piglins off");
        assertEquals("a piece of gold to wear among piglins", boots.why());
        var wearing = new Holding(Map.of(), Map.of(Slot.HELMET, new Piece(Material.GOLD, 0)), Map.of(Worth.GOLD_ARMOUR, 1));
        assertNotEquals(Keep.CARRY, keep("minecraft:golden_boots", 1, wearing), "It wears one already");
    }

    @Test
    void itGoesOutOfItsWayByWhatAThingIsWorth() {
        assertEquals(42, Worth.detour(Worth.judge(new Stack("minecraft:diamond", 1), EARLY)));
        assertEquals(Worth.DETOUR_MAX, Worth.detour(Worth.judge(new Stack("minecraft:gold_block", 1), EARLY)), "Capped");
        assertEquals(0, Worth.detour(Worth.judge(new Stack("minecraft:rail", 1), EARLY)), "Nothing for what it leaves");
        // A chest it cannot see into: what one of its kind usually holds.
        assertEquals(42, Worth.detour(Container.MINESHAFT_CART.expected()));
        assertEquals(54, Worth.detour(Container.RUINED_PORTAL.expected()));
        assertTrue(Worth.detour(Container.VILLAGE.expected()) < Worth.detour(Container.BURIED_TREASURE.expected()));
    }

    @Test
    void withNoRoomItGivesUpWhatIsWorthLeastButNeverWhatItsRunIsShortOf() {
        var held = List.of(new Stack("minecraft:bread", 3), new Stack("minecraft:dirt", 64), new Stack("minecraft:rail", 20),
                new Stack("minecraft:cobblestone", 64));
        var counts = Map.of("minecraft:bread", 3, "minecraft:dirt", 64, "minecraft:rail", 20, "minecraft:cobblestone", 64);
        var full = new Holding(counts, Map.of(), Map.of(Worth.FOOD, 6));
        var diamond = Worth.judge(new Stack("minecraft:diamond", 2), full);
        int given = Worth.makeRoom(held, diamond, full).orElseThrow();
        assertTrue(given == 1 || given == 3, "Blocks it has no want of, not its bread or rails: " + held.get(given));
        // A rail is no reason to give anything up; nor is a like-for-like swap.
        assertTrue(Worth.makeRoom(held, Worth.judge(new Stack("minecraft:rail", 2), full), full).isEmpty());
        var bridging = new Holding(counts, Map.of(), Map.of(Worth.FOOD, 6, Worth.BLOCKS, 200));
        var rails = Worth.judge(new Stack("minecraft:rail", 40), bridging);
        assertEquals(Keep.STORE, rails.keep());
        assertEquals(2, Worth.makeRoom(held, rails, bridging).orElse(-1),
                "Short of blocks, it keeps its dirt and cobblestone and gives up the fewer rails");
        // The bread its run is short of is never given up.
        var dinner = List.of(new Stack("minecraft:bread", 3));
        assertTrue(Worth.makeRoom(dinner, diamond, new Holding(Map.of("minecraft:bread", 3), Map.of(), Map.of(Worth.FOOD, 6))).isEmpty());
    }

    @Test
    void whatItPutsAwayIsWhatItKeepsForLaterNeverWhatItUsesAsItGoes() {
        var held = Map.of("minecraft:saddle", 1, "minecraft:name_tag", 1, "minecraft:enchanted_book", 1, "minecraft:rail", 7,
                "minecraft:diamond", 3, "minecraft:bread", 12, "minecraft:cobblestone", 40, "minecraft:iron_pickaxe", 1,
                "minecraft:torch", 20, "minecraft:water_bucket", 1);
        var kit = Map.of(Slot.PICKAXE, new Piece(Material.IRON, 0));
        assertEquals(Map.of("minecraft:saddle", 1, "minecraft:name_tag", 1, "minecraft:enchanted_book", 1, "minecraft:rail", 7),
                Worth.stow(held, new Holding(held, kit, Map.of("minecraft:diamond", 3)), false),
                "Its run's diamonds, its food, its blocks, its pickaxe, torches and bucket stay on it");
        assertTrue(Worth.stow(held, new Holding(held, kit, Map.of()), false).containsKey("minecraft:diamond"),
                "With no run on and nothing more wanted of them, its diamonds are put away too");
        assertEquals(Map.of("minecraft:saddle", 1, "minecraft:name_tag", 1, "minecraft:enchanted_book", 1, "minecraft:rail", 7),
                Worth.stow(held, new Holding(held, kit, Map.of()), true), "On a run, its diamonds stay with it, wanted now or not");
        assertEquals(Map.of(), Worth.stow(Map.of("minecraft:rotten_flesh", 3, "minecraft:cobweb", 2), Holding.of(Map.of(), Map.of()), false),
                "Nothing not worth a slot");
    }
}
