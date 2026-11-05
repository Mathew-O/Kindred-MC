package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

/**
 * In plain terms: a GameTest: in a real test world, a player shut in a small pen with a jobless villager puts
 * down a lectern so the villager becomes a librarian, looks at its offers without trading and, if they are
 * not worth keeping, breaks and replaces the lectern to get new ones. The test checks the player either saw
 * the offers change or kept the first librarian, finished by its own rule (keeping the librarian, or taking
 * its lectern back and saying why), and never traded.
 *
 * Technical: A librarian of its own (E-45c): a villager with no job and Hal in one small pen, walled so that
 * the villager stays near, Hal holding a lectern. Hal puts the lectern down beside the villager; the villager
 * takes it up, by vanilla's own rules, and is a librarian; Hal looks at its offers, never trading; not worth
 * keeping, it breaks the lectern, takes it back, and puts it down again once the villager has let the job go,
 * which rerolls its offers. What the librarian offers is vanilla's draw, so the fixture asks for a reroll
 * seen (two looks at different offers) or a librarian kept at the first look; then for its own end, kept or
 * given up, and what that leaves; and that no trade was ever made.
 */
public final class NativeRerollGameTests implements FabricGameTest {
    private static final BlockPos VILLAGER = new BlockPos(8, 2, 5), HAL = new BlockPos(7, 2, 6);

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "reroll", tickLimit = 7000)
    public void aJoblessVillagersLecternIsRerolledUntilItsOffersAreWorthKeeping(TestContext context) {
        reroll(context, false);
    }

    /**
     * Its first lectern's placement held on its acknowledgement while something stands on the spot, the spot clear again
     * before it goes ahead: the lectern goes down where it was placed, and the reroll goes on to its own end (F-22c4).
     * The candidates are looked at again while a placement waits; with the spot taken then, the spot moved to another
     * side, the lectern went down on the first, and with none held and none where it looked, the player let the villager
     * be, neither kept nor given up ("Ended by its own rule: null", CI run 388, where it had waited on a slow write).
     * Staged as the other, with an armor stand set on the spot for 40 ticks while its placement's admission is held.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "rerollHeldPlace", tickLimit = 7000)
    public void aLecternPutDownWhereItsSpotWasTakenMeanwhileIsTheOneItRerolls(TestContext context) {
        reroll(context, true);
    }

    private static void reroll(TestContext context, boolean holdFirstPlace) {
        var held = new java.util.concurrent.CompletableFuture<Void>();
        BlockPos[] heldAt = new BlockPos[1];
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> !holdFirstPlace ? writer : new dev.kindred.core.ActionAdmission.Writer() {
            @Override public java.util.concurrent.CompletableFuture<ActionRecord> prepare(ActionRecord intent) {
                var pos = java.util.regex.Pattern.compile("block=minecraft:lectern; pos=(-?\\d+), (-?\\d+), (-?\\d+)").matcher(intent.preconditions());
                if (!"native_place".equals(intent.kind()) || heldAt[0] != null || !pos.find()) return writer.prepare(intent);
                heldAt[0] = new BlockPos(Integer.parseInt(pos.group(1)), Integer.parseInt(pos.group(2)), Integer.parseInt(pos.group(3)));
                return held.thenCompose(ignored -> writer.prepare(intent));
            }
            @Override public java.util.concurrent.CompletableFuture<ActionRecord> transition(ActionRecord next, long expectedRevision) {
                return writer.transition(next, expectedRevision);
            }
        }, true, 6900);
        VillagerEntity[] villager = new VillagerEntity[1];
        net.minecraft.entity.decoration.ArmorStandEntity[] stand = new net.minecraft.entity.decoration.ArmorStandEntity[1];
        long[] standSince = {-1};
        if (holdFirstPlace) {
            // The spot taken while the placement waits, and clear again before it goes ahead.
            FixtureGround.whenDone(context, () -> held.complete(null));
            context.runAtEveryTick(() -> {
                if (heldAt[0] == null || held.isDone()) return;
                var world = context.getWorld();
                if (stand[0] == null) {
                    stand[0] = new net.minecraft.entity.decoration.ArmorStandEntity(world, heldAt[0].getX() + 0.5, heldAt[0].getY(), heldAt[0].getZ() + 0.5);
                    stand[0].setNoGravity(true);
                    world.spawnEntity(stand[0]);
                    standSince[0] = world.getTime();
                } else if (world.getTime() - standSince[0] >= 40) {
                    stand[0].discard();
                    held.complete(null);
                }
            });
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    FixtureGround.simulated(context, o, o.add(14, 0, 12));
                    for (int x = 0; x <= 14; x++) for (int z = 0; z <= 12; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // A pen three by three, walled round three high: a lectern by a wall is a step for a villager to jump it from,
                    // a fence or a wall two high.
                    for (int x = 6; x <= 10; x++) for (int z = 3; z <= 7; z++) {
                        if (x == 6 || x == 10 || z == 3 || z == 7) {
                            for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, z), Blocks.GLASS.getDefaultState());
                        }
                    }
                    world.setTimeOfDay(1000);
                    var jobless = EntityType.VILLAGER.create(world);
                    BlockPos at = context.getAbsolutePos(VILLAGER);
                    jobless.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    jobless.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
                    world.spawnEntity(jobless);
                    villager[0] = jobless;
                    var hal = f.player();
                    BlockPos a = context.getAbsolutePos(HAL);
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    var inventory = hal.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(1, new ItemStack(Items.LECTERN));
                    hal.setHealth(20);
                    hal.getHungerManager().setFoodLevel(20);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> {
                    var looks = f.runtime.rerollLooks(f.id);
                    boolean rerolled = looks.size() >= 2 && looks.stream().distinct().count() >= 2;
                    boolean kept = f.runtime.rerollsKept(f.id) == 1;
                    context.assertTrue(rerolled || kept, "Rerolled, or kept at once: " + looks + " / " + f.runtime.reroll(f.id)
                            + " / " + f.runtime.nativeLastResult(f.id));
                })
                // And to its own end: kept, the lectern standing and its librarian at it; or given up, the lectern taken back.
                .createAndAdd(() -> context.assertTrue(f.runtime.rerollsKept(f.id) + f.runtime.rerollsGivenUp(f.id) == 1,
                        "Ended by its own rule: " + f.runtime.reroll(f.id)))
                .createAndAdd(() -> {
                    var librarian = villager[0];
                    var inventory = f.player().getInventory();
                    int lecterns = 0;
                    for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(Items.LECTERN)) lecterns += inventory.getStack(slot).getCount();
                    if (f.runtime.rerollsKept(f.id) == 1) {
                        context.assertTrue(lecterns == 0 && librarian.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN,
                                "Kept: the lectern down and its librarian at it");
                        context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("This librarian sells ")), "Said: " + f.runtime.said(f.id));
                    } else {
                        context.assertTrue(lecterns == 1, "Given up: the lectern taken back, " + lecterns);
                        context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("No book worth keeping from this librarian in ")
                                || line.equals("The villager won't take up my lectern; I've taken it back.")), "Said why: " + f.runtime.said(f.id));
                    }
                    KindredMod.LOGGER.info("Reroll ended after {} looks: {}", f.runtime.rerollLooks(f.id).size(), f.runtime.rerollLooks(f.id));
                    context.assertTrue(librarian.getExperience() == 0, "Never traded with: its offers its own to reroll");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    librarian.discard();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
