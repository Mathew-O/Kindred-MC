package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;

import java.util.ArrayList;
import java.util.List;

/**
 * In plain terms: making an AI player where the ground right there is water. A human joining the world there is put
 * on dry ground nearby; the AI player is put on dry ground the same way, and is refused, as before, when there is none.
 *
 * Technical: M-06i4a (M-06i4's defect 4: at seed 101's spawn by a lake every create was refused). On a field
 * structure, so the arena lies within its own plot. Staged: a 23 by 23
 * arena of stone, the command's spot in its middle; a pool, or water to the arena's rim, two deep. The arena is put back
 * as each test ends. {@code spawnRadius} is the default 10.
 */
public final class NativeDrySpawnGameTests implements FabricGameTest {
    private static final String TODAYS_REFUSAL = "Make room on solid ground in an already loaded area.";

    /** The arena's middle, the level a player stands at on its stone. */
    private static BlockPos centre(TestContext context) { return context.getAbsolutePos(new BlockPos(40, 3, 40)); }

    /** Stone two deep under open air across the arena, with water two deep out to {@code water} blocks from the middle (none if below 0). */
    private static void arena(TestContext context, BlockPos c, int water) {
        FixtureGround.keep(context, c.add(-11, -3, -11), c.add(11, 12, 11));
        var world = context.getWorld();
        for (int dx = -11; dx <= 11; dx++) for (int dz = -11; dz <= 11; dz++) for (int dy = -3; dy <= 12; dy++) {
            boolean wet = Math.max(Math.abs(dx), Math.abs(dz)) <= water && (dy == -1 || dy == -2);
            var state = dy >= 0 ? Blocks.AIR.getDefaultState() : wet ? Blocks.WATER.getDefaultState() : Blocks.STONE.getDefaultState();
            world.setBlockState(c.add(dx, dy, dz), state);
        }
    }

    private static void defaultRadius(TestContext context) {
        int radius = context.getWorld().getGameRules().getInt(GameRules.SPAWN_RADIUS);
        context.assertTrue(radius == 10, "spawnRadius is the default 10: " + radius);
    }

    /** The body made on the arena's stone beyond the pool, at the spot create found, on a full top, within the radius. */
    private static void onTheDryGround(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos c, int pool) {
        double[] spot = f.runtime.createSpawnForTest();
        context.assertTrue(spot != null, "Create found a spot");
        int dx = (int) Math.floor(spot[0]) - c.getX(), dz = (int) Math.floor(spot[2]) - c.getZ();
        context.assertTrue(Math.max(Math.abs(dx), Math.abs(dz)) > pool, "Beyond the pool, not in it: " + dx + ", " + dz);
        context.assertTrue(Math.max(Math.abs(dx), Math.abs(dz)) <= 10, "Within spawnRadius: " + dx + ", " + dz);
        context.assertTrue(spot[1] == c.getY(), "Standing on the stone: y " + spot[1] + ", not " + c.getY());
        var player = f.player();
        context.assertTrue(player.getX() == spot[0] && player.getY() == spot[1] && player.getZ() == spot[2],
                "The connect's body where create found: " + player.getPos() + " against " + spot[0] + ", " + spot[1] + ", " + spot[2]);
        context.assertTrue(!player.isTouchingWater() && player.getWorld().getFluidState(player.getBlockPos()).isEmpty(), "Not in water");
    }

    /** The command's spot and the ring round it over a pool: made on the dry ground beyond it, as a joining human is. */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "drySpawnPool", tickLimit = 400)
    public void aSpawnByAPoolIsMadeOnTheDryGroundBeyondIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 300);
        BlockPos c = centre(context);
        int[] made = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    defaultRadius(context);
                    arena(context, c, 3);
                })
                .createAndAdd(() -> {
                    // As `positioned over world_surface` puts it: on the water's top.
                    made[0] = f.runtime.createNative(f.source.withPosition(Vec3d.ofBottomCenter(c)), "Dryshod");
                    context.assertTrue(made[0] == 1, "Create accepted over the pool");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    onTheDryGround(context, f, c, 3);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /** One rule whatever the source: a source standing in the water itself is made on the dry ground too. */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "drySpawnInWater", tickLimit = 400)
    public void aSourceStandingInTheWaterIsMadeOnTheDryGroundToo(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 300);
        BlockPos c = centre(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    defaultRadius(context);
                    arena(context, c, 3);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.createNative(f.source.withPosition(Vec3d.ofBottomCenter(c.down())), "Wader") == 1,
                            "Create accepted from in the water");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    onTheDryGround(context, f, c, 3);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /** Water across the whole radius: refused with today's words, nothing made, no slot kept; never put in the water. */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "drySpawnNone", tickLimit = 400)
    public void withNoDryGroundInTheRadiusItIsRefusedAsBeforeAndNothingIsMade(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 300);
        BlockPos c = centre(context);
        List<String> said = new ArrayList<>();
        ServerCommandSource heard = f.source.withPosition(Vec3d.ofBottomCenter(c)).withOutput(new CommandOutput() {
            @Override public void sendMessage(Text message) { said.add(message.getString()); }
            @Override public boolean shouldReceiveFeedback() { return true; }
            @Override public boolean shouldTrackOutput() { return true; }
            @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        });
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    defaultRadius(context);
                    arena(context, c, 10);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.createNative(heard, "Castaway") == 0, "Refused");
                    context.assertTrue(said.contains(TODAYS_REFUSAL), "Today's words: " + said);
                    context.assertTrue(f.runtime.createSpawnForTest() == null, "No spot kept");
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "A while for anything made"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).isEmpty(), "Nothing allocated");
                    context.assertTrue(context.getWorld().getServer().getPlayerManager().getPlayer("Castaway") == null, "No body");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }

    /** On dry ground the ring's spot, exactly where it has always been: the first of the eight, a block east. */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "drySpawnRing", tickLimit = 400)
    public void onDryGroundTheRingsSpotIsKept(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 300);
        BlockPos c = centre(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    arena(context, c, -1);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.createNative(f.source.withPosition(Vec3d.ofBottomCenter(c)), "Ringer") == 1, "Create accepted");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    double[] spot = f.runtime.createSpawnForTest();
                    context.assertTrue(spot != null && spot[0] == c.getX() + 1.5 && spot[1] == c.getY() && spot[2] == c.getZ() + 0.5,
                            "The ring's first spot: " + java.util.Arrays.toString(spot));
                    var player = f.player();
                    context.assertTrue(player.getX() == c.getX() + 1.5 && player.getZ() == c.getZ() + 0.5, "The body there: " + player.getPos());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .completeIfSuccessful();
    }
}
