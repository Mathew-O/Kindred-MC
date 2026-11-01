package dev.kindred.core.tactics;

import dev.kindred.core.tactics.DamageModel.Kind;
import dev.kindred.core.tactics.DamageModel.Tier;
import dev.kindred.core.tactics.DamageModel.Weapon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In plain terms: checks how much damage the player expects each weapon to do, matching the game: why a sword
 * beats an axe over a whole fight, where the dragon takes full damage, and how arrows and the mace hit.
 */
class DamageModelTest {
    private static final Weapon DIAMOND_SWORD = new Weapon(Kind.SWORD, Tier.DIAMOND, 0);
    private static final Weapon DIAMOND_AXE = new Weapon(Kind.AXE, Tier.DIAMOND, 0);
    private static final Weapon IRON_SWORD = new Weapon(Kind.SWORD, Tier.IRON, 0);
    private static final Weapon IRON_AXE = new Weapon(Kind.AXE, Tier.IRON, 0);

    @Test
    void theWeaponsHitAsVanillaSays() {
        assertEquals(7, DIAMOND_SWORD.attackDamage());
        assertEquals(9, DIAMOND_AXE.attackDamage());
        assertEquals(10, new Weapon(Kind.AXE, Tier.NETHERITE, 0).attackDamage());
        assertEquals(9, new Weapon(Kind.AXE, Tier.STONE, 0).attackDamage());
        assertEquals(4, new Weapon(Kind.SWORD, Tier.WOOD, 0).attackDamage());
        assertEquals(1, Weapon.HAND.attackDamage());
        assertEquals(12.5, DIAMOND_SWORD.cooldownTicks(), 1e-9);
        assertEquals(20.0 / 0.9, IRON_AXE.cooldownTicks(), 1e-9);
        // A full swing, a critical, Sharpness V (which the critical does not multiply), a half-charged swing.
        assertEquals(7, DamageModel.hit(DIAMOND_SWORD, 1, false, 0, 0), 1e-9);
        assertEquals(10.5, DamageModel.hit(DIAMOND_SWORD, 1, true, 0, 0), 1e-9);
        assertEquals(13.5, DamageModel.hit(new Weapon(Kind.SWORD, Tier.DIAMOND, 5), 1, true, 0, 0), 1e-9);
        assertEquals(7 * 0.4, DamageModel.hit(DIAMOND_SWORD, 0.5, true, 0, 0), 1e-9, "no critical at half charge");
        assertEquals((7 + 3) * 1.5, DamageModel.hit(DIAMOND_SWORD, 1, true, 1, 0), 1e-9, "Strength I");
    }

    @Test
    void aSwordsCritsOutDamageAnAxesOverAFight() {
        // 10.5 every 12.5 ticks against 13.5 every 20: the sword, though the axe hits harder once.
        assertTrue(DamageModel.perTick(DIAMOND_SWORD, true, 0) > DamageModel.perTick(DIAMOND_AXE, true, 0));
        assertEquals(DIAMOND_SWORD, DamageModel.best(List.of(DIAMOND_AXE, DIAMOND_SWORD), true, 0).orElseThrow());
        assertEquals(IRON_SWORD, DamageModel.best(List.of(IRON_AXE, IRON_SWORD, Weapon.HAND), true, 0).orElseThrow());
        assertEquals(DIAMOND_AXE, DamageModel.hardest(List.of(DIAMOND_AXE, DIAMOND_SWORD), true, 0).orElseThrow());
        // A stone sword still beats a wooden axe; a lone axe beats the hand.
        assertEquals(new Weapon(Kind.SWORD, Tier.STONE, 0),
                DamageModel.best(List.of(new Weapon(Kind.AXE, Tier.WOOD, 0), new Weapon(Kind.SWORD, Tier.STONE, 0)), true, 0).orElseThrow());
        assertEquals(DIAMOND_AXE, DamageModel.best(List.of(Weapon.HAND, DIAMOND_AXE), true, 0).orElseThrow());
        // The hand swings faster than a mob's invulnerability lets it count: ten ticks a hit, not five.
        assertEquals(1.0 / DamageModel.INVULNERABLE_TICKS, DamageModel.perTick(Weapon.HAND, false, 0), 1e-9);
    }

    @Test
    void theDragonTakesTheFullBlowOnlyOnItsHeadAndNoArrowWhileItSits() {
        assertEquals(10.5, DamageModel.toDragon(10.5, true, false, true), 1e-9);
        assertEquals(10.5 / 4 + 1, DamageModel.toDragon(10.5, false, false, false), 1e-9);
        assertEquals(0.5 / 4 + 0.5, DamageModel.toDragon(0.5, false, false, false), 1e-9);
        assertEquals(0, DamageModel.toDragon(9, true, true, true), "an arrow at the perched head does nothing");
        assertEquals(9, DamageModel.toDragon(9, true, true, false), "the flying head takes the arrow in full");
    }

    @Test
    void arrowsAndTheMaceAsVanillaReckonsThem() {
        // A full draw lands at about three blocks a tick: six, and a critical's 0..4 more, two on average.
        assertEquals(6, DamageModel.arrow(3.0, 0, false), 1e-9);
        assertEquals(8, DamageModel.arrow(3.0, 0, true), 1e-9);
        assertEquals(9, DamageModel.arrow(3.0, 1, false), 1e-9, "Power I: three a block a tick");
        // A mace's smash after a fall: 4 a block to three, 2 a block to eight, then 1.
        assertEquals(0, DamageModel.maceBonus(1));
        assertEquals(12, DamageModel.maceBonus(3), 1e-9);
        assertEquals(22, DamageModel.maceBonus(8), 1e-9);
        assertEquals(24, DamageModel.maceBonus(10), 1e-9);
        assertEquals((6 + 24) * 1.5, DamageModel.hit(new Weapon(Kind.MACE, Tier.NONE, 0), 1, true, 0, 10), 1e-9);
    }
}
