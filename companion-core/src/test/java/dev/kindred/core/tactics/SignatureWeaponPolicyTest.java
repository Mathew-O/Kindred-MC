package dev.kindred.core.tactics;

import dev.kindred.core.CanonicalRoster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureWeaponPolicyTest {

    @Test
    void vespPrefersTridentAgainstWetTargetAtMeleeRange() {
        var trident = new WeaponProfile(0, "minecraft:trident", 1L, 9.0, 1.1, 200,
                Map.of("impaling", 5), false, false);
        var sword = new WeaponProfile(1, "minecraft:iron_sword", 2L, 6.0, 1.6, 250, Map.of(), false, true);
        var ctx = new SignatureWeaponPolicy.CombatContext(
                CanonicalRoster.VESP_NAME,
                3.0,
                0.0,
                false,
                true,
                false,
                1,
                false
        );
        var chosen = SignatureWeaponPolicy.selectOptimalWeapon(List.of(sword, trident), ctx);
        assertTrue(chosen.isPresent());
        assertEquals("minecraft:trident", chosen.get().normalizedItemId());
    }

    @Test
    void solPrefersMaceDuringAerialSmash() {
        var mace = new WeaponProfile(0, "minecraft:mace", 1L, 6.0, 0.6, 200,
                Map.of("density", 3), false, false);
        var sword = new WeaponProfile(1, "minecraft:iron_sword", 2L, 6.0, 1.6, 250, Map.of(), false, true);
        var ctx = new SignatureWeaponPolicy.CombatContext(
                CanonicalRoster.SOL_NAME,
                3.0,
                4.0,
                false,
                false,
                false,
                1,
                false
        );
        var chosen = SignatureWeaponPolicy.selectOptimalWeapon(List.of(sword, mace), ctx);
        assertTrue(chosen.isPresent());
        assertTrue(chosen.get().isMace());
    }

    @Test
    void kindredPrefersAxeAgainstShieldBlocker() {
        var axe = new WeaponProfile(0, "minecraft:iron_axe", 1L, 9.0, 0.9, 250, Map.of(), true, false);
        var sword = new WeaponProfile(1, "minecraft:iron_sword", 2L, 6.0, 1.6, 250, Map.of(), false, true);
        var ctx = new SignatureWeaponPolicy.CombatContext(
                CanonicalRoster.KINDRED_NAME,
                2.5,
                0.0,
                false,
                false,
                true,
                1,
                false
        );
        var chosen = SignatureWeaponPolicy.selectOptimalWeapon(List.of(sword, axe), ctx);
        assertTrue(chosen.isPresent());
        assertTrue(chosen.get().isAxe());
    }

    @Test
    void maceSmashDamageScalesWithFallDistance() {
        double flat = SignatureWeaponPolicy.calculateMaceSmashDamage(6.0, 0.0, 0);
        double smash = SignatureWeaponPolicy.calculateMaceSmashDamage(6.0, 5.0, 2);
        assertTrue(smash > flat);
    }
}
