package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionAdmission;
import dev.kindred.core.ActionRecord;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In plain terms: a player stuck where its own ways out have failed asks the optional adviser for a way out, and the
 * adviser's few steps are each checked and made as ordinary moves. A plan too long, a step not in the list, a move the
 * server does not have, or a plan made before the player was told something new is turned down with nothing moved;
 * a step the server's rules refuse stops the plan there.
 *
 * Technical: F-29, with a stub strategist. The pit is three blocks deep in obsidian, which the player has no tool for,
 * and it carries glass, which its own pillar does not use (it pillars with stone and dirt), so its own tries fail: its
 * watchdog's spells are shortened to 600 ticks for the fixture, the only thing staged besides the scene. The stub's
 * plan is {@code pillar 3, walk north 2}; the ledger shows each pillar as a recorded action.
 */
public final class NativeRescueGameTests implements FabricGameTest {
    /** The stub's plan for the pit: up three blocks of its own, then two north onto the ledge. */
    private static final List<String> OUT = List.of("pillar 3", "walk north 2");

    /** A strategist that gives no advice and, for a rescue, the steps it was made with: at once, or when let go. */
    static final class Stub implements Strategist {
        final List<String> steps;
        final boolean held;
        final List<String> reports = new ArrayList<>();
        private final CompletableFuture<Rescue> pending = new CompletableFuture<>();

        Stub(List<String> steps, boolean held) {
            this.steps = steps;
            this.held = held;
        }

        @Override
        public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
            return CompletableFuture.completedFuture(new Outcome(Optional.empty(), 0, 0, 0, 0, Optional.of("a fixture's stub gives no advice")));
        }

        @Override
        public CompletableFuture<Rescue> rescue(String report) {
            reports.add(report);
            var reply = new Rescue(steps, "Up out of the pit and onto the ledge.", 900, 0, 30, 5, Optional.empty());
            if (!held) return CompletableFuture.completedFuture(reply);
            return pending.thenApply(go -> reply);
        }

        void release() { pending.complete(null); }

        @Override public String provider() { return "Fixture"; }
        @Override public String model() { return "stub"; }
        @Override public String pace() { return "at once"; }
        @Override public void close() { }
    }

    /** The ledger's native_pillar intents and verified steps, seen as they are written. */
    private static final class Ledger {
        final AtomicInteger prepared = new AtomicInteger(), verified = new AtomicInteger(), edits = new AtomicInteger();

        java.util.function.UnaryOperator<ActionAdmission.Writer> watch() {
            return writer -> new ActionAdmission.Writer() {
                @Override
                public CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                    if ("native_pillar".equals(intent.kind())) prepared.incrementAndGet();
                    if (Set.of("native_pillar", "native_place", "native_break").contains(intent.kind())) edits.incrementAndGet();
                    return writer.prepare(intent);
                }

                @Override
                public CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                    if ("native_pillar".equals(next.kind()) && next.state() == ActionRecord.State.VERIFIED) verified.incrementAndGet();
                    return writer.transition(next, expectedRevision);
                }
            };
        }
    }

    /** The pit, a player in it with glass and bread, the stub given, its goal begun with short watchdog spells. */
    private static void pit(TestContext context, NativePaidSkillsGameTests.Fixture f, Stub stub, BlockPos[] base) {
        var world = context.getWorld();
        BlockPos b = base[0] = f.player().getBlockPos().up(20).toImmutable();
        // A block of obsidian seven across and four deep, a shaft one wide and three deep in its middle, the sky above.
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            for (int y = -1; y <= 2; y++) world.setBlockState(b.add(x, y, z), Blocks.OBSIDIAN.getDefaultState());
            for (int y = 3; y <= 6; y++) world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
        }
        for (int y = 0; y <= 2; y++) world.setBlockState(b.up(y), Blocks.AIR.getDefaultState());
        world.setTimeOfDay(1000);
        var player = f.player();
        player.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
        player.setVelocity(Vec3d.ZERO);
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.GLASS, 8));
        inventory.setStack(1, new ItemStack(Items.BREAD, 5));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        f.runtime.strategistForFixtures(stub);
    }

    private static void begin(TestContext context, NativePaidSkillsGameTests.Fixture f) {
        f.runtime.stuckTicksForFixtures(f.id, 600);
        context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
    }

    private static String rescued(NativePaidSkillsGameTests.Fixture f) {
        var lines = f.runtime.rescuesForFixtures(f.id);
        return lines.isEmpty() ? "" : lines.getLast();
    }

    /**
     * In the pit, its own tries failed twice over, the stub's plan gets it out: three pillars of its own glass, each a
     * recorded action, then the walk north onto the ledge, and the rescue's line says so. The scene was captured as a
     * replay before the plan ran, with replays on (F-27).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueOut", tickLimit = 4000)
    public void inThePitItsOwnTriesFailedTheStubsPlanGetsItOutThroughRecordedSkills(TestContext context) {
        var ledger = new Ledger();
        var f = new NativePaidSkillsGameTests.Fixture(context, ledger.watch(), true);
        var stub = new Stub(OUT, false);
        var replays = new DeathReplays(f.directory.resolve("replays"), null);
        BlockPos[] base = new BlockPos[1];
        List<String> steps = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rescued"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    pit(context, f, stub, base);
                    f.runtime.captureReplays(replays);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(base[0]), "In the pit"))
                .createAndAdd(() -> begin(context, f))
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (steps.isEmpty() || !steps.getLast().equals(progress)) steps.add(progress);
                    context.assertTrue(rescued(f).endsWith("-> unstuck") || rescued(f).contains("-> failed") || rescued(f).contains("-> refused"),
                            "A rescue carried out: " + progress);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue: its steps {}; the ledger {} pillars prepared, {} verified", steps, ledger.prepared.get(), ledger.verified.get());
                    context.assertTrue(stub.reports.size() == 1, "Asked once: " + stub.reports.size());
                    String report = stub.reports.getFirst();
                    context.assertTrue(report.contains("P") && report.contains("obsidian") && report.contains("glass (hotbar)"),
                            "The report shows the scene and what it carries: " + report);
                    context.assertTrue(rescued(f).equals("rescue: pillar 3, walk north 2 -> unstuck"), "Out: " + rescued(f));
                    BlockPos at = f.player().getBlockPos();
                    context.assertTrue(at.getY() >= base[0].getY() + 3 && at.getZ() <= base[0].getZ() - 1, "On the ledge: " + at.toShortString());
                    context.assertTrue(ledger.prepared.get() == 3 && ledger.verified.get() == 3,
                            "Three pillars, each recorded: " + ledger.prepared.get() + " prepared, " + ledger.verified.get() + " verified");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.GLASS) == 5, "Three of its own glass spent");
                    context.assertTrue(steps.stream().anyMatch(s -> s.contains(PortalRunCampaign.RESCUE_LABEL + "walk north 2")), "The walk: " + steps);
                    var capture = replays.latestForTest();
                    context.assertTrue(capture.isPresent(), "The scene captured before the plan ran");
                    try {
                        capture.get().written().get(10, java.util.concurrent.TimeUnit.SECONDS);
                        String json = java.nio.file.Files.readString(capture.get().folder().resolve("replay.json"));
                        context.assertTrue(json.contains("\"kind\": \"stuck\""), "A stuck scene: " + json.substring(0, Math.min(200, json.length())));
                    } catch (Exception failed) {
                        throw new net.minecraft.test.GameTestException("The replay not written: " + failed);
                    }
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Seven steps, one too many: refused with nothing moved. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueSeven", tickLimit = 3000)
    public void aPlanOfSevenStepsIsRefusedWithNothingMoved(TestContext context) {
        refused(context, "Seven", new Stub(java.util.Collections.nCopies(7, "wait 1"), false), null, "a plan has 1..6 steps, not 7");
    }

    /** A step not in the vocabulary: refused with nothing moved. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueUnknown", tickLimit = 3000)
    public void aPlanWithAnUnknownStepIsRefusedWithNothingMoved(TestContext context) {
        refused(context, "Unknown", new Stub(List.of("pillar 3", "fly up 3"), false), null, "unknown step 'fly up 3'");
    }

    /** A step whose skill this server does not install: refused by the gate with nothing moved. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueUninstalled", tickLimit = 3000)
    public void aPlanUsingASkillNotInstalledIsRefusedWithNothingMoved(TestContext context) {
        refused(context, "Uninstalled", new Stub(OUT, false), f -> f.runtime.rescueSkillsForFixtures(Set.of("native_goto", "native_break",
                "native_place", "native_wait", "native_eat", "native_retreat")), "unknown skill native_pillar");
    }

    /**
     * A plan made for the player before it was stopped and set going again: its fence is stale, refused with nothing
     * moved. The stub's reply is held until the stop and the resume.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueStale", tickLimit = 3000)
    public void aPlanWhoseFenceWentStaleWhileItWasAskedIsRefusedWithNothingMoved(TestContext context) {
        var stub = new Stub(OUT, true);
        refused(context, "Stale", stub, null, "the proposal's revisions are stale", f -> {
            context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Stopped");
            context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Going again");
            stub.release();
        });
    }

    private static void refused(TestContext context, String name, Stub stub, java.util.function.Consumer<NativePaidSkillsGameTests.Fixture> setUp,
                                String why) {
        refused(context, name, stub, setUp, why, null);
    }

    /**
     * The pit and the stub's reply, refused for {@code why}: the rescue's line says so, and nothing moved: the player
     * where it stood, its glass all there, no block placed or broken by a plan. {@code whenAsked} runs once the stub has
     * been asked, before a held reply is let go.
     */
    private static void refused(TestContext context, String name, Stub stub, java.util.function.Consumer<NativePaidSkillsGameTests.Fixture> setUp,
                                String why, java.util.function.Consumer<NativePaidSkillsGameTests.Fixture> whenAsked) {
        var ledger = new Ledger();
        var f = new NativePaidSkillsGameTests.Fixture(context, ledger.watch(), true);
        BlockPos[] base = new BlockPos[1];
        int[] editsBefore = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    pit(context, f, stub, base);
                    if (setUp != null) setUp.accept(f);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(base[0]), "In the pit"))
                .createAndAdd(() -> begin(context, f))
                .createAndAdd(() -> context.assertTrue(stub.reports.size() == 1, "Asked for a way out"))
                .createAndAdd(() -> {
                    editsBefore[0] = ledger.edits.get();
                    if (whenAsked != null) whenAsked.accept(f);
                })
                .createAndAdd(() -> context.assertTrue(!rescued(f).isEmpty(), "The reply dealt with"))
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue {}: {}", name, rescued(f));
                    context.assertTrue(rescued(f).contains("-> refused: ") && rescued(f).contains(why), "Refused for " + why + ": " + rescued(f));
                    context.assertTrue(f.player().getBlockPos().equals(base[0]), "Where it stood: " + f.player().getBlockPos().toShortString());
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.GLASS) == 8, "Its glass all there");
                    context.assertTrue(ledger.edits.get() == editsBefore[0] && ledger.prepared.get() == 0,
                            "No block placed or broken for a plan: " + ledger.edits.get() + " edits, " + ledger.prepared.get() + " pillars");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * With a work area assigned beside the pit, not over it, the server's edit policy lets it place no block in the pit: the
     * plan's first step, the pillar, is refused as it is made (outside the work area), and the plan stops there; the walk
     * after it is never given out. (With the server's edits switched off altogether, the goal does nothing at all, so no
     * step of a plan is given out to be refused.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rescueEdits", tickLimit = 3000)
    public void aStepTheEditsPolicyRefusesStopsThePlan(TestContext context) {
        var ledger = new Ledger();
        var f = new NativePaidSkillsGameTests.Fixture(context, ledger.watch(), true);
        var stub = new Stub(OUT, false);
        BlockPos[] base = new BlockPos[1];
        List<String> steps = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fenced"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    pit(context, f, stub, base);
                    BlockPos b = base[0];
                    var world = context.getWorld();
                    for (int cx = (b.getX() - 4) >> 4; cx <= (b.getX() + 4) >> 4; cx++) for (int cz = (b.getZ() - 4) >> 4; cz <= (b.getZ() + 4) >> 4; cz++) {
                        world.setChunkForced(cx, cz, true);
                    }
                    // Its work patch, and so its work area, a corner of the obsidian beside the pit: the pit is not in it.
                    context.assertTrue(f.runtime.nativePatch(f.source, f.id, b.add(2, -1, 2), b.add(3, 2, 3), "minecraft:obsidian") == 1, "Work area beside the pit");
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(base[0]), "In the pit"))
                .createAndAdd(() -> begin(context, f))
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (steps.isEmpty() || !steps.getLast().equals(progress)) steps.add(progress);
                    context.assertTrue(!rescued(f).isEmpty(), "The plan dealt with: " + progress);
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Rescue under the work area: {}; its steps {}", rescued(f), steps);
                    context.assertTrue(stub.reports.size() == 1, "Asked once");
                    context.assertTrue(rescued(f).startsWith("rescue: pillar 3, walk north 2 -> failed: 'pillar 3' did not go"), "Stopped at the pillar: " + rescued(f));
                    context.assertTrue(steps.stream().noneMatch(s -> s.contains(PortalRunCampaign.RESCUE_LABEL + "walk north 2")), "No walk after it: " + steps);
                    context.assertTrue(f.player().getBlockPos().equals(base[0]), "Still in the pit: " + f.player().getBlockPos().toShortString());
                    context.assertTrue(ledger.verified.get() == 0 && ItemMoves.count(f.player().getInventory(), Items.GLASS) == 8, "Nothing placed");
                    BlockPos b = base[0];
                    for (int cx = (b.getX() - 4) >> 4; cx <= (b.getX() + 4) >> 4; cx++) for (int cz = (b.getZ() - 4) >> 4; cz <= (b.getZ() + 4) >> 4; cz++) {
                        context.getWorld().setChunkForced(cx, cz, false);
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
