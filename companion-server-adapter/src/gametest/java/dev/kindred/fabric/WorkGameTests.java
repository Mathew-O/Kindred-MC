package dev.kindred.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.kindred.core.ActionArbiter;
import dev.kindred.core.ActionLease;
import dev.kindred.core.Actor;
import dev.kindred.core.BodyAdapter;
import dev.kindred.core.Companion;
import dev.kindred.core.SkillBody;
import dev.kindred.core.SkillRunner;
import dev.kindred.core.WorkSetup;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Native contracts for work authority and the first movement skills. They check identity/validation boundaries
 * in a real world; they do not show a body traversing terrain (that remains F-10 live evidence).
 */
public final class WorkGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void setupAndMovementCommandsParse(TestContext context) {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        PlayerCommands.register(dispatcher);
        var source = context.getWorld().getServer().getCommandSource();
        for (String command : List.of("companion setup", "companion home set", "companion area default", "companion area set 0 0 40 40",
                "companion storage set", "companion patch set 1 60 1 10 70 10 minecraft:oak_log", "companion goto 10 64 10",
                "companion gohome", "companion wait 30", "companion pause", "companion continue",
                "companion goto 10 64 10 " + UUID.randomUUID(), "companion patch set 1 60 1 10 70 10 minecraft:oak_log " + UUID.randomUUID())) {
            var parsed = dispatcher.parse(command, source);
            context.assertFalse(parsed.getReader().canRead(), "Must parse: " + command);
            context.assertTrue(parsed.getExceptions().isEmpty(), "Must accept: " + command);
        }
        for (String invalid : List.of("companion wait 0", "companion wait 61", "companion area set 0 0", "companion home")) {
            // Redirect aliases carry a parent help command; only execution resolves the final child.
            boolean rejected = false;
            try { dispatcher.execute(invalid, source); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { rejected = true; }
            context.assertTrue(rejected, "Must reject: " + invalid);
        }
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void assignedStorageIsIdentifiedByBlockAndVersionNotCoordinates(TestContext context) {
        BlockPos relative = new BlockPos(1, 1, 1);
        context.setBlockState(relative, Blocks.CHEST);
        BlockPos absolute = context.getAbsolutePos(relative);
        var observed = StorageTargets.observe(context.getWorld(), absolute);
        context.assertTrue(observed != null && observed.container(), "A chest is a container");
        var target = new WorkSetup.StorageTarget(new WorkSetup.BlockPoint(absolute.getX(), absolute.getY(), absolute.getZ()), observed.blockId(), observed.version());
        context.assertTrue(StorageTargets.validate(context.getWorld(), target) == StorageTargets.Verdict.OK, "Unchanged chest validates");
        context.setBlockState(relative, Blocks.BARREL);
        context.assertTrue(StorageTargets.validate(context.getWorld(), target) == StorageTargets.Verdict.CHANGED, "A replaced container is not the assigned one");
        context.setBlockState(relative, Blocks.STONE);
        context.assertTrue(StorageTargets.validate(context.getWorld(), target) == StorageTargets.Verdict.NOT_A_CONTAINER, "A broken chest blocks before any transfer");
        context.setBlockState(relative, Blocks.AIR);
        context.assertTrue(StorageTargets.observe(context.getWorld(), absolute) != null && !StorageTargets.observe(context.getWorld(), absolute).container(),
                "Air is never a container");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void moveToBlocksOutsideAreaUnsafeAndUnsetHomeAndCompletesOnlyFromAFreshPositionWithinOneBlock(TestContext context) {
        // Explicit floor and headroom so the checks do not depend on the template's own layers.
        for (int x = 1; x <= 3; x++) {
            context.setBlockState(x, 1, 1, Blocks.STONE);
            context.setBlockState(x, 2, 1, Blocks.AIR);
            context.setBlockState(x, 3, 1, Blocks.AIR);
            context.setBlockState(x, 4, 1, Blocks.AIR);
        }
        CompanionEntity body = body(context, new Vec3d(1.5, 2, 1.5));
        BlockPos here = body.getBlockPos();
        var home = new WorkSetup.BlockPoint(here.getX(), here.getY(), here.getZ());
        WorkSetup noArea = WorkSetup.empty(body.companionId(), "minecraft:overworld");
        context.assertTrue(step(new MovementSkills.MoveTo(body, noArea, here, false)).reason().contains("work area"), "No area means no movement authority");
        context.assertTrue(step(new MovementSkills.MoveTo(body, noArea.withArea(WorkSetup.WorkRegion.around(home)), null, true)).reason().contains("home"),
                "Return home needs a home marker");
        WorkSetup setup = noArea.withHome(new WorkSetup.HomeMarker(home)).withArea(WorkSetup.WorkRegion.around(home));
        var far = here.add(400, 0, 0);
        context.assertTrue(step(new MovementSkills.MoveTo(body, setup, far, false)).reason().contains("outside"), "Outside the allocated area is a typed blocker");
        // Standing inside the area on a solid floor: already within one block, so arrival is observed, not assumed.
        var arrived = step(new MovementSkills.MoveTo(body, setup, here, false));
        context.assertTrue(arrived.status() == SkillBody.Status.COMPLETED, "Fresh position within one block completes");
        context.assertTrue("true".equals(arrived.progress().get("arrived")), "Completion carries observed arrival");
        // A destination inside the area but with no standing room is refused, not pathed into.
        BlockPos pillar = here.add(2, 0, 0);
        context.getWorld().setBlockState(pillar, Blocks.STONE.getDefaultState());
        context.getWorld().setBlockState(pillar.up(), Blocks.STONE.getDefaultState());
        var unsafe = step(new MovementSkills.MoveTo(body, setup, pillar, false));
        context.assertTrue(unsafe.status() == SkillBody.Status.BLOCKED && unsafe.permanent(), "Obstructed destination is a permanent blocker: " + unsafe.reason());
        context.getWorld().setBlockState(pillar, Blocks.AIR.getDefaultState());
        context.getWorld().setBlockState(pillar.up(), Blocks.AIR.getDefaultState());
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void waitCountsOnlyDrivenTicksAndContinuesFromItsCheckpointAfterPause(TestContext context) {
        CompanionEntity body = body(context, new Vec3d(1.5, 0, 1.5));
        UUID owner = UUID.randomUUID();
        var companion = Companion.create(body.getUuid(), owner, "Waiter", UUID.randomUUID(), "minecraft:overworld");
        long[] clock = {0};
        BodyAdapter adapter = new BodyAdapter() {
            @Override public void start(ActionLease lease) { }
            @Override public void cancel(ActionLease lease) { }
            @Override public boolean isSettled(ActionLease lease) { return true; }
        };
        ActionArbiter arbiter = new ActionArbiter(companion, UUID.randomUUID(), adapter, Clock.systemUTC(), () -> clock[0], Duration.ofMillis(500));
        SkillRunner runner = new SkillRunner(arbiter, () -> clock[0]);
        Actor actor = Actor.player(owner);
        runner.start(MovementSkills.WAIT, Map.of("seconds", "1"), new MovementSkills.Wait(1), actor, UUID.randomUUID(), Optional.empty());
        for (int tick = 0; tick < 10; tick++) { clock[0] += 50_000_000L; runner.tick(); }
        context.assertTrue(runner.state() == SkillRunner.State.RUNNING, "Half a second of driven ticks is not a second");
        runner.pause();
        clock[0] += 50_000_000L;
        runner.tick();
        context.assertTrue(runner.state() == SkillRunner.State.PAUSED, "Wait pauses at its next tick");
        String elapsed = runner.checkpoint().orElseThrow().progress().get("elapsedTicks");
        context.assertTrue("11".equals(elapsed), "Checkpoint keeps the driven ticks, got " + elapsed);
        clock[0] += 60_000_000_000L; // A minute of wall time while paused is not waiting.
        runner.resume(actor);
        for (int tick = 0; tick < 8; tick++) { clock[0] += 50_000_000L; runner.tick(); }
        context.assertTrue(runner.state() == SkillRunner.State.RUNNING, "19 driven ticks are still short of 20");
        clock[0] += 50_000_000L;
        runner.tick();
        context.assertTrue(runner.state() == SkillRunner.State.COMPLETED, "The twentieth driven tick completes: " + runner.state());
        context.complete();
    }

    private static SkillBody.Step step(SkillBody skill) {
        return skill.tick(Map.of(), Optional.empty(), true);
    }

    private static CompanionEntity body(TestContext context, Vec3d relative) {
        CompanionEntity body = KindredMod.COMPANION.create(context.getWorld());
        if (body == null) throw new IllegalStateException("Missing companion factory");
        body.bind(body.getUuid(), UUID.randomUUID());
        Vec3d position = context.getAbsolute(relative);
        body.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return body;
    }
}
