package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: an AI player put in lava is burned by it like any player, and in a few seconds it is either dead or
 * out of the lava.
 *
 * Technical: a probe kept as a permanent test (F-25c6a). On E-40c3's seed 303 Kindred stayed at the bottom of the
 * Nether's lava sea for some 105,000 ticks with no death recorded and nothing in its console about fire resistance;
 * the run kept no playerdata to say. Here a native body with full health and no effects is set at the bottom of a pool
 * of lava, its runtime running the portal goal. STAGED: the pool (five by five, three deep, in stone), the player set
 * at its bottom, bread and a stone pickaxe; nothing protects it. Passes if it takes damage within 20 ticks, and within
 * 200 it has died or is out of the lava on solid ground. A body that takes no damage would be immune to lava, against
 * earning everything.
 */
public final class NativeLavaProbeGameTests implements FabricGameTest {
    /** The world tick each player was first hurt by lava or fire, by its id: recorded as the damage is dealt. */
    private static final java.util.Map<java.util.UUID, Long> FIRST_BURN = new java.util.concurrent.ConcurrentHashMap<>();
    static {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity player && (source.isOf(net.minecraft.entity.damage.DamageTypes.LAVA)
                    || source.isOf(net.minecraft.entity.damage.DamageTypes.IN_FIRE) || source.isOf(net.minecraft.entity.damage.DamageTypes.ON_FIRE))) {
                FIRST_BURN.putIfAbsent(player.getUuid(), player.getServerWorld().getTime());
            }
            return true;
        });
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "lavaProbe", tickLimit = 1200)
    public void stagedANativeBodyInLavaIsBurnedAndIsDeadOrOutWithinTenSeconds(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        ServerPlayerEntity[] body = {null};
        long[] since = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Probe"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isOnGround(), "Standing");
                    since[0] = context.getWorld().getTime();
                })
                // A player just joined is spared all damage for its first 60 ticks (vanilla's join invulnerability): the first
                // version of this probe set it in the lava inside that window and measured the window, 58 ticks.
                .createAndAdd(() -> context.assertTrue(context.getWorld().getTime() - since[0] >= 80, "Past the join's spared ticks"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(new BlockPos(8, 1, 8));
                    FixtureGround.keep(context, o.add(-6, -4, -6), o.add(6, 6, 6));
                    // The pool: lava source blocks three deep, five by five, in stone, its rim at the ground's level.
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -4; y <= 0; y++) {
                        boolean inside = Math.abs(x) <= 2 && Math.abs(z) <= 2 && y >= -3;
                        world.setBlockState(o.add(x, y, z), (inside ? Blocks.LAVA : Blocks.STONE).getDefaultState());
                    }
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 1; y <= 3; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    player.clearStatusEffects();
                    player.setHealth(player.getMaxHealth());
                    player.getHungerManager().setFoodLevel(20);
                    player.extinguish();
                    BlockPos bottom = o.add(0, -3, 0);
                    player.refreshPositionAndAngles(bottom.getX() + 0.5, bottom.getY(), bottom.getZ() + 0.5, 0, 0);
                    body[0] = player;
                    FIRST_BURN.remove(player.getUuid());
                    since[0] = world.getTime();
                    KindredMod.LOGGER.info("Lava probe: set at the bottom of the pool at {}, health {}, effects {}", bottom.toShortString(),
                            player.getHealth(), player.getStatusEffects().size());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // Burned: hurt by the lava within 20 ticks of going in, by the tick the damage was dealt.
                .createAndAdd(() -> {
                    var player = body[0];
                    long waited = context.getWorld().getTime() - since[0];
                    Long burned = FIRST_BURN.get(player.getUuid());
                    if (burned == null && waited > 20) {
                        throw new net.minecraft.test.GameTestException("Not hurt in lava after " + waited + " ticks: health " + player.getHealth()
                                + ", in lava " + player.isInLava() + ", effects " + player.getStatusEffects() + " - a native body immune to lava");
                    }
                    context.assertTrue(burned != null, "Not hurt yet: " + waited + " ticks");
                    long after = burned - since[0];
                    context.assertTrue(after <= 20, "First hurt by the lava " + after + " ticks after going in, not within 20");
                    KindredMod.LOGGER.info("Lava probe: first hurt by the lava {} ticks after going in; health {} now", after, player.getHealth());
                })
                // Dead, or out of the lava on solid ground, within 200 ticks.
                .createAndAdd(() -> {
                    var player = body[0];
                    long waited = context.getWorld().getTime() - since[0];
                    boolean dead = player.isDead() || player.isRemoved() || f.player() != player;
                    boolean out = !dead && !player.isInLava() && player.isOnGround() && !player.isOnFire();
                    if (waited > 200 && !dead && !out) {
                        throw new net.minecraft.test.GameTestException("Neither dead nor out after " + waited + " ticks: health " + player.getHealth()
                                + ", in lava " + player.isInLava() + ", at " + player.getBlockPos().toShortString());
                    }
                    context.assertTrue(dead || out, "Still in lava: " + waited + " ticks, health " + player.getHealth());
                    KindredMod.LOGGER.info("Lava probe: {} after {} ticks", dead ? "dead" : "out of the lava at " + player.getBlockPos().toShortString(), waited);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
