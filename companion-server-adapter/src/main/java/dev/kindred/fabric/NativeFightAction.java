package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionLease;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.Checkpoint;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recorded fight with one mob: chase it with ordinary movement and strike with vanilla attacks whenever reach and
 * the attack cooldown allow, until it dies, gets away or the bounded time runs out. One continuous action, as a
 * player fights, rather than a walk and a single swing per task while a skeleton keeps shooting. The only effects
 * a fight may have on owned items are weapon and armor wear (or the weapon breaking) and drops picked up on the way.
 * The chase never steps into lava, fire or a drop deeper than the navigator allows; when that leaves no way to the
 * target, the player backs away from the edge before giving up. A creeper is fought hit and run, backing off while
 * the cooldown recovers, and any creeper that starts to hiss nearby is run from, or faced behind a raised shield when
 * the player has one in its off hand. A shooter is closed in on behind that shield, lowered for each swing.
 */
final class NativeFightAction extends NativeRecordedAction {
    static final int MAX_TICKS = 300;
    private static final double STRIKE_RANGE = 2.8;
    /** A hissing creeper this close is run from; it stops its fuse once the player is beyond seven blocks. */
    private static final double CREEPER_FLEE = 7.5;
    private static final int UNSAFE_TICKS = 40;
    /** How long the shield stays up after a shooter stops drawing: long enough for the arrow to arrive. */
    private static final int SHOT_GUARD_TICKS = 12;
    /** Ticks a raised shield takes before it guards (vanilla's five). */
    private static final int SHIELD_WARMUP = 5;
    /**
     * How far the swing has recovered when the player jumps for a critical hit: with about a dozen ticks in the air, it
     * lands the swing just past the top of the jump, when the cooldown is full and the player is falling.
     */
    private static final float JUMP_AT = 0.5f;

    private final NativePlayerBody body;
    private final Supplier<ActionLease> lease;
    private final ActionAdmission.Writer writer;
    private final Consumer<Runnable> dispatch;
    private final UUID targetId;
    private int slot;
    private ActionLease issuedLease;
    private ActionAdmission admission;
    private InventoryEvidence before;
    private boolean halted, unknown, fighting;
    private int ticks, unseen, hits, crits, unsafe, guardTicks;
    private Step result;
    private CompletableFuture<ActionRecord> flush;
    /** The fight's record (E-41a): what was seen, chosen and confirmed each tick. */
    private dev.kindred.core.tactics.EncounterRecorder recorder;
    private String decision = "";
    private final java.util.List<String> events = new java.util.ArrayList<>();
    private int shieldWear = -1;
    /**
     * The melee of a fight with another player (E-41b): the move each tick by {@link dev.kindred.core.tactics.Melee}.
     * Null against a mob, or with the tactics off (the declared baseline: close in and strike when the swing is full).
     */
    private final dev.kindred.core.tactics.MeleePolicy melee;
    private int strafe = 1, strafeTicks;
    /** When the opponent was last seen to swing, and whether it was swinging the tick before. */
    private long opponentSwungAt = -1_000_000L;
    private boolean opponentWasSwinging;
    /** Food eaten in the fight, by item: what eating may take from the pack. */
    private final Map<Item, Integer> eaten = new HashMap<>();
    private Item eating;
    private int eatingBefore;
    /** What it has learned of this opponent (E-41c), kept across its fights with it; null with it off (the control). */
    private final dev.kindred.core.tactics.Tendencies tendencies;
    private boolean wasFull;
    /** Whether it closes in on where the opponent will be rather than where it is (E-41c); off for the control. */
    private final boolean predicts;
    private double lastOpponentX = Double.NaN, lastOpponentZ;
    /**
     * A declared opponent's fixed style for the benchmarks and the mechanics suite (E-41b, E-41c), null for the
     * player's own fight: "turtle" keeps its shield up but for each full swing; "spammer" swings whenever in reach,
     * however little its swing has recovered; "evade" only runs.
     */
    private final String style;
    /** The strategist's proposal for the fight as it stands (E-41f), asked for and checked by the runtime; or null. */
    private java.util.function.BiFunction<net.minecraft.server.network.ServerPlayerEntity, net.minecraft.entity.player.PlayerEntity,
            Optional<dev.kindred.core.tactics.CombatProposals.Tactic>> advisor;
    private dev.kindred.core.tactics.CombatProposals.Tactic leaning;

    NativeFightAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                      UUID targetId, int slot) {
        this(body, lease, writer, dispatch, targetId, slot, true, null, false, null, null);
    }

    NativeFightAction(NativePlayerBody body, Supplier<ActionLease> lease, ActionAdmission.Writer writer, Consumer<Runnable> dispatch,
                      UUID targetId, int slot, boolean tactics, dev.kindred.core.tactics.Tendencies tendencies, boolean predicts, String style,
                      dev.kindred.core.tactics.LearnedMelee.Model learned) {
        this.body = body; this.lease = lease; this.writer = writer; this.dispatch = dispatch;
        this.targetId = targetId; this.slot = slot;
        if (!(body.entity().getServerWorld().getEntity(targetId) instanceof LivingEntity target) || !target.isAlive()) {
            throw new IllegalArgumentException("Nothing to fight");
        }
        // The hand-set melee, or a model trained offline that an operator turned on (E-41e).
        this.melee = !(tactics && target instanceof net.minecraft.entity.player.PlayerEntity) ? null
                : learned != null ? new dev.kindred.core.tactics.LearnedMelee(learned) : new dev.kindred.core.tactics.Melee();
        this.tendencies = melee == null ? null : tendencies;
        this.predicts = melee != null && predicts;
        this.style = melee == null && target instanceof net.minecraft.entity.player.PlayerEntity ? style : null;
    }

    /** This fight advised by {@code advisor} (E-41f): its proposal leans the melee while it holds. */
    NativeFightAction advised(java.util.function.BiFunction<net.minecraft.server.network.ServerPlayerEntity, net.minecraft.entity.player.PlayerEntity,
            Optional<dev.kindred.core.tactics.CombatProposals.Tactic>> advisor) {
        this.advisor = advisor;
        return this;
    }

    private Step waiting() { return Step.running(Map.of("durableAction", phase(), "hits", String.valueOf(hits)), false); }

    @Override
    public Step tick(Map<String, String> args, Optional<Checkpoint> checkpoint, boolean fresh) {
        if (halted || checkpoint.isPresent()) return Step.blocked("A recorded fight never replays", true);
        if (admission == null) {
            issuedLease = lease.get();
            before = InventoryEvidence.capture(body);
            admission = new ActionAdmission(ActionRecord.prepare(issuedLease.actionId(), issuedLease, "native_fight",
                    "target=" + targetId + "; slot=" + slot), before.description(), writer, dispatch);
        }
        if (admission.phase() == ActionAdmission.Phase.BLOCKED) {
            unknown = true;
            return Step.blocked("Durable fight acknowledgement is uncertain; reconcile", true);
        }
        if (result != null) {
            if (flush != null) {
                if (!flush.isDone()) return waiting();
                if (flush.isCompletedExceptionally()) { unknown = true; return Step.blocked("Fight close outcome uncertain", true); }
                return result;
            }
            return switch (admission.phase()) {
                case VERIFIED, FAILED, UNCERTAIN -> result;
                default -> waiting();
            };
        }
        var player = body.entity();
        var entity = player.getServerWorld().getEntity(targetId);
        LivingEntity target = entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
        if (admission.phase() == ActionAdmission.Phase.READY) {
            if (target == null) {
                result = Step.blocked("The target is gone; nothing to fight", true);
                flush = admission.close();
                return waiting();
            }
            if (admission.take().isEmpty()) return waiting();
            fighting = true;
            recorder = new dev.kindred.core.tactics.EncounterRecorder(issuedLease.actionId().toString(), player.getUuidAsString(),
                    player.getServerWorld().getTime());
            if (slot >= 0 && slot < 9) body.selectHotbar(issuedLease, slot);
        }
        if (admission.phase() != ActionAdmission.Phase.ISSUED || !fighting) return waiting();
        if (target == null) { settle("The target was defeated or is gone", true); return waiting(); }
        // A player is fought only while the match both joined runs (M-00a, D7).
        if (!body.mayStrike(target)) { settle("No running match allows striking that player", false); return waiting(); }
        if (++ticks > MAX_TICKS || player.squaredDistanceTo(target) > 24 * 24) { settle("The fight broke off", false); return waiting(); }
        boolean visible = player.canSee(target);
        unseen = visible ? 0 : unseen + 1;
        if (unseen > 60) { settle("Lost sight of the target", false); return waiting(); }

        double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double distance = player.distanceTo(target);
        // A full swing (E-39e): vanilla scales a hit by 0.2 + 0.8 * charge², and a critical needs more than 0.9.
        boolean ready = player.getAttackCooldownProgress(0.5f) >= 1.0f;
        var navigator = new NativeNavigator(player.getServerWorld());
        CreeperEntity hissing = hissingCreeper(player);
        boolean moved;
        boolean shield = player.getOffHandStack().isOf(Items.SHIELD);
        if (hissing != null && shield) {
            // A raised shield facing the blast stops it, as players do; running would turn the shield away.
            float toward = (float) Math.toDegrees(Math.atan2(-(hissing.getX() - player.getX()), hissing.getZ() - player.getZ()));
            moved = body.move(issuedLease, 0, 0, false, false, toward);
            guard(true);
            decision = "face the hissing creeper behind the shield";
        } else if (hissing != null) {
            moved = flee(navigator, hissing);
            decision = "run from the hissing creeper";
        } else if (target instanceof CreeperEntity && !ready && distance < 4.0) {
            // Hit and run: out of its reach while the swing recovers, so it never gets close enough to light.
            moved = flee(navigator, target);
            decision = "back off while the swing recovers";
        } else if (melee != null && target instanceof net.minecraft.entity.player.PlayerEntity opponent) {
            moved = melee(player, opponent, navigator, yaw, distance, visible, ready);
        } else if (style != null) {
            moved = styled(player, target, navigator, yaw, distance, visible, ready);
        } else if (distance > STRIKE_RANGE || !visible) {
            moved = closeIn(player, target, navigator, yaw, dx, dz, visible);
            if (decision.equals("unsafe")) { settle("The way to the target is unsafe", false); return waiting(); }
        } else {
            moved = strikeInReach(player, target, navigator, yaw, ready);
        }
        record(player, target, visible, distance);
        if (!moved) settle("Movement authority denied", false);
        return Step.running(Map.of("hits", String.valueOf(hits), "distance", String.format("%.1f", distance)), true);
    }


    /**
     * A fight with another player (E-41b): the move {@link dev.kindred.core.tactics.Melee} chooses from what the player
     * sees, made with the body's controls. Its opponent's swing is reckoned recovered from the time since it was last
     * seen to swing and the weapon it holds; its health as the player believes it. A strafe goes round it one way, and
     * the other after a while or when it meets a wall or an edge; a step back only where the ground behind is safe.
     * Eating, well out of reach, puts the weapon back in hand once done.
     */
    private boolean melee(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.entity.player.PlayerEntity opponent,
                          NativeNavigator navigator, float yaw, double distance, boolean visible, boolean ready) {
        long now = player.getServerWorld().getTime();
        // How far it moved this tick, as seen (a client's player has no velocity the server knows).
        double vx = Double.isNaN(lastOpponentX) ? 0 : opponent.getX() - lastOpponentX, vz = Double.isNaN(lastOpponentX) ? 0 : opponent.getZ() - lastOpponentZ;
        lastOpponentX = opponent.getX();
        lastOpponentZ = opponent.getZ();
        double opponentCooldown = opponent.getAttackCooldownProgressPerTick();
        if (opponent.handSwinging && !opponentWasSwinging) {
            // A swing seen: one of its chances to swing uncharged, and whether it did (E-41c).
            if (tendencies != null) tendencies.chance(dev.kindred.core.tactics.Tendencies.Habit.SPAMS, now - opponentSwungAt < 0.9 * opponentCooldown);
            opponentSwungAt = now;
        }
        opponentWasSwinging = opponent.handSwinging;
        // Ready, or near enough that a shield raised now (it guards only after five ticks up) is up in time for it.
        boolean opponentReady = now - opponentSwungAt >= opponentCooldown - SHIELD_WARMUP - 1;
        // Its own swing come full in reach: the opponent's chance to raise its shield against it, and whether it did.
        boolean full = player.getAttackCooldownProgress(0.5f) >= dev.kindred.core.tactics.Melee.FULL && distance <= STRIKE_RANGE && visible;
        if (full && !wasFull && tendencies != null) tendencies.chance(dev.kindred.core.tactics.Tendencies.Habit.BLOCKS, opponent.isBlocking());
        wasFull = full;
        var inventory = player.getInventory();
        int food = -1;
        for (int s = 0; s < 9 && food < 0; s++) {
            var stack = inventory.getStack(s);
            var component = stack.get(net.minecraft.component.DataComponentTypes.FOOD);
            if (component != null && !stack.isOf(Items.ROTTEN_FLESH) && !stack.isOf(Items.SPIDER_EYE) && !stack.isOf(Items.POISONOUS_POTATO)
                    && !stack.isOf(Items.PUFFERFISH) && player.canConsume(component.canAlwaysEat())) food = s;
        }
        boolean shield = player.getOffHandStack().isOf(Items.SHIELD) && !player.getItemCooldownManager().isCoolingDown(Items.SHIELD);
        // Against one it has learned keeps its shield up (E-41c), the axe in hand, which disables a shield it strikes;
        // the change costs a swing's recovery, so it is made once, and only when it is sure enough.
        if (tendencies != null && tendencies.likely(dev.kindred.core.tactics.Tendencies.Habit.BLOCKS) > 0.65
                && !(inventory.getMainHandStack().getItem() instanceof net.minecraft.item.AxeItem)) {
            for (int s = 0; s < 9; s++) {
                if (inventory.getStack(s).getItem() instanceof net.minecraft.item.AxeItem && body.selectHotbar(issuedLease, s)) {
                    slot = s;
                    events.add("weapon axe: it keeps its shield up");
                    break;
                }
            }
        }
        boolean axe = inventory.getMainHandStack().getItem() instanceof net.minecraft.item.AxeItem;
        // Food it set out to eat, eaten once it stops using it with one fewer.
        if (eating != null && !player.isUsingItem()) {
            if (inventory.count(eating) < eatingBefore) eaten.merge(eating, eatingBefore - inventory.count(eating), Integer::sum);
            eating = null;
            if (slot >= 0 && slot < 9) body.selectHotbar(issuedLease, slot);
        }
        var sight = new dev.kindred.core.tactics.Melee.Sight(distance * dev.kindred.core.tactics.Melee.REACH / STRIKE_RANGE,
                player.getAttackCooldownProgress(0.5f), player.getHealth(), food >= 0, shield, opponentReady, opponent.isBlocking(),
                NativePlayerBody.believedHealth(player, opponent), visible,
                tendencies == null ? 0.5 : tendencies.likely(dev.kindred.core.tactics.Tendencies.Habit.BLOCKS),
                tendencies == null ? 0.5 : tendencies.likely(dev.kindred.core.tactics.Tendencies.Habit.SPAMS), axe);
        if (advisor != null) {
            var tactic = advisor.apply(player, opponent).orElse(null);
            if (tactic != leaning) events.add("proposal " + (tactic == null ? "none" : tactic.name().toLowerCase(java.util.Locale.ROOT)));
            leaning = tactic;
            melee.lean(tactic);
        }
        var choice = melee.next(sight, now);
        decision = choice.why();
        if (choice.move() != dev.kindred.core.tactics.Melee.Move.EAT && eating != null) {
            // Called away from eating: the weapon back in hand.
            body.stopInputs();
            eating = null;
            if (slot >= 0 && slot < 9) body.selectHotbar(issuedLease, slot);
        }
        switch (choice.move()) {
            case APPROACH -> {
                double tx = opponent.getX(), tz = opponent.getZ();
                if (predicts) {
                    // Toward where it will be by the time the player is in reach, a sprint's pace away, not where it is.
                    var at = dev.kindred.core.tactics.Tendencies.predict(tx, tz, vx, vz, (int) Math.ceil(Math.max(0, distance - STRIKE_RANGE) / 0.28));
                    tx = at[0];
                    tz = at[1];
                }
                double dx = tx - player.getX(), dz = tz - player.getZ();
                return closeIn(player, opponent, navigator, (float) Math.toDegrees(Math.atan2(-dx, dz)), dx, dz, visible);
            }
            case STRIKE -> {
                return strikeInReach(player, opponent, navigator, yaw, ready);
            }
            case BLOCK -> {
                guard(true);
                return body.move(issuedLease, 0, 0, false, false, yaw);
            }
            case BACK_OFF -> {
                guard(false);
                if (player.isOnGround() && safeFrom(navigator, player.getX(), player.getY(), player.getZ(), yaw + 180, opponent)) {
                    return body.move(issuedLease, -1, 0, false, false, yaw);
                }
                return strafe(player, opponent, navigator, yaw);
            }
            case STRAFE -> {
                guard(false);
                return strafe(player, opponent, navigator, yaw);
            }
            case RETREAT -> {
                guard(false);
                return flee(navigator, opponent);
            }
            case EAT -> {
                guard(false);
                if (eating == null && food >= 0) {
                    eating = inventory.getStack(food).getItem();
                    eatingBefore = inventory.count(eating);
                    if (!body.selectHotbar(issuedLease, food) || !body.eat(issuedLease)) eating = null;
                }
                return body.move(issuedLease, 0, 0, false, false, yaw);
            }
        }
        return body.move(issuedLease, 0, 0, false, false, yaw);
    }

    /** A declared opponent's fight in its fixed {@link #style}. */
    private boolean styled(net.minecraft.server.network.ServerPlayerEntity player, LivingEntity target, NativeNavigator navigator,
                           float yaw, double distance, boolean visible, boolean ready) {
        double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
        switch (style) {
            case "evade" -> {
                guard(false);
                decision = "run";
                return flee(navigator, target);
            }
            case "spammer" -> {
                if (distance > STRIKE_RANGE || !visible) return closeIn(player, target, navigator, yaw, dx, dz, visible);
                boolean moved = body.move(issuedLease, 0, 0, false, false, yaw);
                decision = "swing, however little recovered";
                if (moved && player.getAttackCooldownProgress(0.5f) >= 0.3f && body.attack(issuedLease, targetId, slot)) hits++;
                return moved;
            }
            default -> {
                // "turtle": its shield up but for a full swing in reach.
                if (distance > STRIKE_RANGE || !visible) {
                    boolean moved = closeIn(player, target, navigator, yaw, dx, dz, visible);
                    guard(true);
                    return moved;
                }
                if (!ready) {
                    guard(true);
                    decision = "shield up while the swing recovers";
                    return body.move(issuedLease, 0, 0, false, false, yaw);
                }
                guard(false);
                boolean moved = body.move(issuedLease, 0, 0, false, false, yaw);
                decision = "strike, the swing full";
                if (moved && body.attack(issuedLease, targetId, slot)) hits++;
                return moved;
            }
        }
    }

    /** Round {@code opponent} sideways, facing it; the other way after a while, or where this way meets a wall or an edge. */
    private boolean strafe(net.minecraft.server.network.ServerPlayerEntity player, LivingEntity opponent, NativeNavigator navigator, float yaw) {
        if (++strafeTicks > 30 || player.horizontalCollision
                || !safeFrom(navigator, player.getX(), player.getY(), player.getZ(), yaw + (strafe > 0 ? -90 : 90), opponent)) {
            strafe = -strafe;
            strafeTicks = 0;
        }
        return body.move(issuedLease, 0, strafe, false, false, yaw);
    }

    /** Closing in on {@code target}, behind a shield against a shooter, or back from an edge when the way is unsafe. */
    private boolean closeIn(net.minecraft.server.network.ServerPlayerEntity player, LivingEntity target, NativeNavigator navigator,
                            float yaw, double dx, double dz, boolean visible) {
        boolean moved;
        decision = visible ? "close in" : "close in out of sight";
        if (navigator.safeHeading(player, yaw)) {
            unsafe = 0;
            // Closing in on a shooter, the shield goes up while it draws its bow and a moment after, as players
            // do: arrows stop at the shield, and the rest of the way is covered at full pace.
            if (target instanceof RangedAttackMob && visible && target.isUsingItem()) guardTicks = SHOT_GUARD_TICKS;
            guard(guardTicks-- > 0);
            boolean climb = player.isOnGround() && (player.horizontalCollision || target.getY() > player.getY() + 0.6 && Math.hypot(dx, dz) < 1.5);
            moved = body.move(issuedLease, 1.0f, 0, climb || player.isTouchingWater(), false, yaw);
        } else {
            // Cannot get at it from here (a ledge, lava between): step back from the edge, where a hit or an
            // arrow could knock the player over, until giving up on this fight.
            moved = flee(navigator, target);
            decision = "back from the edge";
            if (++unsafe > UNSAFE_TICKS) decision = "unsafe";
        }
        return moved;
    }

    /** In reach of {@code target}: a jump as the swing recovers and the strike on the way down, off any edge first. */
    private boolean strikeInReach(net.minecraft.server.network.ServerPlayerEntity player, LivingEntity target, NativeNavigator navigator,
                                  float yaw, boolean ready) {
        boolean moved;
        // In reach, a player jumps as the swing recovers and strikes on the way down: a falling hit is a critical one,
        // half as much again (an iron sword's kill on a zombie takes three of those, not four). A creeper keeps to hit
        // and run, and in water or on a ladder a jump is no fall.
        boolean jumping = jumps(player, target);
        boolean airborne = !player.isOnGround();
        boolean jump = jumping && !airborne && player.getAttackCooldownProgress(0.5f) >= JUMP_AT;
        // With an edge behind (lava, a drop), a hit would knock the player over it: a player sidesteps round the mob
        // until the ground behind is safe, still facing it (a hoglin threw the staged End run into a fortress's lava).
        int[] step = player.isOnGround() && !safeFrom(navigator, player.getX(), player.getY(), player.getZ(), yaw + 180, target)
                ? offTheEdge(navigator, target, yaw) : new int[]{0, 0};
        boolean stepping = step[0] != 0 || step[1] != 0;
        moved = body.move(issuedLease, step[0], step[1], jump && !stepping, false, yaw);
        decision = stepping ? "sidestep off the edge" : jump && !stepping ? "jump for a critical" : "wait for the swing";
        boolean falling = airborne && player.getVelocity().y < 0 && player.fallDistance > 0;
        if (moved && ready && (!jumping || falling)) {
            guard(false); // The shield comes down for the swing.
            boolean critical = critical(player);
            decision = critical ? "strike, critical" : "strike";
            if (body.attack(issuedLease, targetId, slot)) {
                hits++;
                if (critical) crits++;
                // Confirmed only as a player sees it: the target's hurt flash, from this blow.
                var source = target.getRecentDamageSource();
                boolean landed = target.hurtTime == target.maxHurtTime && source != null && source.getAttacker() == player;
                events.add((landed ? "hit-landed" : "hit-unseen") + " target=" + shortId(target) + (critical ? " critical" : ""));
            }
        }
        return moved;
    }

    /**
     * Whether to jump for critical hits on {@code target}: not a creeper (hit and run keeps it from lighting), and only
     * where a jump is a fall (not in water or lava, on a ladder or vine, or blinded).
     */
    private static boolean jumps(net.minecraft.server.network.ServerPlayerEntity player, LivingEntity target) {
        return !(target instanceof CreeperEntity) && !player.isTouchingWater() && !player.isInLava() && !player.isClimbing()
                && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS) && !player.hasVehicle();
    }

    /** Vanilla's test for a critical hit, as the swing about to be made will meet it: falling, swing recovered, not sprinting. */
    private static boolean critical(net.minecraft.server.network.ServerPlayerEntity player) {
        return player.getAttackCooldownProgress(0.5f) > 0.9f && player.fallDistance > 0 && !player.isOnGround() && !player.isClimbing()
                && !player.isTouchingWater() && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS)
                && !player.hasVehicle() && !player.isSprinting();
    }

    /** The heavy hitters, whose hits toss a player well back. */
    private static boolean heavy(LivingEntity target) {
        return target instanceof net.minecraft.entity.mob.HoglinEntity || target instanceof net.minecraft.entity.mob.ZoglinEntity
                || target instanceof net.minecraft.entity.mob.RavagerEntity || target instanceof net.minecraft.entity.passive.IronGolemEntity
                || target instanceof net.minecraft.entity.mob.PiglinBruteEntity;
    }

    /** How far a hit from {@code target} can push the player. */
    private static double knockback(LivingEntity target) {
        return heavy(target) ? 3.6 : 1.6;
    }

    /**
     * Whether a hit from {@code target} cannot push a player at x, y, z over an edge, pushed toward {@code away}. A
     * heavy hitter's toss is not only straight back: vanilla adds a second push turned by a random angle (a hoglin's,
     * `Hoglin.knockback`, is turned by up to ten radians, so it can point almost anywhere), so the ground to the sides
     * must be safe too; a hoglin's toss threw the player into the lava beside it when only the ground behind was.
     */
    private static boolean safeFrom(NativeNavigator navigator, double x, double y, double z, float away, LivingEntity target) {
        float[] fan = heavy(target) ? new float[]{0, -45, 45, -90, 90} : new float[]{0};
        for (float turn : fan) if (!navigator.safeKnockback(x, y, z, away + turn, knockback(target))) return false;
        return true;
    }

    /**
     * The step (forward, sideways) off an edge the player stands with its back to, still facing the mob along
     * {@code yaw}: round it forward and to a side, or straight to a side, the first that is safe to walk and leaves safe
     * ground behind a hit from where it lands; else the first that is safe to walk; else none.
     */
    private int[] offTheEdge(NativeNavigator navigator, LivingEntity target, float yaw) {
        var player = body.entity();
        int[][] steps = {{1, 1}, {1, -1}, {0, 1}, {0, -1}};
        int[] walkable = {0, 0};
        for (int[] step : steps) {
            float heading = yaw - (step[0] != 0 ? 45 : 90) * step[1];
            if (!navigator.safeHeading(player, heading)) continue;
            if (walkable[0] == 0 && walkable[1] == 0) walkable = step;
            double radians = Math.toRadians(heading);
            double x = player.getX() - Math.sin(radians), z = player.getZ() + Math.cos(radians);
            float away = (float) Math.toDegrees(Math.atan2(-(x - target.getX()), z - target.getZ()));
            if (safeFrom(navigator, x, player.getY(), z, away, target)) return step;
        }
        return walkable;
    }

    /** Raise or lower an off-hand shield, if the player has one; a no-op otherwise. */
    private void guard(boolean up) {
        var player = body.entity();
        boolean raised = player.isUsingItem() && player.getActiveItem().isOf(Items.SHIELD);
        if (up == raised || up && !player.getOffHandStack().isOf(Items.SHIELD)) return;
        body.block(issuedLease, up);
    }

    /** The nearest creeper within flee range whose fuse is burning. */
    private static CreeperEntity hissingCreeper(net.minecraft.server.network.ServerPlayerEntity player) {
        return player.getServerWorld().getEntitiesByClass(CreeperEntity.class, player.getBoundingBox().expand(CREEPER_FLEE),
                        creeper -> creeper.isAlive() && creeper.getFuseSpeed() > 0 && player.distanceTo(creeper) < CREEPER_FLEE)
                .stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }

    /** Sprint away from {@code threat} along the safest heading near straight away from it; stand if none is safe. */
    private boolean flee(NativeNavigator navigator, net.minecraft.entity.Entity threat) {
        var player = body.entity();
        float away = (float) Math.toDegrees(Math.atan2(-(player.getX() - threat.getX()), player.getZ() - threat.getZ()));
        for (float turn : new float[]{0, 35, -35, 70, -70, 105, -105}) {
            float yaw = away + turn;
            if (!navigator.safeHeading(player, yaw)) continue;
            return body.move(issuedLease, 1.0f, 0, player.isOnGround() && player.horizontalCollision || player.isTouchingWater(), true, yaw);
        }
        return body.move(issuedLease, 0, 0, false, false, away);
    }

    /**
     * Lost nothing but weapon or armor wear (a worn-out weapon may break); anything gained was picked up. A hunt's
     * compass is the match's: taken back when the fight ends the hunt, it was never the player's to lose.
     */
    private boolean conserved(InventoryEvidence after) {
        Map<Item, Integer> lost = new HashMap<>();
        for (ItemStack stack : before.stacks()) if (!stack.isEmpty() && !HuntCompass.handed(stack)) lost.merge(stack.getItem(), stack.getCount(), Integer::sum);
        for (ItemStack stack : after.stacks()) if (!stack.isEmpty() && !HuntCompass.handed(stack)) lost.merge(stack.getItem(), -stack.getCount(), Integer::sum);
        for (var entry : lost.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (entry.getValue() <= eaten.getOrDefault(entry.getKey(), 0)) continue; // Eaten in the fight (E-41b).
            boolean brokenWeapon = entry.getValue() == 1 && slot >= 0 && slot < before.stacks().length
                    && before.stacks()[slot].isOf(entry.getKey()) && before.stacks()[slot].isDamageable();
            if (!brokenWeapon) return false;
        }
        return true;
    }

    /** This tick in the fight's record: the player, the target as it could know it, the choice, the confirmed events. */
    private void record(net.minecraft.server.network.ServerPlayerEntity player, LivingEntity target, boolean visible, double distance) {
        if (recorder == null) return;
        if (player.hurtTime > 0 && player.hurtTime == player.maxHurtTime) {
            var source = player.getRecentDamageSource();
            var attacker = source == null ? null : source.getAttacker();
            events.add("hit-taken from=" + (attacker == null ? "unknown" : net.minecraft.registry.Registries.ENTITY_TYPE.getId(attacker.getType()).getPath()));
        }
        var shield = player.getOffHandStack();
        int wear = shield.isOf(Items.SHIELD) ? shield.getDamage() : -1;
        if (shieldWear >= 0 && wear > shieldWear) events.add("blocked");
        shieldWear = wear;
        var belief = body.beliefs().of(target.getUuid(), target.getMaxHealth(), target instanceof net.minecraft.entity.player.PlayerEntity,
                player.getServerWorld().getTime());
        var seen = new dev.kindred.core.tactics.EncounterRecorder.Seen(shortId(target),
                net.minecraft.registry.Registries.ENTITY_TYPE.getId(target.getType()).getPath(), distance, visible,
                NativePlayerBody.believedHealth(player, target), belief.confidence(),
                target instanceof net.minecraft.entity.mob.MobEntity mob && mob.getTarget() == player);
        var self = new dev.kindred.core.tactics.EncounterRecorder.Self(player.getX(), player.getY(), player.getZ(), player.getHealth(),
                player.getAttackCooldownProgress(0.5f), player.isOnGround(), player.isBlocking(),
                net.minecraft.registry.Registries.ITEM.getId(player.getMainHandStack().getItem()).toString());
        recorder.record(new dev.kindred.core.tactics.EncounterRecorder.Frame(player.getServerWorld().getTime(), self, java.util.List.of(seen), decision, events));
        events.clear();
    }

    private static String shortId(net.minecraft.entity.Entity entity) {
        return entity.getUuidAsString().substring(0, 8);
    }

    private void settle(String reason, boolean won) {
        body.stopInputs();
        if (recorder != null) {
            recorder.end(reason);
            EncounterLog.write(body.entity().getServer(), body.entity().getUuid(), recorder);
            recorder = null;
        }
        if (hits > 0) KindredMod.LOGGER.info("{} fight: {} hits, {} critical - {}", body.entity().getName().getString(), hits, crits, reason);
        var after = InventoryEvidence.capture(body);
        if (conserved(after)) {
            admission.verify(after.description() + "; hits=" + hits + "; crits=" + crits, reason);
            result = won ? Step.completed(Map.of("hits", String.valueOf(hits), "outcome", reason)) : Step.blocked(reason, true);
        } else {
            unknown = true;
            admission.uncertain(reason + "; owned items changed beyond wear and pickups");
            result = Step.blocked("Fight outcome requires reconciliation", true);
        }
    }

    @Override boolean permits(NativePlayerBody.Intent intent, BlockPos requested) {
        if (halted || admission == null || admission.phase() != ActionAdmission.Phase.ISSUED || !fighting || result != null) return false;
        return intent == NativePlayerBody.Intent.MOVE || intent == NativePlayerBody.Intent.ATTACK || intent == NativePlayerBody.Intent.SELECT_SLOT
                || intent == NativePlayerBody.Intent.BLOCK || intent == NativePlayerBody.Intent.EAT && melee != null;
    }
    @Override boolean uncertain() { return unknown; }
    @Override ActionRecord snapshot() { return admission == null ? null : admission.snapshot(); }
    @Override String phase() { return admission == null ? "NOT_PREPARED" : admission.phase().name(); }
    @Override CompletableFuture<ActionRecord> close() {
        halted = true;
        body.stopInputs();
        if (flush != null) return flush;
        if (admission != null && admission.phase() == ActionAdmission.Phase.ISSUED && result == null) settle("The fight was interrupted", false);
        flush = admission == null ? CompletableFuture.completedFuture(null) : admission.close();
        return flush;
    }
    @Override public void halt() { close(); }
    @Override public boolean settled() { return true; }
}
