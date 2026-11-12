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

    /**
     * A lake of lava source blocks, {@code half} out from {@code o} each way and {@code deep} deep, its top at {@code o}'s
     * level and level with the stone bank around it, as a natural lake fills to its banks; open above. (The first staging
     * left the bank a block above the lava and capped the lake with stone: out of a pit like that nothing swims, as the
     * fluid's edge lifts a body only some 0.6 of a block.)
     */
    private static void pool(TestContext context, BlockPos o, int half, int deep) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-half - 3, -deep - 2, -half - 3), o.add(half + 3, 6, half + 3));
        for (int x = -half - 2; x <= half + 2; x++) for (int z = -half - 2; z <= half + 2; z++) {
            boolean over = Math.abs(x) <= half && Math.abs(z) <= half;
            for (int y = -deep; y <= 0; y++) {
                world.setBlockState(o.add(x, y, z), (over && y > -deep ? Blocks.LAVA : Blocks.STONE).getDefaultState());
            }
            for (int y = 1; y <= 4; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
        }
    }

    /**
     * In plain terms: an AI player at the bottom of a lake of lava that it can stand in for a while gets itself out, rather
     * than standing there giving up every task it begins.
     *
     * Technical: F-25c6a, E-40c3's seed 303 Kindred at the bottom of the Nether's lava sea, each task it began cancelled
     * "urgent retreat required", the retreat having no cell beside it out of the lava. STAGED: fire resistance for two
     * minutes given by the fixture, as that player must have had something like it (it lived some 105,000 ticks there and
     * the run kept no playerdata to say); a pool nine by nine and five deep, the player set at its bottom; a stone pickaxe,
     * cobblestone and bread; the portal goal. Passes if within 600 ticks it is out of the lava on solid ground.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "lavaEscapeDeep", tickLimit = 1600)
    public void stagedWithFireResistanceAtTheBottomOfALakeOfLavaItGetsItselfOut(TestContext context) {
        escapes(context, 4, 5, true, 600);
    }

    /**
     * In plain terms: an AI player fallen into lava deeper than a step, with lava on every side, makes for the shore before
     * it burns to death.
     *
     * Technical: F-25c6a, the same stall with nothing to protect it. STAGED: a lake three by three and one deep, level
     * with its bank, the player set in its middle, so every cell beside it is lava and the one-step retreat has nowhere to
     * go; no effects; a stone pickaxe, cobblestone and bread; the portal goal. Sized to what a body can do: the probe above
     * lives some 50 ticks in lava, and a body moves through it at most some 0.04 of a block a tick (vanilla's drag), so a
     * bank about a block off is in reach and one two and a half off (a five-by-five lake's middle) is not, for anyone.
     * Passes if within 200 ticks it is out of the lava on solid ground, alive. The burning it carries out (some fifteen
     * seconds, about 15 health) is waited out and logged, not passed on: out after some 38 ticks of lava on six to nine
     * health, with bread and no water, it does not outlast it (measured on the branch, F-25c6a's evidence).
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "lavaEscapeShallow", tickLimit = 1200)
    public void stagedWithNothingToProtectItInLavaWithLavaOnEverySideItMakesTheShoreAlive(TestContext context) {
        escapes(context, 1, 1, false, 200);
    }

    private static void escapes(TestContext context, int half, int deep, boolean fireResistance, int within) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        ServerPlayerEntity[] body = {null};
        long[] since = {-1};
        boolean[] wasIn = {false};
        long[] outAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Kindred"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isOnGround(), "Standing");
                    since[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getTime() - since[0] >= 80, "Past the join's spared ticks"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    // Built up from the field, its floor clear of the world's bottom (the field's own ground is near it).
                    BlockPos o = context.getAbsolutePos(new BlockPos(10, 2 + deep + 1, 10));
                    pool(context, o, half, deep);
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.clearStatusEffects();
                    if (fireResistance) player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE, 2400));
                    player.setHealth(player.getMaxHealth());
                    player.getHungerManager().setFoodLevel(20);
                    player.extinguish();
                    BlockPos bottom = o.add(0, -deep + 1, 0);
                    player.refreshPositionAndAngles(bottom.getX() + 0.5, bottom.getY(), bottom.getZ() + 0.5, 0, 0);
                    body[0] = player;
                    since[0] = world.getTime();
                    KindredMod.LOGGER.info("Lava escape: set at the bottom of a pool {} across and {} deep at {}, fire resistance {}", 2 * half + 1, deep,
                            bottom.toShortString(), fireResistance);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = body[0];
                    long waited = context.getWorld().getTime() - since[0];
                    boolean dead = player.isDead() || player.isRemoved() || f.player() != player;
                    // In the lava first, as the entity's own tick sees it: just after the move it has not looked yet.
                    if (!dead && player.isInLava()) wasIn[0] = true;
                    if (!wasIn[0] && waited > 20) throw new net.minecraft.test.GameTestException("Never in the lava: the staging missed, at "
                            + player.getBlockPos().toShortString());
                    boolean out = !dead && wasIn[0] && !player.isInLava() && player.isOnGround();
                    String progress = dead ? "" : f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    if (dead) throw new net.minecraft.test.GameTestException("Dead in the lava after " + waited + " ticks");
                    if (waited > within && !out) {
                        throw new net.minecraft.test.GameTestException("Still in the lava after " + waited + " ticks at " + player.getBlockPos().toShortString()
                                + ", health " + player.getHealth() + " - " + progress + " / " + f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(out, "In the lava: " + waited + " ticks");
                    outAt[0] = waited;
                    KindredMod.LOGGER.info("Lava escape: out at {} after {} ticks, health {}", player.getBlockPos().toShortString(), waited, player.getHealth());
                })
                .createAndAdd(() -> {
                    // Out is not yet safe: the burning carried out of lava lasts some fifteen seconds. Logged, not passed on.
                    var player = body[0];
                    long waited = context.getWorld().getTime() - since[0];
                    boolean dead = player.isDead() || player.isRemoved() || f.player() != player;
                    context.assertTrue(dead || !player.isOnFire(), "Still burning: " + waited + " ticks, health " + player.getHealth());
                    KindredMod.LOGGER.info("Lava escape: out after {} ticks; {} after {}", outAt[0],
                            dead ? "burned to death" : "the fire out on " + player.getHealth() + " health", waited);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
