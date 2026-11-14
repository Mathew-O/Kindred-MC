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

/**
 * In plain terms: GameTests of the water fetch's search under the sky at the foot of rock (MOVEMENT, F-35o). Wanting
 * water for its cast, an AI player stands under the open sky in a pit too deep to walk out of, no water it has seen;
 * water lies in caves in the rock round it, out of its sight. Its search tunnels into the rock for water. Before, a step
 * in put it under cover, and its climb for the surface walked it back out the way it came, by turns. Now its tunnel
 * goes on while it is in it, finds the cave and fills a bucket.
 *
 * Technical: staged, not a replay. Its walk to look cuts a way into the rock as often as its search tunnels, and both are
 * held (F-35o). On 31102026 Sol at 100800 and 707 Kindred at 50920 in E-40's iteration 21
 * (`e40a21-2354e67`) its search's tunnel went a block along under cover and its way back walked it out again, the (iv)
 * along and back of F-35o; a replay's kept box cannot hold water out of its player's sight past sixteen blocks, so it is
 * staged here. Passes on two counts, over the {@value #BUDGET}-tick budget after the goal is accepted:
 * <ol>
 *   <li>no more than {@value #TURNS} turns between a step of its tunnel for water and a step of its climb for the
 *   surface;</li>
 *   <li>a bucket filled with water within the budget.</li>
 * </ol>
 */
public final class NativeWaterCutGameTests implements FabricGameTest {
    /** Ticks after the goal is accepted by which its bucket must be filled. */
    static final int BUDGET = 2400;
    /** Turns allowed between its tunnel for water and its climb for the surface. */
    static final int TURNS = 4;
    /** How far north of the pit's middle the cave with the water begins. */
    static final int CAVE_OFF = 9;

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "waterCutAlong", tickLimit = BUDGET + 600)
    public void inAPitItsTunnelForWaterGoesOnAlongAndIsNotWalkedBackOut(TestContext context) {
        watch(context, false, "Water cut along");
    }

    /**
     * Down and up: the pit's floor dips a block under each of its walls, under the rock, so that its search's tunnel
     * steps down under cover there and, before, its climb for the surface stepped back up out of it, by turns: on 303
     * Kindred at 40200 and 44060 in E-40's iteration 21 (`e40a21-2354e67`), at the mouth of its cut into a hill. Passes on
     * the same two counts.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "waterCutDown", tickLimit = BUDGET + 600)
    public void inAPitItsTunnelForWaterStepsDownUnderTheRockAndIsNotClimbedBackOut(TestContext context) {
        watch(context, true, "Water cut down");
    }

    private static void watch(TestContext context, boolean dips, String name) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int[] watched = {0};
        int[] turns = {0};
        int[] seenDone = {0};
        String[] lastKind = {""};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cut"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-24, -4, -24), o.add(24, 10, 24));
                    pit(context, o, dips);
                    context.getWorld().setTimeOfDay(6000);
                    kit(f.player());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, 180, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o)
                        && !PortalRunCampaign.underground(f.player()), "In its pit, under the sky: at " + f.player().getBlockPos().subtract(o).toShortString()))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Alive; " + seen);
                    var campaign = f.runtime.campaignForTest(f.id);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) seen.append(watched[0]).append(": ").append(progress).append(" @ ")
                            .append(player.getBlockPos().subtract(o).toShortString()).append("; ");
                    last[0] = progress;
                    watched[0]++;
                    var done = campaign.completedForTest();
                    for (int i = Math.min(seenDone[0], done.size()); i < done.size(); i++) {
                        String label = done.get(i);
                        String kind = dig(label) ? "tunnel" : label.startsWith("Climb toward the surface for water") ? "climb" : "";
                        if (kind.isEmpty()) continue;
                        if (!lastKind[0].isEmpty() && !kind.equals(lastKind[0])) turns[0]++;
                        lastKind[0] = kind;
                    }
                    seenDone[0] = done.size();
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(turns[0] <= TURNS, "1: " + turns[0] + " turns between its tunnel for water and its climb: " + tail);
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.WATER_BUCKET) >= 1,
                            "2: no bucket filled after " + watched[0] + " ticks, " + turns[0] + " turns: " + tail);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("{}: bucket filled after {} ticks, {} turns; {}", name, watched[0], turns[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A block of rock twenty out round {@code o} and six high over grass; a pit five across in it at {@code o}, open to
     * the sky, its walls six high; and {@value #CAVE_OFF} blocks out each way, north, east, south and west, a cave in
     * the rock, five across and three high, lit, with a pool of water on its floor: shut in, out of sight of the pit.
     * With {@code dips}, under the middle of each wall a cell two high cut a block into it and a block lower than the
     * pit's floor, under the rock.
     */
    private static void pit(TestContext context, BlockPos o, boolean dips) {
        var world = context.getWorld();
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) for (int y = -4; y <= 10; y++) {
            boolean rock = Math.abs(x) <= 20 && Math.abs(z) <= 20 && y >= 0 && y <= 5;
            boolean pit = Math.abs(x) <= 2 && Math.abs(z) <= 2 && y >= 0;
            boolean cave = false, pool = false;
            for (int way = 0; way < 4; way++) {
                // Along and across this way: north, east, south, west.
                int along = way == 0 ? -z : way == 1 ? x : way == 2 ? z : -x, across = way % 2 == 0 ? x : z;
                cave |= Math.abs(across) <= 2 && along >= CAVE_OFF && along <= CAVE_OFF + 4 && y >= 0 && y <= 2;
                pool |= Math.abs(across) <= 1 && along >= CAVE_OFF + 1 && along <= CAVE_OFF + 3 && y == -1;
            }
            // With dips: under the middle of each wall, one in, a cell two high a block lower than the pit floor.
            boolean dip = dips && y == -1 && (Math.abs(x) <= 0 && Math.abs(z) == 3 || Math.abs(z) <= 0 && Math.abs(x) == 3);
            var state = y <= -2 ? Blocks.STONE : y == -1 ? (pool ? Blocks.WATER : dip ? Blocks.AIR : Blocks.GRASS_BLOCK)
                    : rock && !pit && !cave && !(dips && y <= 1 && (x == 0 && Math.abs(z) == 3 || z == 0 && Math.abs(x) == 3)) ? Blocks.STONE : Blocks.AIR;
            world.setBlockState(o.add(x, y, z), state.getDefaultState());
        }
        for (var light : new BlockPos[] {o.add(0, 2, -CAVE_OFF - 2), o.add(CAVE_OFF + 2, 2, 0), o.add(0, 2, CAVE_OFF + 2), o.add(-CAVE_OFF - 2, 2, 0)}) {
            world.setBlockState(light.add(light.getX() == o.getX() ? 3 : 0, 0, light.getZ() == o.getZ() ? 3 : 0), Blocks.GLOWSTONE.getDefaultState());
        }
    }

    /** A step of its dig on along the ground for water: its search's tunnel, or its walk's cut through. */
    private static boolean dig(String label) {
        return label.startsWith("Tunnel for water") || label.startsWith("Cut through ") && label.contains(" for water");
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
