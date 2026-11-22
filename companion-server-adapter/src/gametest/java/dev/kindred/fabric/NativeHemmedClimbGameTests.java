package dev.kindred.fabric;

import dev.kindred.core.EditPolicy;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

/**
 * Hemmed in underground with every step refused, its climb to find another way goes no higher than the refused step's own
 * target and two, or eight above where it was hemmed in (F-25c4bx1). In E-40 iteration 26
 * (`claude/corpus-data-e40a26-consoles-1122` at `1b99f522`), batch 2's 707 Sol, at its own cast site at y −54 with its
 * lava bucket filled, had "Cast: step closer" refused ("No walkable route toward −126, −47, 219") and "Cast obsidian"
 * refused ("No reachable face to pour against"); "Climb to find another way" then took it from y −49 to y 66 in three and
 * a half wall minutes, 120 blocks over its own work, and its two stuck spells came at the top.
 *
 * Staged at the fallback itself, as F-25c4at1's fixture is at the walk back: every step refused is a state of the plan no
 * small scene holds for long, so the fixture stages the world and the body, sets where it stands and the server's edit
 * policy (edits on, as the corpus runs them) through the campaign's fixture setter, and asks for the fallback as the plan does with every step refused. It does not cover what refuses the
 * steps (the cast's own refusal is REFACTOR's, under F-25c4dm): the gate's marker and iteration 28 are what test it in play.
 */
public final class NativeHemmedClimbGameTests implements FabricGameTest {
    /** How many cells up the fixture puts it, a pocket at each. */
    private static final int HEIGHTS = 12;

    /** Stone round x −3 to 3, z −3 to 3, y −2 to {@value #HEIGHTS} + 4 over {@code o}, with no sky. */
    private static void rock(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-3, -2, -3), o.add(3, HEIGHTS + 4, 3));
        for (BlockPos cell : BlockPos.iterate(o.add(-3, -2, -3), o.add(3, HEIGHTS + 4, 3))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
    }

    /**
     * The fallback asked for at each height from {@code o} up, a pocket one by two in the stone round its feet at each,
     * staged a few ticks before it is asked, so that the light has settled and the stone over it reads as cover;
     * {@code withTarget}, a step refused just before for want of a route, as the cast's "step closer" was; {@code
     * cancelled}, instead a step cancelled just before a block below its feet, as iteration 27's 404 Echo's "Dig down toward
     * lava" was ("authority lost") before its climb went on up to its crafting table. Fails unless a climb is offered where
     * it was hemmed in, and none at {@code bound} or more above it; with the step cancelled, unless one is offered up to
     * seven above, as with none.
     */
    private static void run(TestContext context, String name, boolean withTarget, boolean cancelled) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 600);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 6, 40));
        BlockPos target = withTarget ? o.up(3).east() : cancelled ? o.down().east() : null;
        int bound = withTarget ? 3 + 2 : 8;
        int[] step = new int[1];
        int[] highest = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    // STAGED: stone round it, no sky; a stone pickaxe in its pack, as one it made, so its climb digs.
                    rock(context, o);
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    context.getWorld().setTimeOfDay(1_000);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var campaign = f.runtime.campaignForTest(f.id);
                    var body = f.runtime.nativeBodyForTest(f.id);
                    var player = f.player();
                    int t = step[0]++;
                    int h = t / 10, phase = t % 10;
                    BlockPos feet = o.up(h);
                    long now = world.getServer().getTicks();
                    if (t == 0 && target != null) {
                        var at = Map.of("x", String.valueOf(target.getX()), "y", String.valueOf(target.getY()), "z", String.valueOf(target.getZ()));
                        // As the runtime ends them, with the runner's own word for why.
                        if (cancelled) campaign.taskFinished(new PortalRunCampaign.Task("native_break", at, "Dig down toward lava: clear "
                                + target.toShortString()), false, now, "authority lost");
                        else campaign.taskFinished(new PortalRunCampaign.Task("native_goto", at, "Cast: step closer"), false, now,
                                "No walkable route toward " + target.toShortString());
                    }
                    if (phase == 0) {
                        // STAGED: its pocket at this height, as the climb would have dug it; the one below closed again.
                        if (h > 0) world.setBlockState(feet.down(), Blocks.STONE.getDefaultState());
                        world.setBlockState(feet, Blocks.AIR.getDefaultState());
                        world.setBlockState(feet.up(), Blocks.AIR.getDefaultState());
                        player.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
                        player.setVelocity(Vec3d.ZERO);
                    } else if (phase == 9) {
                        // As the plan asks, every step refused: the fallback, from where it stands.
                        context.assertTrue(PortalRunCampaign.underground(player), "Not read as underground at " + h + " above: the staging");
                        if (campaign.anotherWayForTest(body, feet, now, new EditPolicy.ServerPolicy(true, true)).isPresent()) highest[0] = h;
                    }
                    context.assertTrue(h > HEIGHTS, "Climbing the staged heights");
                })
                .createAndAdd(() -> {
                    context.assertTrue(highest[0] >= 0, "No climb offered at all where it was hemmed in");
                    context.assertTrue(highest[0] < bound, "The climb to find another way offered " + highest[0] + " above where it was hemmed in, past "
                            + (withTarget ? "the refused step's target, 3 above, and two" : "eight above") + ": up and away from its work");
                    if (cancelled) context.assertTrue(highest[0] >= bound - 1, "The climb to find another way offered only up to " + highest[0]
                            + " above where it was hemmed in: bounded by a step cancelled below it, not refused");
                    KindredMod.LOGGER.info("Hemmed climb ({}): offered up to {} above where it was hemmed in, bound {}", name, highest[0], bound);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: an AI player hemmed in underground with nothing it can do climbs a few blocks to look for another way,
     * not a hundred blocks up and away from its work.
     *
     * Technical: F-25c4bx1, as iteration 26's 707 Sol (above), with no refused step's target known. STAGED: stone round it,
     * no sky; a stone pickaxe; a one-by-two pocket round its feet at each height from o up to {@value #HEIGHTS}, put there
     * as the climb would have dug it; at each, the fixture sets where it stands and asks for the plan's fallback. Fails
     * unless a climb is offered where it was hemmed in, and none at eight or more above it.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "hemmedClimbNoTarget", tickLimit = 900)
    public void hemmedInItsClimbGoesNoHigherThanEightAbove(TestContext context) {
        run(context, "Hemmed", false, false);
    }

    /**
     * In plain terms: as above, with the step it could not take known: it climbs no higher than that step's place and two.
     *
     * Technical: F-25c4bx1. STAGED as above, with a "Cast: step closer" to a cell three above and one east refused just
     * before. Fails unless a climb is offered where it was hemmed in, and none at five or more above it.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "hemmedClimbTarget", tickLimit = 900)
    public void hemmedInItsClimbGoesNoHigherThanTheRefusedStepAndTwo(TestContext context) {
        run(context, "Caster", true, false);
    }

    /**
     * In plain terms: a step it was only interrupted in, below it, does not stop its climb; only a step it could not take does.
     *
     * Technical: F-25c4bx1. STAGED as above, with a "Dig down toward lava" break a block below and one east cancelled just
     * before ("authority lost"), as on iteration 27's batch 1 404 Echo (`claude/corpus-data-e40a27-consoles-1122` at
     * `7b930d48`, 14:37:10), whose climb then went on up toward its crafting table. Fails unless a climb is offered up to
     * seven above where it was hemmed in, and none at eight or more.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "hemmedClimbCancelled", tickLimit = 900)
    public void hemmedInAStepCancelledBelowDoesNotBoundItsClimb(TestContext context) {
        run(context, "Digger", false, true);
    }
}
