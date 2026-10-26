package dev.kindred.fabric;

import dev.kindred.core.construction.Placement;
import dev.kindred.core.construction.TradingHall;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.util.TreeMap;

/**
 * The trading hall (E-45d): a hall of two cells built by Mason from exactly the blocks it takes, every block in its
 * state, its cells and corridor clear; then a librarian in a cell, its lectern across the cell's front, kept in by the
 * cell and keeping its profession, as the hall is meant to hold it. The villager is set in its cell by the fixture:
 * bringing one there is E-45e's.
 */
public final class NativeTradingHallGameTests implements FabricGameTest {
    private static void check(TestContext context, String[] first, boolean ok, String message) {
        if (ok) return;
        if (first[0] == null) first[0] = message;
        context.assertTrue(false, first[0]);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tradingHall", tickLimit = 16000)
    public void aHallOfCellsIsBuiltExactAndHoldsALibrarianAtItsLectern(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 15500);
        String[] first = {null};
        var hall = TradingHall.of(2, "oak", "cobblestone");
        var needed = new TreeMap<String, Integer>();
        // A door's upper half and a bed's head come with their partner's item, as the bill of materials counts them.
        for (var b : hall.blocks()) if (Placement.rule(b.spec()) != Placement.Rule.COMPANION) needed.merge(Placement.item(b.spec()), 1, Integer::sum);
        BlockPos[] origin = new BlockPos[1];
        VillagerEntity[] villager = new VillagerEntity[1];
        int[] waited = {0}, ticks = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -8; x <= 18; x++) for (int z = -8; z <= 18; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 14; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.BREAD, 8));
                    for (var item : needed.entrySet()) {
                        int left = item.getValue();
                        while (left > 0) {
                            int count = Math.min(64, left);
                            inventory.setStack(slot++, new ItemStack(Registries.ITEM.get(Identifier.of(item.getKey())), count));
                            left -= count;
                        }
                    }
                    inventory.setStack(slot, new ItemStack(Items.DIRT, 64));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 12.5, 0, 0);
                    origin[0] = o.add(4, 2, 4);
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, hall, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int wrong = 0;
                    var report = new StringBuilder();
                    for (var b : hall.placed(BuildRunner.pos(origin[0]), 0)) {
                        var got = BuildRunner.spec(world.getBlockState(BuildRunner.blockPos(b.at())));
                        if (!got.sameAs(b.spec())) {
                            wrong++;
                            if (report.length() < 600) report.append(b.at()).append(' ').append(got).append(" for ").append(b.spec()).append("; ");
                        }
                    }
                    check(context, first, wrong == 0, wrong + " blocks not in their state: " + report);
                    for (var p : hall.placedClear(BuildRunner.pos(origin[0]), 0)) {
                        var state = world.getBlockState(BuildRunner.blockPos(p));
                        check(context, first, state.getCollisionShape(world, BuildRunner.blockPos(p)).isEmpty(), "Kept clear: " + p + " holds " + state);
                    }
                    // A villager with no job set in the first cell, on its floor before its bed, and a lectern across the cell's front.
                    var cell = TradingHall.cells(2).getFirst();
                    BlockPos stand = origin[0].add(cell.stand().x(), cell.stand().y(), cell.stand().z());
                    BlockPos job = origin[0].add(cell.jobBlock().x(), cell.jobBlock().y(), cell.jobBlock().z());
                    world.setBlockState(job, Blocks.LECTERN.getDefaultState());
                    var librarian = EntityType.VILLAGER.create(world);
                    librarian.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    librarian.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
                    world.spawnEntity(librarian);
                    villager[0] = librarian;
                })
                .createAndAdd(() -> {
                    if (++ticks[0] % 200 == 0) KindredMod.LOGGER.info("Trading hall: the villager at {}, {}, after {} ticks",
                            villager[0].getBlockPos().subtract(origin[0]).toShortString(), villager[0].getVillagerData().getProfession(), ticks[0]);
                    context.assertTrue(villager[0].getVillagerData().getProfession() == VillagerProfession.LIBRARIAN,
                            "Took up its lectern: " + villager[0].getVillagerData().getProfession());
                })
                .createAndAdd(() -> {
                    var cell = TradingHall.cells(2).getFirst();
                    BlockPos stand = origin[0].add(cell.stand().x(), 0, cell.stand().z());
                    var at = villager[0].getBlockPos();
                    if (waited[0] == 0) KindredMod.LOGGER.info("Trading hall: a librarian after {} ticks", ticks[0]);
                    if (waited[0] % 100 == 0) KindredMod.LOGGER.info("Trading hall: the librarian at {} ({}), {} ticks on",
                            villager[0].getPos().subtract(origin[0].getX(), origin[0].getY(), origin[0].getZ()), villager[0].getVillagerData().getProfession(), waited[0]);
                    // Its floor or its bed: the cell is a block wide, its bed the two behind its floor. Once out, the test has failed.
                    check(context, first, first[0] == null && at.getX() == stand.getX() && at.getZ() >= stand.getZ() - 2 && at.getZ() <= stand.getZ(),
                            "Kept in its cell: at " + at.subtract(origin[0]).toShortString() + " after " + waited[0] + " ticks");
                    check(context, first, villager[0].getVillagerData().getProfession() == VillagerProfession.LIBRARIAN, "Still a librarian");
                    waited[0]++;
                    context.assertTrue(waited[0] >= 600, "Thirty seconds in its cell");
                })
                .createAndAdd(() -> {
                    context.assertTrue(first[0] == null && f.player() != null, first[0] == null ? "Mason still here" : first[0]);
                    var inventory = f.player().getInventory();
                    var left = new TreeMap<String, Integer>();
                    for (var item : needed.keySet()) {
                        int count = inventory.count(Registries.ITEM.get(Identifier.of(item)));
                        if (count != 0) left.put(item, count);
                    }
                    check(context, first, left.isEmpty(), "Every block it was given went into the hall: left " + left);
                    villager[0].discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
