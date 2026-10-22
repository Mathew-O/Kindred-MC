package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * The dragon goal past the End portal (E-39). Fixtures build what the player has reached by then (a portal room's frame
 * ring, an island's pillar and crystal) and hand over what it would carry; every step goes through the goal, the paid
 * activity and the ledger as in a run.
 */
public final class NativeEndGameTests implements FabricGameTest {
    /**
     * A portal room's ring: twelve frames round a 3x3 well at x 3..5, z 3..5, each facing in, all but the north three
     * holding an eye. Returns the north-west frame.
     */
    static BlockPos ring(TestContext context) {
        var world = context.getWorld();
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
            world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE_BRICKS.getDefaultState());
            for (int y = 2; y <= 6; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), Blocks.AIR.getDefaultState());
        }
        for (int i = 3; i <= 5; i++) {
            frame(context, new BlockPos(i, 2, 2), Direction.SOUTH, false);
            frame(context, new BlockPos(i, 2, 6), Direction.NORTH, true);
            frame(context, new BlockPos(2, 2, i), Direction.EAST, true);
            frame(context, new BlockPos(6, 2, i), Direction.WEST, true);
        }
        return context.getAbsolutePos(new BlockPos(3, 2, 2));
    }

    private static void frame(TestContext context, BlockPos at, Direction facing, boolean eye) {
        context.getWorld().setBlockState(context.getAbsolutePos(at), Blocks.END_PORTAL_FRAME.getDefaultState()
                .with(EndPortalFrameBlock.FACING, facing).with(EndPortalFrameBlock.EYE, eye));
    }

    /** What the player remembers of the way here, as a restart would recall it: its portals and the End portal. */
    static void remember(NativePaidSkillsGameTests.Fixture f, BlockPos endPortal) {
        var memory = new PlayerMemoryStore.Memory();
        memory.overworldPortal = new PlayerMemoryStore.Place("minecraft:overworld", endPortal.getX() - 500, endPortal.getY() + 60, endPortal.getZ());
        memory.endPortal = new PlayerMemoryStore.Place("minecraft:overworld", endPortal.getX(), endPortal.getY(), endPortal.getZ());
        memory.stronghold = new double[] {endPortal.getX() + 0.5, endPortal.getZ() + 0.5};
        memory.pinned = true;
        try {
            var folder = f.directory.resolve("memory");
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve(f.id + ".json"), new com.google.gson.Gson().toJson(memory));
        } catch (java.io.IOException failed) {
            throw new AssertionError("Memory fixture could not be written", failed);
        }
    }

    /**
     * At the portal room with four eyes, the ring three short: it puts an eye into each empty frame, the portal opens,
     * and it steps in and comes out in the End. Exactly three eyes are spent.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endEntry", tickLimit = 1600)
    public void theRingIsFilledAndTheEndEntered(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] first = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Seeker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    first[0] = ring(context);
                    remember(f, first[0]);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(4, 2, 0));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.ENDER_EYE, 4));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 16));
                    context.getWorld().setTimeOfDay(1000);
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.DRAGON) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getWorld().getRegistryKey() == World.END, "Into the End: "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var world = context.getWorld();
                    int filled = 0;
                    for (BlockPos at : BlockPos.iterate(context.getAbsolutePos(new BlockPos(2, 2, 2)), context.getAbsolutePos(new BlockPos(6, 2, 6)))) {
                        var state = world.getBlockState(at);
                        if (state.isOf(Blocks.END_PORTAL_FRAME) && state.get(EndPortalFrameBlock.EYE)) filled++;
                    }
                    KindredMod.LOGGER.info("Seeker: in the End at {}, frames filled {}, eyes left {}", player.getBlockPos(), filled,
                            player.getInventory().count(Items.ENDER_EYE));
                    context.assertTrue(filled == 12, "The ring is full: " + filled);
                    context.assertTrue(player.getInventory().count(Items.ENDER_EYE) == 1, "Three eyes went in, one is left");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Without the server's leave, the dragon goal is refused, and the End-portal goal stops at the portal. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endEntry", tickLimit = 200)
    public void withoutTheServersLeaveTheDragonGoalIsRefused(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waiter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    context.assertFalse(f.runtime.dragonFightAllowed(), "Off by default");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.DRAGON) == 0, "Refused while the fight is off");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** The test server ticks no entity but players outside the test's own chunks; arrows and crystals need theirs. */
    static void forceChunks(net.minecraft.server.world.ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 16; x <= o.getX() + 48; x += 16) for (int z = o.getZ() - 32; z <= o.getZ() + 32; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    /**
     * An End crystal on its pillar: obsidian three wide and sixteen high, a bedrock block on top and the crystal on it,
     * twenty-one blocks off across open ground. With a bow and eight arrows the player shoots it down from the ground,
     * an arrow a shot, each on the arc vanilla's arrow flies; the crystal's blast is far from it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endCrystal", tickLimit = 1200)
    public void aCrystalOnItsPillarIsShotDownFromTheGround(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.decoration.EndCrystalEntity[] crystal = new net.minecraft.entity.decoration.EndCrystalEntity[1];
        int[] shots = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Archer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    forceChunks(world, o, true);
                    for (int x = -4; x <= 28; x++) for (int z = -6; z <= 6; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.END_STONE.getDefaultState());
                        for (int y = 2; y <= 24; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 20; x <= 22; x++) for (int z = -1; z <= 1; z++) for (int y = 2; y <= 16; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.OBSIDIAN.getDefaultState());
                    }
                    world.setBlockState(o.add(21, 17, 0), Blocks.BEDROCK.getDefaultState());
                    crystal[0] = net.minecraft.entity.EntityType.END_CRYSTAL.create(world);
                    crystal[0].refreshPositionAndAngles(o.getX() + 21.5, o.getY() + 18, o.getZ() + 0.5, 0, 0);
                    crystal[0].setShowBottom(true);
                    world.spawnEntity(crystal[0]);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 0.5, -90, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BOW));
                    inventory.setStack(1, new ItemStack(Items.ARROW, 8));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the ground"))
                .createAndAdd(() -> {
                    // A shot at a time; another after each that missed, five at most. Only with nothing running (idle, or
                    // not yet loaded before the first request), and counted only when taken: a request while the last is
                    // still reserving its slice is refused, and counting it made two shots of one arrow.
                    if (!crystal[0].isAlive()) return;
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if ((activity.phase().equals("IDLE") || activity.phase().equals("UNLOADED")) && shots[0] < 5 && f.runtime.nativeShoot(f.source, f.id, 0, crystal[0].getUuid()) == 1) {
                        shots[0]++;
                    }
                    context.assertTrue(false, "Shooting: " + shots[0] + " shots, " + activity + " " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int arrows = player.getInventory().count(Items.ARROW);
                    KindredMod.LOGGER.info("Archer: crystal down after {} shots, arrows left {}, health {}", shots[0], arrows, player.getHealth());
                    context.assertTrue(!crystal[0].isAlive(), "The crystal is down");
                    context.assertTrue(arrows == 8 - shots[0], "An arrow a shot, none conjured: " + arrows + " left after " + shots[0]);
                    context.assertTrue(player.getHealth() >= 20, "Far from the blast");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_shoot") && record.state() == ActionRecord.State.VERIFIED,
                            "A verified shot: " + record);
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 28; x++) for (int z = -6; z <= 6; z++) for (int y = 2; y <= 24; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState(), net.minecraft.block.Block.FORCE_STATE);
                    }
                    forceChunks(world, o, false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Struck while breaking a block (the dragon's hits on its armour as it broke a cage's bars, staged dragon run 2): the
     * break stopped part way is settled as having done nothing, the armour's wear no lost item, and the player is not
     * blocked for reconciliation.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endWear", tickLimit = 400)
    public void wornArmourMidBreakIsNoLostItem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos obsidian = context.getAbsolutePos(new BlockPos(5, 2, 3));
        int[] mined = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ward"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(obsidian, Blocks.OBSIDIAN.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    f.player().getInventory().armor.set(3, new ItemStack(Items.IRON_HELMET));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeBreak(f.source, f.id, obsidian) == 1, "A long obsidian break is requested");
                })
                .createAndAdd(() -> context.assertTrue(++mined[0] >= 30, "Mining for a while"))
                .createAndAdd(() -> {
                    // A blow on the helmet, as vanilla wears armour that takes a hit, then the break is stopped.
                    var helmet = f.player().getInventory().armor.get(3);
                    helmet.setDamage(helmet.getDamage() + 6);
                    f.runtime.nativeLifecycle(f.source, "stop", f.id);
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getWorld().getBlockState(obsidian).isOf(Blocks.OBSIDIAN), "The unfinished break changed nothing");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_break") && record.state() != ActionRecord.State.UNCERTAIN,
                            "The stopped break settled without ambiguity: " + record);
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.phase() == dev.kindred.persistence.StoredNativePlayer.Phase.ESTABLISHED && status.connected(),
                            "Still established and connected, not blocked for reconciliation: " + status);
                    context.assertTrue(f.player().getInventory().armor.get(3).isOf(Items.IRON_HELMET), "The worn helmet is still worn");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * An ender pearl thrown up onto a ledge (E-39f): a 3×3 stone platform eight blocks up and twelve across. The throw
     * is chosen by following the pearl through the blocks as vanilla flies it, so that throws a degree off land there
     * too; the recorded throw spends exactly the one pearl, vanilla puts the player on the platform and takes five health.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endPearl", tickLimit = 600)
    public void aPearlIsThrownUpOntoALedgeAndLandsThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] ledge = new BlockPos[1];
        PearlFlight.Throw[] planned = new PearlFlight.Throw[1];
        boolean[] asked = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Leaper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    forceChunks(world, o, true);
                    for (int x = -2; x <= 18; x++) for (int z = -4; z <= 4; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 16; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 13; x <= 15; x++) for (int z = -1; z <= 1; z++) world.setBlockState(o.add(x, 9, z), Blocks.STONE.getDefaultState());
                    ledge[0] = o.add(14, 10, 0);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 2.5, o.getY() + 2, o.getZ() + 0.5, -90, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.ENDER_PEARL, 4));
                    player.setHealth(20);
                    // Below vanilla's regeneration (food 18), so the landings' damage shows in the health left.
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the ground"))
                .createAndAdd(() -> {
                    var player = f.player();
                    planned[0] = PearlFlight.toward(player, player.getPos(), ledge[0], 1.5).orElse(null);
                    context.assertTrue(planned[0] != null, "A steady throw onto the ledge is found");
                    context.assertTrue(f.runtime.nativePearl(f.source, f.id, 0, planned[0].yaw(), planned[0].pitch()) == 1, "Throw requested");
                    asked[0] = true;
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("Completed native_pearl"), "Landed: " + activity + " " + f.runtime.nativeLastResult(f.id));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Leaper: landed at {}, planned {}, health {}", player.getBlockPos().toShortString(),
                            planned[0].rest().toShortString(), player.getHealth());
                    context.assertTrue(player.getBlockY() == ledge[0].getY()
                            && Math.hypot(player.getBlockX() - ledge[0].getX(), player.getBlockZ() - ledge[0].getZ()) <= 1.5,
                            "On the ledge: " + player.getBlockPos().toShortString() + " for " + ledge[0].toShortString());
                    context.assertTrue(player.getInventory().count(Items.ENDER_PEARL) == 3, "One pearl spent, none conjured");
                    context.assertTrue(player.getHealth() <= 15.5 && player.getHealth() >= 14.5, "Five health for the landing: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_pearl") && record.state() == ActionRecord.State.VERIFIED,
                            "A verified throw: " + record);
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 18; x++) for (int z = -4; z <= 4; z++) for (int y = 2; y <= 16; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState(), net.minecraft.block.Block.FORCE_STATE);
                    }
                    forceChunks(world, o, false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A guarded End pillar as vanilla builds one: obsidian of radius two, a 5×5 cage of bars four high, bedrock and a crystal. */
    static net.minecraft.entity.decoration.EndCrystalEntity cagedPillar(net.minecraft.server.world.ServerWorld world, BlockPos center, int height) {
        for (BlockPos pos : BlockPos.iterate(center.add(-2, 0, -2), center.add(2, height - 1, 2))) {
            if (pos.getSquaredDistance(center.getX(), pos.getY(), center.getZ()) <= 5) world.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
        }
        int top = center.getY() + height;
        for (int m = -2; m <= 2; m++) for (int n = -2; n <= 2; n++) for (int o = 0; o <= 3; o++) {
            boolean edgeX = Math.abs(m) == 2, edgeZ = Math.abs(n) == 2, roof = o == 3;
            if (!edgeX && !edgeZ && !roof) continue;
            boolean alongZ = m == -2 || m == 2 || roof, alongX = n == -2 || n == 2 || roof;
            world.setBlockState(new BlockPos(center.getX() + m, top + o, center.getZ() + n), Blocks.IRON_BARS.getDefaultState()
                    .with(net.minecraft.block.PaneBlock.NORTH, alongZ && n != -2).with(net.minecraft.block.PaneBlock.SOUTH, alongZ && n != 2)
                    .with(net.minecraft.block.PaneBlock.WEST, alongX && m != -2).with(net.minecraft.block.PaneBlock.EAST, alongX && m != 2),
                    net.minecraft.block.Block.NOTIFY_LISTENERS);
        }
        var crystal = net.minecraft.entity.EntityType.END_CRYSTAL.create(world);
        crystal.refreshPositionAndAngles(center.getX() + 0.5, top + 1, center.getZ() + 0.5, 0, 0);
        world.spawnEntity(crystal);
        world.setBlockState(new BlockPos(center.getX(), top, center.getZ()), Blocks.BEDROCK.getDefaultState());
        return crystal;
    }

    /**
     * The pearl way into a cage (E-39f): from end stone twelve blocks off, a pearl up onto the cage's roof of bars, the
     * bars over the crystal broken without being picked up, a pearl back down to end stone, and the crystal shot on the
     * high arc down through the opened roof. Two pearls spent, ten health for the landings, and nothing else lost.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "endCage", tickLimit = 2400)
    public void aCageIsOpenedFromItsRoofByPearlAndTheCrystalShotThroughIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.decoration.EndCrystalEntity[] crystal = new net.minecraft.entity.decoration.EndCrystalEntity[1];
        int[] step = {0}, shots = {0};
        java.util.function.Predicate<BlockPos> open = pos -> false;
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Vaulter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    forceChunks(world, o, true);
                    for (int x = -14; x <= 38; x++) for (int z = -16; z <= 16; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.END_STONE.getDefaultState());
                        for (int y = 2; y <= 26; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    crystal[0] = cagedPillar(world, o.add(12, 2, 0), 14);
                    var player = f.player();
                    player.refreshPositionAndAngles(o.getX() + 0.5, o.getY() + 2, o.getZ() + 0.5, -90, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.ENDER_PEARL, 4));
                    inventory.setStack(1, new ItemStack(Items.BOW));
                    inventory.setStack(2, new ItemStack(Items.ARROW, 16));
                    inventory.setStack(3, new ItemStack(Items.DIAMOND_PICKAXE));
                    player.setHealth(20);
                    // Below vanilla's regeneration (food 18), so the landings' damage shows in the health left.
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "On the ground"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var up = EndFightLeg.roofThrow(player, player.getPos(), crystal[0], open);
                    context.assertTrue(up.isPresent(), "A steady throw onto the roof, with one back down from there");
                    context.assertTrue(f.runtime.nativePearl(f.source, f.id, 0, up.get().yaw(), up.get().pitch()) == 1, "Pearl up requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("Completed native_pearl") && f.player().isOnGround(), "Landed: " + activity);
                })
                .createAndAdd(() -> context.assertTrue(EndFightLeg.onRoof(f.player(), crystal[0]),
                        "On the cage's roof: " + f.player().getBlockPos().toShortString() + " over " + crystal[0].getBlockPos().toShortString()))
                .createAndAdd(() -> {
                    // The roof's bars over the crystal, one at a time, none it stands on.
                    var player = f.player();
                    var world = context.getWorld();
                    BlockPos at = crystal[0].getBlockPos();
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if (activity.phase().equals("IDLE") || activity.phase().equals("UNLOADED")) {
                        var under = player.getBoundingBox().expand(0.05, 0, 0.05).offset(0, -0.2, 0);
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                            BlockPos bar = at.add(dx, 2, dz);
                            if (!world.getBlockState(bar).isOf(Blocks.IRON_BARS) || new net.minecraft.util.math.Box(bar).intersects(under)
                                    || player.getEyePos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(bar)) > 4.4) continue;
                            f.runtime.nativeBreak(f.source, f.id, bar, false);
                            context.assertTrue(false, "Breaking " + bar.toShortString());
                        }
                    } else context.assertTrue(false, "Breaking: " + activity);
                    context.assertTrue(!world.getBlockState(at.add(0, 2, 0)).isOf(Blocks.IRON_BARS), "The bar over the crystal is gone");
                    context.assertTrue(EndFightLeg.onRoof(player, crystal[0]), "Still on the roof");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var down = EndFightLeg.downThrow(player, player.getPos(), crystal[0], open);
                    context.assertTrue(down.isPresent(), "A steady throw back down");
                    context.assertTrue(f.runtime.nativePearl(f.source, f.id, 0, down.get().yaw(), down.get().pitch()) == 1, "Pearl down requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.work().startsWith("Completed native_pearl") && f.player().isOnGround()
                            && f.player().getBlockY() == context.getAbsolutePos(BlockPos.ORIGIN).getY() + 2, "Down on the end stone: " + activity);
                })
                .createAndAdd(() -> {
                    // Shot on the arc that is clear through the opened roof (the high one, coming down, where the sides stand).
                    if (!crystal[0].isAlive()) return;
                    var player = f.player();
                    var arc = EndFightLeg.clearArc(context.getWorld(), player.getPos(), crystal[0].getBoundingBox().getCenter());
                    context.assertTrue(arc.isPresent(), "A clear arc through the opened roof");
                    if (shots[0] == 0) KindredMod.LOGGER.info("Vaulter: the clear arc is the {} one", arc.get() ? "high" : "low");
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    if ((activity.phase().equals("IDLE") || activity.phase().equals("UNLOADED")) && shots[0] < 6
                            && f.runtime.nativeShoot(f.source, f.id, 1, crystal[0].getUuid(), arc.get()) == 1) shots[0]++;
                    context.assertTrue(false, "Shooting through the roof: " + shots[0] + " shots");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Vaulter: crystal down after {} high shots; pearls {}, health {}", shots[0],
                            player.getInventory().count(Items.ENDER_PEARL), player.getHealth());
                    context.assertTrue(!crystal[0].isAlive(), "The crystal is down");
                    context.assertTrue(player.getInventory().count(Items.ENDER_PEARL) == 2, "Two pearls spent, none conjured");
                    context.assertTrue(player.getInventory().count(Items.ARROW) == 16 - shots[0], "An arrow a shot");
                    context.assertTrue(player.getHealth() >= 9.5 && player.getHealth() <= 10.5, "Ten health for two landings: " + player.getHealth());
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -14; x <= 38; x++) for (int z = -16; z <= 16; z++) for (int y = 2; y <= 26; y++) {
                        world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState(), net.minecraft.block.Block.FORCE_STATE);
                    }
                    forceChunks(world, o, false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
