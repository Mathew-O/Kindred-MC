package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * In plain terms: a GameTest of a climb boxed in under water (MOVEMENT, F-35n). Wanting water for its cast, an AI player
 * stands in its own tunnel three long under a lake, with water past every wall of it and under its floor, so that no
 * way up, along or down gets it out from under. Before, its climb went round in the tunnel for good. Now the climb hands
 * back once it has got no farther for twenty steps, does not come back into the same tunnel while it is still there, and
 * the goal goes on to its other ways.
 *
 * Technical: staged, not a replay. On 505 in E-40's iteration 21 (`e40a21-2354e67`) Kindred, at Y -51 under an aquifer
 * wanting water for its cast, went round within seven blocks for whole stuck spells, 520 to 570 of the climb's lines a
 * spell, and filled no bucket: the climb gave up only twenty blocks along, which it never got. The water over and round
 * the tunnel is behind stone it never sees through, so no water it knows of is in reach and no bucket can be filled here;
 * the replay of that spell measures the buckets (F-35n's evidence). Passes on three counts, over the
 * {@value #BUDGET}-tick budget after the goal is accepted:
 * <ol>
 *   <li>its climb hands back, said in its events, within {@value #HAND_BACK} ticks;</li>
 *   <li>after that, no step of the climb out from under the water within {@value #MOVED_OFF} blocks of where it
 *   handed back;</li>
 *   <li>(b) after that, the goal completes at least one step that is not the climb out from under the water: a search's
 *   dig for water, a step back the way it came, or the watchdog's move elsewhere.</li>
 * </ol>
 */
public final class NativeWaterBoxGameTests implements FabricGameTest {
    /** Ticks after the goal is accepted that the fixture watches. */
    static final int BUDGET = 2400;
    /** Ticks after the goal is accepted by which the climb must have handed back. */
    static final int HAND_BACK = 600;
    /** How far from where its climb handed back counts as still there: {@code Digger.MOVED_OFF}. */
    static final int MOVED_OFF = dev.kindred.core.route.Digger.MOVED_OFF;
    /** Steps that are the goal's other ways on, part 3's (b). */
    private static final List<String> OTHER_WAYS = List.of("Tunnel for water", "back the way it came", "elsewhere");

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "waterBox505", tickLimit = BUDGET + 600)
    public void boxedInUnderWaterItsClimbHandsBackAndDoesNotComeBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int[] watched = {0};
        int[] handedBack = {-1};
        BlockPos[] gaveUpAt = new BlockPos[1];
        int[] reentered = {0};
        int[] otherWays = {0};
        int[] seenCompleted = {0};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Boxed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-9, -5, -9), o.add(9, 9, 9));
                    box(context, o);
                    context.getWorld().setTimeOfDay(6000);
                    kit(f.player());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o), "In its tunnel: at "
                        + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Alive; " + seen);
                    var campaign = f.runtime.campaignForTest(f.id);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) {
                        seen.append(watched[0]).append(": ").append(progress).append(" @ ").append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                        // Part 2: no climb out from under the water again near where it handed back.
                        if (handedBack[0] >= 0 && progress.contains("out from under the water")
                                && player.getBlockPos().getSquaredDistance(gaveUpAt[0]) <= MOVED_OFF * MOVED_OFF) reentered[0]++;
                    }
                    last[0] = progress;
                    watched[0]++;
                    if (handedBack[0] < 0 && campaign.lastEvents(16).stream().anyMatch(e -> e.contains("too wide to get out from under"))) {
                        handedBack[0] = watched[0];
                        gaveUpAt[0] = player.getBlockPos().toImmutable();
                        seenCompleted[0] = campaign.completedForTest().size();
                    }
                    // Part 3 (b): a step that is one of the goal's other ways, completed after the climb handed back.
                    if (handedBack[0] >= 0) {
                        var done = campaign.completedForTest();
                        for (int i = Math.min(seenCompleted[0], done.size()); i < done.size(); i++) {
                            String label = done.get(i);
                            if (!label.contains("out from under the water") && OTHER_WAYS.stream().anyMatch(label::contains)) otherWays[0]++;
                        }
                        seenCompleted[0] = done.size();
                    }
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(handedBack[0] >= 0 || watched[0] <= HAND_BACK, "1: the climb did not hand back in " + HAND_BACK + " ticks: " + tail);
                    if (reentered[0] > 0) context.assertTrue(false, "2: back out from under the water within " + MOVED_OFF + " of where it handed back ("
                            + gaveUpAt[0].subtract(o).toShortString() + "): " + tail);
                    context.assertTrue(watched[0] >= BUDGET, "Watching");
                })
                .createAndAdd(() -> {
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(otherWays[0] >= 1, "3: no step of the goal's other ways completed after the climb handed back: " + tail);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Water box 505: handed back after {} ticks at {}, {} steps of its other ways after; {}", handedBack[0],
                            gaveUpAt[0] == null ? "-" : gaveUpAt[0].subtract(o).toShortString(), otherWays[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A block of stone round {@code o}: a lake three deep over it (Y +3 to +5, open to the sky), its tunnel three long and
     * two high at {@code o} (east to west), water past each of the tunnel's walls three out from its middle and a layer of
     * water two under its floor, so that every way up, along or down meets water; a glowstone in its wall for light.
     */
    private static void box(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) for (int y = -4; y <= 8; y++) {
            boolean ring = Math.max(Math.abs(x), Math.abs(z)) == 3 && (y == 0 || y == 1);
            boolean tunnel = z == 0 && Math.abs(x) <= 1 && (y == 0 || y == 1);
            var state = y >= 6 ? Blocks.AIR : y >= 3 || y == -2 || ring ? Blocks.WATER : tunnel ? Blocks.AIR : Blocks.STONE;
            world.setBlockState(o.add(x, y, z), state.getDefaultState());
        }
        world.setBlockState(o.add(0, 1, 1), Blocks.GLOWSTONE.getDefaultState());
    }

    /** The cast's kit as though made, its two buckets empty, and blocks to spend. */
    private static void kit(net.minecraft.server.network.ServerPlayerEntity player) {
        var inventory = player.getInventory();
        inventory.clear();
        for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
        inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
        inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
        inventory.setStack(2, new ItemStack(Items.BUCKET, 2));
        inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
        inventory.setStack(4, new ItemStack(Items.BREAD, 16));
        inventory.setStack(5, new ItemStack(Items.STICK, 8));
        inventory.setStack(6, new ItemStack(Items.COBBLESTONE, 64));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.setAir(player.getMaxAir());
    }
}
