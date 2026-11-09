package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** A frame's mould refused with nothing placed is placed on the next try, not passed over for two minutes (F-25c4ct). */
public final class NativeCastRefusalGameTests implements FabricGameTest {
    /**
     * The scene of {@code NativePortalCampaignGameTests.underThickRockTheFramesTopIsCastWithItsWaterTakenBack}: a cast
     * frame's room under rock five thick, with lava in its floor and the cast route's kit. On a CI run of #51 (8 November)
     * its first mould's placement was refused with nothing placed, most likely as a drop of the blocks it had cleared came
     * into its inventory while the placement was being recorded. The mould's cell was then set aside for 2,400 ticks: the
     * cast stepped between two stands for it 267 times until it could be placed, and after a second refusal its watchdog
     * moved it away from the frame, with no way back. Here the refusal is forced: while the first mould's placement is
     * being recorded, the bread in its inventory is moved to another slot, so the inventory no longer matches what the
     * placement recorded. Nothing is given or taken. Passes once the refused mould is placed within 300 ticks of the
     * refusal.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castRefusal", tickLimit = 2400)
    public void aMouldRefusedWithNothingPlacedIsPlacedOnTheNextTry(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        BlockPos[] mould = new BlockPos[1];
        java.util.UUID[] forced = new java.util.UUID[1];
        long[] refusedAt = {-1};
        StringBuilder seen = new StringBuilder();
        String[] last = {""};
        Runnable watch = () -> {
            var bots = f.runtime.panelBots(Actor.console());
            String progress = bots.isEmpty() ? "" : bots.getFirst().goalProgress();
            if (progress != null && !progress.equals(last[0])) {
                last[0] = progress;
                if (seen.length() < 4000) seen.append(progress.replaceAll("^.* - ", "")).append("; ");
            }
            var world = context.getWorld();
            var status = f.runtime.nativeActionStatus(Actor.console(), f.id);
            ActionRecord record = status == null ? null : status.record();
            boolean current = record != null && forced[0] != null && record.actionId().equals(forced[0]) && !record.state().terminal();
            if (forced[0] != null && refusedAt[0] < 0 && !current) {
                // The forced placement is over: refused with its cell still open, or (forced too late) placed.
                if (world.getBlockState(mould[0]).isReplaceable()) {
                    refusedAt[0] = world.getTime();
                } else {
                    forced[0] = null; // Missed this one: the next mould's placement is forced instead.
                }
            }
            if (forced[0] == null && refusedAt[0] < 0 && record != null && "native_place".equals(record.kind())
                    && ("PREPARING".equals(status.phase()) || "DISPATCHING".equals(status.phase()))
                    && progress != null && progress.contains("Cast: mould at")) {
                var at = java.util.regex.Pattern.compile("pos=(-?\\d+), (-?\\d+), (-?\\d+)").matcher(record.preconditions());
                if (at.find()) {
                    mould[0] = new BlockPos(Integer.parseInt(at.group(1)), Integer.parseInt(at.group(2)), Integer.parseInt(at.group(3)));
                    var inventory = f.player().getInventory();
                    ItemStack bread = inventory.getStack(3);
                    inventory.setStack(3, inventory.getStack(35));
                    inventory.setStack(35, bread); // Staged: the inventory changes as the placement is recorded.
                    forced[0] = record.actionId();
                }
            }
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Deepcaster"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    feet[0] = p;
                    FixtureGround.keep(context, p.add(-12, -2, -7), p.add(6, 8, 6));
                    for (int x = -12; x <= 6; x++) for (int z = -7; z <= 6; z++) {
                        boolean edge = x >= -6 && (Math.abs(x) == 6 || z == -7 || z == 6);
                        boolean rock = x >= -6;
                        world.setBlockState(p.add(x, -2, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 8; y++) {
                            boolean solid = edge && y <= 3 || rock && y >= 4;
                            world.setBlockState(p.add(x, y, z), solid ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    for (int[] g : new int[][] {{-5, -6}, {5, -6}, {-5, 5}, {5, 5}}) world.setBlockState(p.add(g[0], 4, g[1]), Blocks.GLOWSTONE.getDefaultState());
                    for (int x = -3; x <= 3; x++) for (int z = 3; z <= 4; z++) world.setBlockState(p.add(x, -1, z), Blocks.LAVA.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() - 3.5, p.getY(), p.getZ() + 2.5, 0, 0);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertTrue(refusedAt[0] >= 0, "A mould's placement refused, the inventory changed as it was recorded: " + seen);
                })
                .createAndAdd(() -> {
                    watch.run();
                    long since = context.getWorld().getTime() - refusedAt[0];
                    context.assertTrue(since <= 300, "The mould at " + mould[0].toShortString() + " not placed within 300 ticks of its refusal: " + seen);
                    context.assertFalse(context.getWorld().getBlockState(mould[0]).isReplaceable(), "The refused mould placed again: " + seen);
                    KindredMod.LOGGER.info("Cast refusal: the mould at {} placed {} ticks after its refusal; {}", mould[0].toShortString(), since, seen);
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
