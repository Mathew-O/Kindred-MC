package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Pets (E-37, E-36, E-38): with pets on, a player holding bones, a name tag, an anvil and a few levels, two wild wolves
 * in sight, tames one with its own bones (a bone a click, until it takes), leaves the other wild (one wolf each), puts
 * its anvil down, letters the name tag with a name of its own at it, and names its wolf with it. Only its bones, the
 * tag, the anvil and a level are spent. When its wolf dies, it says so.
 */
public final class NativePetGameTests implements FabricGameTest {
    private static WolfEntity wolf(TestContext context, BlockPos at) {
        var world = context.getWorld();
        // By hand, as the context's own spawn clears a mob's goals.
        var wolf = EntityType.WOLF.create(world);
        wolf.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        wolf.initialize(world, world.getLocalDifficulty(at), SpawnReason.MOB_SUMMONED, null);
        world.spawnEntity(wolf);
        return wolf;
    }

    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "pets", tickLimit = 8000)
    public void itTamesAWolfWithItsOwnBonesNamesItAndSaysWhenItDies(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 7800);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        UUID[] wolves = new UUID[2];
        int[] bones = {16};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    context.assertTrue(player.isOnGround(), "Standing");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(20, 16, 20));
                    for (int x = -12; x <= 20; x++) for (int z = -12; z <= 20; z++) {
                        for (int y = 0; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BONE, bones[0]));
                    inventory.setStack(1, new ItemStack(Items.NAME_TAG));
                    inventory.setStack(2, new ItemStack(Items.ANVIL));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    player.experienceLevel = 5;
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 4.5, o.getY() + 7, o.getZ() + 4.5, 0, 0);
                    wolves[0] = wolf(context, o.add(9, 7, 4)).getUuid();
                    wolves[1] = wolf(context, o.add(10, 7, 9)).getUuid();
                    context.assertTrue(f.runtime.setPets(f.source, true) == 1, "Pets on");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    if (world.getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Pet fixture: bones {}, tag {}, levels {}; {}", player.getInventory().count(Items.BONE),
                                player.getInventory().count(Items.NAME_TAG), player.experienceLevel, f.runtime.nativeLastResult(f.id));
                    }
                    int tamed = 0, named = 0;
                    for (UUID id : wolves) {
                        if (world.getEntity(id) instanceof WolfEntity wolf && wolf.isTamed()) {
                            context.assertTrue(player.getUuid().equals(wolf.getOwnerUuid()), "Tamed to it");
                            tamed++;
                            if (wolf.hasCustomName()) named++;
                        }
                    }
                    context.assertTrue(tamed == 1 && named == 1, "One wolf tamed and named: " + tamed + " / " + named);
                })
                // Its naming settled and said before anything befalls the wolf.
                .createAndAdd(() -> context.assertTrue(f.runtime.said(f.id).stream().anyMatch(line -> line.startsWith("My wolf is called "))
                        && f.runtime.nativeStatusLine(f.id).contains("actionPending=false"), "Naming settled and said: " + f.runtime.said(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    var inventory = player.getInventory();
                    WolfEntity own = null, other = null;
                    for (UUID id : wolves) {
                        var wolf = (WolfEntity) world.getEntity(id);
                        if (wolf.isTamed()) own = wolf; else other = wolf;
                    }
                    context.assertTrue(own != null && other != null && !other.isTamed(), "The other left wild");
                    int spent = bones[0] - inventory.count(Items.BONE);
                    context.assertTrue(spent >= 1 && inventory.count(Items.NAME_TAG) == 0 && inventory.count(Items.ANVIL) == 0
                            && inventory.count(Items.BREAD) <= 8 && player.experienceLevel >= 4, "Only bones, the tag, the anvil and a level spent: "
                            + inventory.main + ", level " + player.experienceLevel);
                    String name = own.getCustomName().getString();
                    KindredMod.LOGGER.info("Pet fixture: tamed with {} bones, named {}; said {}", spent, name, f.runtime.said(f.id));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    // Its wolf dies.
                    own.kill();
                })
                .createAndAdd(() -> {
                    var said = f.runtime.said(f.id);
                    context.assertTrue(said.stream().anyMatch(line -> line.startsWith("My wolf is called ")) && said.stream().noneMatch(line -> line.contains("my kit"))
                            && said.stream().anyMatch(line -> line.contains(" is dead")), "Said it named its wolf, and that it died: " + said);
                })
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Its anvil is put down where nothing stands (#74's CI, run 42556590202): its tamed wolf sat in the cell it chose
     * for the anvil, a placement the server refuses for the wolf in the way, and it chose that same cell again on every
     * decision until the test's time ran out. Staged: the wolf tamed to it by the fixture, sitting on the cell north of
     * its feet, the first it looks at.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "petsWolfInTheWay", tickLimit = 1600)
    public void itPutsItsAnvilDownBesideTheWolfSittingWhereItWouldHavePutIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        BlockPos o = context.getAbsolutePos(new BlockPos(12, 0, 12));
        UUID[] wolf = new UUID[1];
        BlockPos[] sat = new BlockPos[1];
        boolean[] moved = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    context.assertTrue(player.isOnGround(), "Standing");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-12, 0, -12), o.add(20, 16, 20));
                    for (int x = -12; x <= 20; x++) for (int z = -12; z <= 20; z++) {
                        for (int y = 0; y <= 5; y++) world.setBlockState(o.add(x, y, z), Blocks.DIRT.getDefaultState());
                        world.setBlockState(o.add(x, 6, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 7; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(1, new ItemStack(Items.NAME_TAG));
                    inventory.setStack(2, new ItemStack(Items.ANVIL));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    player.experienceLevel = 5;
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 4.5, o.getY() + 7, o.getZ() + 4.5, 0, 0);
                    // Fixture staging: its wolf, tamed to it, sitting where it would first put its anvil.
                    sat[0] = o.add(4, 7, 4).north();
                    var own = wolf(context, sat[0]);
                    own.setOwner(player);
                    own.setSitting(true);
                    own.setInSittingPose(true);
                    wolf[0] = own.getUuid();
                    context.assertTrue(f.runtime.setPets(f.source, true) == 1, "Pets on");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    var own = (WolfEntity) world.getEntity(wolf[0]);
                    context.assertTrue(own != null && own.isAlive(), "Its wolf");
                    if (!own.getBlockPos().equals(sat[0])) moved[0] = true;
                    if (world.getTime() % 100 == 0) {
                        KindredMod.LOGGER.info("Pet wolf-in-the-way fixture: anvil {}, tag {}, wolf at {} (sat at {}); {}", player.getInventory().count(Items.ANVIL),
                                player.getInventory().count(Items.NAME_TAG), own.getBlockPos().toShortString(), sat[0].toShortString(), f.runtime.nativeLastResult(f.id));
                    }
                    context.assertTrue(own.hasCustomName(), "Its wolf named");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    // The staging held: the wolf sat in the cell throughout, so the anvil went down beside it.
                    context.assertTrue(!moved[0], "The wolf sat where it was put until it was named");
                    context.assertTrue(world.getBlockState(sat[0]).isAir(), "Nothing put down on the wolf's cell: " + world.getBlockState(sat[0]));
                    int anvils = 0;
                    for (BlockPos at : BlockPos.iterate(o.add(-2, 6, -2), o.add(10, 9, 10))) {
                        if (world.getBlockState(at).isIn(net.minecraft.registry.tag.BlockTags.ANVIL)) anvils++;
                    }
                    context.assertTrue(anvils == 1 && player.getInventory().count(Items.ANVIL) == 0 && player.getInventory().count(Items.NAME_TAG) == 0,
                            "Its anvil down beside the wolf and the tag used: " + anvils + " anvils down, " + player.getInventory().main);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> f.runtime.stop())
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
