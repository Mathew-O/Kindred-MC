package dev.kindred.core.tactics;

import java.util.Objects;

/**
 * Standard deterministic implementation of CombatEstimateModel based on Minecraft 1.21.1 / Yarn
 * combat mechanics.
 * Side-effect free: does not mutate player body, inventory, world, entity health, cooldowns, or RNG.
 */
public final class StandardCombatPredictor implements CombatEstimateModel {

    public static final double SAFETY_HEALTH_RESERVE = 6.0; // 3 hearts
    public static final int NORMAL_STRIKE_TICKS = 1;
    public static final int CRITICAL_SETUP_TICKS = 9; // Setup jump to falling phase
    public static final int RETREAT_STEP_TICKS = 8;
    public static final int WEAPON_PREPARE_TICKS = 1;

    @Override
    public double calculateAttackCharge(double attackSpeed, int elapsedTicksSinceAttackOrSwitch) {
        if (attackSpeed <= 0) return 1.0;
        float progressPerTick = (float) (20.0 / attackSpeed);
        float charge = (elapsedTicksSinceAttackOrSwitch + 0.5f) / progressPerTick;
        return Math.max(0.0, Math.min(1.0, charge));
    }

    @Override
    public int ticksToFullCharge(double attackSpeed) {
        if (attackSpeed <= 0) return 0;
        return (int) Math.ceil(20.0 / attackSpeed);
    }

    @Override
    public DamageInterval estimateStrikeDamage(WeaponProfile weapon, MobThreatObservation target, double charge, boolean isCritical) {
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(target, "target");

        // 1. Charge scaling: base attack damage scales by 0.2 + 0.8 * c * c
        double c = Math.max(0.0, Math.min(1.0, charge));
        double baseScaled = weapon.baseDamage() * (0.2 + 0.8 * c * c);

        // 2. Critical hit: requires c > 0.9 and valid critical conditions.
        // Multiplier is 1.5 on the base portion at that point.
        double effectiveBase = (isCritical && c > 0.9) ? baseScaled * 1.5 : baseScaled;

        // 3. Enchantment damage: target-sensitive getDamageAgainst, scaled by c
        double enchantmentDamage = calculateEnchantmentBonus(weapon, target);
        double enchantmentScaled = enchantmentDamage * c;

        double rawDamage = effectiveBase + enchantmentScaled;

        // 4. Armor and toughness mitigation
        double minMitigated = calculateMitigatedDamage(rawDamage, target.armor(), target.toughness());
        // For upper damage interval, consider potential variation if armor/toughness has uncertainty
        double maxMitigated = minMitigated;

        return new DamageInterval(minMitigated, maxMitigated);
    }

    private double calculateEnchantmentBonus(WeaponProfile weapon, MobThreatObservation target) {
        double bonus = 0.0;

        // Sharpness: 0.5 * level + 0.5 (applies to all living targets)
        int sharpness = weapon.enchantmentLevel("sharpness");
        if (sharpness > 0) {
            bonus += 0.5 * sharpness + 0.5;
        }

        // Smite: 2.5 * level (applies ONLY to undead)
        int smite = weapon.enchantmentLevel("smite");
        if (smite > 0 && target.isUndead()) {
            bonus += 2.5 * smite;
        }

        // Bane of Arthropods: 2.5 * level (applies ONLY to arthropods)
        int bane = weapon.enchantmentLevel("bane_of_arthropods");
        if (bane > 0 && target.isArthropod()) {
            bonus += 2.5 * bane;
        }

        return bonus;
    }

    /**
     * Vanilla 1.21.1 armor mitigation calculation:
     * defense = clamp(armor - damage / (2.0 + toughness / 4.0), armor / 5.0, 20.0)
     * mitigatedDamage = damage * (1.0 - defense / 25.0)
     */
    public static double calculateMitigatedDamage(double damage, int armor, double toughness) {
        if (armor <= 0) return damage;
        double reduction = armor - damage / (2.0 + toughness / 4.0);
        double defense = Math.max(armor / 5.0, Math.min(20.0, reduction));
        return damage * (1.0 - defense / 25.0);
    }

    @Override
    public OutcomeEnvelope evaluateSequence(TacticalObservation obs, TacticalSequence sequence) {
        Objects.requireNonNull(obs, "obs");
        Objects.requireNonNull(sequence, "sequence");

        MobThreatObservation target = null;
        if (sequence.targetEntityId() != null) {
            for (var mob : obs.observedMobs()) {
                if (mob.entityId().equals(sequence.targetEntityId())) {
                    target = mob;
                    break;
                }
            }
        }

        // Validate unknown enchantments: refusal of precise forecast for unsupported enchantments
        if (sequence.weapon() != null && sequence.weapon().hasUnknownEnchantments()) {
            return new OutcomeEnvelope(
                    SurvivalClass.UNKNOWN,
                    DamageInterval.zero(),
                    DamageInterval.zero(),
                    TimeInterval.of(1),
                    false, false, false,
                    TacticalObjective.END_ENCOUNTER_SAFELY,
                    0.0, 0.0, false
            );
        }

        // Validate basic eligibility
        if (target != null && target.isCow() && !obs.foodTaskAuthorized()) {
            // Animal interaction requires explicit food task authorization
            return new OutcomeEnvelope(
                    SurvivalClass.INELIGIBLE,
                    DamageInterval.zero(),
                    DamageInterval.zero(),
                    TimeInterval.zero(),
                    false, false, false,
                    TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS,
                    0.0, 0.0, false
            );
        }

        // Calculate sequence execution time & weapon state
        int executionTicks = 0;
        int ticksSinceSwitch = 0;
        boolean switched = false;
        WeaponProfile activeWeapon = sequence.weapon();

        // Check if weapon requires switching
        if (activeWeapon != null && activeWeapon.slot() != obs.heldItemSlot()) {
            switched = !activeWeapon.itemId().equalsIgnoreCase(obs.heldItemId());
        }

        int lastStrikeTick = -100;
        double lastStrikeMinDmg = 0.0;
        double lastStrikeMaxDmg = 0.0;
        DamageInterval totalDealt = DamageInterval.zero();
        boolean hasCriticalStrike = false;
        boolean hasStrike = false;
        boolean hasRetreat = false;
        boolean collateralRisk = false;

        for (ActionStep step : sequence.steps()) {
            switch (step) {
                case PREPARE_WEAPON -> {
                    executionTicks += WEAPON_PREPARE_TICKS;
                    ticksSinceSwitch = 0;
                    if (activeWeapon != null && activeWeapon.slot() != obs.heldItemSlot()) {
                        switched = !activeWeapon.itemId().equalsIgnoreCase(obs.heldItemId());
                    }
                }
                case STAY_AND_GUARD -> {
                    executionTicks += 1;
                    ticksSinceSwitch += 1;
                }
                case APPROACH_TARGET -> {
                    int approachTicks = (target != null ? Math.max(1, (int) Math.ceil(target.distance() * 2.0)) : 4);
                    executionTicks += approachTicks;
                    ticksSinceSwitch += approachTicks;
                }
                case RETREAT_TO_SAFE_CORRIDOR -> {
                    executionTicks += RETREAT_STEP_TICKS;
                    ticksSinceSwitch += RETREAT_STEP_TICKS;
                    hasRetreat = true;
                }
                case NORMAL_STRIKE -> {
                    hasStrike = true;
                    executionTicks += NORMAL_STRIKE_TICKS;
                    ticksSinceSwitch += NORMAL_STRIKE_TICKS;
                    if (target != null && activeWeapon != null) {
                        double charge = switched
                                ? calculateAttackCharge(activeWeapon.attackSpeed(), ticksSinceSwitch)
                                : 1.0;
                        DamageInterval strikeDmg = estimateStrikeDamage(activeWeapon, target, charge, false);
                        // Recent-hit (i-frame) handling: adjacent strikes within 10 ticks are not additive
                        if (executionTicks - lastStrikeTick < 10) {
                            double effectiveMin = Math.max(0.0, strikeDmg.minDamage() - lastStrikeMinDmg);
                            double effectiveMax = Math.max(0.0, strikeDmg.maxDamage() - lastStrikeMaxDmg);
                            totalDealt = totalDealt.add(new DamageInterval(effectiveMin, effectiveMax));
                            lastStrikeMinDmg = Math.max(lastStrikeMinDmg, strikeDmg.minDamage());
                            lastStrikeMaxDmg = Math.max(lastStrikeMaxDmg, strikeDmg.maxDamage());
                        } else {
                            totalDealt = totalDealt.add(strikeDmg);
                            lastStrikeTick = executionTicks;
                            lastStrikeMinDmg = strikeDmg.minDamage();
                            lastStrikeMaxDmg = strikeDmg.maxDamage();
                        }

                        if (activeWeapon.isSword() && target.nearTeammateOrShelter()) {
                            collateralRisk = true; // Sword sweep risk
                        }
                    }
                }
                case CRITICAL_STRIKE -> {
                    hasStrike = true;
                    hasCriticalStrike = true;
                    executionTicks += CRITICAL_SETUP_TICKS;
                    ticksSinceSwitch += CRITICAL_SETUP_TICKS;
                    if (target != null && activeWeapon != null) {
                        double charge = switched
                                ? calculateAttackCharge(activeWeapon.attackSpeed(), ticksSinceSwitch)
                                : 1.0;
                        boolean effectiveCrit = obs.canPerformCritical();
                        DamageInterval strikeDmg = estimateStrikeDamage(activeWeapon, target, charge, effectiveCrit);
                        // Recent-hit (i-frame) handling
                        if (executionTicks - lastStrikeTick < 10) {
                            double effectiveMin = Math.max(0.0, strikeDmg.minDamage() - lastStrikeMinDmg);
                            double effectiveMax = Math.max(0.0, strikeDmg.maxDamage() - lastStrikeMaxDmg);
                            totalDealt = totalDealt.add(new DamageInterval(effectiveMin, effectiveMax));
                            lastStrikeMinDmg = Math.max(lastStrikeMinDmg, strikeDmg.minDamage());
                            lastStrikeMaxDmg = Math.max(lastStrikeMaxDmg, strikeDmg.maxDamage());
                        } else {
                            totalDealt = totalDealt.add(strikeDmg);
                            lastStrikeTick = executionTicks;
                            lastStrikeMinDmg = strikeDmg.minDamage();
                            lastStrikeMaxDmg = strikeDmg.maxDamage();
                        }

                        if (activeWeapon.isSword() && target.nearTeammateOrShelter()) {
                            collateralRisk = true;
                        }
                    }
                }
            }
        }

        // Check target reliable elimination
        boolean killsTargetReliably = false;
        if (target != null && target.isVisible() && totalDealt.covers(target.currentHealth())) {
            killsTargetReliably = true;
        }

        // Estimate incoming self-damage across observed mobs
        double conservativeSelfDamage = 0.0;
        boolean caughtInExplosion = false;
        boolean escapesHazard = hasRetreat && obs.hasSafeEscapeCorridor();

        for (var mob : obs.observedMobs()) {
            if (mob.isCreeper()) {
                boolean creeperKilledInTime = target != null && target.entityId().equals(mob.entityId())
                        && killsTargetReliably && executionTicks < mob.fuseTicksRemaining();

                if (!creeperKilledInTime) {
                    int remainingFuse = mob.fuseTicksRemaining();
                    boolean isIgnited = mob.isIgnited();
                    if ((isIgnited || remainingFuse >= 0) && executionTicks >= remainingFuse) {
                        // Explosion triggers during our sequence!
                        if (!hasRetreat || mob.distance() <= 6.0) {
                            conservativeSelfDamage += 25.0; // Lethal / severe blast damage
                            caughtInExplosion = true;
                        }
                    } else if (mob.distance() <= 3.5 && !hasRetreat) {
                        // In creeper trigger range without retreat
                        conservativeSelfDamage += 15.0;
                    }
                }
            } else if (mob.isSkeleton()) {
                // Skeleton shooting during sequence
                if (mob.distance() <= 16.0 && executionTicks >= mob.estimatedTicksToAttack()) {
                    conservativeSelfDamage += 4.0;
                }
            } else if (mob.isZombie()) {
                // Zombie melee during sequence
                if (mob.distance() <= 2.0 && executionTicks >= mob.estimatedTicksToAttack()) {
                    if (!(target != null && target.entityId().equals(mob.entityId()) && killsTargetReliably)) {
                        conservativeSelfDamage += 3.0;
                    }
                }
            }
        }

        if (obs.isStandingInHazard() && !hasRetreat) {
            conservativeSelfDamage += 4.0; // Standing in fire/magma without retreat
        }

        double residualHealth = obs.companionHealth() - conservativeSelfDamage;
        boolean safeContinuation = (residualHealth > SAFETY_HEALTH_RESERVE) && (!caughtInExplosion);
        if (hasCriticalStrike && !killsTargetReliably && !hasRetreat && !obs.hasSafeEscapeCorridor()) {
            safeContinuation = false;
        }

        // Survival class determination
        SurvivalClass survivalClass;
        if (residualHealth <= 0.0 || caughtInExplosion) {
            survivalClass = SurvivalClass.EMERGENCY_ONLY; // Lethal / severe exposure
        } else if (safeContinuation && (obs.hasSafeEscapeCorridor() || killsTargetReliably)) {
            survivalClass = SurvivalClass.SURVIVABLE;
        } else if (residualHealth > 0.0) {
            survivalClass = SurvivalClass.EMERGENCY_ONLY;
        } else {
            survivalClass = SurvivalClass.INELIGIBLE;
        }

        // Objective determination
        TacticalObjective objective;
        double preventedHarm = 0.0;

        if (obs.isStandingInHazard() || caughtInExplosion || (target != null && target.isCreeper() && target.fuseTicksRemaining() <= 15)) {
            objective = TacticalObjective.RESOLVE_IMMEDIATE_SERIOUS_HARM;
            preventedHarm = killsTargetReliably ? 30.0 : (hasRetreat ? 20.0 : 5.0);
        } else if (target != null && target.nearTeammateOrShelter() && obs.shelterProtectionAuthorized()) {
            objective = TacticalObjective.PREVENT_IRREVERSIBLE_MATERIAL_LOSS;
            preventedHarm = killsTargetReliably ? 25.0 : 10.0;
        } else if (target != null && !target.isCow()) {
            objective = TacticalObjective.END_ENCOUNTER_SAFELY;
            preventedHarm = killsTargetReliably ? 15.0 : totalDealt.minDamage();
        } else {
            objective = TacticalObjective.FULFIL_SURVIVAL_OR_TEAM_TASKS;
            preventedHarm = killsTargetReliably ? 10.0 : 2.0;
        }

        double resourceCost = (activeWeapon != null ? (hasStrike ? 1.0 : 0.0) : 0.0) + (hasCriticalStrike ? 0.5 : 0.0);

        return new OutcomeEnvelope(
                survivalClass,
                totalDealt,
                new DamageInterval(conservativeSelfDamage, conservativeSelfDamage),
                new TimeInterval(executionTicks, executionTicks),
                killsTargetReliably,
                escapesHazard,
                collateralRisk,
                objective,
                preventedHarm,
                resourceCost,
                safeContinuation
        );
    }
}
