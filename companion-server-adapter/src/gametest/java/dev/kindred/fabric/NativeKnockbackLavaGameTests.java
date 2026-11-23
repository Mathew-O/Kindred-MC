package dev.kindred.fabric;

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
 * In plain terms: a badly hurt AI player fighting or backing off from a zombie in a narrow tunnel with lava a few blocks
 * behind it does not end up in the lava.
 *
 * Technical: F-25c6h, melee knockback near lava. MODES's staged trail (F-25c4dh5's lava guard, the behaviour main's): two
 * straight partial back-offs edged the player some three blocks down a one-wide tunnel to a lava cell in its floor, a
 * third was refused, then in the fight a knockback put it over the lava; the hazard retreat found no room and only
 * cancelled the fight, and it died ("tried to swim in lava to escape Zombie"). STAGED as MODES's
 * {@code NativeWayOffGameTests} lava case: a tunnel one wide and two high, east to west, an alcove south; the player at
 * the meeting, five health, an iron sword and bread; a zombie in the alcove a little east of south; a lava source in the
 * tunnel's floor four blocks west; night, NORMAL difficulty.
 */
public final class NativeKnockbackLavaGameTests implements FabricGameTest {
    private static final int MID = 30;
    /** Watched this long: MODES's player died at tick 478. */
    private static final int WATCH = 600;

    @GameTest(templateName = FixtureGround.WIDE, batchId = "knockbackLavaTunnel", tickLimit = 900)
    public void hurtInATunnelWithLavaBehindItAZombiesKnockbackNeverPutsItInTheLava(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        String name = "Tunnelled";
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        long[] since = {-1};
        int[] inLava = {0}, firstLava = {-1};
        boolean[] died = {false}, logged = {false};
        StringBuilder steps = new StringBuilder();
        String[] last = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    FixtureGround.keep(context, context.getAbsolutePos(new BlockPos(MID - 20, 0, MID - 14)), context.getAbsolutePos(new BlockPos(MID + 16, 6, MID + 8)));
                    for (int x = MID - 20; x <= MID + 16; x++) for (int z = MID - 14; z <= MID + 8; z++) for (int y = 0; y <= 6; y++)
                        context.setBlockState(x, y, z, Blocks.STONE);
                    for (int x = MID - 14; x <= MID + 1; x++) for (int y = 2; y <= 3; y++) context.setBlockState(x, y, MID, Blocks.AIR);
                    for (int z = MID + 1; z <= MID + 3; z++) for (int y = 2; y <= 3; y++) context.setBlockState(MID, y, z, Blocks.AIR);
                    context.setBlockState(MID - 4, 1, MID, Blocks.LAVA);
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new BlockPos(MID, 2, MID))), "Standing where the tunnel meets the alcove"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 4));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    context.getWorld().setTimeOfDay(14000);
                    Vec3d at = context.getRelative(new Vec3d(player.getX() + 0.3, player.getY(), player.getZ() + 2.5));
                    var z = context.spawnEntity(net.minecraft.entity.EntityType.ZOMBIE, at);
                    z.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    z.setPersistent();
                    z.setTarget(player);
                    since[0] = context.getTick();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    int now = (int) (context.getTick() - since[0]);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(last[0])) {
                        last[0] = progress;
                        steps.append(" @").append(now).append(' ').append(progress).append(String.format(java.util.Locale.ROOT, " (x %+.1f)",
                                player.getX() - context.getAbsolutePos(new BlockPos(MID, 2, MID)).getX() - 0.5)).append(';');
                    }
                    if (player.isInLava()) {
                        inLava[0]++;
                        if (firstLava[0] < 0) firstLava[0] = now;
                    }
                    died[0] |= !player.isAlive();
                    context.assertTrue(now >= WATCH || died[0], "Watched " + WATCH + " ticks: " + now);
                })
                .createAndAdd(() -> {
                    if (!logged[0]) {
                        logged[0] = true;
                        KindredMod.LOGGER.info("{}: died {}, {} ticks in lava (first at {}); its steps:{}", name, died[0], inLava[0], firstLava[0], steps);
                    }
                    context.assertTrue(!died[0] && inLava[0] == 0, "Never in the lava: died " + died[0] + ", " + inLava[0] + " ticks in it (first at "
                            + firstLava[0] + "); its steps:" + steps);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
