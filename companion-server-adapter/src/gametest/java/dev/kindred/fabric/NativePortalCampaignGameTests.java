package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.PlanPreferences;
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
                    // Wounded by the player's own earlier blows, which it remembers: a player knows a mob's health only
                    // from the hits it saw land (E-41a).
                    f.runtime.nativeBody(f.id).orElseThrow().beliefs().hit(zombie[0].getUuid(), zombie[0].getMaxHealth(), false, 12,
                            context.getWorld().getTime());
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
                    // And believed whole: a player knows a mob's health only from the hits it saw land (E-41a), and here
                    // nothing it saw has worn this one down.
                    f.runtime.nativeBody(f.id).orElseThrow().beliefs().forget(zombie[0].getUuid());
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
     * A recorded fight against a zombie in reach, under a roof with room to jump: the player jumps as its swing
     * recovers and strikes on the way down, as players do, so its hits are critical ones (half as much again). An
     * iron sword's ordinary hits take four to kill a zombie; critical ones take three.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void fightLandsCriticalHitsByJumping(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    roof(context);
                    f.runtime.createNative(f.source, "Critter");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    BlockPos at = player.getBlockPos();
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(context.getWorld());
                    zombie[0].refreshPositionAndAngles(at.getX() + 2.5, at.getY(), at.getZ() + 0.5, 90, 0);
                    zombie[0].setPersistent();
                    zombie[0].setAiDisabled(true); // It stands there: the fight is the player's swings alone.
                    context.getWorld().spawnEntity(zombie[0]);
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeFight(f.source, f.id, zombie[0].getUuid(), 0) == 1, "Fight requested"))
                .createAndAdd(() -> context.assertFalse(zombie[0].isAlive(), "The zombie was killed"))
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record != null && record.kind().equals("native_fight") && record.state() == ActionRecord.State.VERIFIED,
                            "A verified fight: " + record);
                    var evidence = java.util.regex.Pattern.compile("hits=(\\d+); crits=(\\d+)").matcher(record.afterEvidence());
                    context.assertTrue(evidence.find(), "Hits and crits recorded: " + record.afterEvidence());
                    int hits = Integer.parseInt(evidence.group(1)), crits = Integer.parseInt(evidence.group(2));
                    context.assertTrue(crits >= 2 && hits <= 3, "Critical hits by jumping: " + hits + " hits, " + crits + " critical");
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
     * Badly hurt, a skeleton shooting from across a lava moat and a wounded zombie in the next cell: the goal fights the
     * zombie and takes no cover until it is dead. A block put up against the skeleton does not stop a zombie already in
     * reach, and holding one leaves no sword in hand (in the first of B7's second pairs, five of nine deaths were a
     * zombie's or a spider's while the player held a block for cover from a skeleton or a witch). The zombie's wounds
     * are staged as the player's own earlier blows, and the skeleton is spawned without its AI, so it never shoots.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalFightsAZombieInReachBeforeTakingCoverFromASkeleton(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.SkeletonEntity[] skeleton = new net.minecraft.entity.mob.SkeletonEntity[1];
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        boolean[] coveredFirst = new boolean[1];
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
                    f.runtime.createNative(f.source, "Pressed");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(10); // No natural healing while it fights.
                    player.getHungerManager().setSaturationLevel(0);
                    BlockPos at = player.getBlockPos();
                    skeleton[0] = context.spawnMob(net.minecraft.entity.EntityType.SKELETON, new net.minecraft.util.math.Vec3d(6.5, 2, 6.5));
                    skeleton[0].setTarget(player);
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(context.getWorld());
                    zombie[0].refreshPositionAndAngles(at.getX() - 0.5, at.getY(), at.getZ() + 0.5, 270, 0); // The next cell west.
                    zombie[0].setPersistent();
                    zombie[0].setHealth(8);
                    context.getWorld().spawnEntity(zombie[0]);
                    zombie[0].setTarget(player);
                    f.runtime.nativeBody(f.id).orElseThrow().beliefs().hit(zombie[0].getUuid(), zombie[0].getMaxHealth(), false, 12,
                            context.getWorld().getTime());
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    if (zombie[0].isAlive()) coveredFirst[0] |= f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Take cover");
                    context.assertFalse(zombie[0].isAlive(), "The zombie in reach was fought");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertFalse(coveredFirst[0], "No cover taken while the zombie was in reach");
                    context.assertTrue(player.isAlive(), "Alive: " + player.getHealth());
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
     * Night, in a passage sealed in stone, with three iron for its pickaxe but no sticks or wood to make it with: the
     * route's next step is logs from the surface. Below ground the night changes nothing, so it mines the iron ore in
     * view meanwhile rather than wait for morning, as players mine on through the night (in B7's first paired run up
     * to a tenth of a player's time was spent waiting below for daylight). Its stock is given as though gathered.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightMining", tickLimit = 1200)
    public void atNightBelowGroundItMinesTheRoutesIronRatherThanWaitForLogs(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] ore = new BlockPos[1];
        int[] settle = new int[1];
        boolean[] waited = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightshift"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(14000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    // A short passage east, sealed inside the stone, the ore at its end in plain view.
                    for (int i = 0; i <= 2; i++) {
                        world.setBlockState(p.east(i), Blocks.AIR.getDefaultState());
                        world.setBlockState(p.east(i).up(), Blocks.AIR.getDefaultState());
                    }
                    ore[0] = p.east(3);
                    world.setBlockState(ore[0], Blocks.IRON_ORE.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(5, new ItemStack(Items.COAL, 4));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(8, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    waited[0] |= f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("wait for daylight");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.RAW_IRON) >= 1, "Mined the iron in the night");
                })
                .createAndAdd(() -> {
                    context.assertFalse(waited[0], "It did not wait for daylight");
                    context.assertTrue(context.getWorld().isNight(), "Still night");
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
     * The cave the goal explores for its iron in the fixture above, with no mob about, pressing on, but having learned
     * in this world to leave caves alone (F-19c, a habit its strategist's note taught): it digs its own way instead.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 900)
    public void goalKeepsAHabitOfLeavingCavesAlone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] explored = {false};
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Habitual"))
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
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PRESS_ON", java.util.List.of(),
                            "Both lives here ended in the caves under spawn; dig my own way", "", java.util.List.of(), null, "NO_CAVES", null).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "Advice taken");
                })
                .createAndAdd(() -> {
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    explored[0] |= progress.contains("Explore the cave");
                    context.assertTrue(progress.contains("Branch mine") || progress.contains("Dig down"),
                            "It digs its own way for iron: " + progress);
                })
                .createAndAdd(() -> {
                    context.assertFalse(explored[0], "It kept to its habit and left the cave alone");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Stocked as usual for the work below (six bread) at the iron stage, a cow in sight: the route alone goes on to
     * iron, but advised food first (F-19c) the player hunts the cow before anything else.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 700)
    public void goalHuntsFirstWhenAdvisedFoodFirst(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] advised = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Forager"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    world.setTimeOfDay(1000);
                    var cow = net.minecraft.entity.EntityType.COW.create(world);
                    cow.refreshPositionAndAngles(p.getX() + 3.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    cow.setPersistent();
                    cow.setAiDisabled(true);
                    world.spawnEntity(cow);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 6));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 19));
                    inventory.setStack(7, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(6, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PRESS_ON", java.util.List.of(),
                            "", "Six bread will not last the work below", java.util.List.of(), "FOOD", null, null).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "Advice taken");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    advised[0] |= f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Food first");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.BEEF) >= 1,
                            "It hunted the cow: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(advised[0], "Food came first, as advised");
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Advised wood first with four logs in hand and no tree in sight, as on sky-island run 11 once its one tree was cut:
     * the focus only cuts trees it can see, so the route goes on and makes a table and a wooden pickaxe from what it
     * has, rather than exploring for more logs while the logs it holds wait (F-19c).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void woodFirstWithNoTreeInSightLetsTheRouteGoOn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Woodless"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    f.player().getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 4));
                    f.player().getInventory().setStack(1, new ItemStack(Items.BREAD, 6));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                    var advice = dev.kindred.core.survival.StrategyAdvice.validated("PRESS_ON", java.util.List.of(),
                            "", "Stalled for logs", java.util.List.of(), "WOOD", null, null).orElseThrow();
                    context.assertTrue(f.runtime.adviseGoal(f.id, advice), "Advice taken");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.WOODEN_PICKAXE) >= 1,
                            "A wooden pickaxe from the logs in hand: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A player's memory of a world keeps the habits its notes teach across a save and a load (F-19c): a habit stays
     * with its first note, and a retired habit leaves its note as a plain note. The strategist's queued follow-ups
     * (F-19e) are kept the same way.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void memoryKeepsHabitsWithTheirNotesAcrossASave(TestContext context) {
        var folder = context.getWorld().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .resolve("kindred-context-fixture-" + java.util.UUID.randomUUID());
        try (var store = new ModelContextStore(folder)) {
            var id = java.util.UUID.randomUUID();
            var memory = new ModelContextStore.PlayerContext();
            memory.addNote(1, "Caves under spawn killed me twice", PlanPreferences.Habit.NO_CAVES);
            memory.addNote(2, "Ran out of food below", PlanPreferences.Habit.MORE_FOOD);
            memory.addNote(3, "Ran out of food below again", PlanPreferences.Habit.MORE_FOOD);
            memory.addNote(4, "A plain lesson");
            context.assertTrue(memory.habits().size() == 2, "Two habits: " + memory.habits());
            context.assertTrue(memory.noteTexts().stream().filter(text -> text.startsWith("[habit MORE_FOOD]")).count() == 1,
                    "A habit stays with its first note: " + memory.noteTexts());
            memory.addNote(5, "Dug in too late twice", PlanPreferences.Habit.SHELTER_EARLY);
            memory.addNote(6, "Pickaxe wore out with none spare", PlanPreferences.Habit.SPARE_PICKAXE);
            context.assertTrue(memory.habits().size() == 4, "Four habits: " + memory.habits());
            memory.retire(PlanPreferences.Habit.NO_CAVES);
            context.assertFalse(memory.habits().contains(PlanPreferences.Habit.NO_CAVES), "Retired");
            context.assertTrue(memory.noteTexts().contains("Caves under spawn killed me twice"), "Its note kept, plain: " + memory.noteTexts());
            store.save(id, memory);
            var loaded = store.load(id, "Rememberer").get(5, java.util.concurrent.TimeUnit.SECONDS);
            context.assertTrue(loaded.habits().equals(memory.habits()) && loaded.notes.size() == memory.notes.size(),
                    "The habits come back from the file: " + loaded.habits() + " " + loaded.noteTexts());
            // The strategist's follow-ups (F-19e) come back from the file too; one edited into something unknown does not.
            var queued = dev.kindred.core.guide.TaskQueue.EMPTY.advise(java.util.List.of(
                    new dev.kindred.core.guide.Want(dev.kindred.core.guide.Need.SHIELD, 1, dev.kindred.core.guide.Objective.NETHER_TRIP, "skeleton arrows"),
                    new dev.kindred.core.guide.Want(dev.kindred.core.guide.Need.ARROWS, 48, dev.kindred.core.guide.Objective.END_FIGHT, "misses")));
            loaded.queue(queued);
            loaded.queue.add(new ModelContextStore.QueuedRecord("ELYTRA", 1, "END_FIGHT", "edited by hand"));
            store.save(id, loaded);
            var again = store.load(id, "Rememberer").get(5, java.util.concurrent.TimeUnit.SECONDS);
            context.assertTrue(again.taskQueue().equals(queued), "The queue comes back from the file: " + again.taskQueue());
            // Live run 66 at the real-time pace wrote one lesson twenty ways: it is kept once, in its first wording,
            // and moves up as the newest; a different lesson is kept beside it.
            var reworded = new ModelContextStore.PlayerContext();
            String first = "Hurt at 4/20 with no food below ground keeps repeating; climb to the surface, eat and heal before any tunnelling, and avoid the danger zones while hurt.";
            reworded.addNote(1, first);
            reworded.addNote(2, "Dig in before dusk: two nights on the surface cost lives here.", PlanPreferences.Habit.SHELTER_EARLY);
            reworded.addNote(3, "Hurt and hungry below ground keeps repeating; surface, eat and heal before any tunnelling, and stay clear of the danger zones while hurt.");
            reworded.addNote(4, "Hurt and hungry below ground keeps repeating; surface to eat and heal before any tunnelling, and stay clear of the danger zones while hurt.");
            context.assertTrue(reworded.notes.size() == 2 && reworded.notes.get(1).text().equals(first) && reworded.notes.get(1).tick() == 4
                    && reworded.habits().equals(java.util.Set.of(PlanPreferences.Habit.SHELTER_EARLY)),
                    "One lesson kept once, in its first wording, as the newest: " + reworded.noteTexts());
        } catch (Exception failed) {
            throw new AssertionError("Memory round trip failed: " + failed, failed);
        }
        context.complete();
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
     * Morning on a sky island, the player still in the sealed pit it waited out the night in, two deep, standing on
     * the island's bottom layer, as on sky-island run 8, with both buckets filled for a generator. Nothing fits in
     * that layer with the void under it, and from inside the pit nothing else was in reach, so run 8 waited there
     * for ten minutes. In daylight the player climbs out first, then gets on with the generator.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyPit", tickLimit = 2400)
    public void goalClimbsOutOfItsNightPitInTheMorningOnAnIsland(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] surface = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Earlyriser"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = surface[0] = skyIsland(context, f.player());
                    // The night's pit: two cells dug out, the way in sealed with dirt over its head.
                    BlockPos pit = p.add(-2, 0, 2);
                    world.setBlockState(pit.down(), Blocks.AIR.getDefaultState());
                    world.setBlockState(pit.down(2), Blocks.AIR.getDefaultState());
                    world.setBlockState(pit, Blocks.DIRT.getDefaultState());
                    var player = f.player();
                    player.refreshPositionAndAngles(pit.getX() + 0.5, pit.getY() - 2, pit.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(3, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(4, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(5, new ItemStack(Items.BEEF, 4));
                    inventory.setStack(6, new ItemStack(Items.OAK_PLANKS, 3));
                    inventory.setStack(7, new ItemStack(Items.STICK, 11));
                    inventory.setStack(8, new ItemStack(Items.COBBLESTONE, 3));
                    inventory.setStack(9, new ItemStack(Items.IRON_INGOT));
                    inventory.setStack(10, new ItemStack(Items.FLINT));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.player().getBlockY() >= surface[0].getY() - 1,
                            "Out of the pit: " + f.player().getBlockPos().toShortString() + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.runtime.panelBots(Actor.console()).getFirst().goalProgress().contains("Generator"),
                            "On with the generator: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
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
                    // It stands on ground of its own: past the fixture's floor the plot is open below, and the foot of
                    // the staircase rested on whatever the neighbouring fixtures left there.
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 7; y++) {
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
     * Live run 69 at y=61 under its trees: a player in a chamber four below the ground, sealed in dirt, with ten trees
     * above it that it knows of and no way to walk to any. Three walks find no way from there, and it is the place, not
     * the trees: it stops setting out for one tree after another, digs its way up by hand, and takes logs on the
     * surface. Before, it walked for every tree it knew from there, four a second, before it looked for a way up (974
     * such walks in the run, 293 more for a way back up).
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "stranded", tickLimit = 3000)
    public void goalDigsUpOutOfAChamberAfterAFewWalksFindNoWayToItsTrees(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2900);
        BlockPos o = context.getAbsolutePos(new BlockPos(20, 0, 20));
        int feetY = o.getY() + 11;
        int[] failedBeforeUp = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Stranded"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-16, 0, -16), o.add(16, 24, 16));
                    // Stone, then dirt from nine up, grass at fourteen, open sky above.
                    for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = 0; y <= 24; y++) {
                        var state = y < 9 ? Blocks.STONE.getDefaultState() : y < 14 ? Blocks.DIRT.getDefaultState()
                                : y == 14 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState();
                        world.setBlockState(o.add(x, y, z), state);
                    }
                    // The chamber, three square and two high, lit, its feet four below the grass's.
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 11; y <= 12; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    world.setBlockState(o.add(1, 11, 1), Blocks.TORCH.getDefaultState());
                    // Ten trees in a ring round it, eight to eleven out.
                    int[][] trees = {{9, 0}, {-9, 0}, {0, 9}, {0, -9}, {7, 7}, {-7, -7}, {7, -7}, {-7, 7}, {11, 4}, {-4, 11}};
                    for (int[] t : trees) {
                        for (int y = 15; y <= 19; y++) world.setBlockState(o.add(t[0], y, t[1]), Blocks.OAK_LOG.getDefaultState());
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 18; y <= 20; y++) {
                            if ((dx != 0 || dz != 0 || y == 20) && world.getBlockState(o.add(t[0] + dx, y, t[1] + dz)).isAir()) {
                                world.setBlockState(o.add(t[0] + dx, y, t[1] + dz), Blocks.OAK_LEAVES.getDefaultState()
                                        .with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
                            }
                        }
                    }
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(o.getX() + 0.5, feetY, o.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    if (failedBeforeUp[0] < 0 && f.player().getBlockY() > feetY) failedBeforeUp[0] = f.runtime.walksFailed(f.id);
                    context.assertTrue(failedBeforeUp[0] >= 0, "On its way up: at " + f.player().getBlockPos().subtract(o).toShortString()
                            + " after " + f.runtime.walksFailed(f.id) + " walks in vain - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    context.assertTrue(failedBeforeUp[0] <= PortalRunCampaign.STRANDED + 1,
                            "No more than a few walks in vain before it dug its way up: " + failedBeforeUp[0]);
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.OAK_LOG) > 0,
                            "Logs taken on the surface: at " + player.getBlockPos().subtract(o).toShortString() + " - "
                                    + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    KindredMod.LOGGER.info("Stranded: up out of its chamber after {} walks in vain, logs taken", failedBeforeUp[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The staircase above with a pit two deep under the iron ore: the drop falls into it, the player follows it down
     * as its pickaxe breaks, and the way it came is a two-block step up it cannot walk. Before, the walk back up
     * completed at once without moving and was asked for again until the no-progress rule moved the player on three
     * minutes later (the staircase fixture's failure while its foot lay open). Now a walk back that ends where it
     * started is set aside, and the player climbs out, clearing a step by hand. The course runs ten blocks east, into
     * the next plot, so it has a batch of its own: a neighbour's setup once filled its ore and pit with stone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "pitfall", tickLimit = 2400)
    public void goalClimbsOutOfAPitBelowItsWayBackWithNoPickaxe(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] base = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Pitfallen"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos b = base[0] = f.player().getBlockPos().toImmutable();
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) for (int y = -3; y <= 7; y++) {
                        world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    for (int k = 1; k <= 8; k++) for (int y = 8 - k; y <= 10 - k; y++) world.setBlockState(b.add(k, y, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, 1, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, 0, 0), Blocks.IRON_ORE.getDefaultState());
                    // The pit under the ore, two deep.
                    world.setBlockState(b.add(9, -1, 0), Blocks.AIR.getDefaultState());
                    world.setBlockState(b.add(9, -2, 0), Blocks.AIR.getDefaultState());
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
                    context.assertTrue(f.player().getBlockY() < base[0].getY() - 1, "Down in the pit: " + f.player().getBlockPos().toShortString()
                            + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    context.assertTrue(player.getBlockY() >= base[0].getY() + 6 && context.getWorld().isSkyVisible(player.getBlockPos().up()),
                            "Out of the pit and back up under open sky: " + player.getBlockPos().toShortString() + " - "
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
     * At the bottom of a flooded shaft nine blocks deep, short of breath, as live run 69 twice ended up holed up in a
     * flooded passage: the goal swims up and out onto dry ground before anything else, and the runtime's drowning
     * check lets it. Before, each wait it chose there was cut short by that check and the player sank and drowned.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "flooded", tickLimit = 800)
    public void goalSwimsUpOutOfAFloodedShaftBeforeAnythingElse(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] base = new BlockPos[1];
        float[] lowestHealth = {20};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Diver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos b = base[0] = f.player().getBlockPos().up(12).toImmutable();
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -11; y <= 4; y++) {
                        world.setBlockState(b.add(x, y, z), Blocks.STONE.getDefaultState());
                    }
                    // A dry room at the top, and the shaft below it full of still water.
                    for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 2; y++) {
                        world.setBlockState(b.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int y = -10; y <= -1; y++) world.setBlockState(b.add(0, y, 0), Blocks.WATER.getDefaultState());
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.refreshPositionAndAngles(b.getX() + 0.5, b.getY() - 10, b.getZ() + 0.5, 0, 0);
                    player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    player.setAir(100); // A third of a breath left: the runtime's drowning check is already firing.
                    var inventory = player.getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 8));
                    inventory.setStack(5, new ItemStack(Items.CRAFTING_TABLE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    lowestHealth[0] = Math.min(lowestHealth[0], player.getHealth());
                    context.assertTrue(player.isAlive(), "Alive");
                    context.assertTrue(!player.isTouchingWater() && player.isOnGround() && player.getBlockY() >= base[0].getY(),
                            "Out of the shaft onto dry ground: " + player.getBlockPos().toShortString() + " air " + player.getAir()
                                    + " - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(lowestHealth[0] >= 18, "Barely or never short of air on the way: lowest health " + lowestHealth[0]);
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
                    // Rock punched out drops nothing: the stack never grows, though blocks it pillars up on come off it.
                    context.assertTrue(ItemMoves.count(inventory, Items.COBBLESTONE) <= 24, "Rock punched out drops nothing: " + ItemMoves.count(inventory, Items.COBBLESTONE));
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
     * A sky island at the smelting point, with no coal anywhere: seven raw iron for the buckets and the flint and
     * steel, two logs and three planks. The goal first makes a log into charcoal with a plank (F-25c5c), then smelts
     * its iron on the charcoal.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyCharcoal", tickLimit = 2400)
    public void goalMakesCharcoalToSmeltWithWhereThereIsNoCoal(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        boolean[] charcoal = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Collier"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    skyIsland(context, f.player());
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 32));
                    inventory.setStack(4, new ItemStack(Items.STICK, 8));
                    inventory.setStack(5, new ItemStack(Items.RAW_IRON, 7));
                    inventory.setStack(6, new ItemStack(Items.OAK_LOG, 2));
                    inventory.setStack(7, new ItemStack(Items.OAK_PLANKS, 3));
                    inventory.setStack(8, new ItemStack(Items.FURNACE));
                    inventory.setStack(9, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    if (ItemMoves.count(player.getInventory(), Items.CHARCOAL) > 0) charcoal[0] = true;
                    BlockPos.streamOutwards(player.getBlockPos(), 6, 3, 6).forEach(pos -> {
                        if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity furnace
                                && (furnace.getStack(2).isOf(Items.CHARCOAL) || furnace.getStack(1).isOf(Items.CHARCOAL))) charcoal[0] = true;
                    });
                    int ingots = ItemMoves.count(player.getInventory(), Items.IRON_INGOT);
                    context.assertTrue(charcoal[0] && ingots >= 3, "Charcoal made, then iron smelted on it: charcoal " + charcoal[0] + ", "
                            + ingots + " ingots - " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.OAK_LOG) <= 1, "A log went to charcoal");
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A sky island's one tree with its lower logs cut and the top two left hanging, four and five blocks over the
     * feet of a player on the grass, as sky-island run 12 left it. A player standing where the trunk was reaches both.
     * Before, the goal counted them as a tree in sight but would not go for logs that high, and explored over the
     * void for nine minutes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skyTrunkTop", tickLimit = 1200)
    public void goalCutsTheLogsLeftAtTheTopOfACutTrunk(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] logs = new BlockPos[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Topper"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = skyIsland(context, f.player());
                    logs[0] = p.add(3, 4, 1);
                    logs[1] = p.add(3, 5, 1);
                    for (BlockPos log : logs) world.setBlockState(log, Blocks.OAK_LOG.getDefaultState());
                    f.player().getInventory().setStack(0, new ItemStack(Items.BREAD, 5));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    long left = java.util.Arrays.stream(logs).filter(log -> world.getBlockState(log).isIn(net.minecraft.registry.tag.BlockTags.LOGS)).count();
                    context.assertTrue(left == 0, "It cut both logs from where the trunk stood: " + left + " left - "
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
     * No coal, three raw iron, planks and the sticks kept for pickaxes: the goal smelts with two planks, which burn
     * just the three, keeps its sticks, and takes its emptied furnace on with it. Sticks ranked above planks as fuel,
     * so a coal-less smelt burnt the stick reserve kept for the pickaxes still to make (B7's second pairs climbed out
     * for sticks again and again). Its stock is given as though gathered, with both buckets and a chestplate made: the
     * three are more than the route still wants, and so are smelted now (F-25c4q).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2400)
    public void withNoCoalItSmeltsWithPlanksKeepsItsSticksAndTakesItsFurnaceBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] furnace = new BlockPos[1];
        boolean[] sticksBurnt = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                        if (x != 0 && x != 7 && z != 0 && z != 7) continue;
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.OBSIDIAN);
                    }
                    roof(context);
                    f.runtime.createNative(f.source, "Kindler");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 24));
                    inventory.setStack(5, new ItemStack(Items.OAK_PLANKS, 8));
                    inventory.setStack(6, new ItemStack(Items.RAW_IRON, 3));
                    inventory.setStack(7, new ItemStack(Items.FURNACE));
                    inventory.setStack(8, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(9, new ItemStack(Items.SHIELD));
                    inventory.setStack(10, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(11, new ItemStack(Items.BUCKET, 2));
                    inventory.setStack(12, new ItemStack(Items.IRON_CHESTPLATE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    furnace[0] = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 3, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.FURNACE)).findFirst().map(BlockPos::toImmutable).orElse(null);
                    context.assertTrue(furnace[0] != null, "It placed its own furnace");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    if (context.getWorld().getBlockEntity(furnace[0]) instanceof net.minecraft.block.entity.AbstractFurnaceBlockEntity block) {
                        sticksBurnt[0] |= block.getStack(1).isOf(Items.STICK);
                    }
                    context.assertTrue(ItemMoves.count(inventory, Items.IRON_INGOT) + 3 * ItemMoves.count(inventory, Items.IRON_PICKAXE) >= 3,
                            "The three smelted and taken: ingots " + ItemMoves.count(inventory, Items.IRON_INGOT));
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertFalse(sticksBurnt[0], "No stick went in as fuel");
                    context.assertTrue(ItemMoves.count(inventory, Items.STICK) + 2 * ItemMoves.count(inventory, Items.IRON_PICKAXE) == 24,
                            "Its sticks kept: " + ItemMoves.count(inventory, Items.STICK));
                    context.assertTrue(ItemMoves.count(inventory, Items.OAK_PLANKS) == 6, "Two planks burnt: " + ItemMoves.count(inventory, Items.OAK_PLANKS));
                })
                .createAndAdd(() -> context.assertTrue(!context.getWorld().getBlockState(furnace[0]).isOf(Blocks.FURNACE)
                                && ItemMoves.count(f.player().getInventory(), Items.FURNACE) == 1, "Its emptied furnace taken back"))
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Its iron smelted in its own furnace, with raw porkchops carried and coal left: the goal cooks them there before it
     * takes the furnace on with it, as players cook before a trip (cooked, a porkchop fills eight where a raw one fills
     * three; B7's second pairs never cooked, and climbed out for food again and again). Its stock is given as though
     * gathered, with both buckets and a chestplate made, so its three raw iron are smelted now (F-25c4q).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 3600)
    public void itCooksItsRawMeatInItsOwnFurnaceBeforeTakingItOn(TestContext context) {
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
                    f.runtime.createNative(f.source, "Cook");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.PORKCHOP, 6));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 24));
                    inventory.setStack(5, new ItemStack(Items.COAL, 2));
                    inventory.setStack(6, new ItemStack(Items.RAW_IRON, 3));
                    inventory.setStack(7, new ItemStack(Items.FURNACE));
                    inventory.setStack(8, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(9, new ItemStack(Items.SHIELD));
                    inventory.setStack(10, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(11, new ItemStack(Items.BUCKET, 2));
                    inventory.setStack(12, new ItemStack(Items.IRON_CHESTPLATE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    furnace[0] = BlockPos.streamOutwards(f.player().getBlockPos(), 6, 3, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.FURNACE)).findFirst().map(BlockPos::toImmutable).orElse(null);
                    context.assertTrue(furnace[0] != null, "It placed its own furnace");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.COOKED_PORKCHOP) == 6 && ItemMoves.count(inventory, Items.PORKCHOP) == 0,
                            "All six cooked and taken: cooked " + ItemMoves.count(inventory, Items.COOKED_PORKCHOP) + ", raw "
                                    + ItemMoves.count(inventory, Items.PORKCHOP) + "; " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> context.assertTrue(!context.getWorld().getBlockState(furnace[0]).isOf(Blocks.FURNACE)
                                && ItemMoves.count(f.player().getInventory(), Items.FURNACE) == 1, "Then its emptied furnace taken on"))
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Hurt, out of food and below ground, it climbs out; over its head a column of gravel rests on the block above its
     * head room. It does not dig that block out, which would bring the gravel down onto it one block after another as
     * each was dug out of its head room (Kindred suffocated twice so, climbing out for food, in B7's second pairs): it
     * climbs from the end of its passage instead. The passage and the gravel are staged.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gravelOverhead", tickLimit = 1800)
    public void climbingOutItNeverDigsOutTheBlockGravelRestsOnOverItsHead(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] start = new BlockPos[1];
        boolean[] buried = new boolean[1];
        int[] settle = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Burrower"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    start[0] = p;
                    // Solid stone round a passage seven long, east to west, sealed: no daylight, no way but up.
                    for (int dx = -4; dx <= 4; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 12; dy++) {
                        world.setBlockState(p.add(dx, dy, dz), Blocks.STONE.getDefaultState());
                    }
                    for (int dx = -3; dx <= 3; dx++) {
                        world.setBlockState(p.add(dx, 0, 0), Blocks.AIR.getDefaultState());
                        world.setBlockState(p.add(dx, 1, 0), Blocks.AIR.getDefaultState());
                    }
                    // Six gravel resting on the block over its head room.
                    for (int dy = 3; dy <= 8; dy++) world.setBlockState(p.up(dy), Blocks.GRAVEL.getDefaultState());
                })
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 20, "Light settling"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(3, new ItemStack(Items.STICK, 8));
                    player.setHealth(8);
                    player.getHungerManager().setFoodLevel(10); // No natural healing, and nothing to eat.
                    player.getHungerManager().setSaturationLevel(0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    buried[0] |= f.player().isInsideWall();
                    context.assertTrue(f.player().getBlockY() > start[0].getY(), "Climbed a step: " + f.player().getBlockPos() + "; "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int gravel = 0;
                    for (int dy = 3; dy <= 8; dy++) if (world.getBlockState(start[0].up(dy)).isOf(Blocks.GRAVEL)) gravel++;
                    context.assertFalse(buried[0], "Never buried");
                    context.assertTrue(gravel == 6, "The gravel still rests where it was: " + gravel);
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * With its wooden pickaxe made and trees round it, the goal takes the trip's wood before its first cobblestone, which
     * takes it below and out of sight of them: sticks for its stone tools and their reserve, a spare table's, a shield's
     * and the fuel for its iron. In the live runs that checked F-25c4b to h it went straight for stone, packed nothing
     * once below, and three of five players ran out of sticks and wood there and were left with no pickaxe. The stone
     * plot, its four trunks and the wooden pickaxe, table and planks are staged.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "tripWood", tickLimit = 2400)
    public void withItsWoodenPickaxeAmongTheTreesItTakesTheTripsWoodBeforeItsFirstStone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        int[] woodAtFirstStone = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Packer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-10, -3, -10), p.add(10, 6, 10));
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(p.add(x, y, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 6; y++) world.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    // Four trunks of five, enough for the trip and a little over.
                    for (int[] t : new int[][] {{5, 3}, {-4, 6}, {6, -5}, {-6, -4}}) {
                        for (int y = 0; y <= 4; y++) world.setBlockState(p.add(t[0], y, t[1]), Blocks.OAK_LOG.getDefaultState());
                    }
                    world.setBlockState(p.add(1, 0, 0), Blocks.CRAFTING_TABLE.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.WOODEN_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.OAK_PLANKS, 3));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    world.setTimeOfDay(1000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    int wood = ItemMoves.count(inventory, Items.OAK_PLANKS) + 4 * ItemMoves.count(inventory, Items.OAK_LOG);
                    if (woodAtFirstStone[0] < 0 && ItemMoves.count(inventory, Items.COBBLESTONE) > 0) woodAtFirstStone[0] = wood;
                    context.assertTrue(woodAtFirstStone[0] >= 0, "Its first cobblestone: " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(woodAtFirstStone[0] >= 28, "The trip's wood in hand before its first stone, in planks: " + woodAtFirstStone[0]);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Lava it saw beyond one walk's reach is walked toward a leg at a time (Q2). In the live runs on 10a60d5 a source it
     * knew was refused as one walk ("too far for one route") six times and set aside, and Kindred dug down to Y=-55 for
     * lava instead. Staged: a walled causeway 116 blocks long with a lava source set into its floor at the far end, which
     * the campaign is told it saw, as the player had seen its lava earlier in the live run; and the cast route's kit, as
     * though made: iron pickaxe, sword and chestplate, a bucket of water and an empty one, flint and steel, the mould's
     * cobblestone, sticks and food.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lavaLeg", tickLimit = 2400)
    public void lavaItSawBeyondOneWalksReachIsWalkedTowardALegAtATime(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] lava = new BlockPos[1];
        boolean[] dug = new boolean[1];
        boolean[] legged = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Strider"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-3, -3, -3), p.add(116, 4, 3));
                    for (int x = -3; x <= 116; x++) for (int z = -3; z <= 3; z++) {
                        for (int y = -3; y <= -1; y++) world.setBlockState(p.add(x, y, z), Blocks.STONE.getDefaultState());
                        boolean wall = Math.abs(z) == 3 || x == -3 || x == 116;
                        for (int y = 0; y <= 4; y++) world.setBlockState(p.add(x, y, z), (wall && y <= 2 ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    lava[0] = p.add(112, -1, 0).toImmutable();
                    world.setBlockState(lava[0], Blocks.LAVA.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(2, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(3, new ItemStack(Items.BUCKET));
                    inventory.setStack(4, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(5, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(6, new ItemStack(Items.BREAD, 32));
                    inventory.setStack(7, new ItemStack(Items.STICK, 16));
                    f.player().equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    world.setTimeOfDay(1000);
                    // Its first sweep of the world it is in, then the lava it saw earlier: a first sweep starts from nothing known.
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    knowledge.scan(f.player());
                    knowledge.remember(WorldKnowledge.Kind.LAVA, lava[0]);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    dug[0] |= goal.contains("toward lava") || goal.contains("for lava");
                    legged[0] |= goal.contains("Cast: walk toward the lava at " + lava[0].toShortString());
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.LAVA_BUCKET) > 0, "Its bucket of lava: " + goal);
                })
                .createAndAdd(() -> {
                    context.assertTrue(legged[0], "Walked toward it a leg at a time");
                    context.assertTrue(!dug[0], "No digging or tunnelling for other lava on the way");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * At night below ground, the water it wants up at the surface waits for daylight (Q3). In the live runs on 10a60d5
     * Sol climbed out for water at night, was dug in again for the night at the top, and went up and down a step a
     * second for most of the night. Staged: a lit chamber eight blocks down in a block of stone, the night, and the cast
     * route's kit as though made, with its two buckets empty; no water anywhere it knows of.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightWater", tickLimit = 2400)
    public void atNightBelowGroundTheWaterAtTheSurfaceWaitsForDaylight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        int[] start = new int[1];
        int[] waited = new int[1];
        boolean[] climbed = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightowl"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-6, -3, -6), p.add(6, 11, 6));
                    for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -3; y <= 11; y++) {
                        boolean chamber = Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= 0 && y <= 1;
                        world.setBlockState(p.add(x, y, z), (y <= 8 && !chamber ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    // A torch, so nothing spawns in the dark beside it.
                    world.setBlockState(p.add(1, 0, 1), Blocks.TORCH.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BUCKET, 2));
                    inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 32));
                    inventory.setStack(6, new ItemStack(Items.STICK, 16));
                    f.player().equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    world.setTimeOfDay(14000);
                    start[0] = p.getY();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    climbed[0] |= goal.contains("Climb toward the surface for water") || goal.contains("Dig in for the night")
                            || f.player().getBlockY() > start[0] + 1;
                    context.assertTrue(++waited[0] >= 600, "Through the night: " + goal);
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(!climbed[0], "It stayed below, the water left for the morning");
                    context.assertTrue(goal.contains("Wait for daylight"), "Waiting for daylight: " + goal);
                    context.getWorld().setTimeOfDay(1000);
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(goal.contains("Climb toward the surface for water"), "Morning: up for the water: " + goal);
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

    /**
     * A tool worn out in a fight is lost as wear, not an outcome to reconcile (F-25c4o). In the live run on `a286cc8`,
     * Sol had no sword, fought a zombie it could not back away from with the stone pickaxe in its hand, and the pickaxe
     * broke on its last blow: the fight's record counted that as items changed beyond wear, and Sol's body was taken out
     * of the world to be reconciled for the seventeen minutes left of the run. Staged: a closed, lit stone room it cannot
     * back out of, a stone pickaxe one use from breaking and no sword, as though made and worn, and a wounded zombie on it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wornTool", tickLimit = 1400)
    public void aToolThatWearsOutInAFightIsLostAsWearAndTheGoalGoesOn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        int[] waited = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Worn"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-3, -2, -3), p.add(3, 3, 3));
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -2; y <= 3; y++) {
                        boolean room = Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= 0 && y <= 1;
                        world.setBlockState(p.add(x, y, z), (room ? Blocks.AIR : Blocks.STONE).getDefaultState());
                    }
                    world.setBlockState(p.add(-1, 0, -1), Blocks.TORCH.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    var pickaxe = new ItemStack(Items.STONE_PICKAXE);
                    pickaxe.setDamage(pickaxe.getMaxDamage() - 1);
                    inventory.setStack(0, pickaxe);
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    inventory.selectedSlot = 0;
                    world.setTimeOfDay(1000);
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    if (zombie[0] == null) return;
                    zombie[0].refreshPositionAndAngles(p.getX() + 1.5, p.getY(), p.getZ() + 0.5, 90, 0);
                    zombie[0].setPersistent();
                    zombie[0].setHealth(2);
                    world.spawnEntity(zombie[0]);
                    zombie[0].setTarget(f.player());
                })
                .createAndAdd(() -> context.assertTrue(zombie[0] != null, "A zombie"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> context.assertFalse(zombie[0].isAlive(), "The zombie killed"))
                // The fight's record settles off the game thread, and only then is a player stopped whose record is uncertain.
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 100, "Its record settled"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body still in the world, not taken out to be reconciled");
                    context.assertTrue(ItemMoves.count(player.getInventory(), Items.STONE_PICKAXE) == 0, "The pickaxe broke on it");
                    var status = f.runtime.nativeStatuses(Actor.console()).getFirst();
                    context.assertTrue(status.connected() && !status.stopped(), "Not stopped for reconciliation: " + status);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * With no sword or axe, a fight strikes with the pickaxe in its hotbar, not the empty hand left selected (F-25c4r).
     * In the long run on `a286cc8` Kindred, its last torch just placed, fought a zombie it could not back away from with
     * that empty hand, an iron pickaxe beside it: nine critical punches did not kill the zombie, and the zombie killed
     * Kindred. Staged: a closed, lit stone room it cannot back out of, an iron pickaxe and bread as though made, the
     * selected slot empty, and a zombie on it, without AI, wounded.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "noWeapon", tickLimit = 1400)
    public void withNoSwordOrAxeAFightStrikesWithThePickaxeNotTheEmptyHand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        // What was in its hand once the first blow had landed: what it struck with (a pickaxe can wear from digging too).
        net.minecraft.item.Item[] struckWith = new net.minecraft.item.Item[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bare"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-3, -2, -3), p.add(3, 3, 3));
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -2; y <= 3; y++) {
                        boolean room = Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= 0 && y <= 1;
                        world.setBlockState(p.add(x, y, z), (room ? Blocks.AIR : Blocks.STONE).getDefaultState());
                    }
                    world.setBlockState(p.add(-1, 0, -1), Blocks.TORCH.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(1, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    inventory.selectedSlot = 0;
                    world.setTimeOfDay(1000);
                    zombie[0] = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    if (zombie[0] == null) return;
                    zombie[0].refreshPositionAndAngles(p.getX() + 1.5, p.getY(), p.getZ() + 0.5, 90, 0);
                    zombie[0].setAiDisabled(true);
                    zombie[0].setPersistent();
                    zombie[0].setHealth(8);
                    world.spawnEntity(zombie[0]);
                    zombie[0].setTarget(f.player());
                })
                .createAndAdd(() -> context.assertTrue(zombie[0] != null, "A zombie"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    if (struckWith[0] == null && zombie[0].getHealth() < 8 && f.player() != null) struckWith[0] = f.player().getMainHandStack().getItem();
                    context.assertFalse(zombie[0].isAlive(), "The zombie killed");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null, "Its body");
                    context.assertTrue(struckWith[0] == Items.IRON_PICKAXE, "Struck with its iron pickaxe, not the empty hand: " + struckWith[0]);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * At night below ground, the water at the surface waiting for daylight, the cast's lava is fetched meanwhile with
     * one of its two empty buckets (F-25c4n). On `ace1d80` Sol waited out a night for its water with both buckets empty,
     * then dug down for the lava by day. Staged: a chamber eight blocks down in a block of stone with a lava source set
     * into its floor in plain sight, the night, and the cast route's kit as though made, its two buckets empty.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "nightLava", tickLimit = 2400)
    public void atNightBelowGroundTheCastsLavaIsFetchedWhileTheWaterWaits(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        int[] start = new int[1];
        boolean[] climbed = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Nightlava"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-6, -3, -6), p.add(6, 11, 6));
                    for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -3; y <= 11; y++) {
                        boolean chamber = Math.abs(x) <= 3 && Math.abs(z) <= 1 && y >= 0 && y <= 1;
                        world.setBlockState(p.add(x, y, z), (y <= 8 && !chamber ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    world.setBlockState(p.add(3, -1, 0), Blocks.LAVA.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BUCKET, 2));
                    inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 32));
                    inventory.setStack(6, new ItemStack(Items.STICK, 16));
                    f.player().equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    world.setTimeOfDay(14000);
                    start[0] = p.getY();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    climbed[0] |= goal.contains("Climb toward the surface for water") || f.player().getBlockY() > start[0] + 1;
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.LAVA_BUCKET) == 1, "The cast's lava, at night: " + goal);
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    climbed[0] |= goal.contains("Climb toward the surface for water") || f.player().getBlockY() > start[0] + 1;
                    context.assertTrue(goal.contains("Wait for daylight"), "Then waiting for daylight for the water: " + goal);
                })
                .createAndAdd(() -> {
                    context.assertTrue(!climbed[0], "It stayed below through the night");
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.BUCKET) == 1, "One bucket left empty for the water");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * After a mob kill it goes back for its drops once no hostile mob has them in sight (Q4). In the live runs on fb02bc3
     * and 10a60d5 all fourteen deaths were to mobs, and each left its whole kit on the ground for good. Staged: a closed,
     * lit stone room, so nothing from the caves round the test comes in; a respawn point eight blocks from where it
     * dies; the zombie the fixture kills it with, standing still (no AI) and unhurt by blows in sight of the drops, then
     * taken away, as one that wandered off would be gone; and its kit, as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "guardedDrops", tickLimit = 2400)
    public void afterAMobKillItGoesBackForItsDropsOnceNoMobHasThemInSight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        net.minecraft.server.network.ServerPlayerEntity[] dead = new net.minecraft.server.network.ServerPlayerEntity[1];
        int[] waited = new int[1];
        boolean[] early = new boolean[1];
        boolean[] recovering = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Gleaner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-12, -3, -12), p.add(12, 7, 12));
                    for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -3; y <= 7; y++) {
                        boolean shell = y < 0 || y == 7 || Math.abs(x) == 12 || Math.abs(z) == 12;
                        world.setBlockState(p.add(x, y, z), (shell ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    for (int[] t : new int[][] {{6, 6}, {-6, 6}, {6, -6}, {-6, -6}}) world.setBlockState(p.add(t[0], 0, t[1]), Blocks.TORCH.getDefaultState());
                    f.player().setSpawnPoint(world.getRegistryKey(), p.add(8, 0, 0), 0, true, false);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 20));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 12));
                    inventory.setStack(4, new ItemStack(Items.OAK_PLANKS, 8));
                    world.setTimeOfDay(1000);
                    // Its campaign, as its goal makes one, so that its death is remembered; the goal is set once it is back.
                    f.runtime.campaignForTest(f.id);
                    var made = net.minecraft.entity.EntityType.ZOMBIE.create(world);
                    if (made == null) return;
                    zombie[0] = made;
                    made.refreshPositionAndAngles(p.getX() - 2.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    made.setPersistent();
                    made.setAiDisabled(true);
                    made.setInvulnerable(true);
                    world.spawnEntity(made);
                })
                .createAndAdd(() -> context.assertTrue(zombie[0] != null && zombie[0].isAlive(), "A zombie"))
                // Retried until the blow lands: a player that has just joined takes no damage for a moment.
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dead[0].damage(context.getWorld().getDamageSources().mobAttack(zombie[0]), 1000f);
                    context.assertTrue(!dead[0].isAlive(), "Killed by the zombie");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    early[0] |= goal.contains("dropped at death") || ItemMoves.count(f.player().getInventory(), Items.STONE_PICKAXE) > 0;
                    context.assertTrue(++waited[0] >= 300, "The zombie still by its drops: " + goal);
                })
                .createAndAdd(() -> {
                    context.assertTrue(!early[0], "Nothing of its drops gone back for while the zombie that killed it had them in sight");
                    context.assertTrue(zombie[0].isAlive(), "The zombie still there");
                    zombie[0].discard();
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    recovering[0] |= goal.contains("dropped at death");
                    context.assertTrue(recovering[0] && ItemMoves.count(f.player().getInventory(), Items.STONE_PICKAXE) > 0,
                            "Back for its drops, its pickaxe picked up: " + goal);
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

    /**
     * Swimming in a flooded pit with no dry ground it can walk to, it digs a step out of the side and climbs out
     * (F-25c4t). In the live pair on `03faab9` Kindred mined coal that let water in and fell into a pit four blocks
     * below its tunnel. Its goal's climb out was cut short each time ("In deep water; up for air first"), and its swim
     * up for air ended at once at the surface: some 250 times a minute, for the rest of the run, until it
     * drowned there. Staged: the pit in a block of stone, its water three deep under a ceiling, a lamp in its wall, and
     * the kit as though made: a stone pickaxe, dirt and bread.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "floodedPit", tickLimit = 1800)
    public void inAFloodedPitWithNoDryGroundInReachItDigsAStepOutAndClimbsOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] pit = new BlockPos[1];
        boolean[] swimming = new boolean[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Swimmer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().up(4);
                    pit[0] = p;
                    FixtureGround.keep(context, p.add(-4, -5, -4), p.add(4, 6, 4));
                    for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -5; y <= 6; y++) {
                        boolean inside = Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= -3 && y <= 3;
                        var state = !inside ? Blocks.STONE.getDefaultState() : y <= 0 ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    world.setBlockState(p.add(-2, 2, 0), Blocks.SEA_LANTERN.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.DIRT, 16));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    world.setTimeOfDay(1000);
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY() - 1, p.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    swimming[0] = PlayerRuntime.inDeepWater(f.player());
                    context.assertTrue(swimming[0], "In the pit's water");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Alive");
                    context.assertTrue(player.isOnGround() && !player.isTouchingWater() && player.getY() >= pit[0].getY() + 1,
                            "Out of the water, on a step it dug: " + player.getBlockPos().toShortString() + "; "
                                    + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
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

    /**
     * A drop it cannot get to is passed over for the next nearest (F-25c4s). In the live pair on `03faab9` each of Sol's
     * three walks back stopped eight to fifteen blocks short of the one drop it went for, and the rest were given up with it.
     * Staged: the closed, lit stone room of the drops fixture above, with its respawn point eight blocks from where it
     * dies; its death (not to a mob); and the drop nearest the respawn point, its bread, put in a glass cell between,
     * as a drop thrown into a hole or onto a ledge is seen and cannot be walked to. Its kit is given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "unreachedDrop", tickLimit = 2400)
    public void aDropItCannotGetToIsPassedOverForTheNextNearest(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.server.network.ServerPlayerEntity[] dead = new net.minecraft.server.network.ServerPlayerEntity[1];
        BlockPos[] at = new BlockPos[1];
        // What its goal was doing when its pickaxe came back into its inventory.
        String[] pickedUpDoing = new String[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Gatherer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-12, -3, -12), p.add(12, 7, 12));
                    for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -3; y <= 7; y++) {
                        boolean shell = y < 0 || y == 7 || Math.abs(x) == 12 || Math.abs(z) == 12;
                        world.setBlockState(p.add(x, y, z), (shell ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    for (int[] t : new int[][] {{6, 6}, {-6, 6}, {6, -6}, {-6, -6}}) world.setBlockState(p.add(t[0], 0, t[1]), Blocks.TORCH.getDefaultState());
                    // The glass cell: its eight neighbours and its top, so that nothing in it can be reached or picked up.
                    for (int x = 4; x <= 6; x++) for (int z = -1; z <= 1; z++) if (x != 5 || z != 0) world.setBlockState(p.add(x, 0, z), Blocks.GLASS.getDefaultState());
                    world.setBlockState(p.add(5, 1, 0), Blocks.GLASS.getDefaultState());
                    f.player().setSpawnPoint(world.getRegistryKey(), p.add(8, 0, 0), 0, true, false);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 20));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 12));
                    inventory.setStack(4, new ItemStack(Items.OAK_PLANKS, 8));
                    world.setTimeOfDay(1000);
                    // Its campaign, as its goal makes one, so that its death is remembered; the goal is set once it is back.
                    f.runtime.campaignForTest(f.id);
                })
                // Retried until the blow lands: a player that has just joined takes no damage for a moment.
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dead[0].damage(context.getWorld().getDamageSources().generic(), 1000f);
                    context.assertTrue(!dead[0].isAlive(), "Dead");
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var bread = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, new net.minecraft.util.math.Box(at[0]).expand(6),
                            drop -> drop.isAlive() && drop.getStack().isOf(Items.BREAD)).stream().findFirst();
                    context.assertTrue(bread.isPresent() && world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                            new net.minecraft.util.math.Box(at[0]).expand(6), drop -> drop.isAlive() && drop.getStack().isOf(Items.STONE_PICKAXE)).size() == 1,
                            "Its kit on the ground");
                    bread.get().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                    bread.get().refreshPositionAndAngles(at[0].getX() + 5.5, at[0].getY(), at[0].getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (pickedUpDoing[0] == null && ItemMoves.count(f.player().getInventory(), Items.STONE_PICKAXE) > 0) pickedUpDoing[0] = goal;
                    context.assertTrue(pickedUpDoing[0] != null, "Its pickaxe picked up: " + goal);
                })
                .createAndAdd(() -> {
                    context.assertTrue(pickedUpDoing[0].contains("dropped at death"), "Picked up going back for its drops: " + pickedUpDoing[0]);
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.BREAD) == 0, "The bread in the glass cell left there");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Dead again before it had its things back, it leaves what it dropped the second time too (F-25c4v). In the live pair
     * on `458d192` Kindred died eight times in one cave, each time on its way back for what it had dropped before.
     * Staged: the closed, lit stone room of the drops fixture above, with its respawn point eight blocks from where it
     * first dies; that death (not to a mob); and a second, once its walk back for its drops is under way. Its kit is given
     * as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deadAgain", tickLimit = 2400)
    public void deadAgainOnItsWayBackItLeavesWhatItDroppedTheSecondTime(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        net.minecraft.server.network.ServerPlayerEntity[] dead = new net.minecraft.server.network.ServerPlayerEntity[1];
        BlockPos[] respawn = new BlockPos[1];
        // What its goal said as it died the second time, still shown until its next life plans; what said after that sent it
        // back for its things; and how long it was watched.
        String[] dying = new String[1];
        String[] wentBack = new String[1];
        int[] watched = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Gatherer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    respawn[0] = p.add(8, 0, 0);
                    FixtureGround.keep(context, p.add(-12, -3, -12), p.add(12, 7, 12));
                    for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) for (int y = -3; y <= 7; y++) {
                        boolean shell = y < 0 || y == 7 || Math.abs(x) == 12 || Math.abs(z) == 12;
                        world.setBlockState(p.add(x, y, z), (shell ? Blocks.STONE : Blocks.AIR).getDefaultState());
                    }
                    for (int[] t : new int[][] {{6, 6}, {-6, 6}, {6, -6}, {-6, -6}}) world.setBlockState(p.add(t[0], 0, t[1]), Blocks.TORCH.getDefaultState());
                    f.player().setSpawnPoint(world.getRegistryKey(), respawn[0], 0, true, false);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 20));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 12));
                    inventory.setStack(4, new ItemStack(Items.OAK_PLANKS, 8));
                    world.setTimeOfDay(1000);
                    // Its campaign, as its goal makes one, so that its deaths are remembered; the goal is set once it is back.
                    f.runtime.campaignForTest(f.id);
                })
                // Retried until the blow lands: a player that has just joined takes no damage for a moment.
                .createAndAdd(() -> {
                    dead[0] = f.player();
                    dead[0].damage(context.getWorld().getDamageSources().generic(), 1000f);
                    context.assertTrue(!dead[0].isAlive(), "Dead");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // On its way back for its drops, three blocks from where it came back: it dies again there. A kill that a
                // player who has just come back is not spared, as it is spared other harm for its first three seconds.
                .createAndAdd(() -> {
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(goal.contains("dropped at death") && player.getX() <= respawn[0].getX() - 2.5,
                            "On its way back for its drops: " + goal + " at " + player.getBlockPos().toShortString());
                    dead[0] = player;
                    dying[0] = goal;
                    dead[0].damage(context.getWorld().getDamageSources().genericKill(), 1000f);
                    context.assertTrue(!dead[0].isAlive(), "Dead again");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned again");
                })
                // Watched for half a minute once it is back: its goal never sets out for what it dropped either time.
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (wentBack[0] == null && goal.contains("dropped at death") && !goal.equals(dying[0])) wentBack[0] = goal;
                    context.assertTrue(++watched[0] >= 600, "Watched for half a minute");
                })
                .createAndAdd(() -> {
                    context.assertTrue(wentBack[0] == null, "Went back for its things again: " + wentBack[0]);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Water level with the floor it stands on is not jumped into (F-25c4w). In the live pair on `458d192` Echo, in a
     * pocket it dug beside an underground lake, waded into the lake and swam straight back out 107 times in twelve minutes.
     * Staged: a closed obsidian room it cannot dig with a stone pickaxe, as Echo could dig nothing beside the water; a shore
     * one block across, walled on three sides, and in front of it a pool three deep, the water level with the floor it
     * stands on, lit from the pool's bottom. Its kit is given as though made, a table and wood with it, so that nothing is
     * left to do there.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "poolShore", tickLimit = 2400)
    public void waterLevelWithItsFloorIsNotJumpedIntoWhenNothingElseIsFeasible(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        String[] jumped = new String[1];
        int[] watched = new int[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wader"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos();
                    FixtureGround.keep(context, p.add(-2, -4, -3), p.add(5, 3, 3));
                    for (int x = -2; x <= 5; x++) for (int z = -3; z <= 3; z++) for (int y = -4; y <= 3; y++) {
                        boolean shell = x == -2 || x == 5 || Math.abs(z) == 3 || y == -4 || y == 3;
                        boolean shore = x <= 0;
                        boolean open = shore ? x == 0 && z == 0 && y >= 0 : y >= 0;
                        boolean pool = !shore && y <= -1;
                        var state = shell || shore && !open ? Blocks.OBSIDIAN.getDefaultState()
                                : pool ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    world.setBlockState(p.add(3, -4, 0), Blocks.SEA_LANTERN.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 5));
                    inventory.setStack(3, new ItemStack(Items.COBBLESTONE, 24));
                    inventory.setStack(4, new ItemStack(Items.STICK, 8));
                    inventory.setStack(5, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(6, new ItemStack(Items.OAK_PLANKS, 16));
                    inventory.setStack(7, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(8, new ItemStack(Items.TORCH, 16));
                    world.setTimeOfDay(1000);
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // Watched for a minute, less than its watchdog waits before it leaves for somewhere else.
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (jumped[0] == null && goal.contains("Jump down from the ledge")) jumped[0] = f.player().getBlockPos().toShortString() + ": " + goal;
                    context.assertTrue(++watched[0] >= 1200, "Watched for a minute");
                })
                .createAndAdd(() -> {
                    context.assertTrue(jumped[0] == null, "Jumped into the pool: " + jumped[0]);
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
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
     * A cast from below into a cell four up and one across, standing right up against the frame, as for the top of a
     * frame on sky-island run 7: once the lava hardens, every line from the player's eyes to the water above it runs
     * through the new obsidian, so the water could not be taken back. The cast is refused before anything is poured,
     * and nothing changes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void castIsRefusedWhereTheWaterCouldNotBeTakenBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] target = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Highcaster"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos().toImmutable();
                    // The top row of a frame standing one block in front: the cell four up, a frame block already
                    // beside it, the mould behind it and behind the water cell, and the frame's inside open below.
                    target[0] = feet.south().up(4);
                    world.setBlockState(target[0], Blocks.AIR.getDefaultState());
                    world.setBlockState(target[0].up(), Blocks.AIR.getDefaultState());
                    world.setBlockState(target[0].west(), Blocks.OBSIDIAN.getDefaultState());
                    world.setBlockState(target[0].south(), Blocks.STONE.getDefaultState());
                    world.setBlockState(target[0].up().south(), Blocks.STONE.getDefaultState());
                    // Right up against the frame, where stepping closer had left the player on the island.
                    f.player().refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.85, 0, -60);
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.LAVA_BUCKET));
                    inventory.setStack(1, new ItemStack(Items.WATER_BUCKET));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    // Asked again until the resume has settled; then the answer is the cast's own.
                    int cast = f.runtime.nativeCast(f.source, f.id, target[0]);
                    var refusal = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertTrue(cast == 0 && refusal.work().contains("could not be taken back"), "The cast is refused for the water: " + cast + " " + refusal);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var inventory = f.player().getInventory();
                    context.assertTrue(world.getBlockState(target[0]).isAir() && world.getBlockState(target[0].up()).isAir(), "Nothing poured");
                    context.assertTrue(ItemMoves.count(inventory, Items.LAVA_BUCKET) == 1 && ItemMoves.count(inventory, Items.WATER_BUCKET) == 1,
                            "Both buckets as they were");
                    var activity = f.runtime.nativeActivityStatus(Actor.console(), f.id);
                    context.assertFalse(activity.work().contains("reconciliation"), "Not blocked: " + activity);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * The one-block cast with a leaf block beside the cell the water goes in, on the side tried first, as on sky-island
     * run 5: a bucket clicked on leaves waterlogs them instead of pouring beside them, so the cast pours against a
     * solid face that is not waterloggable, and the leaves stay dry.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
    public void castPoursBesideLeavesRatherThanIntoThem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] target = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Leafcaster"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos feet = f.player().getBlockPos().toImmutable();
                    target[0] = feet.east(2);
                    for (int up = 0; up <= 2; up++) {
                        world.setBlockState(target[0].up(up), Blocks.AIR.getDefaultState());
                        world.setBlockState(target[0].east().up(up), Blocks.STONE.getDefaultState());
                    }
                    world.setBlockState(target[0].up().south(), Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
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
                    context.assertFalse(world.getBlockState(target[0].up().south()).get(net.minecraft.state.property.Properties.WATERLOGGED), "The leaves stayed dry");
                    context.assertTrue(ItemMoves.count(inventory, Items.LAVA_BUCKET) == 0 && ItemMoves.count(inventory, Items.BUCKET) == 1
                            && ItemMoves.count(inventory, Items.WATER_BUCKET) == 1, "One lava bucket emptied; the water bucket refilled");
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
     * Past its west wall lies ground it has no way to, and it is set down by the pool's west end, from where the
     * nearest room for a frame is on that ground: it builds within its walls, where it can walk (F-25c4a).
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
                    FixtureGround.keep(context, p.add(-12, -2, -7), p.add(6, 8, 6));
                    // Beyond the west wall, ground at the floor's height that it has no way to: in full suite runs an
                    // earlier test's floor lay there, and the frame site was chosen on it (closing suite on fbdb8ed).
                    for (int x = -12; x <= 6; x++) for (int z = -7; z <= 6; z++) {
                        boolean edge = x >= -6 && (Math.abs(x) == 6 || z == -7 || z == 6);
                        world.setBlockState(p.add(x, -2, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 8; y++) {
                            world.setBlockState(p.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    for (int x = -3; x <= 3; x++) for (int z = 3; z <= 4; z++) world.setBlockState(p.add(x, -1, z), Blocks.LAVA.getDefaultState());
                    // Set down where the suite run's player had walked to fill its bucket, by the pool's west end: from
                    // there the nearest room for a frame, lava aside, is the ground past the wall.
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
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The cast fixture above under a roof at the height of the frame's top row, as where Echo cast its frame into the
     * deepslate at Y=-55 in the live pair on `458d192` (F-25c4y). The cell over each side's top block must
     * be dug out before that block is cast, and from the pool, where it fills its bucket, that cell is out of reach: it
     * goes back to the frame and digs it, and the ten frame blocks are cast in one frame. Before, the frame was given up
     * there, with seven cast, and another begun beside it. Staged as the cast fixture is, the roof's glowstone with it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castRoofed", tickLimit = 9000)
    public void aFrameCastUnderARoofIsNotGivenUpForACellOutOfReachFromTheLava(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Caster"))
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
                        boolean roof = x >= -6;
                        world.setBlockState(p.add(x, -2, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 8; y++) {
                            boolean solid = edge && y <= 3 || roof && y == 4;
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
                    // The lit portal's blocks, anywhere it staged (a side may stand in the room's wall); the frame is the box round
                    // them, one block out.
                    var world = context.getWorld();
                    int portal = 0, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    for (BlockPos pos : BlockPos.iterate(feet[0].add(-12, -2, -7), feet[0].add(6, 8, 6))) {
                        if (!world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) continue;
                        portal++;
                        minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                        maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
                    }
                    // Its ten blocks, and none cast anywhere else in the room above the pool.
                    int frame = 0, stray = 0;
                    var cast = new java.util.ArrayList<String>();
                    for (BlockPos pos : BlockPos.iterate(feet[0].add(-12, -3, -7), feet[0].add(6, 8, 6))) {
                        if (!world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) continue;
                        boolean inFrame = portal > 0 && pos.getX() >= minX - 1 && pos.getX() <= maxX + 1 && pos.getY() >= minY - 1
                                && pos.getY() <= maxY + 1 && pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
                        cast.add(pos.subtract(feet[0]).toShortString());
                        if (inFrame) frame++;
                        else if (pos.getY() >= feet[0].getY()) stray++;
                    }
                    var player = f.player();
                    context.assertTrue(portal == 6, "A cast frame stands lit: " + portal + " portal blocks, " + frame + " obsidian; at "
                            + (player == null ? "no body" : player.getBlockPos().toShortString()) + " from " + feet[0] + "; "
                            + f.runtime.panelBots(Actor.console()).stream().findFirst().map(bot -> bot.goalProgress()).orElse("not on the panel"));
                    context.assertTrue(frame == 10 && stray == 0, "Its ten frame blocks all in that frame, none in another begun beside it: "
                            + frame + " in it, " + stray + " elsewhere; obsidian at " + cast);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive() && player.getHealth() >= 10, "Unhurt by the lava");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * The roofed cast fixture under rock five blocks thick, as where Sol cast its frame into the deepslate at Y=-54 in the
     * live pair on `64c298d` (F-25c4ai). Over a top block the water goes in the rock, and with the rock left over the row in
     * front, every line from below to that water runs through the block once it hardens: the cell in front of the water is
     * dug out first, the top two are cast, the water taken back, and the frame lit. Before, it cast eight and was refused the
     * top two from every stand. Staged as the roofed fixture is, the rock over the room with it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castDeep", tickLimit = 12000)
    public void underThickRockTheFramesTopIsCastWithItsWaterTakenBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
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
                    var world = context.getWorld();
                    int portal = 0, obsidian = 0;
                    for (BlockPos pos : BlockPos.iterate(feet[0].add(-12, -2, -7), feet[0].add(6, 8, 6))) {
                        var state = world.getBlockState(pos);
                        if (state.isOf(Blocks.NETHER_PORTAL)) portal++;
                        if (state.isOf(Blocks.OBSIDIAN)) obsidian++;
                    }
                    var player = f.player();
                    context.assertTrue(portal == 6, "A cast frame stands lit under the rock: " + portal + " portal blocks, " + obsidian
                            + " obsidian; at " + (player == null ? "no body" : player.getBlockPos().toShortString()) + " from " + feet[0] + "; "
                            + f.runtime.nativeActivityStatus(Actor.console(), f.id) + "; "
                            + f.runtime.panelBots(Actor.console()).stream().findFirst().map(bot -> bot.goalProgress()).orElse("not on the panel"));
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    context.assertTrue(ItemMoves.count(inventory, Items.WATER_BUCKET) == 1, "The water taken back each time: "
                            + ItemMoves.count(inventory, Items.WATER_BUCKET) + " water buckets");
                    context.assertTrue(player.isAlive() && player.getHealth() >= 10, "Unhurt by the lava: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * With its lava in hand at the level of lava lakes, it climbs a few blocks into the rock over them for a frame site,
     * rather than tunnel along their shore for one (F-25c4z). No site may lie within three blocks of lava, and in the live
     * pair on `458d192` Echo, at Y=-55 between lava lakes, tunnelled 209 blocks of deepslate before it found one, and its
     * iron pickaxe wore out. Staged: a tunnel through a block of stone with a channel of lava under the rock on each side
     * of it, a block off, its whole length; glowstone over it; the kit, a bucket of lava and one of water with it, given as
     * though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "castOverLava", tickLimit = 2400)
    public void withItsLavaInHandBetweenLavaLakesItClimbsAboveThemForAFrameSite(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] feet = new BlockPos[1];
        String[] site = new String[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Shore"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    feet[0] = p;
                    FixtureGround.keep(context, p.add(-14, -3, -10), p.add(14, 12, 10));
                    for (int x = -14; x <= 14; x++) for (int z = -10; z <= 10; z++) for (int y = -3; y <= 12; y++) {
                        boolean tunnel = Math.abs(x) <= 12 && z == 0 && (y == 0 || y == 1);
                        boolean lava = Math.abs(x) <= 12 && Math.abs(z) == 2 && y == -1;
                        var state = tunnel ? Blocks.AIR.getDefaultState() : lava ? Blocks.LAVA.getDefaultState() : Blocks.STONE.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    for (int x : new int[] {-9, -3, 3, 9}) world.setBlockState(p.add(x, 2, 0), Blocks.GLOWSTONE.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.LAVA_BUCKET));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // A site chosen: its work on the frame begun, clearing it, a corner, a mould or a cast, three blocks or more up.
                .createAndAdd(() -> {
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).stream().findFirst().map(bot -> bot.goalProgress()).orElse("not on the panel");
                    boolean framing = goal.contains("Cast: clear") || goal.contains("Cast: stand in front") || goal.contains("Cast: corner")
                            || goal.contains("Cast: mould") || goal.contains("Cast obsidian");
                    if (site[0] == null && framing && player != null) site[0] = player.getBlockPos().subtract(feet[0]).toShortString() + ": " + goal;
                    context.assertTrue(site[0] != null && player != null && player.getBlockY() >= feet[0].getY() + 3, "Working on a frame site above the lava: "
                            + site[0] + "; now " + (player == null ? "no body" : player.getBlockPos().subtract(feet[0]).toShortString()) + ", " + goal);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive() && player.getHealth() >= 10, "Unhurt by the lava");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Dry ground its walk out of the water failed to get to is passed over for the next nearest (F-25c4aa). In the live pair
     * on `a4df9b4` Echo, in a stream down a hillside, set out for the same spot on its bank again and again, the current
     * carrying it past each time; Sol did so 375 times on `458d192`. Staged: a closed stone room, its floor a pool five deep,
     * a notch of shore one block across near the player and a ledge along the far wall, lit from the pool's bottom; the
     * player in the water by the notch; and one walk out to the notch reported to its goal as failed, as the current made
     * Echo's. Its kit is given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "shorePassedOver", tickLimit = 1800)
    public void dryGroundAWalkOutOfTheWaterFailedToReachIsPassedOverForTheNextNearest(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        // Where it first stood on dry ground out of the pool.
        BlockPos[] landed = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Swimmer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().up(6);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-8, -6, -4), p.add(8, 4, 4));
                    for (int x = -8; x <= 8; x++) for (int z = -4; z <= 4; z++) for (int y = -6; y <= 4; y++) {
                        boolean shell = Math.abs(x) == 8 || Math.abs(z) == 4 || y == -6 || y == 4;
                        boolean pool = Math.abs(x) <= 6 && y <= -1;
                        boolean notch = x == 7 && z == 0 && y >= 0 && y <= 1;
                        boolean ledge = x == -7 && y >= 0;
                        boolean wall = x == 7 && y >= -1 && !notch || x == -7 && y == -1;
                        var state = shell || wall ? Blocks.STONE.getDefaultState() : pool ? Blocks.WATER.getDefaultState()
                                : notch || ledge || Math.abs(x) <= 6 ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    world.setBlockState(p.add(0, -6, 0), Blocks.SEA_LANTERN.getDefaultState());
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    world.setTimeOfDay(1000);
                    f.player().refreshPositionAndAngles(p.getX() + 4.5, p.getY() - 1, p.getZ() + 0.5, 0, 0);
                    // One walk out to the notch, failed, as its goal hears of a walk that fails; then the goal is set.
                    var campaign = f.runtime.campaignForTest(f.id);
                    campaign.taskFinished(PortalRunCampaign.go(p.add(7, 0, 0), 0.8, true, "Get out of the water: to dry ground"), false,
                            context.getWorld().getServer().getTicks());
                })
                .createAndAdd(() -> context.assertTrue(PlayerRuntime.inDeepWater(f.player()), "In the pool"))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var player = f.player();
                    if (landed[0] == null && player != null && player.isOnGround() && !player.isTouchingWater()) landed[0] = player.getBlockPos().subtract(at[0]);
                    context.assertTrue(landed[0] != null, "Out of the water: "
                            + f.runtime.panelBots(Actor.console()).stream().findFirst().map(bot -> bot.goalProgress()).orElse("not on the panel"));
                    context.assertTrue(landed[0].getX() <= -6, "Out on the far ledge, not the notch it failed to reach: " + landed[0].toShortString());
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

    /**
     * Hurt and out of food, up out of the ground by day on open stone, no animal within reach of its sight: it walks
     * out and looks for one, kills it and takes what it drops, rather than going back to its work. In the live pair on
     * a4df9b4 the climb for food ended where the ground opened over the player's head, and the goal's next step dug down
     * again: Sol climbed some ninety blocks for food three times so and never ate (F-25c4ab). The plain and the four cows,
     * kept from wandering, 45 blocks out to each side, are staged; the kit is given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hungryUp", tickLimit = 2400)
    public void hurtAndOutOfFoodOnceUpItLooksForAnimalsRatherThanGoBackDown(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hungry"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-52, -1, -52), p.add(52, 5, 52));
                    for (int x = -52; x <= 52; x++) for (int z = -52; z <= 52; z++) {
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 5; y++) world.setBlockState(p.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    // Only its own four: none another fixture left on this ground, nearer than these.
                    world.getEntitiesByClass(net.minecraft.entity.passive.PassiveEntity.class, new net.minecraft.util.math.Box(p.add(-60, -4, -60).toCenterPos(),
                            p.add(60, 8, 60).toCenterPos()), animal -> true).forEach(net.minecraft.entity.Entity::discard);
                    for (var side : java.util.List.of(new BlockPos(45, 0, 0), new BlockPos(-45, 0, 0), new BlockPos(0, 0, 45), new BlockPos(0, 0, -45))) {
                        var cow = net.minecraft.entity.EntityType.COW.create(world);
                        cow.refreshPositionAndAngles(p.getX() + side.getX() + 0.5, p.getY(), p.getZ() + side.getZ() + 0.5, 0, 0);
                        cow.setPersistent();
                        cow.setAiDisabled(true);
                        world.spawnEntity(cow);
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(3, new ItemStack(Items.STICK, 8));
                    inventory.setStack(4, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    player.setHealth(8);
                    player.getHungerManager().setFoodLevel(10); // No natural healing, and nothing to eat.
                    player.getHungerManager().setSaturationLevel(0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(ItemMoves.count(f.player().getInventory(), Items.BEEF) >= 1, "It found a cow and has its beef: now "
                            + f.player().getBlockPos().subtract(at[0]).toShortString() + ", "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Looking for its cast's lava a block above the level of a lava pool under its tunnel's floor, sealed in rock and out
     * of sight: the tunnel opens the cell over the pool from where it stands, sees the lava and fills its bucket there,
     * unhurt (F-25c4ac). Before, every cell over lava was refused and the tunnel turned away from the pool unseen; in the
     * world saves of the live pair on a4df9b4 most of the lava its tunnels came on lay so. Staged: a closed obsidian
     * chamber its iron pickaxe cannot dig, a corridor of stone the only way on, the pool under the corridor's far half,
     * and glowstone in the ceiling; the kit, a water bucket and an empty one, is given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lavaUnderFloor", tickLimit = 2400)
    public void lookingForItsLavaItsTunnelOpensTheCellOverAPoolAndFillsItsBucketThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Delver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-4, -3, -4), p.add(13, 4, 4));
                    for (int x = -4; x <= 13; x++) for (int z = -4; z <= 4; z++) for (int y = -3; y <= 4; y++) {
                        boolean room = x >= -1 && x <= 1 && z == 0 && (y == 0 || y == 1);
                        boolean corridor = x >= 2 && x <= 11 && z == 0 && (y == 0 || y == 1);
                        boolean pool = x >= 4 && x <= 9 && Math.abs(z) <= 1 && y == -1;
                        var state = room ? Blocks.AIR.getDefaultState() : corridor ? Blocks.STONE.getDefaultState()
                                : pool ? Blocks.LAVA.getDefaultState() : Blocks.OBSIDIAN.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    for (int x : new int[] {0, 6, 11}) world.setBlockState(p.add(x, 2, 0), Blocks.GLOWSTONE.getDefaultState());
                    f.player().refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    var inventory = f.player().getInventory();
                    inventory.clear();
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
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).stream().findFirst().map(bot -> bot.goalProgress()).orElse("not on the panel");
                    context.assertTrue(player != null && ItemMoves.count(player.getInventory(), Items.LAVA_BUCKET) >= 1, "Its bucket filled with the pool's lava: now "
                            + (player == null ? "no body" : player.getBlockPos().subtract(at[0]).toShortString()) + ", " + goal);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive() && !player.isOnFire() && player.getHealth() >= 18, "Unhurt by the lava: "
                            + (player == null ? "no body" : player.getHealth()));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Lava it can see but no walk leads to is dug toward (F-25c4ad). Looking for its cast's lava on the rim of a pit five
     * deep, a pool at the pit's bottom in plain view and no way down it could walk, it digs a way down beside the pit to
     * the pool's level and fills its bucket there. Before, each source was set aside as its walk failed and the next tried:
     * in the live pair on 41f9de9 Echo's only sighting of lava in two pairs was lost so, twenty sources in four seconds.
     * Staged: a block of stone, the pit and its pool, glowstone in the rim, and its look over the pit from the rim; the
     * kit, a water bucket and an empty one, is given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lavaInAPit", tickLimit = 2400)
    public void lavaItCanSeeButNotWalkToIsDugTowardAndItsBucketFilled(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rimmer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-6, -8, -6), p.add(10, 4, 6));
                    for (int x = -6; x <= 10; x++) for (int z = -6; z <= 6; z++) for (int y = -8; y <= 4; y++) {
                        boolean room = x >= -2 && x <= 3 && Math.abs(z) <= 2 && y >= 0 && y <= 2;
                        boolean pit = x >= 4 && x <= 6 && Math.abs(z) <= 1 && y >= -4 && y <= 2;
                        boolean pool = x >= 4 && x <= 6 && Math.abs(z) <= 1 && y == -5;
                        var state = room || pit ? Blocks.AIR.getDefaultState() : pool ? Blocks.LAVA.getDefaultState() : Blocks.STONE.getDefaultState();
                        world.setBlockState(p.add(x, y, z), state);
                    }
                    for (var light : java.util.List.of(new BlockPos(0, 3, 0), new BlockPos(3, 3, 0), new BlockPos(-2, 3, 2), new BlockPos(5, 3, 2)))
                        world.setBlockState(p.add(light), Blocks.GLOWSTONE.getDefaultState());
                    // On the rim, looking down into the pit.
                    f.player().refreshPositionAndAngles(p.getX() + 3.5, p.getY(), p.getZ() + 0.5, -90, 60);
                    var inventory = f.player().getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.IRON_CHESTPLATE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 8));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(7, new ItemStack(Items.WATER_BUCKET));
                    inventory.setStack(8, new ItemStack(Items.BUCKET));
                    // The pool seen from the rim, as a player there sees it: a first sweep, which starts from nothing known,
                    // then a look over the pit, each sighting a line of sight from its eyes. The sweep alone may already have
                    // passed the pit's section before the pit was laid; one run so went to look elsewhere first, and never
                    // saw the pool from there.
                    var knowledge = f.runtime.campaignForTest(f.id).knowledge();
                    knowledge.scan(f.player());
                    knowledge.look(f.player(), p.add(5, -5, 0), 1, 0, WorldKnowledge.Kind.LAVA);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).stream().findFirst().map(bot -> bot.goalProgress()).orElse("not on the panel");
                    context.assertTrue(player != null && ItemMoves.count(player.getInventory(), Items.LAVA_BUCKET) >= 1, "Its bucket filled at the pit's pool: now "
                            + (player == null ? "no body" : player.getBlockPos().subtract(at[0]).toShortString()) + ", " + goal);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive() && !player.isOnFire() && player.getHealth() >= 18, "Unhurt by the lava: "
                            + (player == null ? "no body" : player.getHealth()));
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Home beyond one route's reach is headed for a leg at a time, rather than by a walk refused as it was made and asked
     * for again (F-25c4ae). In the live pair on 41f9de9 Echo, wanting logs with no way up from where it was, asked for its
     * walk home, more than 160 blocks off, nine times, with two stall lines. Staged: the goal begun where the player
     * stands, which is its home, and the player then moved 200 blocks off, sealed in obsidian in a cell its own size,
     * nothing in hand.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "homeFarOff", tickLimit = 1200)
    public void homeFarOffIsHeadedForALegAtATimeNotByAWalkRefusedAsItIsMade(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] settle = {0}, watched = {0};
        String[] refused = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wanderer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    f.player().getInventory().clear();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // Its first plans made where it stands: home is here.
                .createAndAdd(() -> context.assertTrue(++settle[0] >= 40, "Goal under way at home"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos q = f.player().getBlockPos().add(200, 0, 0);
                    at[0] = q;
                    FixtureGround.keep(context, q.add(-2, -1, -2), q.add(2, 4, 2));
                    for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 4; y++) {
                        // Its own size: no wall to cross to, so no way up from where it is, as Echo had none.
                        boolean inside = x == 0 && z == 0 && y >= 0 && y <= 1;
                        world.setBlockState(q.add(x, y, z), inside ? Blocks.AIR.getDefaultState() : Blocks.OBSIDIAN.getDefaultState());
                    }
                    world.setBlockState(q.down(), Blocks.GLOWSTONE.getDefaultState());
                    f.player().getInventory().clear();
                    f.player().refreshPositionAndAngles(q.getX() + 0.5, q.getY(), q.getZ() + 0.5, 0, 0);
                })
                // Watched for twenty seconds in the cell: never held by a walk home refused as it was made.
                .createAndAdd(() -> {
                    var status = f.runtime.nativeStatuses(Actor.console()).stream().filter(st -> st.id().equals(f.id)).findFirst().orElseThrow();
                    String explanation = String.valueOf(status.explanation());
                    if (refused[0] == null && explanation.contains("too far for one route")) refused[0] = explanation;
                    context.assertTrue(++watched[0] >= 400, "Watched for twenty seconds");
                })
                .createAndAdd(() -> {
                    context.assertTrue(refused[0] == null, "Held by a walk home refused as it was made: " + refused[0] + "; "
                            + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Short of breath at the bottom of deep water with a drowned at it, it swims up for air rather than stopping to fight
     * (F-25c4af). Its walk up for air was stopped each time a hostile mob was on it, to fight back first, and the next
     * plan chose the walk up again, and as drowning hurt it, eating came first and was cancelled for the threat each
     * time it began: in the live pair on 41f9de9 Echo drowned at Y=8 so. Staged: a stone pool ten deep, lit at its bottom,
     * the player at the bottom with little air left, a little hurt and hungry, and a drowned beside it after it; the kit is
     * given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "drownedAfterIt", tickLimit = 1200)
    public void shortOfBreathWithADrownedAfterItItSwimsUpForAirRatherThanStopToFight(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] deaths = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Diver"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    world.setTimeOfDay(1000);
                    BlockPos p = f.player().getBlockPos().up(11);
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-3, -11, -3), p.add(3, 2, 3));
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -11; y <= 2; y++) {
                        boolean pool = Math.abs(x) <= 2 && Math.abs(z) <= 2 && y >= -10 && y <= 0;
                        boolean above = y >= 1;
                        world.setBlockState(p.add(x, y, z), (above ? Blocks.AIR : pool ? Blocks.WATER : Blocks.STONE).getDefaultState());
                    }
                    world.setBlockState(p.add(0, -11, 0), Blocks.SEA_LANTERN.getDefaultState());
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY() - 10, p.getZ() + 0.5, 0, 0);
                    player.setAir(40);
                    // A little hurt and hungry, as Echo was: its bread is worth eating from the start.
                    player.setHealth(19);
                    player.getHungerManager().setFoodLevel(17);
                    deaths[0] = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    var drowned = net.minecraft.entity.EntityType.DROWNED.create(world);
                    drowned.refreshPositionAndAngles(p.getX() + 1.5, p.getY() - 10, p.getZ() + 0.5, 0, 0);
                    drowned.setPersistent();
                    world.spawnEntity(drowned);
                    drowned.setTarget(player);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player != null && player.isAlive(), "Alive");
                    context.assertTrue(!player.isSubmergedInWater() && player.getBlockY() >= at[0].getY() - 1, "Up for air: now " + player.getBlockPos().subtract(at[0]).toShortString() + ", air "
                            + player.getAir() + ", " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    int now = player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
                    context.assertTrue(now == deaths[0], "It never drowned: deaths " + deaths[0] + " then " + now);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Hurt and out of food, up by day where the sky shows through a single gap in the rock over its head: it goes on
     * looking for animals under the rock rather than turning back to the climb (F-25c4ag). On a hillside the sky opens
     * and closes over its head cell by cell: in the live pair on 41f9de9 Echo, at the top of its climb from Y=16,
     * switched between the search and the climb several times a second for a minute, until its watchdog moved it on.
     * Staged: stone ground under a lit roof of stone four thick, forty-one blocks across with a gap over the player,
     * walled round, and four cows 45 blocks out beyond the walls, kept from wandering; the kit is given as though made.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "hungryRoof", tickLimit = 1200)
    public void hurtAndOutOfFoodUpUnderAGapInTheRockItGoesOnLookingForAnimals(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] watched = {0};
        String[] climbed = {null};
        boolean[] searched = {false}, underRock = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Roofed"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-52, -1, -52), p.add(52, 6, 52));
                    for (int x = -52; x <= 52; x++) for (int z = -52; z <= 52; z++) {
                        world.setBlockState(p.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 6; y++) {
                            boolean roof = Math.abs(x) <= 20 && Math.abs(z) <= 20 && (x != 0 || z != 0) && y >= 2 && y <= 5;
                            // Walled round, so that a walk out ends under the rock, where the plan is made again.
                            boolean wall = Math.max(Math.abs(x), Math.abs(z)) == 21 && y <= 5;
                            world.setBlockState(p.add(x, y, z), (roof || wall ? Blocks.STONE : Blocks.AIR).getDefaultState());
                        }
                    }
                    for (int x = -16; x <= 16; x += 4) for (int z = -16; z <= 16; z += 4) {
                        if (x != 0 || z != 0) world.setBlockState(p.add(x, 2, z), Blocks.GLOWSTONE.getDefaultState());
                    }
                    world.setTimeOfDay(1000);
                    // Only its own four: none another fixture left on this ground, nearer than these.
                    world.getEntitiesByClass(net.minecraft.entity.passive.PassiveEntity.class, new net.minecraft.util.math.Box(p.add(-60, -4, -60).toCenterPos(),
                            p.add(60, 8, 60).toCenterPos()), animal -> true).forEach(net.minecraft.entity.Entity::discard);
                    for (var side : java.util.List.of(new BlockPos(45, 0, 0), new BlockPos(-45, 0, 0), new BlockPos(0, 0, 45), new BlockPos(0, 0, -45))) {
                        var cow = net.minecraft.entity.EntityType.COW.create(world);
                        cow.refreshPositionAndAngles(p.getX() + side.getX() + 0.5, p.getY(), p.getZ() + side.getZ() + 0.5, 0, 0);
                        cow.setPersistent();
                        cow.setAiDisabled(true);
                        world.spawnEntity(cow);
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.COBBLESTONE, 16));
                    inventory.setStack(3, new ItemStack(Items.STICK, 8));
                    inventory.setStack(4, new ItemStack(Items.CRAFTING_TABLE));
                    inventory.setStack(40, new ItemStack(Items.SHIELD));
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    player.setHealth(8);
                    player.getHungerManager().setFoodLevel(10); // No natural healing, and nothing to eat.
                    player.getHungerManager().setSaturationLevel(0);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                // Watched for twenty seconds: its search takes it in under the rock, and never back to the climb.
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos off = player.getBlockPos().subtract(at[0]);
                    if (Math.abs(off.getX()) <= 20 && Math.abs(off.getZ()) <= 20 && (off.getX() != 0 || off.getZ() != 0)) underRock[0] = true;
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (climbed[0] == null && goal.contains("Climb toward the surface for food")) climbed[0] = goal + " at " + off.toShortString();
                    if (goal.contains("for animals") || goal.contains("Hunt")) searched[0] = true;
                    context.assertTrue(++watched[0] >= 400, "Watched for twenty seconds");
                })
                .createAndAdd(() -> {
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    context.assertTrue(climbed[0] == null, "Back to the climb: " + climbed[0]);
                    context.assertTrue(searched[0] && underRock[0], "Looked for animals under the rock: searched " + searched[0] + ", under it " + underRock[0] + "; " + goal);
                    context.assertTrue(f.player().isAlive(), "Alive");
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Under a lake whose floor of gravel lies on its corridor's ceiling, wanting water for its cast: it tunnels on under
     * the lake until the ground over its head is dry, climbs out at the shore and fills its bucket at the lake (F-25c4ah).
     * In the live pair on 41f9de9 Kindred, so placed, went round once a second for eighteen minutes between a step up
     * with no way on, the wall and the way back, every step up refused by the water and the gravel over its ceiling.
     * Staged: a block of stone, the lake in it three deep on its floor of gravel, the corridor under it, lit, and the day;
     * the cast route's kit as though made, with its two buckets empty; no water anywhere it knows of.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "underLake", tickLimit = 3600)
    public void underALakeItTunnelsOutFromUnderTheWaterClimbsOutAndFillsItsBucket(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos[] at = new BlockPos[1];
        int[] toWall = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Underlake"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos p = f.player().getBlockPos().toImmutable();
                    at[0] = p;
                    FixtureGround.keep(context, p.add(-14, -3, -20), p.add(16, 10, 20));
                    for (int x = -14; x <= 16; x++) for (int z = -20; z <= 20; z++) for (int y = -3; y <= 10; y++) {
                        boolean lake = x >= -10 && x <= 12 && Math.abs(z) <= 8;
                        boolean corridor = z == 0 && x >= -2 && x <= 4 && y >= 0 && y <= 1;
                        var state = y >= 7 ? Blocks.AIR : lake && y >= 4 ? Blocks.WATER : lake && y == 3 ? Blocks.GRAVEL
                                : corridor ? Blocks.AIR : Blocks.STONE;
                        world.setBlockState(p.add(x, y, z), state.getDefaultState());
                    }
                    for (int x = -2; x <= 4; x += 3) world.setBlockState(p.add(x, 1, 1), Blocks.GLOWSTONE.getDefaultState());
                    world.setTimeOfDay(1000);
                    var player = f.player();
                    player.refreshPositionAndAngles(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.IRON_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BUCKET, 2));
                    inventory.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.BREAD, 32));
                    inventory.setStack(6, new ItemStack(Items.STICK, 16));
                    player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    String goal = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (goal.endsWith(": to the wall")) toWall[0]++;
                    context.assertTrue(player != null && ItemMoves.count(player.getInventory(), Items.WATER_BUCKET) >= 1, "Its bucket filled with water: now "
                            + (player == null ? "no body" : player.getBlockPos().subtract(at[0]).toShortString()) + ", walks to the wall " + toWall[0] + ", " + goal);
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.assertTrue(player.isAlive() && player.getHealth() >= 14, "Unhurt: " + player.getHealth());
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
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
