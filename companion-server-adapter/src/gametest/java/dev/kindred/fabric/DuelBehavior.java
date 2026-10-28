package dev.kindred.fabric;

import dev.kindred.core.DuelPolicy;
import dev.kindred.core.Personality;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.UUID;

/**
 * In plain terms: an old sparring prototype kept only so old tests still run. It is not part of the game.
 *
 * Technical: Retired mob duel prototype, retained only in historical regression fixtures and never shipped.
 * Its free/copy equipment and independent controller cannot qualify AI-player combat.
 *
 * <p>States:
 * <ul>
 *   <li>IDLE — no duel active.
 *   <li>CHALLENGED — companion has accepted (or has challenged the player); draws a weapon and waits
 *       up to 30 s for the player to enter the 2–3 block engagement radius.
 *   <li>ACTIVE — duel in progress; companion faces opponent, approaches and attacks weakly every ~1.5 s.
 *   <li>COOLING — duel ended; 5-second pause before returning to IDLE (allows post-duel message to land),
 *       and sheathes any drawn weapon.
 * </ul>
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Non-weapon retaliation: hit-back always strikes bare-handed (unequips weapon temporarily).
 *   <li>Damage is reduced during a duel; no kill intent.
 *   <li>State is never persisted; a restart clears it and restores original equipment.
 *   <li>An owner `/companion stop` always wins — the arbiter's halt propagates here via {@link #forceEnd}.
 * </ul>
 */
final class DuelBehavior {

    enum State { IDLE, CHALLENGED, ACTIVE, COOLING }

    /** Max time (ms) in CHALLENGED before giving up if player doesn't engage. */
    static final long CHALLENGE_TIMEOUT_MS = 30_000;
    /** Max duel duration (ms) before auto-ending. */
    static final long MAX_DUEL_MS = 90_000;
    /** Radius (blocks, squared) at which a duel auto-starts once challenged (2-3 blocks). */
    static final double ENGAGE_RANGE_SQ = 3.0 * 3.0;
    /** Radius (blocks, squared) beyond which ACTIVE → end if player runs too far. */
    static final double RETREAT_RANGE_SQ = 6.0 * 6.0;
    /** Attack cooldown in server ticks (1.5 s = 30 ticks). */
    static final int ATTACK_COOLDOWN_TICKS = 30;
    /** Cooldown after any hit-back before another (ticks, ~2 s). */
    static final int HIT_BACK_COOLDOWN_TICKS = 40;

    private State state = State.IDLE;
    private UUID opponentId;
    private long stateEnteredMs;
    private int attackCooldown;
    private int hitBackCooldown;
    private boolean companionLandedHit;
    private boolean opponentLandedHit;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean weaponDrawn;

    // ----- Queries -----

    State state() { return state; }
    boolean idle() { return state == State.IDLE; }
    UUID opponentId() { return opponentId; }

    // ----- Duel initiation -----

    /**
     * Called when the companion accepts (or sends) a duel challenge.
     * Draws a weapon from inventory or equips a practice blade.
     */
    void beginChallenge(ServerPlayerEntity player, CompanionEntity body) {
        opponentId = player != null ? player.getUuid() : null;
        stateEnteredMs = now();
        state = State.CHALLENGED;
        attackCooldown = 0;
        companionLandedHit = false;
        opponentLandedHit = false;
        drawWeapon(body);
    }

    // ----- Per-tick update -----

    /**
     * Drives the duel state machine each server tick. Must run after the skill runner has had its tick.
     */
    void tick(CompanionEntity body, java.util.function.Function<UUID, ServerPlayerEntity> lookup,
              java.util.function.Consumer<String> speak, Personality personality) {
        if (hitBackCooldown > 0) hitBackCooldown--;
        switch (state) {
            case IDLE -> { }
            case CHALLENGED -> tickChallenged(body, lookup, speak);
            case ACTIVE -> tickActive(body, lookup, speak, personality);
            case COOLING -> {
                if (now() - stateEnteredMs > 5_000) {
                    sheatheWeapon(body);
                    state = State.IDLE;
                }
            }
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    // ----- Hit-back (called from damage hooks) -----

    /**
     * Called when the companion is attacked by a player while not in an active duel.
     * Performs a single non-weapon (unarmed) swing-back if the cooldown permits.
     */
    void reactToHit(CompanionEntity body, ServerPlayerEntity attacker,
                    java.util.function.Consumer<String> speak, Personality personality) {
        if (hitBackCooldown > 0) return;
        if (!body.isAlive()) return;
        hitBackCooldown = HIT_BACK_COOLDOWN_TICKS;

        // Ensure non-weapon attack: temporarily unequip any held weapon
        ItemStack held = body.getMainHandStack();
        boolean isWeapon = !held.isEmpty() && (held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem);
        if (isWeapon) {
            body.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }

        if (attacker != null) {
            body.tryAttack(attacker);
        }
        body.swingHand(Hand.MAIN_HAND);

        if (isWeapon) {
            body.equipStack(EquipmentSlot.MAINHAND, held);
        }

        speak.accept(DuelPolicy.hitBackLine(personality));
    }

    /** Track that the opponent landed a hit during the duel (used for win/loss check). */
    void recordOpponentHit() {
        if (state == State.ACTIVE) opponentLandedHit = true;
    }

    // ----- Force end from outside (stop command / unload) -----

    /** Immediately terminates any in-progress duel and sheathes weapon. */
    void forceEnd(CompanionEntity body) {
        sheatheWeapon(body);
        state = State.IDLE;
        opponentId = null;
        attackCooldown = 0;
    }

    // ----- Weapon draw / sheathe -----

    private void drawWeapon(CompanionEntity body) {
        if (weaponDrawn || body == null) return;
        previousMainHand = body.getMainHandStack().copy();

        // Check if companion has a weapon in inventory
        ItemStack found = ItemStack.EMPTY;
        for (int i = 0; i < body.inventory().size(); i++) {
            ItemStack stack = body.inventory().getStack(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem)) {
                found = stack.copy();
                break;
            }
        }

        if (!found.isEmpty()) {
            body.equipStack(EquipmentSlot.MAINHAND, found);
        } else if (body.getMainHandStack().isEmpty()) {
            body.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        }
        weaponDrawn = true;
    }

    private void sheatheWeapon(CompanionEntity body) {
        if (!weaponDrawn || body == null) return;
        body.equipStack(EquipmentSlot.MAINHAND, previousMainHand);
        previousMainHand = ItemStack.EMPTY;
        weaponDrawn = false;
    }

    // ----- Private state handlers -----

    private void tickChallenged(CompanionEntity body, java.util.function.Function<UUID, ServerPlayerEntity> lookup,
                                 java.util.function.Consumer<String> speak) {
        if (now() - stateEnteredMs > CHALLENGE_TIMEOUT_MS) {
            speak.accept("Guess you changed your mind.");
            sheatheWeapon(body);
            state = State.IDLE;
            opponentId = null;
            return;
        }
        ServerPlayerEntity opponent = opponentId != null ? lookup.apply(opponentId) : null;
        if (opponent == null || !opponent.isAlive()) {
            sheatheWeapon(body);
            state = State.IDLE;
            opponentId = null;
            return;
        }
        // Face the player while waiting for them to enter the 2-3 block radius
        body.getLookControl().lookAt(opponent, 30.0f, 30.0f);
        double distSq = body.squaredDistanceTo(opponent);
        if (distSq <= ENGAGE_RANGE_SQ) {
            // Player entered the 2-3 block engagement radius: duel begins!
            state = State.ACTIVE;
            stateEnteredMs = now();
            companionLandedHit = false;
            opponentLandedHit = false;
            attackCooldown = 15; // Brief reaction pause before first swing
        }
    }

    private void tickActive(CompanionEntity body, java.util.function.Function<UUID, ServerPlayerEntity> lookup,
                             java.util.function.Consumer<String> speak, Personality personality) {
        ServerPlayerEntity opponent = opponentId != null ? lookup.apply(opponentId) : null;
        boolean ended = false;

        if (opponent == null || !opponent.isAlive()) {
            ended = true;
        } else if (body.squaredDistanceTo(opponent) > RETREAT_RANGE_SQ) {
            ended = true;
        } else if (now() - stateEnteredMs > MAX_DUEL_MS) {
            ended = true;
        }

        if (ended) {
            boolean companionWon = !opponentLandedHit && companionLandedHit;
            speak.accept(DuelPolicy.postDuelLine(companionWon, personality));
            state = State.COOLING;
            stateEnteredMs = now();
            opponentId = null;
            sheatheWeapon(body);
            body.getNavigation().stop();
            return;
        }

        // Face and track the opponent
        body.getLookControl().lookAt(opponent, 30.0f, 30.0f);
        double distSq = body.squaredDistanceTo(opponent);

        // Move into melee range if needed
        if (distSq > 3.0 && body.getNavigation().isIdle()) {
            body.getNavigation().startMovingTo(opponent, 1.15);
        }

        // Attack on cooldown within melee range
        if (attackCooldown <= 0 && distSq <= 6.0) {
            if (body.tryAttack(opponent)) {
                companionLandedHit = true;
                body.swingHand(Hand.MAIN_HAND);
            }
            attackCooldown = ATTACK_COOLDOWN_TICKS;
        }
    }

    private static long now() { return System.nanoTime() / 1_000_000; }
}
