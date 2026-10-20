package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** The Nether-portal goal driving real native skills through the runtime: no grants, no repositioning. */
public final class NativePortalCampaignGameTests implements FabricGameTest {

    /**
     * A husk comes after the player while it works on the goal: the work stops and the player fights it to the end
     * in one recorded fight, chasing and striking with its own sword.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalStopsToFightAMobThatIsAfterThePlayer(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.HuskEntity[] husk = new net.minecraft.entity.mob.HuskEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Duelist");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    husk[0] = context.spawnMob(net.minecraft.entity.EntityType.HUSK, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    husk[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertFalse(husk[0].isAlive(), "The husk was fought and killed"))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "The player survived");
                    var sword = f.player().getInventory().getStack(0);
                    context.assertTrue(sword.isOf(Items.IRON_SWORD) && sword.getDamage() > 0, "Its own sword did the work: " + sword);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Badly hurt, with a wounded zombie in reach that two swings of its iron sword will kill: the goal finishes it
     * rather than turn its back on it, and it is the goal's own fight from start to end, with none of the runtime's
     * tactical single swings, one-block retreats or shield blocks cutting in (live runs flipped between them and the
     * goal's steps every second before most deaths).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalFinishesAWoundedZombieInReachRatherThanTurningItsBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean[] backedOff = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Finisher");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().setStack(1, new ItemStack(Items.BREAD, 3));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(10); // No natural healing while it fights.
                    player.getHungerManager().setSaturationLevel(0);
                    BlockPos at = player.getBlockPos();
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(context.getWorld());
                    zombie[0].refreshPositionAndAngles(at.getX() + 1.5, at.getY(), at.getZ() + 0.5, 90, 0); // The next cell.
                    zombie[0].setPersistent();
                    zombie[0].setHealth(8);
                    context.getWorld().spawnEntity(zombie[0]);
                    zombie[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    if (bot.task() != null) seen.add(bot.task());
                    backedOff[0] |= bot.goalProgress().contains("Back off");
                    context.assertFalse(zombie[0].isAlive(), "The wounded zombie was finished");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive: " + f.player().getHealth());
                    context.assertFalse(backedOff[0], "It did not turn its back on the zombie");
                    context.assertTrue(seen.contains("native_fight"), "The goal's own fight: " + seen);
                    context.assertTrue(java.util.Collections.disjoint(seen, java.util.Set.of("native_attack", "native_retreat", "native_block")),
                            "No tactical step cut in: " + seen);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A fight that wears the player down is broken off: once a zombie's hits take it below six health, the recorded
     * fight is stopped and the goal backs off instead of fighting on to the death, as both live deaths in run 37 did.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalBreaksOffAFightOnceBadlyHurt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Spent");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.WOODEN_SWORD)); // Weak, so the fight is still on when the hit lands.
                    player.setHealth(8);
                    player.getHungerManager().setFoodLevel(10); // No natural healing while it fights.
                    player.getHungerManager().setSaturationLevel(0);
                    zombie[0] = context.spawnMob(net.minecraft.entity.EntityType.ZOMBIE, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    zombie[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue("native_fight".equals(f.runtime.panelBots(Actor.console()).getFirst().task()), "It fights the zombie"))
                .createAndAdd(() -> {
                    // The zombie's hit, applied now so the test does not wait on its swing: below six health. The zombie is
                    // whole again, so this is a fight being lost, not one two more swings would win.
                    var player = f.player();
                    zombie[0].setHealth(zombie[0].getMaxHealth());
                    player.damage(context.getWorld().getDamageSources().mobAttack(zombie[0]), 3);
                    context.assertTrue(player.getHealth() < 6, "Hurt: " + player.getHealth());
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    context.assertTrue(f.player().isAlive() && zombie[0].isAlive() && bot.health() < 6
                                    && "native_goto".equals(bot.task()) && bot.goalProgress().contains("Back off from zombie"),
                            "Badly hurt, it broke off the fight and backed off: " + bot.health() + " " + bot.task() + " " + bot.goalProgress()
                                    + "; zombie alive " + zombie[0].isAlive());
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                    zombie[0].discard();
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A block lands in the player's head space, as gravel does when a climb breaks the block under it: the goal
     * breaks it at once instead of carrying on while it suffocates, as happened in live run 38.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void goalDigsOutOfABlockInItsHeadSpace(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] head = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Buried");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    head[0] = BlockPos.ofFloored(f.player().getEyePos()).toImmutable();
                    context.getWorld().setBlockState(head[0], Blocks.DIRT.getDefaultState());
                    context.assertTrue(f.player().isInsideWall(), "Stuck in a wall");
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getBlockState(head[0]).isAir() && !f.player().isInsideWall(),
                        "It broke the block in its head space: " + context.getWorld().getBlockState(head[0])))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() >= 15, "Barely hurt: " + f.player().getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Standing at the edge of its cell, a block lands beside its head that still reaches into its eyes: the goal
     * breaks that block, not just the one its eyes are centred in, as live run 48 suffocated there.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void goalDigsOutOfABlockReachingIntoItsHeadFromBeside(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] beside = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Edged");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 5));
                    BlockPos feet = player.getBlockPos();
                    // Against the west edge of its cell, so its eyes reach into the cell beside it.
                    player.refreshPositionAndAngles(feet.getX() + 0.05, player.getY(), feet.getZ() + 0.5, player.getYaw(), player.getPitch());
                    beside[0] = BlockPos.ofFloored(player.getEyePos()).west().toImmutable();
                    context.getWorld().setBlockState(beside[0], Blocks.DIRT.getDefaultState());
                    context.assertTrue(player.isInsideWall() && context.getWorld().getBlockState(BlockPos.ofFloored(player.getEyePos())).isAir(),
                            "Stuck in the wall beside it");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(!f.player().isInsideWall(),
                        "It got its head out of the block beside it: " + context.getWorld().getBlockState(beside[0])))
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() >= 15, "Barely hurt: " + f.player().getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A skeleton in a dark, walled plot: in one recorded fight the player closes in with its shield raised, as players
     * do, so arrows stop at the shield, then lowers it to strike and kills the skeleton.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void fightClosesInOnASkeletonBehindItsShield(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.SkeletonEntity[] skeleton = new net.minecraft.entity.mob.SkeletonEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // A walled, roofed plot: dark, so the skeleton does not burn, and closed, so neither leaves it.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 6; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Shieldbearer");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    player.getInventory().offHand.set(0, new ItemStack(Items.SHIELD));
                    // In the corner farthest from the player, with the bow a skeleton spawns with.
                    BlockPos feet = player.getBlockPos();
                    BlockPos a = context.getAbsolutePos(new BlockPos(1, 2, 1)), b = context.getAbsolutePos(new BlockPos(6, 2, 6));
                    BlockPos corner = feet.getSquaredDistance(a) > feet.getSquaredDistance(b) ? new BlockPos(1, 2, 1) : new BlockPos(6, 2, 6);
                    skeleton[0] = context.spawnMob(net.minecraft.entity.EntityType.SKELETON, net.minecraft.util.math.Vec3d.ofBottomCenter(corner));
                    skeleton[0].equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                    skeleton[0].setTarget(player);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeFight(f.source, f.id, skeleton[0].getUuid(), 0) == 1, "Fight requested");
                })
                .createAndAdd(() -> context.assertTrue(skeleton[0].isAlive() && f.player().isBlocking()
                        && f.runtime.nativeActivityStatus(Actor.console(), f.id).work().startsWith("Running native_fight"), "Its shield is up while it closes in"))
                .createAndAdd(() -> context.assertFalse(skeleton[0].isAlive(), "The skeleton was fought and killed"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getOffHandStack().isOf(Items.SHIELD), "Alive, shield still in hand: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A creeper the goal is fighting lights its fuse close by: with a shield in its off hand the player turns to face
     * it with the shield up instead of running, and the raised shield takes the blast.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalFacesAHissingCreeperBehindItsShield(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.CreeperEntity[] creeper = new net.minecraft.entity.mob.CreeperEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 6; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Braced");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    player.getInventory().offHand.set(0, new ItemStack(Items.SHIELD));
                    creeper[0] = context.spawnMob(net.minecraft.entity.EntityType.CREEPER, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    creeper[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    context.assertTrue("native_fight".equals(bot.task()) && bot.goalProgress().contains("Fight creeper")
                            && f.player().distanceTo(creeper[0]) < 4, "The goal fights the creeper up close");
                    creeper[0].ignite(); // Its fuse is lit before the sword can finish it.
                })
                .createAndAdd(() -> context.assertFalse(creeper[0].isAlive(), "The creeper went off"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 14, "The raised shield took the blast: health " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A tunnel breaks into a small dark cave with a creeper in it, and the player has only stone tools and no shield:
     * it walls off the way in where it stands, as players do, instead of fighting or running across the cave, and
     * waits behind the wall unhurt.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalWallsOffACaveItIsNoMatchFor(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.CreeperEntity[] creeper = new net.minecraft.entity.mob.CreeperEntity[1];
        BlockPos[] way = new BlockPos[2];
        int[] settle = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cautious"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute positions around where the player stood up: solid stone with the dead end of a tunnel
                    // where it stands, one cell of passage south, and a dark three-deep cave beyond.
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    for (int x = -3; x <= 3; x++) for (int z = -2; z <= 5; z++) for (int y = -1; y <= 3; y++) {
                        boolean air = (x == 0 && (z == 0 || z == 1) && (y == 0 || y == 1))
                                || (Math.abs(x) <= 2 && z >= 2 && z <= 4 && y >= 0 && y <= 2);
                        world.setBlockState(p.add(x, y, z), air ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    way[0] = p.add(0, 0, 1);
                    way[1] = p.add(0, 1, 1);
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.STONE_SWORD));
                    player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.getInventory().setStack(3, new ItemStack(Items.BREAD, 5));
                })
                // Sky light reaches the new stone a tick or two late; until then the player would not count as underground.
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settles"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos p = way[0].north();
                    creeper[0] = net.minecraft.entity.EntityType.CREEPER.create(world);
                    creeper[0].refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 4.5, 180, 0);
                    creeper[0].setPersistent();
                    world.spawnEntity(creeper[0]);
                    creeper[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(!world.getBlockState(way[0]).isAir() && !world.getBlockState(way[1]).isAir(),
                            "It walled off the way into the cave: " + world.getBlockState(way[0]) + " " + world.getBlockState(way[1])
                                    + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 18, "Unhurt behind its wall: " + player.getHealth());
                    context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Hole up")
                            || !creeper[0].isAlive() || creeper[0].getTarget() != player, "Waiting behind the wall while the creeper is after it");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The same dead end, with a skeleton standing in the cave beyond that has not noticed the player (no AI, so it
     * never will): without a shield the player walls off the way in from the passage instead of going to look,
     * as live run 50 walked into the cave under spawn and was shot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalWallsOffACaveWithASkeletonInViewBeforeItComes(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.SkeletonEntity[] skeleton = new net.minecraft.entity.mob.SkeletonEntity[1];
        BlockPos[] way = new BlockPos[2];
        int[] settle = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Peeker"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    for (int x = -3; x <= 3; x++) for (int z = -2; z <= 5; z++) for (int y = -1; y <= 3; y++) {
                        boolean air = (x == 0 && (z == 0 || z == 1) && (y == 0 || y == 1))
                                || (Math.abs(x) <= 2 && z >= 2 && z <= 4 && y >= 0 && y <= 2);
                        world.setBlockState(p.add(x, y, z), air ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    way[0] = p.add(0, 0, 1);
                    way[1] = p.add(0, 1, 1);
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.STONE_SWORD));
                    player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.getInventory().setStack(3, new ItemStack(Items.BREAD, 5));
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settles"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = way[0].north();
                    skeleton[0] = net.minecraft.entity.EntityType.SKELETON.create(world);
                    skeleton[0].refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 4.5, 180, 0);
                    skeleton[0].equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                    skeleton[0].setPersistent();
                    skeleton[0].setAiDisabled(true);
                    world.spawnEntity(skeleton[0]);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertTrue(!world.getBlockState(way[0]).isAir() && !world.getBlockState(way[1]).isAir(),
                            "It walled off the way into the cave: " + world.getBlockState(way[0]) + " " + world.getBlockState(way[1])
                                    + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 18 && player.getBlockPos().equals(way[0].north()),
                            "Unhurt, still in its passage: " + player.getHealth() + " at " + player.getBlockPos().toShortString());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                    skeleton[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The tunnel dead end again, with an enderman in the cave that is after the player and only a stone sword to
     * meet it with: the goal walls itself into its two-high passage, where the enderman cannot follow, instead of
     * fighting it, as in live run 51.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalHolesUpFromAProvokedEnderman(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.EndermanEntity[] enderman = new net.minecraft.entity.mob.EndermanEntity[1];
        BlockPos[] way = new BlockPos[2];
        int[] settle = {0};
        boolean[] chased = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stared"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    for (int x = -3; x <= 3; x++) for (int z = -2; z <= 5; z++) for (int y = -1; y <= 4; y++) {
                        boolean air = (x == 0 && (z == 0 || z == 1) && (y == 0 || y == 1))
                                || (Math.abs(x) <= 2 && z >= 2 && z <= 4 && y >= 0 && y <= 3);
                        world.setBlockState(p.add(x, y, z), air ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    way[0] = p.add(0, 0, 1);
                    way[1] = p.add(0, 1, 1);
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.STONE_SWORD));
                    player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.getInventory().setStack(3, new ItemStack(Items.BREAD, 5));
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settles"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos p = way[0].north();
                    enderman[0] = net.minecraft.entity.EntityType.ENDERMAN.create(world);
                    enderman[0].refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 3.5, 180, 0);
                    enderman[0].setPersistent();
                    world.spawnEntity(enderman[0]);
                    enderman[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    chased[0] |= "native_fight".equals(bot.task()) && f.player().squaredDistanceTo(enderman[0]) > 3.5 * 3.5;
                    var world = context.getWorld();
                    // Walled in toward the cave, wherever in the passage it now stands (an enderman that teleports away
                    // and back can find it a step further back).
                    BlockPos at = f.player().getBlockPos();
                    boolean walled = at.getZ() <= way[0].getZ() && !world.getBlockState(at.south()).isAir() && !world.getBlockState(at.south().up()).isAir();
                    context.assertTrue(walled, "It walled itself into the passage: " + bot.goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertFalse(chased[0], "It did not go after the enderman");
                    context.assertTrue(player.isAlive() && player.getHealth() >= 10, "Alive behind its wall: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                    enderman[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * An enderman standing two blocks from a player with an iron sword, minding its own business: nothing strikes it
     * over five seconds of autonomous play, since hitting one provokes it (live run 56 died to one it had hit).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void anEndermanThatIsNotAfterThePlayerIsLeftAlone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.EndermanEntity[] enderman = new net.minecraft.entity.mob.EndermanEntity[1];
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Bystander");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    BlockPos feet = player.getBlockPos();
                    enderman[0] = net.minecraft.entity.EntityType.ENDERMAN.create(world);
                    enderman[0].refreshPositionAndAngles(feet.getX() + 2.5, feet.getY(), feet.getZ() + 0.5, 90, 0);
                    enderman[0].setPersistent();
                    enderman[0].setAiDisabled(true);
                    world.spawnEntity(enderman[0]);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 100, "Five seconds of autonomous play"))
                .createAndAdd(() -> {
                    context.assertTrue(enderman[0].isAlive() && enderman[0].getHealth() >= enderman[0].getMaxHealth(),
                            "Nothing struck the enderman: " + enderman[0].getHealth());
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() >= 20, "The player is unhurt");
                    f.runtime.stop();
                    enderman[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Nothing in hand, as just after a respawn, and a zombie after it a few blocks off: the player keeps away rather
     * than start a fist fight it would lose.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalKeepsAwayFromAZombieWhenUnarmed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        boolean[] fought = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Barehanded");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.BREAD, 5));
                    zombie[0] = context.spawnMob(net.minecraft.entity.EntityType.ZOMBIE, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    zombie[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    fought[0] |= "native_fight".equals(bot.task()) && f.player().squaredDistanceTo(zombie[0]) > 2.5 * 2.5;
                    context.assertTrue("native_goto".equals(bot.task()) && bot.goalProgress().contains("Back off from zombie"),
                            "Unarmed, it keeps away from the zombie: " + bot.task() + " " + bot.goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertFalse(fought[0], "It did not walk into a fist fight");
                    f.runtime.stop();
                    zombie[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Nothing in hand and a skeleton shooting at it across a dark plot: walking away would stay in its line of fire,
     * so the player puts a block between them, as a player without a weapon ducks behind cover.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalTakesCoverFromASkeletonWhenUnarmed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.SkeletonEntity[] skeleton = new net.minecraft.entity.mob.SkeletonEntity[1];
        boolean[] fought = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 6; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Unarmed");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.BREAD, 5));
                    player.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 16));
                    BlockPos feet = player.getBlockPos();
                    BlockPos a = context.getAbsolutePos(new BlockPos(1, 2, 1)), b = context.getAbsolutePos(new BlockPos(6, 2, 6));
                    BlockPos corner = feet.getSquaredDistance(a) > feet.getSquaredDistance(b) ? new BlockPos(1, 2, 1) : new BlockPos(6, 2, 6);
                    skeleton[0] = context.spawnMob(net.minecraft.entity.EntityType.SKELETON, net.minecraft.util.math.Vec3d.ofBottomCenter(corner));
                    skeleton[0].equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                    skeleton[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    fought[0] |= "native_fight".equals(bot.task()) && f.player().squaredDistanceTo(skeleton[0]) > 2.5 * 2.5;
                    context.assertTrue(bot.goalProgress().contains("Take cover from skeleton"),
                            "Unarmed, it takes cover from the skeleton: " + bot.task() + " " + bot.goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertFalse(fought[0], "It did not walk into a fist fight");
                    f.runtime.stop();
                    skeleton[0].discard();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Hurt enough to want to wall itself in, but a zombie is already in the tunnel beside it: no wall can help, so
     * the player fights it where it stands instead of turning its back to place blocks.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalFightsAMobAlreadyInTheTunnelRatherThanWallingOff(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        BlockPos[] mouth = new BlockPos[1];
        int[] settle = {0};
        boolean[] walled = {false};
        boolean[] fought = {false}, flipped = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Cornered"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    mouth[0] = p;
                    for (int x = -3; x <= 3; x++) for (int z = -2; z <= 5; z++) for (int y = -1; y <= 3; y++) {
                        boolean air = (x == 0 && z >= 0 && z <= 2 && (y == 0 || y == 1))
                                || (Math.abs(x) <= 2 && z >= 3 && z <= 4 && y >= 0 && y <= 2);
                        world.setBlockState(p.add(x, y, z), air ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.setHealth(10); // Below the line at which it would wall itself in.
                    player.getHungerManager().setFoodLevel(10);
                    player.getHungerManager().setSaturationLevel(0);
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settles"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = mouth[0];
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    zombie[0].refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 1.5, 180, 0); // The very next cell.
                    zombie[0].setPersistent();
                    world.spawnEntity(zombie[0]);
                    zombie[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    walled[0] |= progress.contains("Hole up: seal");
                    // Once the fight is on, it stays on until the zombie is dead: no turning to hole up or back off when a
                    // hit knocks it a step back.
                    fought[0] |= progress.contains("Fight zombie");
                    flipped[0] |= fought[0] && zombie[0].isAlive() && (progress.contains("Hole up") || progress.contains("Back off"));
                    context.assertFalse(zombie[0].isAlive(), "The zombie in the tunnel was fought and killed");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive: " + f.player().getHealth());
                    context.assertFalse(walled[0], "It did not turn to wall off a tunnel the zombie was already in");
                    context.assertFalse(flipped[0], "It held the fight once it was on");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The same tunnel mouth with a zombie in the cave beyond: the player holds the passage and lets the zombie come
     * to it, as players do, instead of chasing it out into the open cave, and kills it there.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalHoldsThePassageAgainstAZombie(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        BlockPos[] mouth = new BlockPos[1];
        int[] farthest = {0};
        boolean[] held = {false};
        int[] settle = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Steadfast"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    mouth[0] = p;
                    for (int x = -3; x <= 3; x++) for (int z = -2; z <= 5; z++) for (int y = -1; y <= 3; y++) {
                        boolean air = (x == 0 && (z == 0 || z == 1) && (y == 0 || y == 1))
                                || (Math.abs(x) <= 2 && z >= 2 && z <= 4 && y >= 0 && y <= 2);
                        world.setBlockState(p.add(x, y, z), air ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.getInventory().setStack(3, new ItemStack(Items.BREAD, 5));
                })
                // Sky light reaches the new stone a tick or two late; until then the player would not count as underground.
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settles"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    BlockPos p = mouth[0];
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    zombie[0].refreshPositionAndAngles(p.getX() + 1.5, p.getY(), p.getZ() + 4.5, 180, 0);
                    zombie[0].setPersistent();
                    world.spawnEntity(zombie[0]);
                    zombie[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    farthest[0] = Math.max(farthest[0], f.player().getBlockPos().getZ() - mouth[0].getZ());
                    held[0] |= f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Hold the passage");
                    context.assertFalse(zombie[0].isAlive(), "The zombie was killed");
                })
                .createAndAdd(() -> {
                    context.assertTrue(held[0], "It held the passage while the zombie came");
                    // A step after a zombie knocked back by its own swing is fine; a chase across the cave is not.
                    context.assertTrue(farthest[0] <= 2, "It never went out into the cave: " + farthest[0] + " blocks in");
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Badly hurt, with a shield, and a creeper already hissing three blocks away: running would not get clear in
     * time, so the goal faces it behind the shield, and the player lives through the blast.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalFacesAHissingCreeperBehindItsShieldEvenWhenBadlyHurt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.CreeperEntity[] creeper = new net.minecraft.entity.mob.CreeperEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 6; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Bruised");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().offHand.set(0, new ItemStack(Items.SHIELD));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(10);
                    player.getHungerManager().setSaturationLevel(0);
                    creeper[0] = net.minecraft.entity.EntityType.CREEPER.create(context.getWorld());
                    creeper[0].refreshPositionAndAngles(player.getX() + 3, player.getY(), player.getZ(), 0, 0);
                    if (!context.getWorld().isSpaceEmpty(creeper[0])) creeper[0].refreshPositionAndAngles(player.getX() - 3, player.getY(), player.getZ(), 0, 0);
                    creeper[0].setPersistent();
                    context.getWorld().spawnEntity(creeper[0]);
                    creeper[0].setTarget(player);
                    creeper[0].ignite();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertFalse(creeper[0].isAlive(), "The creeper went off"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive(), "The raised shield took the blast: health " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A creeper lights up close by while the player is fighting something else: the fight is not cut short, and it
     * turns its raised shield to the creeper until the blast is over.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalShieldsAgainstACreeperThatLightsUpMidFight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.HuskEntity[] husk = new net.minecraft.entity.mob.HuskEntity[1];
        net.minecraft.entity.mob.CreeperEntity[] creeper = new net.minecraft.entity.mob.CreeperEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 6; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Steady");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.WOODEN_SWORD)); // A long fight, so the creeper comes mid-way.
                    player.getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    player.getInventory().offHand.set(0, new ItemStack(Items.SHIELD));
                    husk[0] = context.spawnMob(net.minecraft.entity.EntityType.HUSK, new net.minecraft.util.math.Vec3d(1.5, 2, 6.5));
                    husk[0].setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    context.assertTrue("native_fight".equals(bot.task()) && bot.goalProgress().contains("Fight husk"), "It fights the husk");
                    // A creeper two blocks off the player, already hissing.
                    var player = f.player();
                    creeper[0] = net.minecraft.entity.EntityType.CREEPER.create(context.getWorld());
                    creeper[0].refreshPositionAndAngles(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
                    if (!context.getWorld().isSpaceEmpty(creeper[0])) creeper[0].refreshPositionAndAngles(player.getX() - 2, player.getY(), player.getZ(), 0, 0);
                    creeper[0].setPersistent();
                    context.getWorld().spawnEntity(creeper[0]);
                    creeper[0].ignite();
                })
                .createAndAdd(() -> context.assertFalse(creeper[0].isAlive(), "The creeper went off"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 10, "The shield took the blast: health " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The last stage of the goal: holding ten obsidian, four cobblestone and its own flint and steel, the player
     * chooses a site, places the 4×5 frame block by block with cobblestone corners, and lights it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalBuildsAndLightsThePortalFrameFromOwnedBlocks(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Gatewright");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.OBSIDIAN, 10));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 4));
                    inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(4, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(5, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(6, new ItemStack(Items.IRON_SWORD)); // Earlier stages are done; nothing else to craft.
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.OBSIDIAN) == 0 && ItemMoves.count(inventory, Items.COBBLESTONE) == 0,
                            "All ten obsidian and four corners were placed from the inventory");
                })
                .createAndAdd(() -> {
                    BlockPos feet = f.player().getBlockPos();
                    long portalBlocks = BlockPos.streamOutwards(feet, 6, 6, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.NETHER_PORTAL)).count();
                    context.assertTrue(portalBlocks == 6, "A lit 2x3 portal: " + portalBlocks);
                    int wear = -1;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.FLINT_AND_STEEL)) wear = f.player().getInventory().getStack(s).getDamage();
                    }
                    context.assertTrue(wear == 1, "Lit with its own flint and steel, one use of wear: " + wear);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A lava source recessed below its shore, the usual cave case. With the goal set and only a diamond pickaxe and
     * a water bucket for the job, the player pours onto the shore so the water runs over the lava, mines the
     * obsidian it hardens while the water guards the hole, and takes its water back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalMakesObsidianFromLavaWithPouredWaterAndRecoversTheWater(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos lava = context.getAbsolutePos(new BlockPos(5, 1, 3));
        BlockPos shore = context.getAbsolutePos(new BlockPos(4, 2, 3));
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Walls keep the poured water inside this test's area.
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    context.setBlockState(5, 0, 3, Blocks.STONE);
                    context.setBlockState(5, 1, 3, Blocks.LAVA);
                    roof(context);
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the shore"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(2, new ItemStack(Items.IRON_INGOT)); // Flint and steel's iron is already in hand.
                    inventory.setStack(3, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(context.getWorld().getBlockState(lava).isOf(Blocks.OBSIDIAN)
                        || ItemMoves.count(f.player().getInventory(), Items.OBSIDIAN) > 0, "The poured water hardened the lava"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.OBSIDIAN) == 1, "The obsidian was mined and picked up");
                    context.assertTrue(ItemMoves.count(inventory, Items.WATER_BUCKET) == 1 && ItemMoves.count(inventory, Items.BUCKET) == 0,
                            "The water was taken back into the bucket");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertFalse(world.getFluidState(lava).isIn(FluidTags.LAVA), "No lava left at the source");
                    context.assertFalse(world.getFluidState(shore).isStill() && world.getFluidState(shore).isIn(FluidTags.WATER),
                            "No water source left on the shore");
                    int pickDamage = -1;
                    for (int s = 0; s < 36; s++) {
                        if (f.player().getInventory().getStack(s).isOf(Items.DIAMOND_PICKAXE)) pickDamage = f.player().getInventory().getStack(s).getDamage();
                    }
                    context.assertTrue(pickDamage >= 1, "Vanilla wear from mining: " + pickDamage);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A husk out of reach beyond a lava moat: the goal starts a fight with it, but the chase stops at the edge
     * instead of walking into the lava, and the fight ends as unsafe. The husk is too far for a strike across.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 700)
    public void goalFightStopsAtLavaRatherThanChasingIntoIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.HuskEntity[] husk = new net.minecraft.entity.mob.HuskEntity[1];
        boolean[] burned = new boolean[1];
        double[] closest = {Double.MAX_VALUE};
        Runnable watch = () -> {
            var player = f.player();
            if (player == null || husk[0] == null) return;
            burned[0] |= player.isInLava() || player.isOnFire() || !player.isAlive();
            closest[0] = Math.min(closest[0], Math.hypot(player.getX() - husk[0].getX(), player.getZ() - husk[0].getZ()));
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    // A moat two blocks wide around the far corner, where the husk stands.
                    for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) {
                        if (x < 4 && z < 4 || x == 6 && z == 6) continue;
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.LAVA);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Wary");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    f.player().getInventory().setStack(1, new ItemStack(Items.BREAD, 5));
                    husk[0] = context.spawnMob(net.minecraft.entity.EntityType.HUSK, new net.minecraft.util.math.Vec3d(6.5, 2, 6.5));
                    husk[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Fight husk"), "The goal took up the fight");
                })
                .createAndAdd(() -> {
                    watch.run();
                    context.assertFalse(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Fight husk"), "The fight ended");
                })
                .createAndAdd(() -> {
                    f.runtime.stop();
                    context.assertFalse(burned[0], "The player never stepped into the lava");
                    // Standing anywhere on the island keeps at least 3.6 blocks to the husk; a step onto the lava would not.
                    context.assertTrue(closest[0] >= 3.6, "It stopped at the edge: " + closest[0]);
                    context.assertTrue(husk[0].isAlive(), "The husk stayed out of reach");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A skeleton shooting across a lava moat it cannot be reached over: the fight stops at the edge, and the
     * player then takes cover behind its own cobblestone, building up from the floor until the skeleton has no
     * line of fire, instead of going on with its work under fire.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalTakesCoverFromASkeletonItCannotReach(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.SkeletonEntity[] skeleton = new net.minecraft.entity.mob.SkeletonEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 3; y++) context.setBlockState(x, y, z, Blocks.STONE);
                    }
                    for (int x = 1; x <= 6; x++) for (int z = 1; z <= 6; z++) {
                        if (x < 4 && z < 4 || x == 6 && z == 6) continue;
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.LAVA);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Ducker");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    skeleton[0] = context.spawnMob(net.minecraft.entity.EntityType.SKELETON, new net.minecraft.util.math.Vec3d(6.5, 2, 6.5));
                    skeleton[0].setTarget(f.player());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Take cover from skeleton"),
                        "Took cover once the fight could not reach it"))
                .createAndAdd(() -> context.assertFalse(f.player().canSee(skeleton[0]), "No line of fire left"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && !player.isInLava(), "Alive and out of the lava");
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.COBBLESTONE) < 16, "The cover is its own cobblestone");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Night in a pit open to the sky with an empty inventory, as after a respawn: the goal digs one staircase step
     * into the hill around it, whichever way it faces, and once under cover seals both openings with the dirt the
     * dig gave it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightShelter", tickLimit = 900)
    public void goalDigsInAndSealsTheShelterAtNight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Runnable night = () -> context.getWorld().setTimeOfDay(14000);
        BlockPos[] pit = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = -1; y <= 1; y++) context.setBlockState(x, y, z, Blocks.DIRT);
                    night.run();
                    f.runtime.createNative(f.source, "Burrow");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // The hill rises around wherever the player stood up: a pit three deep, open to the sky. Absolute
                    // positions throughout, since a rotated test's relative positions do not round-trip.
                    pit[0] = f.player().getBlockPos().toImmutable();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = 2; y <= 4; y++) {
                        BlockPos cell = context.getAbsolutePos(new BlockPos(x, y, z));
                        if (cell.getX() == pit[0].getX() && cell.getZ() == pit[0].getZ()) continue;
                        context.getWorld().setBlockState(cell, Blocks.DIRT.getDefaultState());
                    }
                    night.run();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    night.run();
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    context.assertTrue(feet.getY() == pit[0].getY() - 1 && feet.getManhattanDistance(pit[0].down()) == 1, "One step into the hill: " + feet);
                    context.assertTrue(world.getBlockState(pit[0]).isOf(Blocks.DIRT) && world.getBlockState(feet.up(2)).isOf(Blocks.DIRT),
                            "Both openings were sealed");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos();
                    var around = new java.util.ArrayList<>(java.util.List.of(feet.down(), feet.up(2)));
                    for (Direction side : Direction.Type.HORIZONTAL) {
                        around.add(feet.offset(side));
                        around.add(feet.up().offset(side));
                    }
                    for (BlockPos cell : around) {
                        context.assertTrue(world.getBlockState(cell).isSolidBlock(world, cell), "Enclosed: " + cell);
                    }
                    context.assertTrue(f.player().isAlive() && f.player().getHealth() >= 20, "Unhurt");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A dark L-shaped cave passage with iron ore out of sight around the corner, and no mob within earshot. At the
     * iron stage, with no shield yet, the goal lights the dark with a torch crafted from its own coal and a spare
     * stick, walks to the part of the cave it can see,
     * spots the ore from the corner, and mines it, rather than digging a tunnel from where it stood.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1200)
    public void goalExploresACaveAndMinesIronItCouldNotSeeAtFirst(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] ore = new BlockPos[1];
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute layout around wherever the player stood up. The first leg runs toward the near edge
                    // of a 4-block knowledge cell, so the corner lies in a cave cell the player has not walked yet.
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    Direction alongX = (p.getX() & 3) >= 2 ? Direction.EAST : Direction.WEST;
                    Direction alongZ = (p.getZ() & 3) >= 2 ? Direction.SOUTH : Direction.NORTH;
                    // One block wider than the passage needs, so stone stands behind the ore and its drop cannot fly out.
                    for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    // Sealed inside the stone block, so no daylight reaches it.
                    var passage = new java.util.ArrayList<BlockPos>();
                    for (int i = 0; i <= 2; i++) passage.add(p.offset(alongX, i));
                    for (int i = 1; i <= 2; i++) passage.add(p.offset(alongX, 2).offset(alongZ, i));
                    for (BlockPos cell : passage) {
                        world.setBlockState(cell, Blocks.AIR.getDefaultState());
                        world.setBlockState(cell.up(), Blocks.AIR.getDefaultState());
                    }
                    ore[0] = p.offset(alongX, 2).offset(alongZ, 3);
                    world.setBlockState(ore[0], Blocks.IRON_ORE.getDefaultState());
                })
                // A cave is old ground, not freshly built: let the light engine darken the passage first.
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    // The reserve (the iron pickaxe's two, and spares for the eighteen iron's digging) and one for a torch.
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE)); // Already packed: a spare for the work below.
                    inventory.setStack(5, new ItemStack(Items.COAL, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.RAW_IRON) >= 1,
                        "Found and mined the iron around the corner"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    context.assertFalse(world.getBlockState(ore[0]).isOf(Blocks.IRON_ORE), "The ore is gone");
                    long torches = BlockPos.streamOutwards(ore[0], 6, 3, 6).filter(pos -> world.getBlockState(pos).isOf(Blocks.TORCH)).count();
                    context.assertTrue(torches >= 1, "It lit the dark passage with its own torch");
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.COAL) == 3 && ItemMoves.count(inventory, Items.STICK) == 18,
                            "One coal and one spare stick became torches: coal " + ItemMoves.count(inventory, Items.COAL)
                                    + ", sticks " + ItemMoves.count(inventory, Items.STICK));
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The same kind of dark cave opening ahead, no shield yet, and a zombie within earshot behind the rock (out of
     * sight, no AI): the goal does not go exploring the cave for iron, as open caves are where skeletons ended most
     * early lives; it digs its own way instead.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void goalLeavesCavesAloneWithoutAShieldWhileItHearsMobs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] explored = {false};
        int[] settle = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Unshielded"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Solid stone around the player with a dark chamber opening in front of it.
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -1; y <= 3; y++) {
                        boolean air = x == 0 && z == 0 && (y == 0 || y == 1) || Math.abs(x) <= 2 && z >= 1 && z <= 3 && y >= 0 && y <= 2;
                        world.setBlockState(p.add(x, y, z), air ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    // A pocket in the rock behind the player, sealed off, with a zombie in it.
                    BlockPos pocket = p.add(-3, 0, -3);
                    world.setBlockState(pocket, Blocks.AIR.getDefaultState());
                    world.setBlockState(pocket.up(), Blocks.AIR.getDefaultState());
                    var zombie = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    zombie.refreshPositionAndAngles(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 0, 0);
                    zombie.setPersistent();
                    zombie.setAiDisabled(true);
                    world.spawnEntity(zombie);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 12));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settles"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    explored[0] |= progress.contains("Explore the cave");
                    context.assertTrue(progress.contains("Branch mine") || progress.contains("Dig down"),
                            "It digs its own way for iron: " + progress);
                })
                .createAndAdd(() -> {
                    context.assertFalse(explored[0], "It did not go exploring the cave without a shield while it could hear a mob");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The cave the goal explores for its iron in the fixture above, with no mob about, but with a strategist's
     * advice to play safe (F-19b): it leaves the cave alone and digs its own way for the iron.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalStaysOutOfCavesWhenAdvisedToPlaySafe(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] explored = {false};
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Careful"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    Direction alongX = (p.getX() & 3) >= 2 ? Direction.EAST : Direction.WEST;
                    Direction alongZ = (p.getZ() & 3) >= 2 ? Direction.SOUTH : Direction.NORTH;
                    for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    var passage = new java.util.ArrayList<BlockPos>();
                    for (int i = 0; i <= 2; i++) passage.add(p.offset(alongX, i));
                    for (int i = 1; i <= 2; i++) passage.add(p.offset(alongX, 2).offset(alongZ, i));
                    for (BlockPos cell : passage) {
                        world.setBlockState(cell, Blocks.AIR.getDefaultState());
                        world.setBlockState(cell.up(), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(p.offset(alongX, 2).offset(alongZ, 3), Blocks.IRON_ORE.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(5, new ItemStack(Items.COAL, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PLAY_SAFE", java.util.List.of(),
                            "", "Two deaths in caves already", java.util.List.of()).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "Advice taken");
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    explored[0] |= progress.contains("Explore the cave");
                    context.assertTrue(progress.contains("Branch mine") || progress.contains("Dig down"),
                            "It digs its own way for iron: " + progress);
                })
                .createAndAdd(() -> {
                    context.assertFalse(explored[0], "It did not go exploring the cave while advised to play safe");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Hurt but fed at the iron stage, sealed in rock ten blocks down in daylight, with a strategist's advice to
     * surface and recover (F-19b): instead of mining for iron where it stands, it climbs a staircase of its own to
     * open sky, eating on the way, and goes on only once healed. Then the advice turns to playing safe, so it does
     * not climb out again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2400)
    public void goalClimbsOutToRecoverWhenAdvised(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] base = new BlockPos[1];
        boolean[] climbedAgain = {false};
        int[] watched = new int[1];
        float[] healthWhenOn = new float[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Recoverer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = base[0] = f.player().getBlockPos().toImmutable();
                    for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 9; dy++) {
                        boolean pocket = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                        world.setBlockState(p.add(dx, dy, dz), pocket ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    f.player().setHealth(6);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("SURFACE_AND_RECOVER", java.util.List.of(),
                            "", "Hurt twice below already", java.util.List.of()).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "Advice taken");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(progress.contains("advised to recover"), "Climbing out as advised: " + progress);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player.getBlockY() >= base[0].getY() + 7 && context.getWorld().isSkyVisible(player.getBlockPos().up()),
                            "Up under open sky: " + player.getBlockPos().toShortString() + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(!progress.contains("heal before going on") && !progress.contains("advised to recover"), "Recovering: " + progress);
                    healthWhenOn[0] = f.player().getHealth();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    climbedAgain[0] |= f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("advised to recover");
                    context.assertTrue(++watched[0] >= 200, "Watching it carry on");
                })
                .createAndAdd(() -> {
                    context.assertFalse(climbedAgain[0], "Once up and healed it did not climb out again");
                    context.assertTrue(healthWhenOn[0] >= 18, "Healed before it went on: " + healthWhenOn[0]);
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A sky island: a dirt island with nothing under it at the bottom of the world, no stone anywhere, a wooden
     * pickaxe and the classic starter buckets of lava and water. With no ground to dig the goal builds a
     * cobblestone generator (a trench with water at one end falling into a hole and lava at the other), mines
     * cobblestone from it, and makes a stone pickaxe. Run in its own batch so no other plot's stone is in view.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyGenerator", tickLimit = 2400)
    public void goalBuildsACobblestoneGeneratorWhereThereIsNoStone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] center = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Islander"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    center[0] = skyIsland(context, f.player());
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(2, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(4, new ItemStack(Items.STICK, 8));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.STONE_PICKAXE) >= 1,
                            "A stone pickaxe from the generator's cobblestone: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = f.player();
                    long fires = BlockPos.streamOutwards(center[0], 10, 4, 10).filter(pos -> world.getBlockState(pos).isOf(Blocks.FIRE)).count();
                    context.assertTrue(fires == 0, "Nothing caught fire");
                    long lava = BlockPos.streamOutwards(center[0], 8, 3, 8).filter(pos -> world.getFluidState(pos).isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                            && world.getFluidState(pos).isStill()).count();
                    context.assertTrue(lava == 1, "The poured lava stays in the generator: " + lava);
                    context.assertTrue(player.isAlive() && player.getHealth() >= 18, "Unhurt: " + player.getHealth());
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.BUCKET) == 2, "Both buckets came back empty");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Live run 57's dead end, reached the ordinary way: the player walks down an open staircase into rock after iron,
     * and its pickaxe, one block from worn out, breaks on the first thing it digs down there. With no pickaxe and no
     * wood, it walks back up the way it came for logs, breaking nothing on the way.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600)
    public void goalWalksBackUpTheWayItCameWhenItsPickaxeBreaksBelow(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] base = new BlockPos[1];
        long[] stone = new long[1];
        java.util.function.Supplier<Long> stoneLeft = () -> {
            BlockPos b = base[0];
            return BlockPos.stream(b.add(-2, 0, -2), b.add(10, 7, 2)).filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.STONE)).count();
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Returner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos b = base[0] = f.player().getBlockPos().toImmutable();
                    // A rock mass eight high with a staircase cut down into it from its top, open to the sky for its
                    // first three steps and roofed below, and iron ore at the foot of the last step, off the way back
                    // up and in sight from the top straight down the cut.
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 7; y++) {
                        world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    for (int k = 1; k <= 8; k++) for (int y = 8 - k; y <= 10 - k; y++) world.setBlockState(b.add(k, y, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, 1, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, 0, 0), Blocks.IRON_ORE.getDefaultState());
                    // A wall round the top, two high, so the staircase is the only way on from there.
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = 8; y <= 9; y++) {
                        if (x == -2 || x == 10 || z == -2 || z == 2) world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.refreshPositionAndAngles(b.getX() + 0.5, b.getY() + 8, b.getZ() + 0.5, -90, 30);
                    var inventory = player.getInventory();
                    ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
                    pickaxe.setDamage(pickaxe.getMaxDamage() - 1); // One block left in it.
                    inventory.setStack(0, pickaxe);
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 12));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.IRON_PICKAXE) == 0,
                            "The pickaxe broke on the work below: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    context.assertTrue(f.player().getBlockY() <= base[0].getY() + 4, "Well down the staircase when it broke: " + f.player().getBlockPos().toShortString());
                    stone[0] = stoneLeft.get();
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player.getBlockY() >= base[0].getY() + 6 && context.getWorld().isSkyVisible(player.getBlockPos().up()),
                            "Back up under open sky: " + player.getBlockPos().toShortString() + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(stoneLeft.get() == stone[0], "Nothing dug on the way back up: " + stoneLeft.get() + " of " + stone[0] + " stone left");
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Sealed in rock with no pickaxe left and no wood, as live run 57 ended up after its last stone pickaxe broke on
     * the way up: the goal punches its way up toward the surface by hand, slowly, rather than standing there.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 3600)
    public void goalPunchesItsWayUpWithNoPickaxeLeft(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] start = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Puncher"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = start[0] = f.player().getBlockPos().toImmutable();
                    for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 7; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    world.setBlockState(p, Blocks.AIR.getDefaultState());
                    world.setBlockState(p.up(), Blocks.AIR.getDefaultState());
                    world.setTimeOfDay(1000);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(1, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(2, new ItemStack(Items.STICK, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.player().getBlockY() >= start[0].getY() + 2,
                            "Two blocks up by hand: " + f.player().getBlockPos().toShortString() + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.COBBLESTONE) == 24, "Rock punched out drops nothing: " + ItemMoves.count(inventory, Items.COBBLESTONE));
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The bundled sky island's outcrop (docs/worlds/sky-island): sixteen stone with iron ore on its faces, so seen
     * from beside it the middle of a stone block is often behind ore a wooden pickaxe cannot take. The goal still
     * mines its way in, makes a stone pickaxe, then takes the iron, rather than writing the outcrop off and leaving
     * to look for stone across the void (live sky-island run 1).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyOutcrop", tickLimit = 4000)
    public void goalMinesTheIslandOutcropPastTheOreOnItsFaces(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] center = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Outcropper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos c = center[0] = skyIsland(context, f.player());
                    // The bundled island's low wall round the edge, which keeps drops (and animals) from going over.
                    for (int i = -5; i <= 5; i++) for (BlockPos edge : java.util.List.of(c.add(i, 0, -5), c.add(i, 0, 5), c.add(-5, 0, i), c.add(5, 0, i))) {
                        world.setBlockState(edge, Blocks.COBBLESTONE_WALL.getDefaultState());
                    }
                    for (int x = 2; x <= 4; x++) for (int y = 0; y <= 1; y++) for (int z = 1; z <= 4; z++) world.setBlockState(c.add(x, y, z), Blocks.STONE.getDefaultState());
                    for (int[] ore : new int[][] {{2, 0, 1}, {4, 1, 1}, {2, 1, 3}, {4, 0, 4}, {3, 1, 4}, {4, 0, 2}, {3, 1, 2}, {2, 0, 4}}) {
                        world.setBlockState(c.add(ore[0], ore[1], ore[2]), Blocks.IRON_ORE.getDefaultState());
                    }
                    var player = f.player();
                    player.refreshPositionAndAngles(c.getX() - 2.5, c.getY(), c.getZ() + 2.5, player.getYaw(), player.getPitch());
                    var inventory = player.getInventory();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(2, new ItemStack(Items.STICK, 8));
                    inventory.setStack(3, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.player() != null, "The player is still established");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.RAW_IRON) >= 3,
                            "Iron from the outcrop after a stone pickaxe from its stone: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive(), "Alive");
                    context.assertTrue(player.getBlockY() >= center[0].getY() - 1, "Still on the island: " + player.getBlockPos().toShortString());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A sky island whose one tree is gone, its leaves decayed, and the saplings they dropped lying on the grass: the
     * goal walks over and picks them up, then plants one (sky-island run 2 left them to despawn and had no wood left).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skySaplingDrops", tickLimit = 1200)
    public void goalPicksUpTheSaplingsTheLeavesDroppedAndPlantsThem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] center = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Gleaner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos c = center[0] = skyIsland(context, f.player());
                    for (int[] at : new int[][] {{3, 2}, {-3, -2}}) {
                        var drop = new net.minecraft.entity.ItemEntity(world, c.getX() + at[0] + 0.5, c.getY() + 0.2, c.getZ() + at[1] + 0.5,
                                new ItemStack(Items.OAK_SAPLING));
                        drop.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        world.spawnEntity(drop);
                    }
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(2, new ItemStack(Items.STICK, 4));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    long planted = BlockPos.streamOutwards(center[0], 6, 2, 6).filter(pos -> world.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.SAPLINGS)).count();
                    long lying = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(center[0]).expand(8),
                            drop -> drop.getStack().isOf(Items.OAK_SAPLING)).size();
                    context.assertTrue(lying == 0 && planted >= 1, "Picked up and planted: " + lying + " still lying, " + planted + " planted - "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A sky island with no tree left, a sapling in hand and nothing else: the goal plants the sapling on the grass
     * and waits by it for wood. The fixture then grows it, as time would, and the goal cuts the tree.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyReplant", tickLimit = 2400)
    public void goalPlantsASaplingAndWaitsForTheTreeWhereTreesDoNotComeBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] center = new BlockPos[1];
        BlockPos[] sapling = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Planter"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    center[0] = skyIsland(context, f.player());
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.OAK_SAPLING));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    sapling[0] = BlockPos.streamOutwards(center[0], 8, 3, 8).filter(pos -> world.getBlockState(pos).isOf(Blocks.OAK_SAPLING))
                            .map(BlockPos::toImmutable).findFirst().orElse(null);
                    context.assertTrue(sapling[0] != null, "It planted the sapling");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Wait for the sapling"),
                            "It waits for the sapling to grow: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    // Time passing: the sapling grows into a tree.
                    var world = context.getWorld();
                    var state = world.getBlockState(sapling[0]);
                    for (int i = 0; i < 20 && world.getBlockState(sapling[0]).isOf(Blocks.OAK_SAPLING); i++) {
                        ((net.minecraft.block.SaplingBlock) Blocks.OAK_SAPLING).generate(world, sapling[0], world.getBlockState(sapling[0]), world.getRandom());
                    }
                    context.assertTrue(world.getBlockState(sapling[0]).isIn(net.minecraft.registry.tag.BlockTags.LOGS), "The sapling grew: " + state);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.OAK_LOG) >= 1,
                            "It cut the grown tree: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A sky island whose tree has been cut, its leaves still hanging over the grass: with no sapling yet the goal
     * breaks the leaves within reach for the saplings they drop, until one does (each leaf drops one by chance), and
     * plants it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyLeaves", tickLimit = 1200)
    public void goalBreaksLeftoverLeavesForSaplings(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        java.util.List<BlockPos> leaves = new java.util.ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Gleaner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = skyIsland(context, f.player());
                    // A canopy left floating three blocks over the grass, two blocks off, as after the logs were cut.
                    for (int dx = 1; dx <= 3; dx++) for (int dz = -1; dz <= 1; dz++) {
                        BlockPos leaf = p.add(dx + 1, 3, dz);
                        world.setBlockState(leaf, Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
                        leaves.add(leaf);
                    }
                    f.player().getInventory().setStack(0, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    long left = leaves.stream().filter(pos -> world.getBlockState(pos).isOf(Blocks.OAK_LEAVES)).count();
                    boolean planted = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 2, 6).anyMatch(pos -> world.getBlockState(pos).isOf(Blocks.OAK_SAPLING));
                    context.assertTrue(left == 0 || planted && left < leaves.size(), "It broke the leaves until a sapling dropped, and planted it: "
                            + left + " left, planted " + planted + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Fuel and ore matched: with fourteen raw iron and one coal (eight items' worth), the goal loads eight and keeps
     * six. Then, as if a load had been left in a cold furnace, the six are put in the furnace with no fuel: the
     * goal does not wait by it, and when it next holds coal it goes back and adds it as fuel. Obsidian walls keep
     * its search for coal inside the plot.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 6000)
    public void goalRefuelsAFurnaceThatWentColdWithIronStillInIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] furnace = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.OBSIDIAN);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Stoker");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 4));
                    inventory.setStack(5, new ItemStack(Items.RAW_IRON, 14));
                    inventory.setStack(6, new ItemStack(Items.IRON_INGOT, 4)); // With the raw iron, enough for every iron item and two buckets.
                    inventory.setStack(7, new ItemStack(Items.COAL));
                    inventory.setStack(8, new ItemStack(Items.FURNACE));
                    inventory.setStack(9, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    furnace[0] = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 3, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.FURNACE)).findFirst().map(BlockPos::toImmutable).orElse(null);
                    context.assertTrue(furnace[0] != null, "It placed its own furnace");
                })
                .createAndAdd(() -> {
                    var block = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) context.getWorld().getBlockEntity(furnace[0]);
                    var inventory = f.player().getInventory();
                    context.assertTrue(block.getStack(0).getCount() + block.getStack(2).getCount() + ItemMoves.count(inventory, Items.IRON_INGOT) == 12
                                    && ItemMoves.count(inventory, Items.RAW_IRON) == 6 && ItemMoves.count(inventory, Items.COAL) == 0,
                            "One coal took eight raw iron and the other six stayed in the inventory: furnace " + block.getStack(0)
                                    + ", raw iron held " + ItemMoves.count(inventory, Items.RAW_IRON));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var block = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) world.getBlockEntity(furnace[0]);
                    boolean lit = world.getBlockState(furnace[0]).get(net.minecraft.block.AbstractFurnaceBlock.LIT);
                    context.assertTrue(block.getStack(0).isEmpty() && block.getStack(2).isEmpty() && !lit, "The eight are smelted and taken");
                    // A load left in a cold furnace: its own six raw iron moved into the input, and the coal it would find next.
                    var inventory = f.player().getInventory();
                    for (int slot = 0; slot < 36; slot++) {
                        if (inventory.getStack(slot).isOf(Items.RAW_IRON)) block.setStack(0, inventory.removeStack(slot));
                    }
                    inventory.insertStack(new ItemStack(Items.COAL));
                })
                .createAndAdd(() -> {
                    var block = (net.minecraft.block.entity.AbstractFurnaceBlockEntity) context.getWorld().getBlockEntity(furnace[0]);
                    context.assertTrue(block.getStack(0).isEmpty() && ItemMoves.count(f.player().getInventory(), Items.RAW_IRON) == 0,
                            "It went back, added the coal, and the rest was smelted: input " + block.getStack(0));
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.COAL) == 0, "Its coal became the fuel");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The night pit again, but with water under the whole floor, as over an aquifer: every staircase step would
     * end in the water, so the goal turns away from each without digging anything, and no hole is opened into it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightShelter", tickLimit = 900)
    public void goalDoesNotDigAStaircaseIntoWaterBelow(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        Runnable night = () -> context.getWorld().setTimeOfDay(14000);
        BlockPos[] pit = new BlockPos[1];
        int[] waited = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        context.setBlockState(x, -1, z, Blocks.STONE);
                        context.setBlockState(x, 0, z, Blocks.WATER);
                        context.setBlockState(x, 1, z, Blocks.DIRT);
                    }
                    night.run();
                    f.runtime.createNative(f.source, "Wader");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    pit[0] = f.player().getBlockPos().toImmutable();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) for (int y = 2; y <= 4; y++) {
                        BlockPos cell = context.getAbsolutePos(new BlockPos(x, y, z));
                        if (cell.getX() == pit[0].getX() && cell.getZ() == pit[0].getZ()) continue;
                        context.getWorld().setBlockState(cell, Blocks.DIRT.getDefaultState());
                    }
                    night.run();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    night.run();
                    context.assertTrue(++waited[0] >= 200, "Giving the goal time to try every way down");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    for (Direction side : Direction.Type.HORIZONTAL) {
                        BlockPos step = pit[0].down().offset(side);
                        context.assertTrue(world.getBlockState(step).isOf(Blocks.DIRT), "No step dug toward the water: " + step);
                    }
                    context.assertTrue(f.player().isAlive() && !f.player().isTouchingWater(), "Dry and alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The progress watchdog: sealed in an obsidian room it cannot dig with a stone pickaxe, the goal looks around
     * the room and then has nothing feasible. After {@link PortalRunCampaign#STUCK_TICKS} of game time with no
     * progress it gives up on the spot and tries to leave for somewhere else, rather than waiting forever.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 9000)
    public void goalLeavesForElsewhereAfterMakingNoProgress(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] gaveUp = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        context.setBlockState(x, 1, z, Blocks.OBSIDIAN);
                        context.setBlockState(x, 5, z, Blocks.OBSIDIAN);
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.OBSIDIAN);
                    }
                    f.runtime.createNative(f.source, "Restless");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 4));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    gaveUp[0] |= progress.contains("no progress here for a while") || progress.contains("tried elsewhere");
                    context.assertTrue(gaveUp[0], "The watchdog gave up on the spot and tried somewhere else");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Stranded on a one-block pillar five blocks above the floor, with every way off a fall the navigator would not
     * take: the goal jumps down, taking vanilla fall damage it can afford, rather than wait for ever.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 3000)
    public void goalJumpsDownFromALedgeWhenNothingElseIsFeasible(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] top = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Leaper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Everything but the column under the player sinks to the lowest floor: a pillar it stands on.
                    var world = context.getWorld();
                    top[0] = f.player().getBlockPos().toImmutable();
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        BlockPos column = context.getAbsolutePos(new BlockPos(x, 0, z));
                        boolean pillar = column.getX() == top[0].getX() && column.getZ() == top[0].getZ();
                        for (int y = -3; y <= 1; y++) {
                            world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)), pillar ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                        world.setBlockState(context.getAbsolutePos(new BlockPos(x, -4, z)), Blocks.STONE.getDefaultState());
                    }
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 4));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.player().getBlockY() <= top[0].getY() - 4 && f.player().isOnGround(),
                        "Jumped down to the floor: " + f.player().getBlockPos()))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 10, "Took only damage it could afford: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * One obsidian cast in place, the speedrunners' way to a portal without a diamond pickaxe: water from the
     * player's own bucket above the target, lava from its own bucket into the target, where it hardens at once,
     * and the water taken back. Obsidian stands at the target; one lava bucket became an empty bucket.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void castOneObsidianInPlaceFromOwnBuckets(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] target = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caster"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute positions around wherever the player stood up: the target two blocks east, a wall behind it.
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos().toImmutable();
                    target[0] = feet.east(2);
                    for (int up = 0; up <= 2; up++) {
                        world.setBlockState(target[0].up(up), Blocks.AIR.getDefaultState());
                        world.setBlockState(target[0].east().up(up), Blocks.STONE.getDefaultState());
                    }
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                    context.assertTrue(f.runtime.nativeCast(f.source, f.id, target[0]) == 1, "Cast requested");
                })
                .createAndAdd(() -> {
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(activity.phase().equals("IDLE") && activity.work().startsWith("Completed native_cast"), "Cast completes: " + activity);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var inventory = f.player().getInventory();
                    context.assertTrue(world.getBlockState(target[0]).isOf(Blocks.OBSIDIAN), "Obsidian stands where the lava was poured");
                    context.assertFalse(world.getFluidState(target[0].up()).isStill(), "The water was taken back");
                    context.assertTrue(ItemMoves.count(inventory, Items.LAVA_BUCKET) == 0 && ItemMoves.count(inventory, Items.BUCKET) == 1
                            && ItemMoves.count(inventory, Items.WATER_BUCKET) == 1, "One lava bucket emptied; the water bucket refilled");
                    context.assertTrue(f.player().isAlive() && !f.player().isOnFire(), "Unhurt");
                    var action = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(action != null && action.kind().equals("native_cast") && action.state() == ActionRecord.State.VERIFIED,
                            "Durable ledger records a verified cast: " + action);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * No diamonds anywhere, only a lava pool: the goal casts the whole frame instead of mining obsidian. Each frame
     * block is lava carried from the pool in its own bucket and hardened in place under water from its other
     * bucket, against a mould of its own cobblestone; the corners are cobblestone; then flint and steel lights it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castPortal", tickLimit = 7000)
    public void goalCastsAWholePortalFrameFromALavaPoolWithoutDiamonds(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Founder"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Absolute positions around where the player stood up: open stone ground with room above for a
                    // frame, walled in so cast water and the player stay inside, and a lava pool set into the floor
                    // three blocks south.
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    feet[0] = p;
                    for (int x = -6; x <= 6; x++) for (int z = -7; z <= 6; z++) {
                        boolean edge = Math.abs(x) == 6 || z == -7 || z == 6;
                        world.setBlockState(p.add(x, -2, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 8; y++) {
                            world.setBlockState(p.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    for (int x = -3; x <= 3; x++) for (int z = 3; z <= 4; z++) world.setBlockState(p.add(x, -1, z), Blocks.LAVA.getDefaultState());
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
                    int portal = 0, frame = 0;
                    for (BlockPos pos : BlockPos.iterate(feet[0].add(-6, 0, -7), feet[0].add(6, 8, 6))) {
                        var state = context.getWorld().getBlockState(pos);
                        if (state.isOf(Blocks.NETHER_PORTAL)) portal++;
                        if (state.isOf(Blocks.OBSIDIAN) && pos.getY() >= feet[0].getY()) frame++;
                    }
                    context.assertTrue(portal == 6 && frame >= 10, "A cast frame stands lit: " + portal + " portal blocks, " + frame
                            + " obsidian; " + f.runtime.nativeActivityStatus(Actor.console(), f.id) + "; at " + f.player().getBlockPos()
                            + " from " + feet[0] + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress() + "; " + pool(context, feet[0]));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.LAVA_BUCKET) == 0 && ItemMoves.count(inventory, Items.WATER_BUCKET) == 1
                            && ItemMoves.count(inventory, Items.BUCKET) == 1, "Both buckets kept, the water one full");
                    context.assertTrue(ItemMoves.count(inventory, Items.DIAMOND) == 0 && ItemMoves.count(inventory, Items.OBSIDIAN) == 0,
                            "No diamonds and no mined obsidian: the frame was cast where it stands");
                    context.assertTrue(player.isAlive() && player.getHealth() >= 10, "Unhurt by the lava: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** What became of the cast fixture's lava pool, for a failure message. */
    private static String pool(TestContext context, BlockPos p) {
        int sources = 0, flowing = 0, obsidian = 0, stone = 0, air = 0;
        for (int x = -3; x <= 3; x++) for (int z = 3; z <= 4; z++) {
            BlockPos at = p.add(x, -1, z);
            var fluid = context.getWorld().getFluidState(at);
            var state = context.getWorld().getBlockState(at);
            if (fluid.isIn(FluidTags.LAVA)) { if (fluid.isStill()) sources++; else flowing++; }
            else if (state.isOf(Blocks.OBSIDIAN)) obsidian++;
            else if (state.isAir()) air++;
            else stone++;
        }
        return "pool: " + sources + " lava sources, " + flowing + " flowing, " + obsidian + " obsidian, " + stone + " other blocks, " + air + " empty";
    }

    /** A roof over the fixture: the goal treats it as underground, so a night set by another test changes nothing here. */
    /**
     * A grass-topped dirt island eleven blocks across and three deep, floating twelve blocks over the plot with
     * nothing at the bottom of the world under it (the void, as below a sky island), and the player moved onto its
     * middle. Nothing to walk off to: concurrent sky fixtures sit a dozen blocks apart. Returns the player's feet.
     */
    private static BlockPos skyIsland(TestContext context, net.minecraft.server.network.ServerPlayerEntity player) {
        var world = context.getWorld();
        BlockPos p = player.getBlockPos().up(12).toImmutable();
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            for (int dy = -3; dy <= -1; dy++) world.setBlockState(p.add(dx, dy, dz), (dy == -1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).getDefaultState());
            for (int dy = 0; dy <= 8; dy++) world.setBlockState(p.add(dx, dy, dz), Blocks.AIR.getDefaultState());
            world.setBlockState(new BlockPos(p.getX() + dx, world.getBottomY(), p.getZ() + dz), Blocks.AIR.getDefaultState());
        }
        player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, player.getYaw(), player.getPitch());
        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
        world.setTimeOfDay(1000); // Daytime: these fixtures are not about the night.
        return p;
    }

    private static void roof(TestContext context) {
        for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) context.setBlockState(x, 7, z, Blocks.STONE);
    }
}
