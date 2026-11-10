package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.rescue.RescuePlan;
import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.core.survival.SurvivalGoal;
import dev.kindred.model.Strategist;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In plain terms: the moves a rescue plan can ask for, each carried out a block at a time from what the player sees round
 * it, and stopping where it must: a walk at a wall, saying how far it went; a tunnel before water it would let in; a
 * staircase down before lava. And a stuck spell talked through in up to three rounds, each told how the last went: one
 * that gets out at its third round, and one that does not and leaves the player to its own ways with nothing broken.
 *
 * Technical: F-29c. The scenes are staged twenty blocks up, out of the terrain's way. For a verb's own fixture the plan
 * is handed to the goal as the bridge would hand a checked one; for the rounds a stub strategist answers each round with
 * its own plan. Every break, placement and move is a recorded task; the ledger counts them.
 */
public final class NativeRescueRoundsGameTests implements FabricGameTest {
    /** A strategist that answers each round with the next plan of its list, the last again after that. */
    static final class Rounds implements Strategist {
        final List<List<String>> plans;
        final List<String> reports = new ArrayList<>();

        Rounds(List<List<String>> plans) {
            this.plans = plans;
        }

        @Override
        public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
            return CompletableFuture.completedFuture(new Outcome(Optional.empty(), 0, 0, 0, 0, Optional.of("a fixture's stub gives no advice")));
        }

        @Override
        public CompletableFuture<Rescue> rescue(String report) {
            reports.add(report);
            var steps = plans.get(Math.min(reports.size(), plans.size()) - 1);
            return CompletableFuture.completedFuture(new Rescue(steps, "Round " + reports.size() + ".", 900, 0, 30, 5, Optional.empty()));
        }

        @Override public String provider() { return "Fixture"; }
        @Override public String model() { return "stub"; }
        @Override public String pace() { return "at once"; }
        @Override public void close() { }
    }

    /** The ledger's breaks and placements, prepared and verified. */
    private static final class Ledger {
        final AtomicInteger edits = new AtomicInteger(), breaks = new AtomicInteger(), places = new AtomicInteger();

        java.util.function.UnaryOperator<ActionAdmission.Writer> watch() {
            return writer -> new ActionAdmission.Writer() {
                @Override
                public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                    if (java.util.Set.of("native_pillar", "native_place", "native_break").contains(intent.kind())) edits.incrementAndGet();
                    return writer.prepare(intent);
                }

                @Override
                public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                    if (next.state() == ActionRecord.State.VERIFIED && "native_break".equals(next.kind())) breaks.incrementAndGet();
                    if (next.state() == ActionRecord.State.VERIFIED && "native_place".equals(next.kind())) places.incrementAndGet();
                    return writer.transition(next, expectedRevision);
                }
            };
        }
    }

    /** What a fixture checks, with the player and its goal as they were when the run was stopped. */
    @FunctionalInterface
    interface Check {
        void accept(NativePaidSkillsGameTests.Fixture f, BlockPos base, net.minecraft.server.network.ServerPlayerEntity player, PortalRunCampaign campaign);
    }

    /** A scene staged by a fixture, from the base cell the player stands in. */
    @FunctionalInterface
    interface Scene {
        void build(net.minecraft.server.world.ServerWorld world, BlockPos base);
    }

    /** A floor of stone eleven across under the base, air four high over it, the player on it facing north. */
    private static void floor(net.minecraft.server.world.ServerWorld world, BlockPos b) {
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            for (int y = -5; y <= -1; y++) world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
            for (int y = 0; y <= 6; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
        }
    }

    private static void stage(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos[] base, Scene scene, ItemStack... carried) {
        stage(context, f, base, false, scene, carried);
    }

    /**
     * {@code newGroundNorth}: the pit placed in the watchdog's eight-block cells so that a walk two north off its floor's
     * rim crosses into a cell the player has not stood in, which is the goal's progress. Without it, whether a spell out
     * of the pit counts as out rested on the goal's next gain within the plan's watch: in the three-round fixture, a log
     * cut from the ledge whose drop bounced, a fraction of a block, in or out of the reach a player picks up from.
     */
    private static void stage(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos[] base, boolean newGroundNorth, Scene scene,
                              ItemStack... carried) {
        var world = context.getWorld();
        BlockPos up = f.player().getBlockPos().up(20);
        BlockPos b = base[0] = newGroundNorth ? new BlockPos(up.getX(), up.getY(), (up.getZ() & ~7) + 1) : up.toImmutable();
        scene.build(world, b);
        world.setTimeOfDay(1000);
        var player = f.player();
        player.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
        player.setVelocity(Vec3d.ZERO);
        var inventory = player.getInventory();
        inventory.clear();
        for (int i = 0; i < carried.length; i++) inventory.setStack(i, carried[i]);
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
    }

    private static List<String> lines(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.rescuesForFixtures(f.id);
    }

    private static String lastPlan(NativePaidSkillsGameTests.Fixture f) {
        var plans = lines(f).stream().filter(l -> l.startsWith("rescue: ")).toList();
        return plans.isEmpty() ? "" : plans.getLast();
    }

    /**
     * One plan handed to the goal as the bridge hands a checked one, in a staged scene; {@code check} runs once the plan's
     * line is in, with the base cell.
     */
    private static void verb(TestContext context, String name, Scene scene, String plan, ItemStack[] carried,
                             Check check, Ledger ledger) {
        var f = new NativePaidSkillsGameTests.Fixture(context, ledger.watch(), true);
        BlockPos[] base = new BlockPos[1];
        net.minecraft.server.network.ServerPlayerEntity[] who = new net.minecraft.server.network.ServerPlayerEntity[1];
        PortalRunCampaign[] goal = new PortalRunCampaign[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> stage(context, f, base, scene, carried))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(base[0]), "On the staged floor"))
                .createAndAdd(() -> {
                    // The plan is handed before the goal's first look, so no task of the goal's is under way when it starts.
                    f.runtime.stuckTicksForFixtures(f.id, PortalRunCampaign.STUCK_TICKS);
                    var campaign = f.runtime.campaignForFixtures(f.id);
                    var parsed = RescuePlan.parse(List.of(plan.split(", ")), "A fixture's plan.");
                    context.assertTrue(parsed.plan().isPresent(), "Parsed: " + parsed.refusals());
                    campaign.rescue(parsed.plan().get(), 0, name);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.assertTrue(!lastPlan(f).isEmpty(), "The plan carried out");
                    // Stopped as the plan's line comes in: what is checked is what the plan left, not the goal's work after.
                    who[0] = f.player();
                    goal[0] = f.runtime.campaignForFixtures(f.id);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue {}: {}; the ledger {} edits, {} breaks, {} placements; at {}", name, lastPlan(f),
                            ledger.edits.get(), ledger.breaks.get(), ledger.places.get(), who[0].getBlockPos().subtract(base[0]).toShortString());
                    check.accept(f, base[0], who[0], goal[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ItemStack[] none() { return new ItemStack[0]; }

    /** A walk north at a wall two high two blocks off: it goes two, and its line says so and what stopped it. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueWalk", tickLimit = 3000)
    public void aWalkStopsAtAWallAndSaysHowFarItWent(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Walker", (world, b) -> {
            floor(world, b);
            for (int x = -6; x <= 6; x++) for (int y = 0; y <= 1; y++) world.setBlockState(b.add(x, y, -3), Blocks.STONE.getDefaultState());
        }, "walk north 4", none(), (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).equals("rescue: walk north 4 -> failed: 'walk north 4' went 2, then a two-high wall of stone"), "Its line: " + lastPlan(f));
            context.assertTrue(p.getBlockPos().equals(b.north(2)), "Two north: " + p.getBlockPos().subtract(b).toShortString());
            context.assertTrue(ledger.edits.get() == 0, "Nothing broken or placed: " + ledger.edits.get());
        }, ledger);
    }

    /** A tunnel north through three blocks of stone with its own stone pickaxe: six breaks, each recorded, and it walks through. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueTunnel", tickLimit = 4000)
    public void aTunnelBreaksAPassageTwoHighAndWalksIt(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Tunneller", (world, b) -> {
            floor(world, b);
            for (int x = -6; x <= 6; x++) for (int z = -3; z <= -1; z++) for (int y = 0; y <= 1; y++) world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
        }, "tunnel north 4", new ItemStack[]{new ItemStack(Items.STONE_PICKAXE)}, (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).equals("rescue: tunnel north 4 -> unstuck"), "Its line: " + lastPlan(f));
            context.assertTrue(p.getBlockPos().equals(b.north(4)), "Four north: " + p.getBlockPos().subtract(b).toShortString());
            context.assertTrue(ledger.breaks.get() == 6, "Six breaks, each recorded: " + ledger.breaks.get());
            for (int z = 1; z <= 3; z++) context.assertTrue(context.getWorld().getBlockState(b.north(z)).isAir() && context.getWorld().getBlockState(b.north(z).up()).isAir(), "The passage open at " + z);
        }, ledger);
    }

    /**
     * A tunnel north whose second block lies under water it can see, in a basin on top of the wall open to the sky: it
     * goes one block and stops before the break that would let the water in. No water in the passage.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueTunnelWet", tickLimit = 4000)
    public void aTunnelThatWouldLetWaterInStops(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Wet", (world, b) -> {
            floor(world, b);
            for (int x = -6; x <= 6; x++) for (int z = -4; z <= -1; z++) for (int y = 0; y <= 2; y++) world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
            world.setBlockState(b.add(0, 2, -2), Blocks.WATER.getDefaultState());
        }, "tunnel north 4", new ItemStack[]{new ItemStack(Items.STONE_PICKAXE)}, (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).equals("rescue: tunnel north 4 -> failed: 'tunnel north 4' went 1, then water beside the stone in the passage, which breaking it would let in"),
                    "Its line: " + lastPlan(f));
            var world = context.getWorld();
            context.assertTrue(world.getBlockState(b.add(0, 1, -2)).isOf(Blocks.STONE), "The block under the water not broken");
            context.assertTrue(world.getFluidState(b.add(0, 1, -1)).isEmpty() && world.getFluidState(b.add(0, 0, -1)).isEmpty(), "No water in the passage");
            context.assertTrue(p.getBlockPos().equals(b.north(1)), "One north: " + p.getBlockPos().subtract(b).toShortString());
        }, ledger);
    }

    /** A staircase up north into a cliff four high, with its stone pickaxe: three steps up and three along. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueStairsUp", tickLimit = 4000)
    public void stairsUpClimbAStepABlockAlong(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Climber", (world, b) -> {
            floor(world, b);
            for (int x = -6; x <= 6; x++) for (int z = -6; z <= -1; z++) for (int y = 0; y <= 3; y++) world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
        }, "stairs up north 3", new ItemStack[]{new ItemStack(Items.STONE_PICKAXE)}, (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).equals("rescue: stairs up north 3 -> unstuck"), "Its line: " + lastPlan(f));
            context.assertTrue(p.getBlockPos().equals(b.add(0, 3, -3)), "Three up and three north: " + p.getBlockPos().subtract(b).toShortString());
        }, ledger);
    }

    /**
     * A staircase down north over lava hidden under the second stair: the first step goes; at the second, the break of
     * the cell over the lava is refused by the break's own guard, which every break has (the lava is not in the slice:
     * the player cannot see it), and the plan stops there with the lava shut in and the player not on it. Never straight
     * down: the cell under where it started stays. The slice's own stop at lava it can see is the JUnit's.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueStairsLava", tickLimit = 4000)
    public void stairsDownStopAtLava(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Descender", (world, b) -> {
            floor(world, b);
            world.setBlockState(b.add(0, -3, -2), Blocks.LAVA.getDefaultState());
        }, "stairs down north 3", new ItemStack[]{new ItemStack(Items.STONE_PICKAXE)}, (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).equals("rescue: stairs down north 3 -> failed: 'stairs down north 3' did not go (Breaking it would release lava)"),
                    "Its line: " + lastPlan(f));
            var world = context.getWorld();
            context.assertTrue(p.getBlockPos().equals(b.add(0, -1, -1)), "One down and one north: " + p.getBlockPos().subtract(b).toShortString());
            context.assertTrue(!p.isInLava() && !p.isOnFire(), "Not in the lava");
            context.assertTrue(world.getBlockState(b.add(0, -2, -2)).isOf(Blocks.STONE), "The block over the lava not broken");
            context.assertTrue(world.getBlockState(b.add(0, -3, -2)).isOf(Blocks.LAVA) && world.getFluidState(b.add(0, -2, -2)).isEmpty(), "The lava shut in");
            context.assertTrue(world.getBlockState(b.down()).isOf(Blocks.STONE), "The block under where it started not dug");
        }, ledger);
    }

    /** A bridge east over a gap two wide and four deep with its dirt: two placements, each recorded, then on across. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueBridge", tickLimit = 4000)
    public void aBridgeCrossesAGapWithItsOwnBlocks(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Bridger", (world, b) -> {
            floor(world, b);
            for (int z = -6; z <= 6; z++) for (int x = 1; x <= 2; x++) for (int y = -5; y <= -1; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
        }, "bridge east 3", new ItemStack[]{new ItemStack(Items.DIRT, 8)}, (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).equals("rescue: bridge east 3 -> unstuck"), "Its line: " + lastPlan(f));
            context.assertTrue(p.getBlockPos().equals(b.east(3)), "Three east: " + p.getBlockPos().subtract(b).toShortString());
            context.assertTrue(ledger.places.get() == 2, "Two placements, each recorded: " + ledger.places.get());
            context.assertTrue(ItemMoves.count(p.getInventory(), Items.DIRT) == 6, "Two of its own dirt spent");
        }, ledger);
    }

    /** A torch beside its feet, from its hotbar. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueTorch", tickLimit = 3000)
    public void aTorchIsPlacedAtItsFeet(TestContext context) {
        var ledger = new Ledger();
        verb(context, "Lamplighter", (world, b) -> floor(world, b), "torch, wait 1", new ItemStack[]{new ItemStack(Items.TORCH, 4)}, (f, b, p, c) -> {
            context.assertTrue(lastPlan(f).startsWith("rescue: torch, wait 1 -> "), "Its line: " + lastPlan(f));
            context.assertTrue(context.getWorld().getBlockState(b.north()).isOf(Blocks.TORCH), "A torch beside its feet: " + context.getWorld().getBlockState(b.north()));
            context.assertTrue(ItemMoves.count(p.getInventory(), Items.TORCH) == 3, "One of its own torches");
        }, ledger);
    }

    // ------------------------------------------------------------------ rounds

    /** The pit, as F-29's fixtures have it: obsidian, glass and bread; with a small oak on the ledge for wood. */
    private static void pit(net.minecraft.server.world.ServerWorld world, BlockPos b, boolean log) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            for (int y = -1; y <= 2; y++) world.setBlockState(b.add(x, y, z), Blocks.OBSIDIAN.getDefaultState());
            for (int y = 3; y <= 6; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        for (int y = 0; y <= 2; y++) world.setBlockState(b.up(y), Blocks.AIR.getDefaultState());
        // A small oak on the ledge beside where the way out ends, its crown in sight from the ledge: wood for the goal.
        if (log) {
            for (int x = -2; x <= 2; x++) for (int z = -5; z <= -1; z++) for (int y = 5; y <= 6; y++) {
                if (Math.abs(x) + Math.abs(z + 3) <= 3) world.setBlockState(b.add(x, y, z), Blocks.OAK_LEAVES.getDefaultState()
                        .with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
            }
            for (int y = 3; y <= 6; y++) world.setBlockState(b.add(0, y, -3), Blocks.OAK_LOG.getDefaultState());
        }
    }

    private static void spell(TestContext context, String name, Rounds stub, boolean log, Ledger ledger, String[] after, Check check) {
        spell(context, name, stub, log, false, ledger, after, check);
    }

    private static void spell(TestContext context, String name, Rounds stub, boolean log, boolean newGroundNorth, Ledger ledger, String[] after,
                              Check check) {
        var f = new NativePaidSkillsGameTests.Fixture(context, ledger.watch(), true);
        BlockPos[] base = new BlockPos[1];
        net.minecraft.server.network.ServerPlayerEntity[] who = new net.minecraft.server.network.ServerPlayerEntity[1];
        PortalRunCampaign[] goal = new PortalRunCampaign[1];
        String[] progress = {""};
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    stage(context, f, base, newGroundNorth, (world, b) -> pit(world, b, log), new ItemStack(Items.GLASS, 8), new ItemStack(Items.BREAD, 5));
                    f.runtime.strategistForFixtures(stub);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(base[0]), "In the pit"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.assertTrue(lines(f).stream().anyMatch(l -> l.startsWith("rescue spell ")), "The spell over");
                    progress[0] = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                })
                // Twenty ticks of its own ways after the spell, then stopped: what is checked is what the spell left.
                .createAndAdd(() -> context.assertTrue(++waited[0] > 20, "Its own ways a moment"))
                .createAndAdd(() -> {
                    after[0] = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    who[0] = f.player();
                    goal[0] = f.runtime.campaignForFixtures(f.id);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue {}: {}; the ledger {} edits; at {}; its progress {} then {}", name, lines(f), ledger.edits.get(),
                            who[0].getBlockPos().subtract(base[0]).toShortString(), progress[0], after[0]);
                    for (int i = 0; i < stub.reports.size(); i++) KindredMod.LOGGER.info("Rescue {} report {}:\n{}", name, i + 1, stub.reports.get(i));
                    check.accept(f, base[0], who[0], goal[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A spell of three rounds that gets out: round one's walk stops at the pit's wall, and round two's report says so;
     * round two looks north, and round three's report shows the look; round three pillars up and walks onto the ledge,
     * into ground it has not stood in (the pit placed so): progress, the spell out after three rounds.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueRoundsOut", tickLimit = 6000)
    public void aSpellOfThreeRoundsGetsOutEachToldHowTheLastWent(TestContext context) {
        var ledger = new Ledger();
        var stub = new Rounds(List.of(List.of("walk north 2"), List.of("look north"), List.of("pillar 3", "walk north 2")));
        spell(context, "Rounds", stub, true, true, ledger, new String[]{""}, (f, b, p, c) -> {
            context.assertTrue(stub.reports.size() == 3, "Three rounds asked: " + stub.reports.size());
            context.assertTrue(stub.reports.get(1).contains("round 1: walk north 2: went 0, then a two-high wall of obsidian"),
                    "Round two told how round one went: " + stub.reports.get(1));
            context.assertTrue(stub.reports.get(2).contains("Its look north") && stub.reports.get(2).contains("round 2: look north: done"),
                    "Round three shown the look: " + stub.reports.get(2));
            context.assertTrue(lines(f).contains("rescue spell 1: out after 3 rounds"), "Out after three rounds: " + lines(f));
            context.assertTrue(lines(f).stream().filter(l -> l.startsWith("rescue asked: spell 1 round ")).count() == 3, "Three asks: " + lines(f));
        });
    }

    /**
     * A spell of three rounds that does not get out: every round's walk stops at the wall. After the third the spell is
     * over, not out; nothing was broken or placed for a plan; the local rules go on (its own move elsewhere), and it is
     * not asked again this spell.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueRoundsFail", tickLimit = 6000)
    public void aSpellOfThreeRoundsThatFailsLeavesItToItsOwnWaysWithNothingBroken(TestContext context) {
        var ledger = new Ledger();
        var stub = new Rounds(List.of(List.of("walk north 2"), List.of("walk east 2"), List.of("walk south 2")));
        String[] after = {""};
        spell(context, "Stuck", stub, false, ledger, after, (f, b, p, c) -> {
            context.assertTrue(stub.reports.size() == 3, "Three rounds asked: " + stub.reports.size());
            context.assertTrue(stub.reports.get(2).contains("round 2: walk east 2: went 0, then a two-high wall of obsidian"), "Told: " + stub.reports.get(2));
            context.assertTrue(lines(f).contains("rescue spell 1: not out after 3 rounds"), "Not out: " + lines(f));
            context.assertTrue(ledger.edits.get() == 0, "Nothing broken or placed: " + ledger.edits.get());
            context.assertTrue(p.getBlockPos().equals(b), "Where it stood: " + p.getBlockPos().subtract(b).toShortString());
            context.assertTrue(c.rescueRound() == 0, "No round more this spell");
            context.assertTrue(!after[0].contains("asking for a way out") && !after[0].contains(PortalRunCampaign.RESCUE_LABEL),
                    "Its own ways again: " + after[0]);
        });
    }
}
