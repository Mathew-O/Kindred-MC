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

import java.util.UUID;

/**
 * The baseline-duel benchmark (E-41b): duels, one after another, between a player fighting with its melee tactics
 * (spacing, strafing, a shield raised against a swing, getting away and eating) and the declared baseline, the same
 * body and fight with the tactics off (it closes in and strikes whenever its swing is full, with a jump for a critical),
 * in equal kit (an iron sword, an iron chestplate, a shield and bread) from randomised legal starts in a walled arena.
 * Run apart from the regression suite (not registered there); the number of duels is the system property
 * {@code kindred.duels} (20 by default, 200 for the benchmark). It reports the tactics' wins, the baseline's, the draws
 * and the win rate with its 95% interval; it asserts only that every duel was fought to an end.
 */
public final class NativeDuelBenchmarkGameTests implements FabricGameTest {
    static final int DUELS = Integer.getInteger("kindred.duels", 20);
    /** The declared opponent: "baseline" (the tactics off), or a fixed style, "turtle", "spammer" or "evade". */
    static final String OPPONENT = System.getProperty("kindred.duel.opponent", "baseline");
    /** What is switched off in the player's own fight, for a control: "none", "tactics", "tendencies" or "prediction". */
    static final String OFF = System.getProperty("kindred.duel.off", "none");
    /** Whether both carry a shield (true by default). */
    static final boolean SHIELDS = !"false".equals(System.getProperty("kindred.duel.shields"));

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return id == null ? null : f.source.getServer().getPlayerManager().getPlayer(id);
    }

    private static boolean settled(NativePaidSkillsGameTests.Fixture f, UUID id) {
        String line = f.runtime.nativeStatusLine(id);
        return line.contains("busy=false") && line.contains("workPending=false") && line.contains("actionPending=false")
                && (line.contains("activity=IDLE") || line.contains("activity=none")) && line.contains("flushes=true/true") && line.contains("cancelling=false");
    }

    /** The Wilson score interval at 95% for {@code wins} of {@code n}. */
    static double[] wilson(int wins, int n) {
        if (n == 0) return new double[]{0, 1};
        double z = 1.96, p = (double) wins / n, d = 1 + z * z / n;
        double centre = (p + z * z / (2.0 * n)) / d, half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d;
        return new double[]{centre - half, centre + half};
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "duelBench", tickLimit = 1_000_000)
    public void theMeleeTacticsAgainstTheDeclaredBaseline(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 999_000);
        var server = context.getWorld().getServer();
        UUID[] ids = new UUID[2], players = new UUID[2];
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        var rng = new java.util.Random(20261028L);
        int[] state = {0}, tally = new int[3], done = {0};
        UUID[] match = {null};
        long[] since = {0}, ticks = {0}, waiting = {-1};
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
                    // The baseline: the same fight with its tactics off; or a declared style.
                    f.runtime.meleeTactics(ids[1], false);
                    if (!OPPONENT.equals("baseline")) f.runtime.meleeStyle(ids[1], OPPONENT);
                    // The control: the player's fight with a part of it off.
                    if (OFF.equals("tactics")) f.runtime.meleeTactics(ids[0], false);
                    if (OFF.equals("tendencies")) f.runtime.meleeTendencies(ids[0], false);
                    if (OFF.equals("prediction")) f.runtime.meleePrediction(ids[0], false);
                    KindredMod.LOGGER.info("Duel benchmark: {} duels against the {}, with {} off", DUELS, OPPONENT, OFF);
                })
                .createAndAdd(() -> {
                    long now = server.getTicks();
                    var hal = player(f, players[0]);
                    var wren = player(f, players[1]);
                    switch (state[0]) {
                        case 0 -> {
                            if (done[0] >= DUELS) break;
                            if (hal == null || wren == null || !hal.isAlive() || !wren.isAlive() || !settled(f, ids[0]) || !settled(f, ids[1])) {
                                if (now % 200 == 0) {
                                    KindredMod.LOGGER.info("Duel benchmark: waiting for both to settle; Hal {}, Wren {}", f.runtime.nativeStatusLine(ids[0]), f.runtime.nativeStatusLine(ids[1]));
                                }
                                if (waiting[0] < 0) waiting[0] = now;
                                if (now - waiting[0] > 2400) {
                                    // A player that never settles (one blocked for reconciliation): the benchmark ends, said loudly.
                                    KindredMod.LOGGER.error("Duel benchmark STUCK after {} duels: Hal {}, Wren {}", done[0], f.runtime.nativeStatusLine(ids[0]),
                                            f.runtime.nativeStatusLine(ids[1]));
                                    done[0] = DUELS;
                                }
                                break;
                            }
                            var world = context.getWorld();
                            waiting[0] = -1;
                            for (var drop : world.getEntitiesByClass(ItemEntity.class, new Box(o).expand(16), e -> true)) drop.discard();
                            // A randomised legal start: anywhere in the arena, at least six apart, facing anywhere.
                            double ax, az, bx, bz;
                            do {
                                ax = rng.nextInt(15) - 7 + 0.5; az = rng.nextInt(15) - 7 + 0.5;
                                bx = rng.nextInt(15) - 7 + 0.5; bz = rng.nextInt(15) - 7 + 0.5;
                            } while (Math.hypot(ax - bx, az - bz) < 6);
                            double[][] at = {{ax, az}, {bx, bz}};
                            int i = 0;
                            for (var p : new ServerPlayerEntity[]{hal, wren}) {
                                p.getInventory().clear();
                                p.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                                p.getInventory().setStack(1, new ItemStack(Items.BREAD, 4));
                                p.getInventory().setStack(2, new ItemStack(Items.IRON_AXE));
                                p.getInventory().selectedSlot = 0;
                                p.equipStack(EquipmentSlot.OFFHAND, SHIELDS ? new ItemStack(Items.SHIELD) : ItemStack.EMPTY);
                                p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                                p.setHealth(20);
                                p.getHungerManager().setFoodLevel(20);
                                p.getHungerManager().setSaturationLevel(5);
                                p.teleport(world, o.getX() + at[i][0], o.getY() + 2, o.getZ() + at[i][1], rng.nextFloat() * 360 - 180, 0);
                                i++;
                            }
                            // Both at full health, as each is told at a duel's start: the fixture's own heal made known.
                            f.runtime.forgetHealth(players[0]);
                            f.runtime.forgetHealth(players[1]);
                            if (f.runtime.proposeDuel(f.source, players[0], players[1]) != 1) {
                                KindredMod.LOGGER.info("Duel benchmark: duel {} not proposed", done[0] + 1);
                                break;
                            }
                            var proposed = f.runtime.matches().getLast();
                            match[0] = proposed.id();
                            if (f.runtime.startMatch(f.source, match[0]) != 1) {
                                KindredMod.LOGGER.info("Duel benchmark: duel {} not started", done[0] + 1);
                                break;
                            }
                            since[0] = now;
                            state[0] = 1;
                        }
                        case 1 -> {
                            var duel = f.runtime.matches().stream().filter(m -> m.id().equals(match[0])).findFirst().orElse(null);
                            if (duel == null) break;
                            if (duel.state() == MatchRegistry.State.ENDED) {
                                int side = players[0].equals(duel.winner()) ? 0 : players[1].equals(duel.winner()) ? 1 : 2;
                                tally[side]++;
                                done[0]++;
                                ticks[0] += now - since[0];
                                var rate = wilson(tally[0], tally[0] + tally[1]);
                                KindredMod.LOGGER.info("Duel benchmark {}/{}: {} ({}; {} ticks, Hal {}, Wren {}); tactics {}, baseline {}, draws {}; the tactics' rate {} [{}, {}]",
                                        done[0], DUELS, side == 0 ? "tactics" : side == 1 ? "baseline" : "draw", duel.result(), now - since[0],
                                        hal == null ? "-" : String.format("%.1f", hal.getHealth()), wren == null ? "-" : String.format("%.1f", wren.getHealth()),
                                        tally[0], tally[1], tally[2],
                                        String.format("%.2f", tally[0] + tally[1] == 0 ? 0 : (double) tally[0] / (tally[0] + tally[1])),
                                        String.format("%.2f", rate[0]), String.format("%.2f", rate[1]));
                                state[0] = 2;
                            } else if (now - since[0] > 6000) {
                                KindredMod.LOGGER.info("Duel benchmark: duel {} ran out, {}", done[0] + 1, duel.state());
                                f.runtime.stopMatch(f.source, match[0]);
                            }
                        }
                        default -> {
                            if (hal != null && wren != null && hal.isAlive() && wren.isAlive()) state[0] = 0;
                        }
                    }
                    context.assertTrue(done[0] >= DUELS && state[0] != 1, "Duels fought: " + done[0] + " of " + DUELS);
                })
                .createAndAdd(() -> {
                    var rate = wilson(tally[0], tally[0] + tally[1]);
                    KindredMod.LOGGER.info("Duel benchmark done: {} duels against the {} with {} off{}, {} ticks a duel on average; the player won {}, the opponent {}, {} drawn; the player's rate {} with 95% interval [{}, {}]",
                            DUELS, OPPONENT, OFF, SHIELDS ? "" : " and no shields", ticks[0] / Math.max(1, done[0]), tally[0], tally[1], tally[2], String.format("%.3f", (double) tally[0] / Math.max(1, tally[0] + tally[1])),
                            String.format("%.3f", rate[0]), String.format("%.3f", rate[1]));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
