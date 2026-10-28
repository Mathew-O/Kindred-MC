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
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
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
}
