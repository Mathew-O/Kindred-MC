package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.EnumMap;
import java.util.UUID;

/**
 * The melee mechanics suite (E-41b): each move of the player's melee tried on its own, from randomised legal starts in a
 * walled arena, in a duel both joined, against a declared opponent that either stands (the strike, the strafe, getting
 * away, eating) or fights as the baseline does (the shield, the step back). Each trial passes when the move is seen made
 * in the world, from what the game shows, within its time:
 * <ul>
 *   <li>strike: its first blow on the opponent lands a full swing (at least an iron sword's full hit through iron);</li>
 *   <li>strafe: between its first two blows it goes round the opponent, sideways, at least thirty degrees;</li>
 *   <li>shield: a blow of the opponent's is taken on its raised shield (the shield wears);</li>
 *   <li>step back: after its first blow, it walks backward out of the opponent's way;</li>
 *   <li>get away: losing (five health against twenty), it gets five blocks further off;</li>
 *   <li>eat: hurt and well out of reach, it eats, and its weapon is back in hand after.</li>
 * </ul>
 * Then a fight stopped half way: nothing more is struck, and nothing of the fight moves the body. Run apart from the
 * regression suite (not registered there); the trials of each move are the system property {@code kindred.trials} (20
 * by default). It reports each move's completions and asserts only that every trial ran.
 */
public final class NativeMeleeMechanicsGameTests implements FabricGameTest {
    static final int TRIALS = Integer.getInteger("kindred.trials", 20);

    enum Skill { STRIKE, STRAFE, SHIELD, BACK_OFF, GET_AWAY, EAT }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return id == null ? null : f.source.getServer().getPlayerManager().getPlayer(id);
    }

    private static boolean settled(NativePaidSkillsGameTests.Fixture f, UUID id) {
        String line = f.runtime.nativeStatusLine(id);
        return line.contains("busy=false") && line.contains("workPending=false") && line.contains("actionPending=false")
                && (line.contains("activity=IDLE") || line.contains("activity=none")) && line.contains("flushes=true/true") && line.contains("cancelling=false");
    }

    /** One trial's watch: what was seen of it so far. */
    private static final class Watch {
        final Skill skill;
        long start;
        float wrenHealth = 20, startDistance;
        int hits;
        double swept, lastAngle = Double.NaN, maxDistance;
        int backward, bread;
        boolean ate, passed, failed;
        String why = "";

        Watch(Skill skill) { this.skill = skill; }
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "meleeMechanics", tickLimit = 1_000_000)
    public void eachMoveOfTheMeleeMadeFromRandomisedStarts(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 999_000);
        var server = context.getWorld().getServer();
        UUID[] ids = new UUID[2], players = new UUID[2];
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        var rng = new java.util.Random(20261029L);
        var passes = new EnumMap<Skill, Integer>(Skill.class);
        var runs = new EnumMap<Skill, Integer>(Skill.class);
        int[] state = {0}, trial = {0};
        UUID[] match = {null};
        Watch[] watch = {null};
        long[] stoppedAt = {0};
        float[] stoppedHealth = {0};
        int total = TRIALS * Skill.values().length;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = status.name().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(12, 10, 12));
                    for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                        for (int y = 0; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean wall = Math.abs(x) >= 9 || Math.abs(z) >= 9;
                        for (int y = 2; y <= 10; y++) world.setBlockState(o.add(x, y, z), wall && y <= 4 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    server.setPvpEnabled(true);
                    f.runtime.meleeTactics(ids[1], false);
                })
                .createAndAdd(() -> {
                    long now = server.getTicks();
                    var hal = player(f, players[0]);
                    var wren = player(f, players[1]);
                    var world = context.getWorld();
                    switch (state[0]) {
                        case 0 -> {
                            // A trial set up: the kit for its move, the declared opponent, and the duel.
                            if (trial[0] >= total) {
                                state[0] = 10;
                                break;
                            }
                            if (hal == null || wren == null || !hal.isAlive() || !wren.isAlive() || !settled(f, ids[0]) || !settled(f, ids[1])) break;
                            var skill = Skill.values()[trial[0] % Skill.values().length];
                            for (var drop : world.getEntitiesByClass(ItemEntity.class, new Box(o).expand(16), e -> true)) drop.discard();
                            boolean halShield = skill == Skill.STRIKE || skill == Skill.SHIELD || skill == Skill.GET_AWAY || skill == Skill.EAT;
                            for (var p : new ServerPlayerEntity[]{hal, wren}) {
                                p.getInventory().clear();
                                p.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                                if (p == hal && skill != Skill.GET_AWAY) p.getInventory().setStack(1, new ItemStack(Items.BREAD, 4));
                                p.getInventory().selectedSlot = 0;
                                p.equipStack(EquipmentSlot.OFFHAND, p == wren || halShield ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
                                p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                                p.setHealth(20);
                                p.getHungerManager().setFoodLevel(20);
                                p.getHungerManager().setSaturationLevel(5);
                            }
                            // Out of each other's way until the fight starts.
                            hal.teleport(world, o.getX() - 6.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                            wren.teleport(world, o.getX() + 6.5, o.getY() + 2, o.getZ() + 0.5, 0, 0);
                            boolean stands = skill == Skill.STRIKE || skill == Skill.STRAFE || skill == Skill.GET_AWAY || skill == Skill.EAT;
                            f.runtime.meleeStyle(ids[1], stands ? "idle" : null);
                            f.runtime.forgetHealth(players[0]);
                            f.runtime.forgetHealth(players[1]);
                            if (f.runtime.proposeDuel(f.source, players[0], players[1]) != 1) break;
                            match[0] = f.runtime.matches().getLast().id();
                            if (f.runtime.startMatch(f.source, match[0]) != 1) break;
                            watch[0] = new Watch(skill);
                            state[0] = 1;
                        }
                        case 1 -> {
                            // The fight's start: a randomised legal start for the move, both placed and set as it needs.
                            var duel = f.runtime.matches().stream().filter(m -> m.id().equals(match[0])).findFirst().orElse(null);
                            if (duel == null || duel.state() != MatchRegistry.State.RUNNING) break;
                            var w = watch[0];
                            double near = switch (w.skill) {
                                case GET_AWAY -> 3 + rng.nextDouble() * 2;
                                case EAT -> 8 + rng.nextDouble() * 3;
                                default -> 4 + rng.nextDouble() * 5;
                            };
                            // The opponent anywhere within four of the middle; the player that far from it, any way round,
                            // inside the walls.
                            double wx, wz, hx, hz;
                            do {
                                wx = rng.nextDouble() * 8 - 4;
                                wz = rng.nextDouble() * 8 - 4;
                                double angle = rng.nextDouble() * Math.PI * 2;
                                hx = wx + Math.cos(angle) * near;
                                hz = wz + Math.sin(angle) * near;
                            } while (Math.abs(hx) > 7.5 || Math.abs(hz) > 7.5);
                            wren.teleport(world, o.getX() + wx, o.getY() + 2, o.getZ() + wz, rng.nextFloat() * 360 - 180, 0);
                            hal.teleport(world, o.getX() + hx, o.getY() + 2, o.getZ() + hz, rng.nextFloat() * 360 - 180, 0);
                            if (w.skill == Skill.GET_AWAY) hal.setHealth(5);
                            if (w.skill == Skill.EAT) {
                                hal.setHealth(10);
                                hal.getHungerManager().setFoodLevel(14);
                            }
                            w.start = now;
                            w.wrenHealth = wren.getHealth();
                            w.startDistance = hal.distanceTo(wren);
                            w.bread = hal.getInventory().count(Items.BREAD);
                            state[0] = 2;
                        }
                        case 2 -> {
                            var w = watch[0];
                            long t = now - w.start;
                            float drop = w.wrenHealth - wren.getHealth();
                            boolean struck = drop > 0.01f;
                            if (struck) w.hits++;
                            w.wrenHealth = wren.getHealth();
                            double dx = hal.getX() - wren.getX(), dz = hal.getZ() - wren.getZ();
                            double angle = Math.toDegrees(Math.atan2(dz, dx));
                            switch (w.skill) {
                                case STRIKE -> {
                                    if (struck) {
                                        // An iron sword's full hit through an iron chestplate is 5.28; anything less was early.
                                        if (drop >= 5.0f) w.passed = true;
                                        else { w.failed = true; w.why = "a weak first blow: " + drop; }
                                    }
                                    if (t > 200 && !w.passed) { w.failed = true; w.why = "no blow landed"; }
                                }
                                case STRAFE -> {
                                    if (w.hits >= 1) {
                                        if (!Double.isNaN(w.lastAngle)) {
                                            double turn = Math.abs(((angle - w.lastAngle) + 540) % 360 - 180);
                                            w.swept += turn;
                                        }
                                        w.lastAngle = angle;
                                        w.maxDistance = Math.max(w.maxDistance, hal.distanceTo(wren));
                                        if (w.swept >= 30 && w.maxDistance <= 6) w.passed = true;
                                        if (w.hits >= 2 && !w.passed) { w.failed = true; w.why = "swept only " + Math.round(w.swept) + " degrees, as far as " + w.maxDistance; }
                                    }
                                    if (t > 300 && !w.passed) { w.failed = true; w.why = "swept " + Math.round(w.swept) + " in time, " + w.hits + " blows"; }
                                }
                                case SHIELD -> {
                                    var shield = hal.getOffHandStack();
                                    if (shield.isOf(Items.SHIELD) && shield.getDamage() > 0) w.passed = true;
                                    if (!hal.isAlive() || t > 400 && !w.passed) { w.failed = true; w.why = "no blow taken on the shield, health " + hal.getHealth(); }
                                }
                                case BACK_OFF -> {
                                    if (w.hits >= 1) {
                                        w.backward = hal.forwardSpeed < 0 ? w.backward + 1 : 0;
                                        if (w.backward >= 3) w.passed = true;
                                        if (w.hits >= 2 && !w.passed) { w.failed = true; w.why = "no step back between its blows"; }
                                    }
                                    if (!hal.isAlive() || t > 300 && !w.passed) { w.failed = true; w.why = "no step back, " + w.hits + " blows"; }
                                }
                                case GET_AWAY -> {
                                    if (hal.distanceTo(wren) >= w.startDistance + 5) w.passed = true;
                                    if (t > 120 && !w.passed) { w.failed = true; w.why = "only " + hal.distanceTo(wren) + " off, from " + w.startDistance; }
                                }
                                case EAT -> {
                                    if (hal.getInventory().count(Items.BREAD) < w.bread) w.ate = true;
                                    if (w.ate && !hal.isUsingItem() && hal.getInventory().selectedSlot == 0 && hal.getMainHandStack().isOf(Items.IRON_SWORD)) w.passed = true;
                                    if (t > 200 && !w.passed) { w.failed = true; w.why = w.ate ? "ate, but the sword not back in hand" : "did not eat, health " + hal.getHealth(); }
                                }
                            }
                            if (w.passed || w.failed) {
                                runs.merge(w.skill, 1, Integer::sum);
                                if (w.passed) passes.merge(w.skill, 1, Integer::sum);
                                KindredMod.LOGGER.info("Mechanics trial {}/{} {}: {} in {} ticks{}", trial[0] + 1, total, w.skill, w.passed ? "made" : "not made",
                                        t, w.passed ? "" : " (" + w.why + ")");
                                f.runtime.stopMatch(f.source, match[0]);
                                trial[0]++;
                                state[0] = 3;
                            }
                        }
                        case 3 -> {
                            var duel = f.runtime.matches().stream().filter(m -> m.id().equals(match[0])).findFirst().orElse(null);
                            if ((duel == null || duel.state() == MatchRegistry.State.ENDED) && hal != null && wren != null && hal.isAlive() && wren.isAlive()) state[0] = 0;
                        }
                        case 10 -> {
                            // A fight stopped half way: the player against the opponent standing, stopped after its first blow.
                            if (hal == null || wren == null || !hal.isAlive() || !wren.isAlive() || !settled(f, ids[0]) || !settled(f, ids[1])) break;
                            for (var p : new ServerPlayerEntity[]{hal, wren}) {
                                p.setHealth(20);
                                p.getInventory().clear();
                                p.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                                p.getInventory().selectedSlot = 0;
                                p.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                            }
                            hal.teleport(world, o.getX() - 2.5, o.getY() + 2, o.getZ() + 0.5, -90, 0);
                            wren.teleport(world, o.getX() + 2.5, o.getY() + 2, o.getZ() + 0.5, 90, 0);
                            f.runtime.meleeStyle(ids[1], "idle");
                            if (f.runtime.proposeDuel(f.source, players[0], players[1]) != 1) break;
                            match[0] = f.runtime.matches().getLast().id();
                            if (f.runtime.startMatch(f.source, match[0]) != 1) break;
                            watch[0] = new Watch(Skill.STRIKE);
                            watch[0].wrenHealth = 20;
                            state[0] = 11;
                        }
                        case 11 -> {
                            if (wren.getHealth() < 20) {
                                context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[0]) == 1, "Hal stopped");
                                stoppedAt[0] = now;
                                stoppedHealth[0] = wren.getHealth();
                                state[0] = 12;
                            }
                        }
                        case 12 -> {
                            // Stopped: no blow after it, and nothing of the fight moving the body.
                            if (wren.getHealth() < stoppedHealth[0]) { KindredMod.LOGGER.info("Mechanics: struck after the stop"); state[0] = 13; break; }
                            if (now - stoppedAt[0] > 2 && (hal.forwardSpeed != 0 || hal.sidewaysSpeed != 0 || hal.isUsingItem())) {
                                KindredMod.LOGGER.info("Mechanics: moved after the stop ({} {} {})", hal.forwardSpeed, hal.sidewaysSpeed, hal.isUsingItem());
                                state[0] = 13;
                                break;
                            }
                            if (now - stoppedAt[0] > 80) {
                                KindredMod.LOGGER.info("Mechanics: stopped clean, no blow and no input in 80 ticks after the stop");
                                state[0] = 14;
                            }
                        }
                        default -> { }
                    }
                    context.assertTrue(state[0] >= 13, "Trials run: " + trial[0] + " of " + total);
                })
                .createAndAdd(() -> {
                    var line = new StringBuilder();
                    for (var skill : Skill.values()) {
                        line.append(skill).append(' ').append(passes.getOrDefault(skill, 0)).append('/').append(runs.getOrDefault(skill, 0)).append("; ");
                    }
                    KindredMod.LOGGER.info("Mechanics done: {}stopped {}", line, state[0] == 14 ? "clean" : "with a stale action");
                    f.runtime.stopMatch(f.source, match[0]);
                    f.runtime.nativeLifecycle(f.source, "resume", ids[0]);
                    context.assertTrue(state[0] == 14, "No stale action after the stop");
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
