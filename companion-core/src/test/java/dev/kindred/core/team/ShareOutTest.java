package dev.kindred.core.team;

import dev.kindred.core.team.ShareOut.Material;
import dev.kindred.core.team.ShareOut.Member;
import dev.kindred.core.team.ShareOut.Slot;
import dev.kindred.core.team.ShareOut.Work;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how a team splits a find of diamonds, iron, gold or netherite: the first diamond
 * pickaxe goes to whoever mines, then each piece goes where it adds the most protection for what it costs,
 * nobody is given a piece no better than their own, and a find too small for any piece is kept.
 */
class ShareOutTest {
    private static final UUID WREN = new UUID(0, 1), MASON = new UUID(0, 2), RUE = new UUID(0, 3);

    private static Map<Slot, Material> iron() {
        return Map.of(Slot.HELMET, Material.IRON, Slot.CHESTPLATE, Material.IRON, Slot.LEGGINGS, Material.IRON, Slot.BOOTS, Material.IRON,
                Slot.SWORD, Material.IRON, Slot.PICKAXE, Material.IRON);
    }

    @Test
    void theTeamsFirstDiamondPickaxeGoesToWhoeverMinesBeforeAnyArmour() {
        var team = List.of(new Member(WREN, "Wren", iron(), Work.NETHER), new Member(MASON, "Mason", iron(), Work.GATHERING),
                new Member(RUE, "Rue", iron(), Work.STRONGHOLD));
        var grants = ShareOut.share(team, Material.DIAMOND, 3);
        assertEquals(List.of(new ShareOut.Grant(MASON, "Mason", Slot.PICKAXE, Material.DIAMOND, 3)), grants);
    }

    @Test
    void thenThePieceThatDoesTheMostForWhatItCostsToWhoeverItDoesMostFor() {
        // Mason has the team's diamond pickaxe; eleven diamonds left over.
        var mason = new java.util.HashMap<>(iron());
        mason.put(Slot.PICKAXE, Material.DIAMOND);
        var team = List.of(new Member(WREN, "Wren", iron(), Work.NETHER), new Member(MASON, "Mason", mason, Work.GATHERING));
        var grants = ShareOut.share(team, Material.DIAMOND, 11);
        // Armour first for the one going to the Nether, whose armour counts for more there: the most protection for
        // each gem (boots, a point and their toughness for four). Spent down to less than the cheapest piece.
        assertEquals(WREN, grants.getFirst().to(), "the Nether goer first: " + grants);
        assertTrue(grants.getFirst().slot().ordinal() <= Slot.BOOTS.ordinal(), "armour first: " + grants);
        assertEquals(new ShareOut.Grant(WREN, "Wren", Slot.BOOTS, Material.DIAMOND, 4), grants.getFirst());
        assertTrue(11 - grants.stream().mapToInt(ShareOut.Grant::cost).sum() < Slot.SWORD.cost(), "spent down: " + grants);
        assertTrue(grants.stream().noneMatch(g -> g.slot() == Slot.PICKAXE), "no second pickaxe: " + grants);
        assertTrue(grants.stream().noneMatch(g -> g.slot() == Slot.AXE), "no diamond axe before armour: " + grants);
    }

    @Test
    void betweenEqualsTheWorseProtectedFirstAndNoneGetsAPieceNoBetterThanItsOwn() {
        var wren = new java.util.HashMap<>(iron());
        wren.put(Slot.BOOTS, Material.NONE);
        var team = List.of(new Member(MASON, "Mason", iron(), Work.GATHERING), new Member(WREN, "Wren", wren, Work.GATHERING));
        // Iron boots: Mason has a pair already; Wren none.
        assertEquals(List.of(new ShareOut.Grant(WREN, "Wren", Slot.BOOTS, Material.IRON, 4)), ShareOut.share(team, Material.IRON, 4));
        // Two equally bare players: the lower id first, so that every player reckons it alike.
        var bare = List.of(new Member(MASON, "Mason", Map.of(), Work.GATHERING), new Member(WREN, "Wren", Map.of(), Work.GATHERING));
        assertEquals(WREN, ShareOut.share(bare, Material.IRON, 8).getFirst().to());
    }

    @Test
    void tooLittleForAnyPieceIsKeptAndNobodyIsGivenWhatTheyHave() {
        var full = new java.util.HashMap<Slot, Material>();
        for (Slot slot : Slot.values()) full.put(slot, Material.DIAMOND);
        assertEquals(List.of(), ShareOut.share(List.of(new Member(WREN, "Wren", full, Work.NETHER)), Material.DIAMOND, 24));
        assertEquals(List.of(), ShareOut.share(List.of(new Member(WREN, "Wren", Map.of(), Work.NETHER)), Material.IRON, 1));
    }

    @Test
    void goldIsArmourOnlyAndOnePieceGoesToTheNetherGoerWearingNoneWhereItCostsNoProtection() {
        var team = List.of(new Member(MASON, "Mason", iron(), Work.GATHERING), new Member(WREN, "Wren", iron(), Work.NETHER));
        var grants = ShareOut.share(team, Material.GOLD, 9);
        // A gold helmet (as good as iron, two points) for Wren, against piglins; nothing else of gold is any better.
        assertEquals(List.of(new ShareOut.Grant(WREN, "Wren", Slot.HELMET, Material.GOLD, 5)), grants);
        // No gold tools or swords for a bare player either.
        var bare = ShareOut.share(List.of(new Member(MASON, "Mason", Map.of(), Work.GATHERING)), Material.GOLD, 40);
        assertTrue(bare.stream().allMatch(g -> g.slot().ordinal() <= Slot.BOOTS.ordinal()), "armour only: " + bare);
    }

    @Test
    void netheriteUpgradesOnlyADiamondPieceAnIngotEach() {
        var kit = new java.util.HashMap<>(iron());
        kit.put(Slot.CHESTPLATE, Material.DIAMOND);
        var grants = ShareOut.share(List.of(new Member(WREN, "Wren", kit, Work.NETHER)), Material.NETHERITE, 3);
        assertEquals(List.of(new ShareOut.Grant(WREN, "Wren", Slot.CHESTPLATE, Material.NETHERITE, 1)), grants);
    }
}
