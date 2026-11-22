package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.ActionRecord;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * e40a22's A1 (five of its deaths): badly hurt, a zombie after it, the goal switched between "Back off" and "Hole up"
 * every second or two until the zombie killed it. A zombie walks as fast as a player, so each back-off ended with it
 * still in reach, and the hole-up's wait was cut short by its hits; nothing in between struck it, and in 505-kindred-33951
 * it died with a shield, armour 6 and cooked porkchop in hand, its sword never drawn. Staged after that scene: hurt, food
 * in hand, a sword on the hotbar, a shield in the off hand, no block to wall itself in with, one zombie after it on open
 * ground under a roof: in 505-kindred-33951 its "Hole up: recover behind the wall" was a rest in the open cave, with no wall.
 */
public class NativeBackOffFlipGameTests implements FabricGameTest {
    /** The middle of the arena, in the plot's own coordinates. */
    private static final int MID = 60;

    /**
     * Level stone ground sixty across in the middle of the test's own wide plot, open to a roof four blocks up: room for a
     * back-off to end as walked, as in the scenes, and shade so the zombies keep after it. All of it inside the plot.
     */
    private static void arena(TestContext context) {
        for (int x = MID - 30; x <= MID + 30; x++) for (int z = MID - 30; z <= MID + 30; z++) {
            context.setBlockState(x, 0, z, Blocks.STONE);
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
            context.setBlockState(x, 6, z, Blocks.STONE);
        }
    }

    /**
     * Once a back-off has left the zombie in reach, the player fights it with its sword in hand, and holds one choice:
     * at most two switches between backing off and holing up, the zombie killed and the player alive.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlip", tickLimit = 1400)
    public void hurtWithAZombieThatKeepsPaceItFightsItWithItsSwordRatherThanFlipBetweenBackingOffAndHolingUp(TestContext context) {
        run(context, "Pacekept", false);
    }

    /**
     * The same, with a second zombie coming up behind it from eight blocks off three seconds in (the lead's variant): the
     * fight it falls through to does not turn its back on the new one. Alive a minute on, or with both killed, its sword
     * drawn and its choice held.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipSecond", tickLimit = 2000)
    public void hurtWithAZombieThatKeepsPaceAndASecondComingUpBehindItFightsBothAndLives(TestContext context) {
        run(context, "Pacebehind", true);
    }

    /**
     * As in 505-kindred-33951 itself: a zombie and a spider after it together, so a hit keeps landing while it eats and
     * rests, and its health stays low.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipPair", tickLimit = 1400)
    public void hurtWithAZombieAndASpiderAfterItItFightsWithItsSwordRatherThanFlipBetweenBackingOffAndHolingUp(TestContext context) {
        run(context, "Pacepair", false, true);
    }

    /**
     * The eating rule's bound (the lead's guard): walled in its night pocket, the bar at six, a zombie after it just outside
     * the wall. The zombie cannot get at it, so it eats, within twenty seconds, as before.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipSealedEat", tickLimit = 800)
    public void walledInWithAZombieOutsideAndHungryItStillEats(TestContext context) {
        walledIn(context, "Walledin", false);
    }

    /**
     * F-25c4dh3, staged: the same pocket, the zombie held 2.0 off just outside the wall each tick, its target the player. Within
     * two it was read as able to get at it before sight or a path was asked, so the meal waited out the test (CI on #359,
     * b2d75bcb, "the zombie 2.0 off"). Through stone it neither sees nor reaches the player, so it eats within twenty seconds.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipSealedEatHeld", tickLimit = 800)
    public void walledInWithAZombieHeldJustOutsideTheWallItStillEats(TestContext context) {
        walledIn(context, "Walledheld", true);
    }

    private static void walledIn(TestContext context, String name, boolean held) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        long[] started = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    arena(context);
                    // A pocket one cell across and two high, stone all round it and over it.
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 2; y <= 4; y++) {
                        boolean inside = dx == 0 && dz == 0 && y <= 3;
                        context.setBlockState(MID + dx, y, MID + dz, inside ? Blocks.AIR : Blocks.STONE);
                    }
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID))), "In the pocket"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.COOKED_PORKCHOP, 3));
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    player.setHealth(14);
                    player.getHungerManager().setFoodLevel(6);
                    player.getHungerManager().setSaturationLevel(0);
                    context.getWorld().setTimeOfDay(14000);
                    zombie[0] = zombie(context, player, context.getRelative(new net.minecraft.util.math.Vec3d(player.getX() + 2, player.getY(), player.getZ())));
                    started[0] = context.getTick();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    if (held) {
                        // Staged: held 2.0 off, against the wall's far side, still after the player.
                        zombie[0].refreshPositionAndAngles(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
                        zombie[0].setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        zombie[0].setTarget(player);
                    }
                    context.assertTrue(player.getHungerManager().getFoodLevel() > 6, "It ate behind its wall within "
                            + (context.getTick() - started[0]) + " ticks; the zombie " + String.format("%.1f", Math.sqrt(player.squaredDistanceTo(zombie[0])))
                            + " off; doing " + f.runtime.panelBots(Actor.console()).getFirst().goalProgress());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.player().isAlive(), "Alive");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * F-22c104, staged after CI's second-zombie failures (#385, #416, main): a back-off has just ended and the player is
     * backing off again, its retreat still held, when a zombie comes into reach. In those runs it went on backing off with
     * the zombie striking its back, never drew its sword, and was killed. Staged: hurt, one zombie after it held still while
     * the player backs off past the end of its first back-off, then set a step and a half behind it and let go. Within three
     * seconds of that, it fights with its sword in hand.
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipCaught", tickLimit = 1200)
    public void caughtAsItBacksOffAgainItTurnsAndFightsRatherThanBackOffOnce(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        net.minecraft.util.math.Vec3d[] from = new net.minecraft.util.math.Vec3d[1];
        long[] caughtAt = {-1};
        boolean[] fought = {false};
        String[] after = {null};
        StringBuilder trail = new StringBuilder();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    arena(context);
                    f.runtime.createNative(f.source, "Pacecaught");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID))), "Standing in the arena"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.COOKED_PORKCHOP, 3));
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().selectedSlot = 0;
                    player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
                    player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    zombie[0] = zombie(context, player, context.getRelative(new net.minecraft.util.math.Vec3d(player.getX(), player.getY(), player.getZ() + 2.3)));
                    zombie[0].setAiDisabled(true); // Held still: the player's first back-off ends with it far behind.
                    from[0] = player.getPos();
                    context.getWorld().setTimeOfDay(14000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    String progress = bot.goalProgress();
                    if (caughtAt[0] < 0) {
                        // Past ten blocks and a half from where it stood, its first back-off (ten blocks off, within three)
                        // has ended, and it is backing off again.
                        boolean again = "native_goto".equals(bot.task()) && progress.contains("Back off")
                                && player.getPos().squaredDistanceTo(from[0]) > 13.0 * 13.0;
                        context.assertTrue(again, "Backing off past its first back-off's end: " + progress + " ("
                                + String.format("%.1f", Math.sqrt(player.getPos().squaredDistanceTo(from[0]))) + " from where it stood)");
                        // A step and a half behind it, from where it started, and let go: in reach as it backs off.
                        var back = from[0].subtract(player.getPos()).normalize().multiply(1.5);
                        zombie[0].refreshPositionAndAngles(player.getX() + back.x, player.getY(), player.getZ() + back.z, 0, 0);
                        zombie[0].setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        zombie[0].setAiDisabled(false);
                        zombie[0].setTarget(player);
                        caughtAt[0] = context.getTick();
                        trail.append("caught @").append(caughtAt[0]).append(", ");
                    }
                    if (!progress.isEmpty() && (trail.isEmpty() || !trail.toString().endsWith(progress + ", "))) trail.append(progress).append(", ");
                    fought[0] |= "native_fight".equals(bot.task()) && player.getMainHandStack().isOf(Items.IRON_SWORD);
                    if (after[0] == null && (fought[0] || !player.isAlive() || context.getTick() - caughtAt[0] >= 60)) {
                        after[0] = "health " + player.getHealth() + ", the zombie " + String.format("%.1f", player.distanceTo(zombie[0])) + " off";
                    }
                    context.assertTrue(after[0] != null, "Three seconds after it was caught");
                })
                .createAndAdd(() -> {
                    context.assertTrue(fought[0], "Caught as it backed off again, it fought with its sword within three seconds ("
                            + after[0] + "; " + trail + ")");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static void run(TestContext context, String name, boolean second) {
        run(context, name, second, false);
    }

    private static void run(TestContext context, String name, boolean second, boolean spider) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        // Mobs that strike, as in the scenes (the test world's own difficulty leaves them harmless): normal difficulty while
        // it runs, put back as it ends, as NativeMobGriefGameTests does.
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        net.minecraft.entity.mob.ZombieEntity[] zombies = new net.minecraft.entity.mob.ZombieEntity[2];
        net.minecraft.entity.mob.SpiderEntity[] spiders = new net.minecraft.entity.mob.SpiderEntity[1];
        String[] last = {""};
        int[] switches = {0}, gainless = {0};
        String[] wasBackingOff = {null};
        long[] started = {-1};
        boolean[] swordFight = {false}, died = {false};
        // The body it started the fight in: a native player respawns within the tick it dies, as another entity.
        net.minecraft.server.network.ServerPlayerEntity[] body = new net.minecraft.server.network.ServerPlayerEntity[1];
        StringBuilder trail = new StringBuilder();
        // Each change of its goal step, with its health, hunger and the zombies' distances then (F-22c104a): CI's second-zombie
        // failures came on a step this test did not name, so its failure names them.
        StringBuilder steps = new StringBuilder();
        String[] lastStep = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    arena(context);
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID))), "Standing in the arena"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.COOKED_PORKCHOP, 3));
                    player.getInventory().setStack(1, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().selectedSlot = 0; // Food in hand, as after eating.
                    player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
                    player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
                    player.setHealth(5);
                    player.getHungerManager().setFoodLevel(16);
                    player.getHungerManager().setSaturationLevel(0);
                    zombies[0] = zombie(context, player, context.getRelative(new net.minecraft.util.math.Vec3d(player.getX(), player.getY(), player.getZ() + 2.3)));
                    if (spider) {
                        var s = context.spawnEntity(net.minecraft.entity.EntityType.SPIDER, context.getRelative(new net.minecraft.util.math.Vec3d(player.getX() + 2.5, player.getY(), player.getZ())));
                        s.setPersistent();
                        s.setTarget(player);
                        spiders[0] = s;
                    }
                    started[0] = context.getTick();
                    context.getWorld().setTimeOfDay(14000);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(14000);
                    if (body[0] == null) body[0] = player;
                    died[0] |= player != body[0] || !player.isAlive() || player.getHealth() <= 0;
                    if (second && zombies[1] == null && context.getTick() - started[0] >= 60) {
                        // Eight blocks behind it, from the zombie it faces.
                        var away = player.getPos().subtract(zombies[0].getPos()).normalize().multiply(8);
                        var at = player.getPos().add(away.x, 0, away.z);
                        zombies[1] = zombie(context, player, context.getRelative(at).add(0, 0.5, 0));
                        trail.append("second @").append(context.getTick()).append(", ");
                    }
                    var bot = f.runtime.panelBots(Actor.console()).getFirst();
                    String progress = bot.goalProgress();
                    String step = progress + " / " + bot.task();
                    if (!died[0] && !step.equals(lastStep[0])) { // Its first life only: a respawned body starts afresh.
                        lastStep[0] = step;
                        steps.append("@").append(context.getTick() - started[0]).append(' ').append(progress.replaceFirst("^Wood: ", ""))
                                .append(" [").append(bot.task()).append(String.format("; health %.1f, food %d", player.getHealth(),
                                        player.getHungerManager().getFoodLevel()));
                        for (var z : zombies) if (z != null) steps.append(z.isAlive() ? String.format(", a zombie %.1f", Math.sqrt(z.squaredDistanceTo(player))) : ", a zombie dead");
                        steps.append("], ");
                    }
                    String kind = progress.contains("Back off") ? "back off" : progress.contains("Hole up") ? "hole up" : "";
                    if (!kind.isEmpty() && !kind.equals(last[0])) {
                        if (!last[0].isEmpty()) switches[0]++;
                        last[0] = kind;
                        trail.append(kind).append(" @").append(context.getTick()).append(", ");
                    }
                    swordFight[0] |= "native_fight".equals(bot.task()) && player.getMainHandStack().isOf(Items.IRON_SWORD);
                    // A back-off that has just ended as walked, with a mob on foot still within 2.5 blocks: gainless, the A1
                    // signature. Counted at the tick its goal step is no longer that back-off.
                    String backingOff = "native_goto".equals(bot.task()) && progress.contains("Back off") ? progress : null;
                    if (wasBackingOff[0] != null && !wasBackingOff[0].equals(backingOff)) {
                        var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                        boolean walked = record == null || record.state() != ActionRecord.State.FAILED && record.state() != ActionRecord.State.CANCELLED;
                        boolean close = java.util.stream.Stream.concat(java.util.Arrays.stream(zombies), java.util.Arrays.stream(spiders))
                                .anyMatch(m -> m != null && m.isAlive() && m.squaredDistanceTo(player) <= 2.5 * 2.5);
                        if (walked && close) {
                            gainless[0]++;
                            trail.append("gainless @").append(context.getTick()).append(", ");
                        }
                    }
                    wasBackingOff[0] = backingOff;
                    boolean allDead = !zombies[0].isAlive() && (!second || zombies[1] != null && !zombies[1].isAlive())
                            && (!spider || !spiders[0].isAlive());
                    // Holding out alive for a minute is as good as the fight won: holed up and staying holed up is a choice
                    // held, as the lead set it; a death, or the loop, fails it.
                    boolean heldOut = context.getTick() - started[0] >= 1200;
                    context.assertTrue(died[0] || allDead || heldOut, "The fight decided (health " + player.getHealth() + ", the zombie " + (zombies[0].isAlive() ? String.format("%.1f off, after %s, at %s; the player at %s", Math.sqrt(player.squaredDistanceTo(zombies[0])), zombies[0].getTarget() == null ? "nobody" : zombies[0].getTarget().getName().getString(), zombies[0].getBlockPos().toShortString(), player.getBlockPos().toShortString()) + " ai off " + zombies[0].isAiDisabled() + " idle " + zombies[0].getNavigation().isIdle() + " persistent " + zombies[0].isPersistent() + " difficulty " + context.getWorld().getDifficulty() : "dead") + ", switches " + switches[0] + ": " + trail + ")");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    KindredMod.LOGGER.info("Back-off flip ({}): gainless back-offs {}, switches {}, killed {}, a sword fight {}; {}; its steps {}", name,
                            gainless[0], switches[0], died[0], swordFight[0], trail, steps);
                    context.assertTrue(gainless[0] <= 2, "At most two back-offs that left a mob in reach: " + gainless[0] + " (" + trail + ")");
                    context.assertTrue(!died[0] && player.isAlive(), "Alive, never killed (switches " + switches[0] + ": " + trail + "; its steps " + steps + ")");
                    // With a zombie and a spider, holing up and staying holed up passes as well (the lead's guard): no sword
                    // fight is asked of it, only that it lives and holds its choice.
                    context.assertTrue(spider || swordFight[0], "It fought with its sword in hand (" + trail + ")");
                    context.assertTrue(switches[0] <= 2, "It held one choice: " + switches[0] + " switches between backing off and holing up: " + trail);
                    var record = f.runtime.nativeActionStatus(Actor.console(), f.id).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * F-25c4dh1, STAGED: the zombie is held ten blocks off the player by the fixture each tick, behind a wall at first so the
     * player does not see it, its target the player. "Heal before going on", a four-second rest, read nothing after it from
     * {@code threats()}, which wants sight (and is empty below six health by design), so the player stood still for the mob
     * to walk up, and the
     * fight cut the rest short; in main's corpus gate a fifth of the rests were followed within five seconds by a fight
     * or a back-off. On open ground with a mob on foot after it within sixteen, it rests no more: for five seconds no
     * "heal before going on".
     */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipHealHunted", tickLimit = 600)
    public void hurtInTheOpenWithAZombieAfterItItDoesNotStopToRest(TestContext context) {
        healRest(context, "Healhunted", true);
    }

    /** The control: the same, with nothing after it, it rests to heal within five seconds, as before. */
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "backOffFlipHealAlone", tickLimit = 600)
    public void hurtInTheOpenWithNothingAfterItItRestsToHeal(TestContext context) {
        healRest(context, "Healalone", false);
    }

    private static void healRest(TestContext context, String name, boolean hunted) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        long[] started = {-1};
        boolean[] rested = {false};
        double[] farthest = {0};
        StringBuilder steps = new StringBuilder();
        String[] lastStep = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Level stone ground open to the sky, and a wall five off between the player and where the zombie is held.
                    for (int x = MID - 30; x <= MID + 30; x++) for (int z = MID - 30; z <= MID + 30; z++) {
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.STONE);
                        for (int y = 2; y <= 8; y++) context.setBlockState(x, y, z, Blocks.AIR);
                    }
                    for (int x = MID - 3; x <= MID + 3; x++) for (int y = 2; y <= 5; y++) context.setBlockState(x, y, MID + 5, Blocks.STONE);
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround()
                        && f.player().getBlockPos().equals(context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID))), "Standing on the ground"))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.setHealth(7);
                    player.getHungerManager().setFoodLevel(20);
                    player.getHungerManager().setSaturationLevel(0);
                    context.getWorld().setTimeOfDay(6000);
                    if (hunted) zombie[0] = zombie(context, player, context.getRelative(new net.minecraft.util.math.Vec3d(player.getX(), player.getY(), player.getZ() + 10)));
                    started[0] = context.getTick();
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    var player = f.player();
                    context.getWorld().setTimeOfDay(6000);
                    if (hunted) {
                        // Staged: held ten off it each tick, behind the wall at first, still after the player: a zombie
                        // keeps pace, and one left where it was would be outwalked past sixteen by the player's work.
                        zombie[0].refreshPositionAndAngles(player.getX(), player.getY(), player.getZ() + 10, 0, 0);
                        zombie[0].setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                        zombie[0].setTarget(player);
                        // On the first tick it is held, as the player decides from where it stands: its work then walks it off.
                        if (farthest[0] == 0) farthest[0] = Math.sqrt(player.squaredDistanceTo(zombie[0]));
                    }
                    String progress = f.runtime.panelBots(Actor.console()).getFirst().goalProgress();
                    if (!progress.equals(lastStep[0])) {
                        lastStep[0] = progress;
                        steps.append(" @").append(context.getTick() - started[0]).append(" ").append(progress).append(";");
                    }
                    rested[0] |= progress.contains("heal before going on");
                    if (!hunted) context.assertTrue(rested[0], "Rests to heal with nothing after it; its steps:" + steps);
                    else context.assertTrue(context.getTick() - started[0] >= 100, "Five seconds on");
                })
                .createAndAdd(() -> {
                    // The staging itself: the zombie was where it was held, within sixteen of the player as it decided.
                    context.assertTrue(!hunted || farthest[0] <= 12, "The zombie held at most 12 off on its first tick held; it was " + String.format("%.1f", farthest[0]));
                    context.assertTrue(!hunted || !rested[0], "Rested to heal with a zombie after it ten off; its steps:" + steps);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A zombie after the player, in a helmet so the sun does not burn it, kept from despawning. */
    private static net.minecraft.entity.mob.ZombieEntity zombie(TestContext context, net.minecraft.server.network.ServerPlayerEntity player,
                                                                 net.minecraft.util.math.Vec3d at) {
        // Spawned with its goals: TestContext.spawnMob clears them, and a mob so spawned neither pursues nor strikes (F-22c80).
        var zombie = context.spawnEntity(net.minecraft.entity.EntityType.ZOMBIE, at);
        zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        zombie.setPersistent();
        zombie.setTarget(player);
        return zombie;
    }
}
