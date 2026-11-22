package dev.kindred.fabric;

import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A refused set-down of the bed it carries is offered again only after a while, and given up for the night after a few
 * (F-25c4di1c). Vanilla places no bed where an entity stands in its cells, and says nothing: the action finds no bed and is
 * BLOCKED "The bed was not set down". Nothing about the spot changes from one try to the next, and it was offered again
 * at once: in a corpus run on #362's gate, `e40a20-79a9aad-101-sol-107500`'s player was refused 426 times in a row in its
 * sealed pocket; in E-40 iteration 23 (`e40a23-3115b1c`, #352) 101 Sol, 85 times before its bed went down (RUNNER 2's
 * {@code claude/corpus-data-e40a23-1115}). And its own body at its pocket's edge over the bed's cells steps clear first
 * (F-25c4di1d), the cause of the refusals where nothing else stood there.
 */
public final class NativeBedRetryGameTests implements FabricGameTest {
    /** How long a night it is watched. */
    private static final int WATCH = 1200;
    /** The least time between two tries at setting its bed down, and the most tries in the watched night. */
    private static final int MIN_GAP = 100, MOST_TRIES = 4;

    /** Rock round {@code o} with no sky; its pocket two high, closed, its bed's room open north of it, two long, on solid floor. */
    private static void pocket(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-6, -2, -6), o.add(6, 6, 6));
        for (BlockPos cell : BlockPos.iterate(o.add(-6, -2, -6), o.add(6, 6, 6))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
        for (BlockPos cell : BlockPos.iterate(o.add(0, 0, -2), o.add(0, 1, 0))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
    }

    /**
     * Its night in {@code pocket}, the player at {@code standZ} (within its cell) and {@code stage} run after, its bed and
     * nothing else, no home bed, night held, its own play; every {@code native_bed} it starts counted. {@code check} is given
     * the tries so far and the bed's foot, each tick, for {@value #WATCH} ticks; it throws to fail and returns true to pass.
     */
    private static void night(TestContext context, String name, double standZ, java.util.function.Consumer<BlockPos> stage,
                              CheckTries check) {
        java.util.List<Long> tries = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.Set<String> details = new java.util.LinkedHashSet<>();
        long[] now = {0};
        java.util.function.UnaryOperator<ActionAdmission.Writer> watch = writer -> new ActionAdmission.Writer() {
            @Override
            public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                if ("native_bed".equals(intent.kind())) tries.add(now[0]);
                return writer.prepare(intent);
            }

            @Override
            public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                if ("native_bed".equals(next.kind()) && next.detail() != null && !next.detail().isBlank()) {
                    synchronized (details) { details.add(next.detail()); }
                }
                return writer.transition(next, expectedRevision);
            }
        };
        var f = new NativePaidSkillsGameTests.Fixture(context, watch, true, WATCH + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        int[] watched = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    pocket(context, o);
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    player.getInventory().setStack(0, new ItemStack(Items.WHITE_BED));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setWeather(12_000, 0, false, false);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + standZ, 180, 10);
                    player.setVelocity(Vec3d.ZERO);
                    stage.accept(o);
                    world.setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(14_000);
                    now[0] = context.getWorld().getTime();
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died");
                    java.util.List<Long> seen;
                    synchronized (tries) { seen = java.util.List.copyOf(tries); }
                    String said;
                    synchronized (details) { said = String.join(" | ", details); }
                    String note = f.runtime.lifeNote(f.id) + " / its bed's records: " + said;
                    if (check.passed(seen, o.north(), ++watched[0], note)) return;
                    throw new net.minecraft.test.GameTestException("Watching the night");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Bed retry ({}): {} tries, at {}; {}; its bed's records: {}", name, tries.size(), tries,
                            f.runtime.bedChoreForTest(f.id), details);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** A night's check: the tries so far, the bed's foot, the ticks watched and the player's last note; true when passed. */
    private interface CheckTries {
        boolean passed(java.util.List<Long> tries, BlockPos foot, int watched, String note);
    }

    /**
     * In plain terms: an AI player whose bed will not go down where it wants it, for something in the way it cannot move,
     * does not try again and again on the spot, hundreds of times; it waits before trying again, and after a few tries
     * gives it up for the night.
     *
     * Technical: F-25c4di1c (above). STAGED: its pocket (above); the player at its cell's middle; a sheep with its AI off in
     * the bed's foot cell, where vanilla will not set a bed down (F-25c4di1c's first staging, its own body a tenth of a block
     * into that cell, is F-25c4di1d's, which steps it clear; restaged here so that this still meets a refusal); its bed and
     * nothing else; no home bed; night held; no goal, its own play. Each {@code native_bed} it starts is counted. Fails if two
     * start within {@value #MIN_GAP} ticks of each other or more than {@value #MOST_TRIES} start in {@value #WATCH} ticks of
     * night; passes otherwise, alive, with at least one tried, and its refusal names the sheep.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedRetry", tickLimit = WATCH + 800)
    public void aRefusedSetDownIsTriedAgainOnlyAfterAWhileAndGivenUpForTheNight(TestContext context) {
        boolean[] named = {false};
        net.minecraft.entity.passive.SheepEntity[] sheep = {null};
        night(context, "Bedder", 0.5, o -> {
            var world = context.getWorld();
            var made = net.minecraft.entity.EntityType.SHEEP.create(world);
            made.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() - 0.5, 0, 0);
            made.setAiDisabled(true);
            made.setPersistent();
            world.spawnEntity(made);
            sheep[0] = made;
        }, (seen, foot, watched, note) -> {
            if (note.contains("in its cells minecraft:sheep")) named[0] = true;
            for (int i = 1; i < seen.size(); i++) {
                if (seen.get(i) - seen.get(i - 1) < MIN_GAP) {
                    throw new net.minecraft.test.GameTestException("Its bed tried again " + (seen.get(i) - seen.get(i - 1)) + " ticks after a try: "
                            + seen.size() + " tries so far; " + note);
                }
            }
            context.assertTrue(seen.size() <= MOST_TRIES, "Its bed tried " + seen.size() + " times in a night");
            if (watched <= WATCH) return false;
            context.assertTrue(!seen.isEmpty(), "Its bed never tried: " + note);
            context.assertTrue(named[0], "Its refusal did not name the sheep: " + note);
            sheep[0].discard();
            return true;
        });
    }

    /**
     * In plain terms: an AI player standing at the very edge of its pocket, so close to where its bed goes that the bed will
     * not fit, steps back first and sets its bed down on the first try.
     *
     * Technical: F-25c4di1d. STAGED: its pocket (above); the player at the pocket's north edge, so that its body (0.6
     * across) stands a tenth of a block into the bed's foot cell, where vanilla will not set a bed down: F-25c4di1c's
     * fixture as first staged; its bed and nothing else; no home bed; night held; no goal, its own play. Each
     * {@code native_bed} it starts is counted. Fails at a second try, or with no bed down after {@value #WATCH} ticks of night;
     * passes once its bed stands in the room on its first try.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedStep", tickLimit = WATCH + 800)
    public void standingOverItsBedsRoomItStepsClearAndSetsTheBedDownFirstTime(TestContext context) {
        night(context, "Stepper", 0.2, o -> { }, (seen, foot, watched, note) -> {
            context.assertTrue(seen.size() <= 1, "Its bed tried " + seen.size() + " times: " + note);
            if (context.getWorld().getBlockState(foot).isIn(net.minecraft.registry.tag.BlockTags.BEDS)) return true;
            context.assertTrue(watched <= WATCH, "No bed down after " + WATCH + " ticks of night: " + seen.size() + " tries; " + note);
            return false;
        });
    }

    /**
     * In plain terms: an AI player dug into a pocket for the night with its bed, whose bed will not go down, does not
     * open the shelter it just sealed to mine on and seal it again, over and over, all night.
     *
     * Technical: F-25c4dl. In E-40 step 8 (`e40a25s-0d3432b`, RUNNER 2's consoles at `fe0be8cd`) on 303 with the change on,
     * four of the five "mining for a find" spells were one cell by turns: "Seal the shelter at C" from the night in the bed
     * it carries, then its branch mine's "clear C", then the seal again, with no advance (Kindred at -1, 20, -78; Sol at
     * 176, 20, 10, at 196, 37, 49 and at -93, -54, 158), and 101 Echo's with it off: 15 of the run's stuck minutes. Sol's
     * at 176, 20, 10 came after its bed was refused three times ("Owned inventory changed before the bed was set down"):
     * the bed's night seals its pocket before it asks whether its bed may go down now, and with it not to go down (waiting
     * to try again, given up tonight, or a way in left) hands the night back, and the iron's branch mine, which below
     * ground goes on through the night, opens the seal. STAGED: a slab of stone, the pocket and its bed's room as above,
     * a sheep with its AI off in the bed's foot cell (vanilla sets no bed down there), its kit as though made by the
     * iron stage and its bed, night held, the portal's goal. Fails when one cell is sealed and cleared by turns more than
     * {@value #FLIPS_MOST} times; passes after {@value #WATCH_MINE} ticks of night with its pocket sealed at least once
     * and none so.
     */
    @GameTest(templateName = FixtureGround.WIDE, batchId = "bedSealMine", tickLimit = WATCH_MINE + 800)
    public void aSealedPocketWhoseBedWillNotGoDownIsNotOpenedByItsOwnMineAndSealedAgain(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, WATCH_MINE + 700);
        BlockPos o = context.getAbsolutePos(new BlockPos(60, 8, 60));
        java.util.Map<BlockPos, Integer> flips = new java.util.HashMap<>();
        java.util.Map<BlockPos, String> lastKind = new java.util.HashMap<>();
        java.util.regex.Pattern sealAt = java.util.regex.Pattern.compile("Seal the shelter at (-?\\d+), (-?\\d+), (-?\\d+)");
        java.util.regex.Pattern clearAt = java.util.regex.Pattern.compile(": clear (-?\\d+), (-?\\d+), (-?\\d+)");
        int[] watched = {0}, seals = {0}, clears = {0};
        String[] last = {""}, failed = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Sleeper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null && f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-MINE_FIELD, -2, -MINE_FIELD), o.add(MINE_FIELD, 6, MINE_FIELD));
                    for (BlockPos cell : BlockPos.iterate(o.add(-MINE_FIELD, -2, -MINE_FIELD), o.add(MINE_FIELD, 6, MINE_FIELD))) {
                        world.setBlockState(cell, Blocks.STONE.getDefaultState());
                    }
                    for (BlockPos cell : BlockPos.iterate(o.add(0, 0, -2), o.add(0, 1, 0))) world.setBlockState(cell, Blocks.AIR.getDefaultState());
                    var sheep = net.minecraft.entity.EntityType.SHEEP.create(world);
                    sheep.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() - 0.5, 0, 0);
                    sheep.setAiDisabled(true);
                    sheep.setPersistent();
                    world.spawnEntity(sheep);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(3, new ItemStack(Items.TORCH, 32));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(6, new ItemStack(Items.FURNACE));
                    inventory.setStack(7, new ItemStack(Items.COAL, 8));
                    inventory.setStack(8, new ItemStack(Items.WHITE_BED));
                    inventory.setStack(9, new ItemStack(Items.STICK, 8));
                    inventory.setStack(10, new ItemStack(Items.STONE_PICKAXE));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    world.setWeather(12_000, 0, false, false);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 180, 10);
                    player.setVelocity(Vec3d.ZERO);
                    world.setTimeOfDay(14_000);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In its pocket"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // The first failure is the one reported, every tick after, until the test's time runs out.
                    context.assertTrue(failed[0] == null, failed[0]);
                    context.getWorld().setTimeOfDay(14_000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died: " + last[0]);
                    var bots = f.runtime.panelBots(dev.kindred.core.Actor.console());
                    String progress = bots.isEmpty() ? "" : String.valueOf(bots.getFirst().goalProgress());
                    if (!progress.equals(last[0])) {
                        last[0] = progress;
                        var seal = sealAt.matcher(progress);
                        var clear = clearAt.matcher(progress);
                        String kind = seal.find() ? "seal" : clear.find() ? "clear" : null;
                        if (kind != null) {
                            var m = "seal".equals(kind) ? seal : clear;
                            BlockPos cell = new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                            if ("seal".equals(kind)) seals[0]++; else clears[0]++;
                            String before = lastKind.put(cell, kind);
                            if (before != null && !before.equals(kind)) {
                                int n = flips.merge(cell, 1, Integer::sum);
                                if (n > FLIPS_MOST && failed[0] == null) failed[0] = "Its pocket's cell " + cell.subtract(o).toShortString()
                                        + " sealed and cleared by turns " + n + " times: " + progress + " / " + f.runtime.lifeNote(f.id);
                                context.assertTrue(failed[0] == null, failed[0]);
                            }
                        }
                    }
                    if (++watched[0] <= WATCH_MINE) throw new net.minecraft.test.GameTestException("Watching the night");
                    context.assertTrue(seals[0] > 0, "Its pocket never sealed: " + last[0] + " / " + f.runtime.lifeNote(f.id));
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Bed seal and mine: {} seals, {} clears, flips by cell {}; {}", seals[0], clears[0], flips, last[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** How long a night its pocket is watched, the stone round it, and the most a cell may be sealed and cleared by turns. */
    private static final int WATCH_MINE = 2400, MINE_FIELD = 20, FLIPS_MOST = 6;
}
