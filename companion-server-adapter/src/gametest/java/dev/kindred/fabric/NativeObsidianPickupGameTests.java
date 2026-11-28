package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** The obsidian picked up from its poured water's flow when a choice to take the water back is not taken (F-25c4cs). */
public final class NativeObsidianPickupGameTests implements FabricGameTest {
    /**
     * The scene of {@code NativePortalCampaignGameTests.goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater}: a
     * lava source recessed below its shore, a diamond pickaxe and a water bucket. In a class run of that fixture (batch
     * 23's bench, 8 November) Smith mined the obsidian from the shore and walked back to its stand. As that walk ended, its
     * goal chose to take the water back, and the runtime, still on the walk, did not take that step; the goal had given up
     * the obsidian site as it chose it. With no site, the drop's settle-wait ran out in the water's flow, the bucket was
     * refilled from it, and Smith branch mined away with the obsidian left in the draining water. Here that choice is
     * forced: on the first tick it stands on its stand after the mining, the fixture asks for the player's next tasks once,
     * as the runtime's own decision does, and runs none of them. Passes once its water is back in the bucket with the site
     * kept, no wait for the drop and no refill by the bucket's own upkeep before it, and the obsidian is in hand before any
     * walk to explore. The obsidian alone does not tell them apart: a player that swims through the water after mining
     * picks it up as it passes, as two of three did without the fix.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickup", tickLimit = 1500)
    public void anUntakenStepToTakeTheWaterBackLeavesNoObsidianBehind(TestContext context) {
        scene(context, false, Mode.AS_IS);
    }

    /**
     * Its lava out of sight at the goal's first look (F-22c118): its first step a walk to look for lava, before any obsidian
     * is made, as on #465's CI (run 45074150100, attempt 1: "Obsidian: Make and mine obsidian (0/10) - Explore the cave toward
     * lava", then "stand beside lava"), where the check read that walk as exploring with its obsidian left in the water and
     * failed a player that then mined, picked up and took its water back as it should. Staged: a block over the lava, taken
     * away once it looks. Passes as above: a walk to look before any obsidian is made is not exploring with it left.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickupUnseen", tickLimit = 1500)
    public void aWalkToLookForLavaBeforeAnyObsidianIsMadeIsNotExploringWithItsObsidianLeft(TestContext context) {
        scene(context, false, Mode.HIDDEN);
    }

    /**
     * The check still catches what it is for (F-22c118): the obsidian mined and left lying in the water, out of the
     * player's reach to pick up (staged: its drop made one no player can pick up), the player goes on to explore. Passes
     * only once the check reads that as exploring with its obsidian left in the water.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickupLeft", tickLimit = 1500)
    public void obsidianLeftInTheWaterAndAWalkToExploreIsStillCaught(TestContext context) {
        scene(context, false, Mode.LEFT);
    }

    /**
     * As the last, the obsidian's drop at the player's feet as it is mined (F-22c118a; STAGED: set on the player as it is
     * born), as on #502's CI (run 45186083711, attempt 1): it was picked up at once and the water taken back within the
     * tick it reached its stand, so the moment the scene asked its steps at (on its stand, the obsidian mined, its water
     * still poured) never came, and the check failed "On its stand, ...". The check is armed then by its own goal line to
     * take the water back, and the water must be back before any other goal starts.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickupUnseenAtFeet", tickLimit = 1500)
    public void itsLavaUnseenAndItsObsidianDroppedAtItsFeetTheWaterIsStillTakenBack(TestContext context) {
        scene(context, false, Mode.HIDDEN_AT_FEET);
    }

    /**
     * The goal line's arming still catches water left behind (F-22c118a): as the last, and its step to take the water back
     * held back (STAGED: its empty bucket taken away as that goal line comes). The moment does not arm it here, so that the
     * goal line's arming is the one exercised (alone, the moment comes first). Passes only once the check, armed by the goal
     * line, reads the water as not taken back before the next goal.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickupWaterHeldBack", tickLimit = 1500)
    public void itsStepToTakeTheWaterBackHeldBackTheGoalLineArmingStillCatchesIt(TestContext context) {
        scene(context, false, Mode.WATER_HELD_BACK);
    }

    private enum Mode { AS_IS, HIDDEN, LEFT, HIDDEN_AT_FEET, WATER_HELD_BACK }

    private static final String MOMENT = "the moment", GOAL_LINE = "the goal line";

    /**
     * As above, staged (F-22c94): from the obsidian mined until its water is back in the bucket, the player held on its stand,
     * off the drop's way, and then the shore cell the water was poured on left holding the last of its flow, as a source
     * taken up leaves it for a few ticks. In CI on #357 and
     * #360 the player went exploring then with the obsidian lying in the draining water ("Went exploring with its obsidian
     * left in the water"). Passes as above.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "obsidianPickupDrain", tickLimit = 1500)
    public void theWaterTakenBackItsLastFlowStillDrainingTheObsidianIsPickedUpFirst(TestContext context) {
        scene(context, true, Mode.AS_IS);
    }

    private static void scene(TestContext context, boolean drain, Mode mode) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos lava = context.getAbsolutePos(new BlockPos(5, 1, 3));
        BlockPos shore = context.getAbsolutePos(new BlockPos(4, 2, 3));
        BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 3));
        boolean hidden = mode == Mode.HIDDEN || mode == Mode.HIDDEN_AT_FEET || mode == Mode.WATER_HELD_BACK;
        boolean atFeet = mode == Mode.HIDDEN_AT_FEET || mode == Mode.WATER_HELD_BACK;
        boolean[] droppedAtFeet = {false};
        // A staging error, if the at-feet staging did not find exactly one drop at its own lava cell: then the test fails
        // as a staging error naming the count, never as the property (F-22c118a; a neighbouring copy's drop was once taken).
        String[] stagingError = {null};
        // Left in the water (LEFT): its own drop, found once at its lava cell, made unpickable for good.
        boolean[] leftStaged = {false};
        boolean[] heldBack = {false};
        // What armed the check (F-22c118a), whichever came first: the moment on its stand, or its goal line to take the
        // water back. Armed by the goal line, whether its water was back before the next goal: "" if so, else what was not.
        String[] armedBy = {null};
        boolean[] bucketCompleted = {false};
        // Its water in the bucket and the source gone as the next goal began, the bucket's COMPLETED not yet reported:
        // the tick it began (F-22c118a, #509's CI, where the goal went on one line before the runner reported the step).
        long[] reportDue = {-1};
        // How many world ticks after the next goal was seen its native_bucket COMPLETED may be reported. The goal change is
        // seen on the test's timed tasks and the report made on the runtime's tick, two clocks a tick apart at most: the
        // diagnostic's 60 copies under load saw gaps of -1, 1 and -1 (F-22c118a). The property is unchanged: the report is
        // still the first task to end after the next goal began.
        final long reportBound = 1;
        // The world time its native_bucket COMPLETED for the water was reported at, for the diagnostic of the gap between
        // the two clocks the check reads: the test's tick, which sees the goal change, and the runtime's, which reports.
        long[] reportAt = {-1};
        // Armed by the goal line, the first task to end since: "" if it was its native_bucket COMPLETED for the water, else
        // that task. A report up to a tick before the goal change is seen (the -1 case) must still be this first task.
        String[] firstEnded = {null};
        // The arming edge, the same lag as at the goal change: the step before "recover the water" can report its own end a
        // tick after the goal line moved on. Its label and the arming's world time, and the one report so exempted.
        long[] armedAt = {-1};
        String[] stepBefore = {null};
        String[] exempted = {null};
        String[] previous = {""};
        String[] notTaken = {null};
        String[] asked = {null};
        String[] lostSite = {null};
        boolean[] waterBack = {false};
        boolean[] exploredFirst = {false};
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        Runnable watch = () -> {
            var bots = f.runtime.panelBots(Actor.console());
            String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
            if (reportDue[0] >= 0 && notTaken[0] == null && context.getWorld().getTime() > reportDue[0] + reportBound) {
                notTaken[0] = "no native_bucket COMPLETED within " + reportBound + " world tick of the next goal beginning (seen at world time "
                        + reportDue[0] + ", now " + context.getWorld().getTime() + ", a gap past " + (context.getWorld().getTime() - reportDue[0]) + " ticks); ";
                KindredMod.LOGGER.info("Obsidian pickup: armed by the goal line: {}", notTaken[0]);
            }
            if (progress != null && !progress.equals(last[0])) {
                previous[0] = last[0];
                last[0] = progress;
                if (seen.length() < 3000) seen.append(progress.replaceAll("^.* - ", "")).append("; ");
                // Exploring with its obsidian left: once the obsidian is mined, not a walk to look for lava before any is
                // made (F-22c118).
                var lookWorld = context.getWorld();
                boolean minedYet = !lookWorld.getBlockState(lava).isOf(Blocks.OBSIDIAN) && !lookWorld.getFluidState(lava).isIn(FluidTags.LAVA);
                if (progress.contains("Explore") && minedYet && ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) == 0) exploredFirst[0] = true;
                // Armed by its goal line to take the water back, if the moment has not come first (F-22c118a); with its lava
                // in sight from the start (AS_IS, the drain), the moment alone arms it, as before.
                boolean recovering = progress.contains("Obsidian: recover the water");
                if (hidden && armedBy[0] == null && recovering && minedYet) {
                    armedBy[0] = GOAL_LINE;
                    armedAt[0] = lookWorld.getTime();
                    stepBefore[0] = previous[0].replaceAll("^.* - ", "");
                    KindredMod.LOGGER.info("Obsidian pickup: armed by the goal line at world time {}, the step before it [{}]", armedAt[0], stepBefore[0]);
                    armed(f, lookWorld, shore, GOAL_LINE);
                    // Staged (F-22c118a): its step to take the water back held back, its empty bucket taken away.
                    if (mode == Mode.WATER_HELD_BACK) {
                        var inventory = f.player().getInventory();
                        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(Items.BUCKET)) inventory.setStack(i, ItemStack.EMPTY);
                        heldBack[0] = true;
                        KindredMod.LOGGER.info("Obsidian pickup: STAGED, its step to take the water back held back, its empty bucket taken away");
                    }
                } else if (GOAL_LINE.equals(armedBy[0]) && notTaken[0] == null && !recovering) {
                    // The next goal begun: its water back, all three, before it.
                    KindredMod.LOGGER.info("Obsidian pickup: the clocks: the next goal seen at world time {}, its native_bucket COMPLETED {}",
                            lookWorld.getTime(), reportAt[0] < 0 ? "not yet reported" : "reported at " + reportAt[0] + ", a gap of " + (reportAt[0] - lookWorld.getTime()) + " ticks");
                    var held = f.player().getInventory();
                    boolean inBucket = ItemMoves.count(held, Items.WATER_BUCKET) == 1;
                    boolean sourceGone = !(lookWorld.getFluidState(shore).isStill() && lookWorld.getFluidState(shore).isIn(FluidTags.WATER));
                    if (!bucketCompleted[0] && inBucket && sourceGone) {
                        // The step's own report, due within reportBound world ticks of the next goal beginning, before any other task ends.
                        reportDue[0] = lookWorld.getTime();
                        KindredMod.LOGGER.info("Obsidian pickup: armed by the goal line, the next goal {}: its water back, its native_bucket COMPLETED due within {} world tick",
                                progress.replaceAll("^.* - ", ""), reportBound);
                    } else {
                        // Reported already (the -1 case, or earlier): it must have been the first task to end since the goal armed.
                        String first = bucketCompleted[0] && firstEnded[0] != null && !firstEnded[0].isEmpty() ? firstEnded[0] : "";
                        notTaken[0] = (bucketCompleted[0] ? "" : "no native_bucket COMPLETED; ") + first + (inBucket ? "" : "no water bucket in hand; ")
                                + (sourceGone ? "" : "its poured source still on the shore; ");
                        KindredMod.LOGGER.info("Obsidian pickup: armed by the goal line, the next goal {}: {}", progress.replaceAll("^.* - ", ""),
                                notTaken[0].isEmpty() ? "its water back before it" : notTaken[0]);
                    }
                }
                // Staged (F-22c118): the lava's cover taken away once it has looked for lava.
                if (hidden && progress.contains("Explore") && lookWorld.getBlockState(lava.up()).isOf(Blocks.STONE)) {
                    lookWorld.setBlockState(lava.up(), Blocks.AIR.getDefaultState());
                    KindredMod.LOGGER.info("Obsidian pickup: the lava's cover taken away as it looks: {}", progress);
                }
            }
            // Staged (F-22c118a): the obsidian's drop set at the player's feet as it is born, as CI's came to it at once.
            // Only this copy's own lava cell is searched: a wider box reached the next plot's lava cell, 13 blocks along, and
            // carried a neighbouring copy's drop to this player when copies shared a batch.
            if (atFeet && !droppedAtFeet[0] && stagingError[0] == null && f.player() != null) {
                var world = context.getWorld();
                var drops = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(lava).expand(1),
                        i -> i.isAlive() && i.getStack().isOf(Items.OBSIDIAN));
                boolean mined = !world.getBlockState(lava).isOf(Blocks.OBSIDIAN) && !world.getFluidState(lava).isIn(FluidTags.LAVA);
                int count = drops.stream().mapToInt(i -> i.getStack().getCount()).sum();
                if (drops.size() > 1 || count > 1) {
                    stagingError[0] = "Staging error: " + count + " obsidian in " + drops.size() + " drops at its own lava cell " + lava.toShortString() + ", not 1";
                } else if (drops.size() == 1) {
                    var item = drops.getFirst();
                    item.refreshPositionAndAngles(f.player().getX(), f.player().getY(), f.player().getZ(), 0, 0);
                    item.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    item.setPickupDelay(0);
                    droppedAtFeet[0] = true;
                    KindredMod.LOGGER.info("Obsidian pickup: STAGED, the obsidian's drop (1 of 1 at its own lava cell) set at the player's feet, at {}", f.player().getBlockPos().toShortString());
                } else if (mined) {
                    stagingError[0] = "Staging error: 0 obsidian drops at its own lava cell " + lava.toShortString() + " once it was mined, not 1";
                }
                if (stagingError[0] != null) KindredMod.LOGGER.info("Obsidian pickup: {}", stagingError[0]);
            }
            if (stagingError[0] != null) context.assertTrue(false, stagingError[0]);
            // Staged (F-22c118): the obsidian mined left lying where it fell, no player able to pick it up.
            // Only its own lava cell is searched, as for the drop at its feet: a wider box reached the next plot's lava cell.
            if (mode == Mode.LEFT && !leftStaged[0] && stagingError[0] == null) {
                var world = context.getWorld();
                var drops = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(lava).expand(1),
                        i -> i.isAlive() && i.getStack().isOf(Items.OBSIDIAN));
                boolean mined = !world.getBlockState(lava).isOf(Blocks.OBSIDIAN) && !world.getFluidState(lava).isIn(FluidTags.LAVA);
                int count = drops.stream().mapToInt(i -> i.getStack().getCount()).sum();
                if (drops.size() > 1 || count > 1) {
                    stagingError[0] = "Staging error: " + count + " obsidian in " + drops.size() + " drops at its own lava cell " + lava.toShortString() + ", not 1";
                } else if (drops.size() == 1) {
                    drops.getFirst().setPickupDelayInfinite();
                    leftStaged[0] = true;
                    KindredMod.LOGGER.info("Obsidian pickup: STAGED, the obsidian's drop (1 of 1 at its own lava cell) left where it fell, unpickable");
                } else if (mined) {
                    stagingError[0] = "Staging error: 0 obsidian drops at its own lava cell " + lava.toShortString() + " once it was mined, not 1";
                }
                if (stagingError[0] != null) KindredMod.LOGGER.info("Obsidian pickup: {}", stagingError[0]);
            }
            if (stagingError[0] != null) context.assertTrue(false, stagingError[0]);
            // Staged (F-22c94): from the obsidian mined until its water is back in the bucket, the player held on its stand,
            // two cells off the hole the obsidian lies in, so it never passes over the drop, as in CI, where it lay out of
            // the player's way; the drop itself is not touched. Then let go, the water's last flow still draining over it.
            var standWorld = context.getWorld();
            if (drain && !waterBack[0] && !standWorld.getBlockState(lava).isOf(Blocks.OBSIDIAN) && !standWorld.getFluidState(lava).isIn(FluidTags.LAVA)) {
                var held = f.player();
                if (held != null) {
                    held.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, held.getYaw(), held.getPitch());
                    held.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                }
            }
            // From the untaken step until its water is back in the bucket: a wait for the drop, or a refill by the bucket's
            // own upkeep, is a goal with the obsidian site given up while its water still stands.
            if (armedBy[0] != null && !waterBack[0]) {
                if (ItemMoves.count(f.player().getInventory(), Items.WATER_BUCKET) == 1) {
                    waterBack[0] = true;
                    // Staged (F-22c94): the shore cell left holding the last of the flow, draining as a taken-up source's does.
                    if (drain) {
                        for (var item : context.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                                new net.minecraft.util.math.Box(context.getAbsolutePos(BlockPos.ORIGIN)).expand(8),
                                i -> i.isAlive() && i.getStack().isOf(Items.OBSIDIAN))) {
                            KindredMod.LOGGER.info("Obsidian pickup: the player let go, the obsidian at {}, in {}", item.getPos(), context.getWorld().getFluidState(item.getBlockPos()));
                        }
                    }
                    if (drain && context.getWorld().getFluidState(shore).isEmpty()) {
                        context.getWorld().setBlockState(shore, net.minecraft.fluid.Fluids.FLOWING_WATER.getFlowing(7, false).getBlockState());
                        KindredMod.LOGGER.info("Obsidian pickup: the water back, its last flow left on the shore at {}", shore.toShortString());
                    }
                }
                else if (lostSite[0] == null && progress != null && (progress.contains("Wait for the drop to settle")
                        || progress.contains("Fill a bucket at") || progress.contains("Walk to water"))) lostSite[0] = progress.replaceAll("^.* - ", "");
            }
        };
        java.util.function.Supplier<String> tail = () -> seen.length() > 700 ? "..." + seen.substring(seen.length() - 700) : seen.toString();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Walls keep the poured water inside this test's area.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(5, 0, 3, Blocks.STONE);
                    context.setBlockState(5, 1, 3, Blocks.LAVA);
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) context.setBlockState(x, 7, z, Blocks.STONE);
                    // Staged (F-22c118): the lava out of sight under a block until the player looks for it.
                    if (hidden) context.setBlockState(5, 2, 3, Blocks.STONE);
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the shore"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(2, new ItemStack(Items.IRON_INGOT)); // Flint and steel's iron is already in hand.
                    inventory.setStack(3, new ItemStack(Items.BREAD, 5));
                    f.runtime.observeRun(new RunObserver() {
                        @Override
                        public void ended(String p, String skill, String state, String label) {
                            boolean recovered = "native_bucket".equals(skill) && "COMPLETED".equals(state) && label != null && label.contains("recover the water");
                            if (recovered) bucketCompleted[0] = true;
                            long now = context.getWorld().getTime();
                            if (GOAL_LINE.equals(armedBy[0]) && firstEnded[0] == null) {
                                if (!recovered && exempted[0] == null && label != null && label.equals(stepBefore[0]) && now - armedAt[0] <= reportBound) {
                                    // The step before the goal line reporting its own end within the bound of the arming: not another task.
                                    exempted[0] = skill + " " + state + " [" + label + "] at world time " + now + ", armed at " + armedAt[0];
                                    KindredMod.LOGGER.info("Obsidian pickup: the arming edge: not counted as another task, the step before it: {}", exempted[0]);
                                } else {
                                    firstEnded[0] = recovered ? "" : "another task ended before its native_bucket COMPLETED: " + skill + " " + state + " [" + label
                                            + "] at world time " + now + ", armed by the goal line at " + armedAt[0] + " after [" + stepBefore[0] + "]"
                                            + (exempted[0] == null ? "" : ", exempted " + exempted[0]) + "; ";
                                }
                            }
                            if (recovered && reportAt[0] < 0) {
                                reportAt[0] = context.getWorld().getTime();
                                if (reportDue[0] >= 0) KindredMod.LOGGER.info("Obsidian pickup: the clocks: its native_bucket COMPLETED reported at world time {}, the next goal seen at {}, a gap of {} ticks",
                                        reportAt[0], reportDue[0], reportAt[0] - reportDue[0]);
                            }
                            // Due as the next goal began: within reportBound world ticks, and the first task to end since (F-22c118a).
                            if (reportDue[0] >= 0 && notTaken[0] == null) {
                                long gap = context.getWorld().getTime() - reportDue[0];
                                notTaken[0] = !recovered ? "another task ended before its native_bucket COMPLETED: " + skill + " " + state + " [" + label + "], a gap of " + gap + " ticks; "
                                        : gap > reportBound ? "its native_bucket COMPLETED " + gap + " world ticks after the next goal began, past " + reportBound + "; " : "";
                                KindredMod.LOGGER.info("Obsidian pickup: armed by the goal line: {}", notTaken[0].isEmpty()
                                        ? "its native_bucket COMPLETED " + gap + " world tick(s) after the next goal began, before any other task ended" : notTaken[0]);
                            }
                        }
                    });
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    if (mode != Mode.LEFT) return;
                    // The check still catches its obsidian left in the water (F-22c118).
                    watch.run();
                    context.assertTrue(leftStaged[0], "Staging error: no obsidian drop found at its own lava cell to leave unpickable, not 1");
                    context.assertTrue(exploredFirst[0], "Went exploring with its obsidian left in the water, and the check caught it: " + tail.get());
                    KindredMod.LOGGER.info("Obsidian pickup: left in the water, caught; {}", seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> {
                    if (mode == Mode.LEFT) return;
                    watch.run();
                    var world = context.getWorld();
                    boolean mined = !world.getBlockState(lava).isOf(Blocks.OBSIDIAN) && !world.getFluidState(lava).isIn(FluidTags.LAVA);
                    boolean standing = world.getFluidState(shore).isStill() && world.getFluidState(shore).isIn(FluidTags.WATER);
                    // The control (WATER_HELD_BACK) is armed by its goal line alone: alone, the moment comes first.
                    if (mode != Mode.WATER_HELD_BACK && armedBy[0] == null && mined && standing && f.player().getBlockPos().equals(stand)) {
                        armedBy[0] = MOMENT;
                        armed(f, world, shore, MOMENT);
                        // Staged: the player's next tasks asked for once, as its decision asks, and none of them run.
                        asked[0] = f.runtime.nativeCandidates(Actor.console(), f.id).stream()
                                .map(c -> c.skill() + " " + c.arguments().getOrDefault("mode", "")).toList().toString();
                    }
                    context.assertTrue(armedBy[0] != null, "On its stand, the obsidian mined, its water still poured"
                            + (hidden ? ", or its goal line to take it back" : "") + ": " + tail.get());
                })
                .createAndAdd(() -> {
                    if (mode == Mode.LEFT) return;
                    if (MOMENT.equals(armedBy[0])) {
                        // The moment first: unchanged.
                        context.assertTrue(mode != Mode.WATER_HELD_BACK, "Held back, the goal line's arming exercised, not the moment: " + asked[0] + "; " + tail.get());
                        context.assertTrue(asked[0].contains("native_bucket fill"), "The step asked for and not taken was to take the water back: " + asked[0] + "; " + tail.get());
                        return;
                    }
                    // The goal line first: the step taken, its water back before the next goal starts.
                    watch.run();
                    context.assertTrue(notTaken[0] != null, "Armed by the goal line, the next goal begun: " + tail.get());
                    if (mode == Mode.WATER_HELD_BACK) {
                        context.assertTrue(heldBack[0] && !notTaken[0].isEmpty(), "Held back, its water not taken back, and the check caught it: " + notTaken[0] + "; " + tail.get());
                        KindredMod.LOGGER.info("Obsidian pickup: held back, caught: {}; {}", notTaken[0], seen);
                        return;
                    }
                    context.assertTrue(notTaken[0].isEmpty(), "Armed by the goal line, its water taken back before the next goal: " + notTaken[0] + "; " + tail.get());
                })
                .createAndAdd(() -> {
                    if (mode == Mode.WATER_HELD_BACK) f.runtime.stop();
                })
                .createAndAdd(() -> {
                    if (mode == Mode.LEFT || mode == Mode.WATER_HELD_BACK) return;
                    watch.run();
                    context.assertTrue(lostSite[0] == null, "The site given up with its water still standing: " + lostSite[0] + "; " + tail.get());
                    context.assertTrue(waterBack[0], "Its water back in the bucket: " + tail.get());
                })
                .createAndAdd(() -> {
                    if (mode == Mode.LEFT || mode == Mode.WATER_HELD_BACK) return;
                    watch.run();
                    if (atFeet) context.assertTrue(droppedAtFeet[0], "Staging error: no obsidian drop found at its own lava cell to set at its feet, not 1");
                    context.assertFalse(exploredFirst[0], "Went exploring with its obsidian left in the water: " + tail.get());
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) == 1, "The obsidian mined and picked up: " + tail.get());
                })
                .createAndAdd(() -> {
                    if (mode == Mode.LEFT || mode == Mode.WATER_HELD_BACK) return;
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.WATER_BUCKET) == 1 && ItemMoves.count(inventory, Items.BUCKET) == 0,
                            "The water was taken back into the bucket: " + tail.get());
                    var world = context.getWorld();
                    context.assertFalse(world.getFluidState(lava).isIn(FluidTags.LAVA), "No lava left at the source");
                    context.assertFalse(world.getFluidState(shore).isStill() && world.getFluidState(shore).isIn(FluidTags.WATER),
                            "No water source left on the shore");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Obsidian pickup: armed by {}, asked {}; {}", armedBy[0], asked[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** What the stage read as it armed (F-22c118a): the shore's water as the check reads it, where the player is, its action. */
    private static void armed(NativePaidSkillsGameTests.Fixture f, net.minecraft.server.world.ServerWorld world, BlockPos shore, String by) {
        var water = world.getFluidState(shore);
        var status = f.runtime.nativeActionStatus(Actor.console(), f.id);
        var record = status == null ? null : status.record();
        KindredMod.LOGGER.info("Obsidian pickup: armed by {}: the shore {} (still {}, level {}), the player at {}, its action {} {} {}", by,
                water.getFluid(), water.isStill(), water.getLevel(), f.player().getBlockPos().toShortString(),
                status == null ? "none" : status.phase(), record == null ? "" : record.kind(), record == null ? "" : record.state());
    }
}
