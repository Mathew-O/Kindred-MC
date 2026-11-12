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
 * In plain terms: a GameTest of the water fetch under shallow cover (MOVEMENT, F-35o's option 1). Wanting water for its
 * cast, an AI player stands in a short cut into a low hill, two blocks of stone over its head and no water it has seen;
 * a pond lies on the open ground {@value #POND_OFF} blocks east, which it can see from the hill's top and not from the
 * cut. It climbs out to the top first, sees the pond, walks to it and fills a bucket, rather than tunnel on for water.
 * A control: main climbs first under cover too (F-35o), so this passes before and after alike.
 *
 * Technical: staged, not a replay: a replay's kept box is flat grass outside sixteen blocks of its player, so water past
 * them cannot be in it (F-35o's feasibility check). Passes on two counts, over the {@value #BUDGET}-tick budget after the
 * goal is accepted:
 * <ol>
 *   <li>a bucket filled with water within the budget;</li>
 *   <li>no step of the search's tunnel for water completed before it.</li>
 * </ol>
 */
public final class NativeWaterHillGameTests implements FabricGameTest {
    /** Ticks after the goal is accepted by which its bucket must be filled. */
    static final int BUDGET = 2400;
    /** How far east of the cut the pond's near edge lies. */
    static final int POND_OFF = 22;

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "waterHill", tickLimit = BUDGET + 600)
    public void underShallowCoverItClimbsOutSeesWaterAndFillsItsBucket(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, BUDGET + 500);
        BlockPos o = context.getAbsolutePos(new BlockPos(40, 8, 40));
        int[] watched = {0};
        int[] tunnels = {0};
        boolean[] topped = {false};
        String[] last = {""};
        StringBuilder seen = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hill"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, o.add(-32, -3, -32), o.add(32, 10, 32));
                    hill(context, o);
                    context.getWorld().setTimeOfDay(6000);
                    kit(f.player());
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 0);
                    player.setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround() && f.player().getBlockPos().equals(o)
                        && PortalRunCampaign.underground(f.player()), "In its cut, under cover: at " + f.player().getBlockPos().subtract(o).toShortString()))
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
                    if (player.getBlockY() >= o.getY() + 4) topped[0] = true;
                    tunnels[0] = (int) campaign.completedForTest().stream().filter(label -> label.startsWith("Tunnel for water")).count();
                    String tail = seen.length() > 900 ? "..." + seen.substring(seen.length() - 900) : seen.toString();
                    context.assertTrue(tunnels[0] == 0, "2: a step of its tunnel for water before its bucket: " + tail);
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.WATER_BUCKET) >= 1,
                            "1: no bucket filled after " + watched[0] + " ticks (on the hill's top " + topped[0] + "): " + tail);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    KindredMod.LOGGER.info("Water hill: bucket filled after {} ticks, on the hill's top {}, {} tunnel steps; {}", watched[0], topped[0], tunnels[0], seen);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Open grass round {@code o}; a hill of stone seven across and four high on it, with a ramp down its west side, a cut three long and two high into
     * it at {@code o} (east to west) with two blocks of stone over it, a glowstone in its wall for light; and a pond a
     * block deep and five across on the grass {@value #POND_OFF} blocks east.
     */
    private static void hill(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) for (int y = -3; y <= 10; y++) {
            // A ramp down its west side, a block at a time, so a walk can get down off it.
            boolean hill = Math.abs(x) <= 3 && Math.abs(z) <= 3 && y >= 0 && y <= 3 || x >= -6 && x <= -4 && Math.abs(z) <= 1 && y >= 0 && y <= x + 6;
            boolean cut = z == 0 && Math.abs(x) <= 1 && (y == 0 || y == 1);
            boolean pond = x >= POND_OFF && x <= POND_OFF + 4 && Math.abs(z) <= 2 && y == -1;
            var state = y <= -2 ? Blocks.STONE : y == -1 ? (pond ? Blocks.WATER : Blocks.GRASS_BLOCK) : hill && !cut ? Blocks.STONE : Blocks.AIR;
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
