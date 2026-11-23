package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a player far out in water, with no dry ground in reach where it is, swims back to the dry ground it last
 * stood on, as a player swims back the way it came (M-06m3). One that has never stood on dry ground since it was in the
 * water has nothing to swim back to, and does as before.
 *
 * Technical: in M-06k9's shipped-configuration runs (seed 101) a claim walk carried Kindred, and in the other arm Sol, out
 * into the sea; there `surface`'s searches for dry ground (16 out) found none, the shore 24 to 75 blocks back, and every
 * plan was "up for air", for 12 and 31 minutes, 14,488 and 21,059 plans, until the watchdog's third move elsewhere went
 * inland. STAGED: a pool {@value #R} out each way and four deep, of bedrock, its rim three above the water, out of reach to
 * climb, nothing in it its goal would go for; a pad of bedrock at its west edge, its top level with the water's, to stand on. BACK: the player stands on the pad, its goal
 * on, its home a bedrock stand {@value #HOME} east of the pool, then is set {@value #OUT} blocks out, east of it, beyond the searches' 16; out of the water within the bound. NEVER:
 * set straight out there with its goal on, never having stood dry: still in the water at the bound, as before.
 *
 * What BACK asserts is the mechanism, its plan the swim back within {@value #PLANNED} ticks, and then that it is out: in
 * this staging main's explore can take it to the pad by chance (its heading turns back toward home), so being out alone
 * would not tell the two apart. The outcome in play rests on the shipped-configuration runs (M-06m3's evidence).
 */
public final class NativeSwimBackGameTests implements FabricGameTest {
    /** The pool's reach from its middle each way. */
    static final int R = 40;
    /** How far east of the pad the player is set out in the water: past the dry-ground searches' 16. */
    static final int OUT = 34;
    /** How far east of the pool's rim its home is. */
    static final int HOME = 40;
    /** Ticks within which, once set out, its plan is the swim back. */
    static final int PLANNED = 100;
    /** The swim back's plan, as the goal's progress names it. */
    static final String SWIM_BACK = "back to dry ground it stood on";
    /** Ticks it is given, once set out, to be out of the water. */
    static final int BOUND = 1200;

    /** It stood on the pad, then was set far out: it swims back to the pad and is out of the water within the bound. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "swimBack", tickLimit = 2400)
    public void farOutWithNoDryGroundInReachItSwimsBackToWhereItStoodDry(TestContext context) {
        run(context, true);
    }

    /** The control: never stood dry since in the water; nothing to swim back to: still in the water at the bound. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "swimBackNever", tickLimit = 2400)
    public void withNowhereItStoodDryItIsStillInTheWaterAsBefore(TestContext context) {
        run(context, false);
    }

    private static void run(TestContext context, boolean stood) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        BlockPos[] at = new BlockPos[1];
        long[] set = {-1}, onPad = {-1}, swimBack = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Floater"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    // p is the top water cell at the pool's middle; the pool's floor is four below it.
                    BlockPos p = f.player().getBlockPos().up(6);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-R - 1, -5, -R - 1), p.add(R + 1 + HOME + 2, 4, R + 1));
                    // Home, where its goal starts, well east of the pool: inland, away from the shore it will swim back to,
                    // as in the shipped runs, where its explore turns (back toward home) does not take it to that shore.
                    for (int x = R + HOME - 1; x <= R + HOME + 1; x++) for (int z = -1; z <= 1; z++) {
                        world.setBlockState(p.add(x, 0, z), Blocks.BEDROCK.getDefaultState());
                        for (int y = 1; y <= 3; y++) world.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -R - 1; x <= R + 1; x++) for (int z = -R - 1; z <= R + 1; z++) for (int y = -5; y <= 4; y++) {
                        boolean pool = Math.abs(x) <= R && Math.abs(z) <= R && y >= -3 && y <= 0;
                        boolean rim = (Math.abs(x) > R || Math.abs(z) > R) && y <= 3;
                        boolean pad = x >= -R && x <= -R + 2 && Math.abs(z) <= 1 && y >= -3 && y <= 0;
                        // All bedrock, its pad too: nothing its goal wants to mine or walk to in the pool, as out at sea, nor a pad to dig into.
                        world.setBlockState(p.add(x, y, z), (pad || rim || y < -3 ? Blocks.BEDROCK : pool ? Blocks.WATER : Blocks.AIR).getDefaultState());
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    // Home first, where its goal starts; the control straight out in the water instead, never having stood dry.
                    if (stood) place(f, p.add(R + HOME, 1, 0), false);
                    else place(f, out(p), true);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    onPad[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    // At home a while, its goal's first plans made there; then on the pad, or (the control) out in the water.
                    context.assertTrue(context.getTick() - onPad[0] >= 40, "A while in");
                    if (stood) place(f, at[0].add(-R + 1, 1, 0), false);
                    onPad[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    if (stood) {
                        // On the pad, out of the water, a while: its plans see it stand dry there.
                        var player = f.player();
                        context.assertTrue(context.getTick() - onPad[0] >= 60, "On the pad a while");
                        context.assertTrue(player.isOnGround() && !player.isTouchingWater(), "Standing dry on the pad: " + player.getBlockPos().subtract(at[0]).toShortString());
                        place(f, out(at[0]), true);
                    }
                    set[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    long since = context.getTick() - set[0];
                    // Its ground and water read a second after it was set out, not on that tick, when they are still the pad's.
                    boolean dry = since >= 20 && player.isOnGround() && !player.isTouchingWater();
                    if (stood) {
                        // The mechanism, not only the outcome: in this staging main's explore can take it to the pad by chance
                        // (its heading turned back toward home), so what is asserted is that its plan is the swim back.
                        if (swimBack[0] < 0 && progress(f).contains(SWIM_BACK)) swimBack[0] = since;
                        context.assertTrue(swimBack[0] >= 0 || since <= PLANNED, "Its plan the swim back to where it stood dry within "
                                + PLANNED + " ticks of being set out: " + progress(f) + " at " + since + " ticks");
                        if (!dry) {
                            context.assertTrue(since <= BOUND, "Out of the water within " + BOUND + " ticks: at " + player.getBlockPos().subtract(at[0]).toShortString()
                                    + " from the pool's middle; " + progress(f));
                            throw new net.minecraft.test.GameTestException("Not out yet: " + progress(f));
                        }
                        context.assertTrue(swimBack[0] >= 0, "Out of the water, its plan the swim back first: " + progress(f));
                        KindredMod.LOGGER.info("Swim-back fixture: its plan the swim back {} ticks after it was set out; out of the water {} ticks after, at {} from the pool's middle; {}",
                                swimBack[0], since, player.getBlockPos().subtract(at[0]).toShortString(), progress(f));
                    } else {
                        if (since < BOUND) {
                            context.assertTrue(!dry, "Never stood dry: still in the water at " + since + " ticks; " + progress(f));
                            throw new net.minecraft.test.GameTestException("Watching: " + since);
                        }
                        KindredMod.LOGGER.info("Swim-back fixture (never stood dry): still in the water after {} ticks, at {} from the pool's middle; {}",
                                since, player.getBlockPos().subtract(at[0]).toShortString(), progress(f));
                    }
                })
                .completeIfSuccessful();
    }

    /** Far out: {@value #OUT} east of the pad, its head out of the water. */
    private static BlockPos out(BlockPos p) {
        return p.add(-R + 1 + OUT, 0, 0);
    }

    /** At {@code cell}: on its floor, or in the water with its head out ({@code water}). */
    private static void place(NativePaidSkillsGameTests.Fixture f, BlockPos cell, boolean water) {
        var player = f.player();
        player.refreshPositionAndAngles(cell.getX() + 0.5, cell.getY() - (water ? 0.4 : 0), cell.getZ() + 0.5, player.getYaw(), player.getPitch());
        player.setVelocity(0, 0, 0);
        player.setAir(player.getMaxAir());
    }

    private static String progress(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
    }
}
