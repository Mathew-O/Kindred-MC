package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Told by a teammate where it filled its cast's bucket at lava, a player short of lava for its own cast, with none seen
 * and no cave to look in, walks there before it mines blind for its own (F-25c4dm). In E-40's iteration 25
 * (`e40a25s-0d3432b`) on 404 with the atlas off, Echo branch-mined for lava at Y=-54 for three stuck spells after
 * Kindred had filled its bucket 138 to 158 blocks off; on 101 off and on 606 on, the same.
 */
public final class NativeToldLavaGameTests implements FabricGameTest {
    /** Ticks for the filler's own fill; and for the walker's lava, or its walk set aside and the hunt begun, after it starts. */
    private static final int FILL = 1200, BUDGET = 2400;
    private static final String WALK = "Cast: walk to the lava Filler found";

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID nativeId) {
        return f.source.getServer().getPlayerManager().getPlayer(nativeId);
    }

    private static String progress(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(id)).findFirst().map(PlayerRuntime.PanelBot::goalProgress).orElse("");
    }

    /**
     * Andesite round {@code o} with no sky, eleven high: a passage two high from {@code o} 44 east, then 24 south, into a
     * chamber three high whose floor south of its first two rows is a pool of lava. Andesite, not stone, so no floor in it
     * reads as a cave's ({@code Sight.caveFloor}): as in the iteration-25 spells, where its hunt came to the blind mine,
     * there is no cave for the walker to look in, and the pool is out of its sight round the corner, 50 blocks off.
     * Glowstone along the passage and in the chamber, so no mob spawns in the dark. With {@code walled}, the passage is
     * shut 30 blocks east of {@code o}. With {@code CORNER}, the passage runs on to 25 south, against a wall right in
     * front of the pool, a single row of lava on the wall's far side; the way into the chamber is round to the east, along
     * the wall to 48 and in through a gap at its end, out of sight of the pool (F-25c4dm2).
     */
    private enum Layout { OPEN, WALLED, CORNER, UNSAFE }

    private static void ground(TestContext context, BlockPos o, Layout layout) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-6, -5, -6), o.add(62, 5, 40));
        for (BlockPos c : BlockPos.iterate(o.add(-6, -5, -6), o.add(62, 5, 40))) world.setBlockState(c, Blocks.ANDESITE.getDefaultState());
        for (BlockPos c : BlockPos.iterate(o.add(0, 0, 0), o.add(44, 1, 0))) world.setBlockState(c, Blocks.AIR.getDefaultState());
        if (layout == Layout.CORNER) {
            // The passage on to the wall; along the wall east to 48, in through the gap; the chamber behind the wall, its
            // pool a single row against the wall, west of the gap so no line from the gap's side shows it.
            for (BlockPos c : BlockPos.iterate(o.add(44, 0, 0), o.add(44, 1, 25))) world.setBlockState(c, Blocks.AIR.getDefaultState());
            for (BlockPos c : BlockPos.iterate(o.add(45, 0, 25), o.add(48, 1, 25))) world.setBlockState(c, Blocks.AIR.getDefaultState());
            for (BlockPos c : BlockPos.iterate(o.add(48, 0, 26), o.add(48, 1, 26))) world.setBlockState(c, Blocks.AIR.getDefaultState());
            for (BlockPos c : BlockPos.iterate(o.add(40, 0, 27), o.add(48, 2, 32))) world.setBlockState(c, Blocks.AIR.getDefaultState());
            for (BlockPos c : BlockPos.iterate(o.add(41, -1, 27), o.add(45, -1, 27))) world.setBlockState(c, Blocks.LAVA.getDefaultState());
            for (int x = 0; x <= 44; x += 8) world.setBlockState(o.add(x, 2, 0), Blocks.GLOWSTONE.getDefaultState());
            for (int z = 8; z <= 24; z += 8) world.setBlockState(o.add(44, 2, z), Blocks.GLOWSTONE.getDefaultState());
            world.setBlockState(o.add(46, 2, 25), Blocks.GLOWSTONE.getDefaultState());
            for (int[] g : new int[][] {{40, 28}, {48, 28}, {40, 32}, {48, 32}}) world.setBlockState(o.add(g[0], 3, g[1]), Blocks.GLOWSTONE.getDefaultState());
            return;
        }
        boolean walled = layout == Layout.WALLED;
        for (BlockPos c : BlockPos.iterate(o.add(44, 0, 0), o.add(44, 1, 24))) world.setBlockState(c, Blocks.AIR.getDefaultState());
        for (BlockPos c : BlockPos.iterate(o.add(40, 0, 25), o.add(48, 2, 32))) world.setBlockState(c, Blocks.AIR.getDefaultState());
        for (BlockPos c : BlockPos.iterate(o.add(41, -1, 27), o.add(47, -1, 31))) world.setBlockState(c, Blocks.LAVA.getDefaultState());
        for (int x = 0; x <= 44; x += 8) world.setBlockState(o.add(x, 2, 0), Blocks.GLOWSTONE.getDefaultState());
        for (int z = 8; z <= 24; z += 8) world.setBlockState(o.add(44, 2, z), Blocks.GLOWSTONE.getDefaultState());
        for (int[] g : new int[][] {{40, 25}, {48, 25}, {40, 32}, {48, 32}}) world.setBlockState(o.add(g[0], 3, g[1]), Blocks.GLOWSTONE.getDefaultState());
        if (walled) for (BlockPos c : BlockPos.iterate(o.add(30, 0, 0), o.add(30, 1, 0))) world.setBlockState(c, Blocks.ANDESITE.getDefaultState());
    }

    /** The cast's kit, as the cast-pool fixture gives it: no diamond pickaxe, so the portal goal casts its frame. */
    private static void castKit(ServerPlayerEntity who) {
        var inventory = who.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
        inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
        inventory.setStack(3, new ItemStack(Items.BREAD, 8));
        inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
        inventory.setStack(5, new ItemStack(Items.STICK, 4));
        inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
        inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
        inventory.setStack(8, new ItemStack(Items.BUCKET));
        who.setHealth(20);
        who.getHungerManager().setFoodLevel(20);
    }

    /**
     * In plain terms: one AI player fills a bucket at a pool of lava down a passage and tells its team; another, needing
     * lava for its own portal and seeing none, walks down the passage to that pool and fills its own bucket there, rather
     * than tunnel blind for lava. A third player, racing alone in a speedrun match, is not on the team: it is never told.
     *
     * Technical: F-25c4dm. STAGED: the ground above; the cast's kit for Walker (at the passage's west end, facing east)
     * and Filler (in the chamber over the pool); Rival with nothing, in a one-player speedrun match it starts before the
     * word, at the west end too; day held. Filler sees the pool by its campaign's own sweep and fills its bucket on its own
     * portal goal, which tells the team through the runtime as in play; both goals start together, 51 blocks apart, beyond the run's division (48), so each casts its own frame; once
     * Walker is told, the fixture ends Filler's goal (staging, not the runtime): close by, the run's division would make
     * Walker its provisioner rather than a caster, and the iteration-25 fillers had gone on to their own portals.
     * Fails if, once told, Walker mines for lava blind ("Dig down toward lava", "Branch mine for lava") before its lava, if Rival is ever
     * told of the lava or walks to it, or if within {@value #BUDGET} ticks Walker has no bucket of lava. Passes with
     * Walker's lava, filled after its walk to the pool Filler told it of.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "toldLava", tickLimit = FILL + BUDGET + 1200)
    public void toldWhereATeammateFilledItsBucketAPlayerShortOfLavaWalksThereNotMinesBlind(TestContext context) {
        scene(context, Layout.OPEN);
    }

    /**
     * As above, with the passage walled 30 blocks east of Walker and no Rival: the walk there gets it no nearer than the
     * wall, the told lava is set aside with its reason, and the cast's own hunt goes on as before. STAGED as above. Fails if
     * within {@value #BUDGET} ticks the told lava is not set aside, or the hunt does not begin after it. Passes once the hunt
     * has begun after the set-aside.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "toldLavaWalled", tickLimit = FILL + BUDGET + 1200)
    public void toldOfLavaNoWalkReachesThePlayerSetsItAsideAndHuntsAsBefore(TestContext context) {
        scene(context, Layout.WALLED);
    }

    /**
     * In plain terms: as the first, but the pool it was told of lies just behind a wall at the passage's end, the way in
     * round to the side. Its walk toward the pool stops in the passage a few blocks short of it, the pool out of sight; it
     * goes on round into the chamber, sees the pool and fills there, and never digs down blind meanwhile.
     *
     * Technical: F-25c4dm2. On #457's CI (run 45063245649, attempt 2) the first case failed, "Walker mined blind for lava
     * (1 steps)": its walk there, partial with a range of three, still ending in the passage round the corner from the
     * pool, a plan within six of the told place found {@code toldLava} empty while it waited for sight, and the chain went
     * on to {@code descendOrTunnel}'s "Dig down toward lava" at the pool's level. Locally that race went the other way 9
     * times in 9. STAGED as the first, with {@code CORNER}'s ground: the told cell a block behind the wall, so a walk that
     * stops within three of it stops in the passage, every time; Filler in the chamber, a row in from the pool, facing it.
     * Fails, as the first does, if once told Walker mines for lava blind before its lava, if Rival is told, or if Walker has
     * no bucket of lava in {@value #BUDGET} ticks. Passes with Walker's lava, filled after its walk to the told pool.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "toldLavaCorner", tickLimit = FILL + BUDGET + 1200)
    public void toldOfLavaRoundACornerItsWalkEndingNearAndOutOfSightItWalksOnNotDigsBlind(TestContext context) {
        scene(context, Layout.CORNER);
    }

    /**
     * In plain terms: as the first, but every time it sets off to the pool it was told of, something sets it alight, and it
     * backs off for its safety. Once is taken as the walk ending where it got to; the second time the told pool is set
     * aside, not walked back toward again and again, and its own hunt goes on.
     *
     * Technical: F-25c4dm2, the bound on a safety cut. STAGED as the walled case's ground but open, and no Rival: whenever
     * Walker's step is a walk to the told lava, the fixture sets it alight (staging, not the runtime), so the runtime's
     * hazard retreat cuts the walk short, as the pool's own edge cut the walk onto it in the corner case; the first walk
     * only once 10 blocks on its way, so the first cut is one with ground gained (cut where it set off, the walk is judged
     * as one that got it no nearer, and set aside so, before any second). Fails if, once
     * told, it mines blind before the told lava is set aside, if it sets off toward it more than three times, or if within
     * {@value #BUDGET} ticks the told lava is not set aside "cut short for its safety twice" with the hunt begun after.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "toldLavaUnsafe", tickLimit = FILL + BUDGET + 1200)
    public void toldOfLavaItsWalkCutShortForItsSafetyTwiceSetsItAsideNotWalkedIntoAgain(TestContext context) {
        scene(context, Layout.UNSAFE);
    }

    private static void scene(TestContext context, Layout layout) {
        boolean walled = layout == Layout.WALLED, unsafe = layout == Layout.UNSAFE, rivalless = walled || unsafe;
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, FILL + BUDGET + 1100);
        BlockPos o = context.getAbsolutePos(new BlockPos(8, 12, 8));
        String[] names = rivalless ? new String[] {"Walker", "Filler"} : new String[] {"Walker", "Filler", "Rival"};
        UUID[] ids = new UUID[names.length], players = new UUID[names.length];
        int[] blind = {0}, watched = {0}, fill = {0}, setOffs = {0};
        PortalRunCampaign.Task[] lastTask = new PortalRunCampaign.Task[1];
        boolean[] walked = {false}, aside = {false}, hunted = {false}, walking = {false}, twice = {false};
        StringBuilder seen = new StringBuilder();
        String[] failed = {null};
        var runner = context.createTimedTaskRunner().createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"));
        for (int n = 0; n < names.length; n++) {
            String name = names[n];
            int count = n + 1;
            runner = runner.createAndAdd(() -> f.runtime.createNative(f.source, name))
                    .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == count, name + " allocated"));
        }
        runner.createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == names.length && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "All established");
                    for (var status : statuses) {
                        String name = player(f, status.nativeId()).getName().getString();
                        int i = java.util.Arrays.asList(names).indexOf(name);
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    ground(context, o, unsafe ? Layout.OPEN : layout);
                    context.getWorld().setTimeOfDay(6000);
                    var walker = player(f, players[0]);
                    var filler = player(f, players[1]);
                    castKit(walker);
                    castKit(filler);
                    walker.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    walker.setVelocity(Vec3d.ZERO);
                    // In the chamber, its first two rows of floor solid, facing south and down at the pool; round the corner,
                    // a row in from the pool against the wall, facing north and down at it.
                    if (layout == Layout.CORNER) filler.refreshPositionAndAngles(o.getX() + 43.5, o.getY(), o.getZ() + 28.5, 180, 60);
                    else filler.refreshPositionAndAngles(o.getX() + 44.5, o.getY(), o.getZ() + 26.5, 0, 60);
                    filler.setVelocity(Vec3d.ZERO);
                    if (!rivalless) {
                        var rival = player(f, players[2]);
                        rival.getInventory().clear();
                        rival.refreshPositionAndAngles(o.getX() + 2.5, o.getY(), o.getZ() + 0.5, -90, 0);
                        rival.setVelocity(Vec3d.ZERO);
                    }
                })
                .createAndAdd(() -> {
                    for (UUID p : players) context.assertTrue(player(f, p).isOnGround(), player(f, p).getName().getString() + " standing");
                })
                .createAndAdd(() -> {
                    if (rivalless) return;
                    // Rival races alone: in a match, it is no one's teammate in this run.
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.proposeSpeedrun(f.source, java.util.List.of(players[2])) == 1, "Rival's speedrun proposed");
                })
                .createAndAdd(() -> {
                    if (rivalless) return;
                    context.assertTrue(f.runtime.startMatch(f.source, f.runtime.matches().getFirst().id()) == 1, "Rival's speedrun started");
                })
                .createAndAdd(() -> {
                    // The pool seen as Filler sees it from where it stands: its campaign's own sweep, sight lines and all.
                    var filler = player(f, players[1]);
                    var knowledge = f.runtime.campaignForTest(ids[1]).knowledge();
                    for (int i = 0; i < 400 && knowledge.count(WorldKnowledge.Kind.LAVA) == 0; i++) knowledge.scan(filler);
                    context.assertTrue(knowledge.count(WorldKnowledge.Kind.LAVA) > 0, "Filler saw the pool");
                    f.runtime.observeRun(new RunObserver() {
                        @Override
                        public void ended(String p, String skill, String state, String label) {
                            if (label == null || !"Walker".equals(p) || !"COMPLETED".equals(state)) return;
                            if (label.startsWith("Dig down toward lava") || label.startsWith("Branch mine for lava")) {
                                blind[0]++;
                                if (aside[0]) hunted[0] = true;
                            }
                        }
                    });
                    // Both on the portal goal at once, 51 blocks apart: farther than the run's division reaches (48), so each
                    // casts its own frame, as the iteration-25 players did with their teammates far off.
                    context.assertTrue(f.runtime.startGoal(f.source, ids[1], SurvivalGoal.NETHER_PORTAL) == 1, "Filler's goal accepted");
                    context.assertTrue(f.runtime.startGoal(f.source, ids[0], SurvivalGoal.NETHER_PORTAL) == 1, "Walker's goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    boolean told = f.runtime.campaignForTest(ids[0]).lastEvents(16).stream().anyMatch(e -> e.contains("told of lava at") && e.contains("by Filler"));
                    context.assertTrue(++fill[0] <= FILL || told, "Walker not told of Filler's lava in " + FILL + " ticks: Filler " + progress(f, ids[1]));
                    context.assertTrue(told, "Not yet");
                    // Staging, not the runtime: its word given, the fixture ends Filler's goal, as the iteration-25 fillers had gone on:
                    // with it close by, the run's division would make the walker its provisioner, not a caster.
                    context.assertTrue(f.runtime.startGoal(f.source, ids[1], SurvivalGoal.NONE) == 1, "Filler's goal ended");
                    blind[0] = 0;
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var walker = player(f, players[0]);
                    context.assertTrue(walker != null && walker.isAlive(), "Walker died: " + seen);
                    String doing = progress(f, ids[0]);
                    walked[0] |= doing.contains(WALK);
                    if (!aside[0]) {
                        var events = f.runtime.campaignForTest(ids[0]).lastEvents(16);
                        aside[0] = events.stream().anyMatch(e -> e.contains("set aside the lava Filler told of"));
                        twice[0] = events.stream().anyMatch(e -> e.contains("set aside the lava Filler told of") && e.contains("cut short for its safety twice"));
                        // Before the told lava is set aside, no blind step: counted here, the hunt's after it.
                        if (unsafe && blind[0] > 0 && failed[0] == null) failed[0] = "Walker mined blind for lava (" + blind[0] + " steps) before it set the told lava aside: " + seen;
                    }
                    if (unsafe && !aside[0]) {
                        // Staging, not the runtime: each walk toward the told lava set alight, so the body's own retreat cuts it
                        // short; the first only once it is 10 blocks on its way, so that cut is one with ground gained.
                        boolean nowWalking = doing.contains(WALK);
                        // A set-off is a told walk dispatched, a new task: not the plan's label coming back to the walk, which
                        // flips with the water bucket's climb while the fire is put out (F-25c4dm3: counted so, 4 in 1 of 20
                        // runs under load, with two walks cut and set aside as it should be).
                        var task = f.runtime.activeCampaignTaskForTest(ids[0]);
                        if (task != null && task != lastTask[0] && task.label().startsWith(WALK)) setOffs[0]++;
                        lastTask[0] = task;
                        walking[0] = nowWalking;
                        boolean onItsWay = setOffs[0] >= 2 || walker.getBlockPos().getManhattanDistance(o) >= 10;
                        if (nowWalking && onItsWay && walker.getFireTicks() <= 0) walker.setFireTicks(40);
                        if (setOffs[0] > 3 && failed[0] == null) failed[0] = "Walker set off toward the told lava " + setOffs[0] + " times: " + seen;
                    }
                    if (seen.length() < 1600 && !seen.toString().endsWith(doing + "; ")) seen.append(doing).append("; ");
                    if (!rivalless) {
                        String rival = progress(f, ids[2]);
                        boolean told = f.runtime.campaignForTest(ids[2]) != null
                                && f.runtime.campaignForTest(ids[2]).lastEvents(16).stream().anyMatch(e -> e.contains("told of lava"));
                        // Sticky: the runner tries a failed step again, and a told event slides out of the last sixteen.
                        if (failed[0] == null && (told || rival.contains("lava Filler found"))) failed[0] = "Rival, in a match, was told of the lava: " + rival;
                        if (failed[0] == null && blind[0] > 0) failed[0] = "Walker mined blind for lava (" + blind[0] + " steps) before its lava: " + seen;
                    }
                    if (failed[0] != null) throw new GameTestException(failed[0]);
                    ++watched[0];
                    if (rivalless) {
                        boolean done = aside[0] && hunted[0] && (!unsafe || twice[0]);
                        context.assertTrue(watched[0] <= BUDGET || done, "In " + BUDGET + " ticks, set aside " + aside[0] + (unsafe ? " (cut short twice " + twice[0] + ", set off " + setOffs[0] + " times)" : "")
                                + ", hunted after " + hunted[0] + ": " + seen);
                        context.assertTrue(done, "Not yet");
                    } else {
                        boolean lava = walker.getInventory().count(Items.LAVA_BUCKET) > 0;
                        context.assertTrue(watched[0] <= BUDGET || lava, "No bucket of lava in " + BUDGET + " ticks: " + seen);
                        context.assertTrue(lava, "Not yet");
                    }
                })
                .createAndAdd(() -> {
                    if (failed[0] != null) throw new GameTestException(failed[0]);
                    if (!rivalless) context.assertTrue(walked[0], "Its lava not from the pool it was told of: " + seen);
                    KindredMod.LOGGER.info("Told lava{}: walked {}, set aside {}, blind steps {}, hunted after {}, set off {} times, after {} ticks; {}",
                            walled ? " (walled)" : unsafe ? " (unsafe)" : layout == Layout.CORNER ? " (corner)" : "",
                            walked[0], aside[0], blind[0], hunted[0], setOffs[0], watched[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
