package dev.kindred.fabric;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * The bed it carries is taken back up in the morning without a refusal repeated every tick (F-25c4di1g). In E-40 iteration
 * 26 (`claude/corpus-data-e40a26-consoles-1122` at `1b99f522`), batch 1's 20261030 Echo set its bed down in a sealed
 * pocket at −107, −54, 7, slept, and woke on top of it: "Take its bed back up in the morning" was then refused 10,958 times
 * in ten game minutes, "Would remove the block the player stands on", and the watchdog counted three stuck spells. Batch 1's
 * 303 Kindred did the same, 3,209 times. The morning take-up was offered every tick within 4.5 of its eyes, whatever the
 * break's own rules said, and a refused take-up changed nothing.
 */
public final class NativeBedTakeUpGameTests implements FabricGameTest {
    /** How long a night it has to set its bed down and lie in it. */
    private static final int NIGHT = 2400;
    /** How long a morning it has to take its bed back up. */
    private static final int MORNING = 600;

    /** Rock round {@code o}, with no sky: x −4 to 6, z −4 to 4, y −2 to 5. */
    private static void rock(TestContext context, BlockPos o) {
        var world = context.getWorld();
        FixtureGround.keep(context, o.add(-4, -2, -4), o.add(6, 5, 4));
        for (BlockPos cell : BlockPos.iterate(o.add(-4, -2, -4), o.add(6, 5, 4))) world.setBlockState(cell, Blocks.STONE.getDefaultState());
    }

    private static void air(TestContext context, BlockPos from, BlockPos to) {
        for (BlockPos cell : BlockPos.iterate(from, to)) context.getWorld().setBlockState(cell, Blocks.AIR.getDefaultState());
    }

    /** Its bed's two cells in the staged rock round {@code o}, head first; null while there is none. */
    private static BlockPos[] bed(TestContext context, BlockPos o) {
        var world = context.getWorld();
        for (BlockPos cell : BlockPos.iterate(o.add(-4, -2, -4), o.add(6, 5, 4))) {
            var state = world.getBlockState(cell);
            if (!state.isIn(BlockTags.BEDS) || state.get(BedBlock.PART) != BedPart.HEAD) continue;
            BlockPos head = cell.toImmutable();
            return new BlockPos[] {head, head.offset(state.get(BedBlock.FACING).getOpposite())};
        }
        return null;
    }

    /** Where its morning finds it. */
    private enum Morning {
        /** On top of its bed's head. */
        ON_HEAD,
        /** On top of its bed's foot. */
        ON_FOOT,
        /** Outside its pocket, its bed seen only past the pocket's wall. */
        WALLED,
        /** As {@link #WALLED}, with lava behind the wall's top block, which no break may let out. */
        WALLED_LAVA
    }

    /**
     * Its night in the bed it carries in a sealed pocket three cells long, then its morning where {@code at} puts it. Fails
     * unless, within {@value #MORNING} ticks of morning: on its bed, the bed is back in its pack with no bed block left,
     * and it stands on the floor under the bed, unhurt and in no liquid; walled, the same, with at most two blocks of its
     * pocket's wall broken; walled with lava, its bed's chore set aside, the lava kept in and the bed still down.
     */
    private static void morning(TestContext context, String name, Morning at) {
        boolean onHead = at == Morning.ON_HEAD;
        boolean walled = at == Morning.WALLED || at == Morning.WALLED_LAVA;
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, NIGHT + MORNING + 400);
        BlockPos o = context.getAbsolutePos(new BlockPos(30, 14, 40));
        int[] ticks = new int[2];
        BlockPos[][] staged = new BlockPos[1][];
        float[] health = new float[1];
        BlockPos[] wall = new BlockPos[3];
        var open = new java.util.HashSet<BlockPos>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player() != null, "Its body"))
                .createAndAdd(() -> {
                    // STAGED: rock round it, no sky; a pocket one cell wide and two high sealed all round, its feet at o.
                    // The bed's room it digs itself, as in play, from dirt east of its feet (stone would take the night by
                    // hand), on the stone floor.
                    rock(context, o);
                    air(context, o, o.up());
                    for (BlockPos cell : BlockPos.iterate(o.add(1, 0, 0), o.add(2, 1, 0))) context.getWorld().setBlockState(cell, Blocks.DIRT.getDefaultState());
                    var player = f.player();
                    player.getInventory().clear();
                    for (var slot : EquipmentSlot.values()) if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) player.equipStack(slot, ItemStack.EMPTY);
                    // STAGED: its bed in its hotbar, as one it made; no home bed, so home is out of reach.
                    player.getInventory().setStack(0, new ItemStack(Items.WHITE_BED));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    context.getWorld().setWeather(12_000, 0, false, false);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90, 10);
                    player.setVelocity(Vec3d.ZERO);
                    context.getWorld().setTimeOfDay(14_000);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Its own play resumed");
                    // The portal goal, as the iteration's players had: its morning chore is the campaign's own step.
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    // Night held until it lies in the bed it set down itself.
                    context.getWorld().setTimeOfDay(14_000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died in the night");
                    var seen = bed(context, o);
                    context.assertTrue(++ticks[0] <= NIGHT, "No bed set down and slept in within " + NIGHT + " ticks of night: "
                            + f.runtime.bedChoreForTest(f.id) + "; bed " + (seen == null ? "none" : seen[0].subtract(o).toShortString() + " / " + seen[1].subtract(o).toShortString())
                            + ", spawn " + (player.getSpawnPointPosition() == null ? "none" : player.getSpawnPointPosition().subtract(o).toShortString()));
                    // Lain in: vanilla sets its spawn at the bed as it lies down (the night itself the server may skip).
                    var down = bed(context, o);
                    var spawn = player.getSpawnPointPosition();
                    boolean lain = down != null && spawn != null && (spawn.equals(down[0]) || spawn.equals(down[1]));
                    context.assertTrue(lain, "Waiting for it to lie in its bed");
                })
                .createAndAdd(() -> {
                    // Morning: vanilla wakes it, and it is staged where its morning finds it on the tick it wakes, before
                    // its take-up can run.
                    context.getWorld().setTimeOfDay(1_000);
                    context.assertTrue(!f.player().isSleeping(), "Waiting for it to wake");
                    staged[0] = bed(context, o);
                    context.assertTrue(staged[0] != null, "Its bed still down");
                    var player = f.player();
                    var world = context.getWorld();
                    if (walled) {
                        // STAGED: as iteration 26's 202 Kindred, at a stand outside its pocket with the pocket's wall between
                        // it and its bed: the pocket's own cell, beyond the bed's foot, walled up with stone; a cell dug beyond
                        // that, where it stands; with lava, a lava source shut in the rock beside the wall's top block.
                        var away = net.minecraft.util.math.Direction.fromVector(staged[0][1].getX() - staged[0][0].getX(), 0,
                                staged[0][1].getZ() - staged[0][0].getZ());
                        BlockPos pocket = staged[0][1].offset(away), stand = pocket.offset(away);
                        world.setBlockState(pocket, Blocks.STONE.getDefaultState());
                        world.setBlockState(pocket.up(), Blocks.STONE.getDefaultState());
                        air(context, stand, stand.up());
                        wall[0] = pocket;
                        wall[1] = pocket.up();
                        wall[2] = stand;
                        if (at == Morning.WALLED_LAVA) world.setBlockState(pocket.up().offset(away.rotateYClockwise()), Blocks.LAVA.getDefaultState());
                        // STAGED: a stone pickaxe in its pack, as one it made: iteration 26's players all had one by then.
                        player.getInventory().setStack(1, new ItemStack(Items.STONE_PICKAXE));
                        player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, away.getOpposite().asRotation(), 20);
                    } else {
                        // STAGED: on top of one half of its bed, as vanilla leaves a player woken with nowhere beside the bed
                        // to stand (20261030 Echo, above).
                        BlockPos under = staged[0][onHead ? 0 : 1];
                        player.refreshPositionAndAngles(under.getX() + 0.5, under.getY() + 0.5625, under.getZ() + 0.5, -90, 10);
                    }
                    player.setVelocity(Vec3d.ZERO);
                    health[0] = player.getHealth();
                    for (BlockPos cell : BlockPos.iterate(o.add(-4, -2, -4), o.add(6, 5, 4))) if (world.getBlockState(cell).isAir()) open.add(cell.toImmutable());
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1_000);
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "The player died in the morning");
                    if (at == Morning.WALLED_LAVA) {
                        context.assertTrue(context.getWorld().getBlockState(wall[1]).isOf(Blocks.STONE), "The wall's top block broken, its lava let out");
                        context.assertTrue(++ticks[1] <= MORNING, "Its bed's chore not set aside within " + MORNING
                                + " ticks of morning, walled in by a block it may not break: " + f.runtime.bedChoreForTest(f.id));
                        context.assertTrue(f.runtime.bedChoreForTest(f.id).contains("aside=true"), "Waiting for the chore set aside");
                        return;
                    }
                    context.assertTrue(++ticks[1] <= MORNING, "Its bed not taken back up within " + MORNING + " ticks of morning, "
                            + at.name().toLowerCase(java.util.Locale.ROOT) + ": " + f.runtime.bedChoreForTest(f.id));
                    context.assertTrue(player.getInventory().count(Items.WHITE_BED) == 1 && bed(context, o) == null, "Waiting for its bed back in its pack");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    if (at == Morning.WALLED_LAVA) {
                        context.assertTrue(bed(context, o) != null && world.getBlockState(wall[1]).isOf(Blocks.STONE), "Its bed or its wall gone");
                    } else if (walled) {
                        // No more broken than its pocket's own wall, two blocks at most: no tunnel dug for its bed.
                        int broken = 0;
                        for (BlockPos cell : BlockPos.iterate(o.add(-4, -2, -4), o.add(6, 5, 4))) {
                            if (cell.equals(staged[0][0]) || cell.equals(staged[0][1])) continue;
                            if (world.getBlockState(cell).isAir() && !open.contains(cell.toImmutable())) broken++;
                        }
                        context.assertTrue(broken <= 2, "More than its pocket's wall broken: " + broken);
                    } else {
                        // Down the bed's height onto the stone floor under it, where it may step across to pick its bed up.
                        var feet = player.getBlockPos();
                        context.assertTrue(player.isOnGround() && (feet.equals(staged[0][0]) || feet.equals(staged[0][1]))
                                        && world.getBlockState(feet.down()).isOf(Blocks.STONE),
                                "Not on the floor under its bed: at " + feet.subtract(o).toShortString() + ", its bed was at "
                                        + staged[0][0].subtract(o).toShortString() + " / " + staged[0][1].subtract(o).toShortString());
                    }
                    context.assertTrue(player.getHealth() >= health[0], "Hurt taking its bed up: " + player.getHealth() + " of " + health[0]);
                    context.assertTrue(!player.isTouchingWater() && !player.isInLava(), "In liquid taking its bed up");
                    KindredMod.LOGGER.info("Bed take-up ({}): {}, done {} ticks into the morning; {}", name,
                            at.name().toLowerCase(java.util.Locale.ROOT), ticks[1], f.runtime.bedChoreForTest(f.id));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: an AI player that wakes on top of its own bed, in a pocket with nowhere beside the bed to stand, takes
     * the bed back up in the morning: from the half it does not stand on, dropping the bed's height onto the floor.
     *
     * Technical: F-25c4di1g, as iteration 26's 20261030 Echo (above). STAGED: rock round it, no sky, a sealed pocket three
     * cells long, dug by itself from a one-cell pocket into staged dirt; its bed in its pack and nothing else; no home bed; the portal goal; night held until it has set its bed down and lies in
     * it; then morning, and it is put on top of its bed's head, the half its take-up targets. Fails unless, within
     * {@value #MORNING} ticks, the bed is back in its pack with no bed block left, and it stands on the floor under its
     * bed, unhurt and in no liquid. The foot broken this way drops the bed: vanilla's loot drops it from the head, which the
     * foot's break takes away with its drops.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedTakeUpOnHead", tickLimit = NIGHT + MORNING + 600)
    public void wokenOnItsBedsHeadItTakesItsBedBackUp(TestContext context) {
        morning(context, "Sleeper", Morning.ON_HEAD);
    }

    /**
     * In plain terms: as above, woken on the other half of its bed: it takes the bed back up from the head.
     *
     * Technical: F-25c4di1g. STAGED as above, but put on top of its bed's foot. This half was taken up before the change
     * too; it checks the head's own drop, and that the drop onto the floor under the foot is as safe.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedTakeUpOnFoot", tickLimit = NIGHT + MORNING + 600)
    public void wokenOnItsBedsFootItTakesItsBedBackUp(TestContext context) {
        morning(context, "Dozer", Morning.ON_FOOT);
    }

    /**
     * In plain terms: an AI player whose way back to its bed in the morning leaves it outside its sealed pocket, the pocket's
     * own wall between it and its bed, opens that wall (no more than two blocks) and takes the bed back up, by day.
     *
     * Technical: F-25c4di1g, as iteration 26's 202 Kindred and Sol, 101 Kindred and Echo, and 20261030r Kindred: "out of
     * sight from ... the line to its middle meets Block{minecraft:stone}". STAGED as above to the morning; then, on the tick
     * it wakes, the pocket's own cell beyond its bed's foot walled up with stone (two blocks), a cell dug beyond that, a stone pickaxe in its pack (as one it made: iteration 26's players all had one by then; a bare hand harvests no stone), and it
     * put there, within reach of its bed but with no sight of it. Fails unless, within {@value #MORNING} ticks, the bed is
     * back in its pack with no bed block left and at most two blocks broken besides the bed, unhurt and in no liquid.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedTakeUpWalled", tickLimit = NIGHT + MORNING + 600)
    public void walledFromItsBedItOpensItsPocketAndTakesItsBedBackUp(TestContext context) {
        morning(context, "Waller", Morning.WALLED);
    }

    /**
     * In plain terms: as above, but with lava behind its pocket's wall: it does not break the wall and let the lava out; its
     * bed's chore is set aside instead of tried again every tick.
     *
     * Technical: F-25c4di1g. STAGED as the walled case, with a lava source shut in the rock beside the wall's top block,
     * which `RoamingEdits.breakDenial` refuses to break ("Breaking it would release lava"). Fails unless, within
     * {@value #MORNING} ticks, its bed's chore is set aside, the wall's top block still stone and the bed still down,
     * unhurt and in no liquid.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "bedTakeUpLava", tickLimit = NIGHT + MORNING + 600)
    public void walledFromItsBedByLavaItSetsTheChoreAside(TestContext context) {
        morning(context, "Lavaside", Morning.WALLED_LAVA);
    }
}
