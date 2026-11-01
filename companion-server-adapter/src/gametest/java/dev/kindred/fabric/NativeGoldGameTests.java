package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * In plain terms: a GameTest: in a real test world, a player in iron with no gold, placed in a room in the
 * Nether on its way to the End, is checked to mine the Nether gold ore in the wall, make the nuggets into a
 * golden helmet and wear it, keeping its iron helmet; out of the Nether it puts the iron helmet back on.
 * Further tests check that a piglin leaves a player wearing gold alone and the player never moves to strike
 * it, that a piglin goes for a player in iron and is fought (the control), and that no gold ore is mined
 * while a piglin is near enough to be angered by it.
 *
 * Technical: A piece of gold for the Nether (E-07e). Gold armour keeps piglins neutral: one piece is enough.
 * On the End goal in the Nether with no gold at all, wearing iron, the player finds Nether gold ore in the
 * wall of the room it stands in. It mines it with its own pickaxe (two to six nuggets a block), makes the
 * nuggets into ingots at its own crafting table, crafts a golden helmet (five ingots, as good as iron's) and
 * swaps it for the iron helmet it wore. Nothing is given: every gold nugget comes from the ore.
 */
public final class NativeGoldGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "goldForPiglins", tickLimit = 4000)
    public void inTheNetherAPieceOfGoldIsMadeFromTheOreAndWorn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3800);
        int[] oreBefore = {0};
        BlockPos[] room = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Goldsmith"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    room[0] = netherRoom(context, f, true, Items.IRON_HELMET);
                    oreBefore[0] = ore(f.player().getServerWorld(), room[0]);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getServerWorld().getRegistryKey() == World.NETHER, "In the Nether");
                    context.assertTrue(player.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.GOLDEN_HELMET), "A golden helmet worn: "
                            + player.getEquippedStack(EquipmentSlot.HEAD) + "; nuggets " + player.getInventory().count(Items.GOLD_NUGGET)
                            + ", ingots " + player.getInventory().count(Items.GOLD_INGOT) + " / "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    ServerWorld nether = player.getServerWorld();
                    int ore = ore(nether, room[0]);
                    KindredMod.LOGGER.info("Goldsmith: wears {}; iron helmet carried {}; mined {} of {} ore; nuggets {}, ingots {}; {}",
                            player.getEquippedStack(EquipmentSlot.HEAD), player.getInventory().count(Items.IRON_HELMET), oreBefore[0] - ore, oreBefore[0],
                            player.getInventory().count(Items.GOLD_NUGGET), player.getInventory().count(Items.GOLD_INGOT),
                            f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    context.assertTrue(player.getInventory().count(Items.IRON_HELMET) == 1, "The iron helmet it wore is carried, not lost");
                    context.assertTrue(oreBefore[0] - ore >= 2, "The gold came from the ore it mined: " + (oreBefore[0] - ore));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    forced(context.getWorld().getServer().getWorld(World.NETHER), room[0], false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
    /**
     * Out of the Nether the iron helmet goes back on (E-07e): it guards as well as the golden one and lasts twice as
     * long, which tells in the End. Wearing gold in the Overworld with its iron helmet carried, the player swaps them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "goldOff", tickLimit = 800)
    public void outOfTheNetherTheIronHelmetGoesBackOn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Homecomer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(12, new ItemStack(Items.IRON_HELMET));
                    player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET),
                        "The iron helmet back on: " + f.player().getEquippedStack(EquipmentSlot.HEAD)))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.getInventory().count(Items.GOLDEN_HELMET) == 1, "The golden helmet carried for the next trip");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A piglin leaves a player wearing gold alone (E-07e), and the player never offers to strike it: one struck would
     * turn every piglin about on the player. In the Nether with a golden helmet on and no goal, the player has a piglin
     * three blocks off for three hundred of the piglin's ticks. Its control, {@link #withoutGoldAPiglinGoesForThePlayer},
     * wears iron.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "piglinGold", tickLimit = 2400)
    public void aPiglinLeavesAPlayerWearingGoldAlone(TestContext context) {
        piglinMeets(context, true);
    }

    /** The control: wearing iron, the same piglin goes for the player, as vanilla's do, and the player fights back. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "piglinIron", tickLimit = 2400)
    public void withoutGoldAPiglinGoesForThePlayer(TestContext context) {
        piglinMeets(context, false);
    }

    /**
     * Puts the player in a Nether room in a golden or iron helmet with a piglin three blocks off, and watches
     * how each treats the other.
     */
    private static void piglinMeets(TestContext context, boolean gold) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2200);
        net.minecraft.entity.mob.PiglinEntity[] piglin = new net.minecraft.entity.mob.PiglinEntity[1];
        BlockPos[] room = new BlockPos[1];
        int[] after = {0}, listed = {0}, offered = {0}, hits = {0};
        String name = gold ? "Gilded" : "Ungilded";
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, name))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> room[0] = netherRoom(context, f, false, gold ? Items.GOLDEN_HELMET : Items.IRON_HELMET))
                // Mobs move only on ground the server ticks entities on. A spot just reached far out in the Nether waits
                // for the chunks about it to generate (the other fixtures' exploring keeps the generator busy).
                .createAndAdd(() -> context.assertTrue(f.player().getServerWorld().shouldTickEntity(room[0].add(3, 1, 0)), "The ground ticking"))
                .createAndAdd(() -> piglin[0] = piglin(f.player().getServerWorld(), room[0].add(3, 1, 0)))
                .createAndAdd(() -> {
                    var player = f.player();
                    if (player.hurtTime == 9) hits[0]++;
                    if (piglin[0].isAlive() && piglin[0].getTarget() == player) after[0]++;
                    var seen = NativeTacticalObserver.observe(player, f.id, 0, false, false, context.getWorld().getTime());
                    if (seen.observedMobs().stream().anyMatch(mob -> mob.entityId().equals(piglin[0].getUuid()))) listed[0]++;
                    if (f.runtime.nativeCandidates(Actor.console(), f.id).stream().anyMatch(candidate -> "native_attack".equals(candidate.skill())
                            && piglin[0].getUuid().toString().equals(candidate.arguments().get("target")))) offered[0]++;
                    if (gold) context.assertTrue(piglin[0].getHealth() >= piglin[0].getMaxHealth(), "The piglin was hurt: "
                            + piglin[0].getHealth() + " by " + piglin[0].getRecentDamageSource());
                    // The piglin's own ticks: it is about only while the server moves it.
                    context.assertTrue(piglin[0].age >= 300 || !gold && after[0] > 0 && offered[0] > 0, "Three hundred ticks with the piglin about");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("{}: wears {}; the piglin after it {} ticks, seen as a threat {}, a strike offered {}, of its {}; hits taken {}; piglin health {}",
                            name, player.getEquippedStack(EquipmentSlot.HEAD), after[0], listed[0], offered[0], piglin[0].age, hits[0], piglin[0].getHealth());
                    if (gold) {
                        context.assertTrue(after[0] == 0, "The piglin went for a player wearing gold: " + after[0] + " ticks");
                        context.assertTrue(listed[0] == 0, "A calm piglin was taken for a threat: " + listed[0] + " ticks");
                        context.assertTrue(offered[0] == 0, "A strike on a calm piglin was offered: " + offered[0] + " ticks");
                        context.assertTrue(hits[0] == 0, "Hit: " + hits[0]);
                    } else {
                        context.assertTrue(after[0] > 0 && offered[0] > 0, "Without gold the piglin went for the player, and it fought back");
                    }
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    piglin[0].discard();
                    forced(context.getWorld().getServer().getWorld(World.NETHER), room[0], false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * No gold is mined with a piglin about (E-07e). Vanilla angers every piglin within sixteen blocks of a player who
     * breaks gold ore, seen or not, and gold worn does not calm a piglin angered so. With a piglin shut in the rock
     * eight blocks from the seam, the player wanting gold leaves the ore beside it alone (five seconds; it would have mined at once); once the piglin is gone, it mines it
     * and makes its helmet.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "piglinGuards", tickLimit = 4400)
    public void noGoldIsMinedWhileAPiglinIsNear(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 4200);
        net.minecraft.entity.mob.PiglinEntity[] piglin = new net.minecraft.entity.mob.PiglinEntity[1];
        BlockPos[] room = new BlockPos[1];
        int[] oreBefore = {0};
        long[] start = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Waiter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    room[0] = netherRoom(context, f, true, Items.IRON_HELMET);
                    var nether = f.player().getServerWorld();
                    oreBefore[0] = ore(nether, room[0]);
                    // A cell two high in the rock north of the room, out of sight: seen or not, it would be angered.
                    BlockPos cell = room[0].add(3, 1, -8);
                    nether.setBlockState(cell, Blocks.AIR.getDefaultState());
                    nether.setBlockState(cell.up(), Blocks.AIR.getDefaultState());
                    piglin[0] = piglin(nether, cell);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                    start[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(ore(player.getServerWorld(), room[0]) == oreBefore[0], "Gold mined with a piglin about");
                    context.assertTrue(angryAt(piglin[0]).isEmpty(), "The piglin was angered: " + angryAt(piglin[0]));
                    context.assertTrue(context.getWorld().getTime() - start[0] >= 100, "Five seconds with the piglin about");
                })
                .createAndAdd(() -> {
                    KindredMod.LOGGER.info("Waiter: with the piglin about, ore left {} of {}, {} blocks from the room; {}", ore(f.player().getServerWorld(), room[0]),
                            oreBefore[0], Math.round(Math.sqrt(f.player().getBlockPos().getSquaredDistance(room[0]))),
                            f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    piglin[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(f.player().getEquippedStack(EquipmentSlot.HEAD).isOf(Items.GOLDEN_HELMET),
                        "With the piglin gone, a golden helmet worn: " + f.player().getEquippedStack(EquipmentSlot.HEAD) + " / "
                                + f.runtime.panelBots(Actor.console()).getFirst().goalProgress()))
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Waiter: wears {}; mined {} of {} ore once the piglin was gone; {}", player.getEquippedStack(EquipmentSlot.HEAD),
                            oreBefore[0] - ore(player.getServerWorld(), room[0]), oreBefore[0], f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    forced(context.getWorld().getServer().getWorld(World.NETHER), room[0], false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A room carved in the netherrack at this test's own spot in the Nether, a seam of twenty gold ore in its east wall
     * if asked (forty-five nuggets are wanted, and vanilla gives two to six a block), and the player put in it in iron
     * armour with the given helmet, tools, food, blocks and a crafting table. Fixture repositioning only: it proves what
     * the player does in the Nether, not the trip there. Returns the room's floor centre.
     */
    private static BlockPos netherRoom(TestContext context, NativePaidSkillsGameTests.Fixture f, boolean seam, net.minecraft.item.Item helmet) {
        ServerWorld nether = context.getWorld().getServer().getWorld(World.NETHER);
        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
        BlockPos c = new BlockPos(o.getX() / 8, 100, o.getZ() / 8);
        // The test server simulates no chunk but the player's own; the framework force-loads the Overworld's test areas,
        // and this room is loaded the same way, so the mobs in it move as they would around any player.
        forced(nether, c, true);
        // A piglin left about by a test that failed before taking it away would hold this one's gold back.
        nether.getEntitiesByClass(net.minecraft.entity.mob.PiglinEntity.class, new net.minecraft.util.math.Box(c).expand(32), e -> true)
                .forEach(net.minecraft.entity.Entity::discard);
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) for (int y = -1; y <= 6; y++) {
            boolean inside = Math.abs(x) <= 5 && Math.abs(z) <= 5 && y >= 1 && y <= 5;
            nether.setBlockState(c.add(x, y, z), inside ? Blocks.AIR.getDefaultState() : Blocks.NETHERRACK.getDefaultState());
        }
        if (seam) for (int z = -2; z <= 2; z++) for (int y = 1; y <= 4; y++) nether.setBlockState(c.add(6, y, z), Blocks.NETHER_GOLD_ORE.getDefaultState());
        var player = f.player();
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
        inventory.setStack(1, new ItemStack(Items.IRON_PICKAXE));
        inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
        inventory.setStack(3, new ItemStack(Items.BREAD, 16));
        inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 48));
        inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(helmet));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.teleport(nether, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5, java.util.Set.of(), 270, 0);
        f.runtime.syncNativeDimensionAfterTransit(f.id);
        return c;
    }

    private static void forced(ServerWorld nether, BlockPos room, boolean forced) {
        for (int x = (room.getX() - 26) >> 4; x <= (room.getX() + 26) >> 4; x++) {
            for (int z = (room.getZ() - 26) >> 4; z <= (room.getZ() + 26) >> 4; z++) nether.setChunkForced(x, z, forced);
        }
    }

    private static int ore(ServerWorld nether, BlockPos room) {
        int ore = 0;
        for (BlockPos pos : BlockPos.iterate(room.add(6, 1, -2), room.add(6, 4, 2))) if (nether.getBlockState(pos).isOf(Blocks.NETHER_GOLD_ORE)) ore++;
        return ore;
    }

    /** An adult piglin with nothing in its hands, kept from despawning. */
    private static net.minecraft.entity.mob.PiglinEntity piglin(ServerWorld nether, BlockPos at) {
        var piglin = net.minecraft.entity.EntityType.PIGLIN.create(nether);
        piglin.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);
        piglin.setBaby(false);
        piglin.setPersistent();
        nether.spawnEntity(piglin);
        return piglin;
    }

    private static java.util.Optional<java.util.UUID> angryAt(net.minecraft.entity.mob.PiglinEntity piglin) {
        return piglin.getBrain().getOptionalRegisteredMemory(net.minecraft.entity.ai.brain.MemoryModuleType.ANGRY_AT);
    }
}
