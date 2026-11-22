package dev.kindred.fabric;

import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In plain terms: a GameTest: a hurt player with bread in its hotbar and two zombies coming at it fights them, and the
 * console says how the player stood as the fight began and as it ended, and that its meal was held back by the zombie.
 *
 * Technical: F-25c4dh4's diagnostic lines, log only. E-40's iteration 27 lost more players to zombies than 26 in long
 * fights before the first chestplate, with no meal in them, and its consoles could not say whether a meal was refused
 * or never asked for. Staged on open stone at noon: health 8, food 12, a stone sword and bread in the hotbar, two zombies
 * in helmets four and seven blocks off and after it, the goal running. It must print "fight starts with zombie" and "fight ends"
 * with health, food, saturation, armour, weapon, the mobs within 8, the light and Y; and "meal held back: zombie" once.
 */
public final class NativeFightLogGameTests implements FabricGameTest {
    private static final int MID = 16;

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "fightLog", tickLimit = 900)
    public void aFightsStartAndEndAndAMealHeldBackAreLogged(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        String name = "Logged";
        List<String> lines = new CopyOnWriteArrayList<>();
        var appender = new AbstractAppender("fightLog", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String line = event.getMessage().getFormattedMessage();
                if (line.startsWith(name + " fight ") || line.startsWith(name + " meal held back")) lines.add(line);
            }
        };
        // On the root's config, so the Kindred logger's own console output is left as it is.
        var logContext = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
        var root = logContext.getConfiguration().getRootLogger();
        appender.start();
        root.addAppender(appender, null, null);
        logContext.updateLoggers();
        FixtureGround.whenDone(context, () -> { root.removeAppender(appender.getName()); logContext.updateLoggers(); appender.stop(); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = MID - 12; x <= MID + 12; x++) for (int z = MID - 12; z <= MID + 12; z++) {
                        context.setBlockState(x, 0, z, Blocks.STONE);
                        context.setBlockState(x, 1, z, Blocks.STONE);
                        for (int y = 2; y <= 8; y++) context.setBlockState(x, y, z, Blocks.AIR);
                    }
                    f.runtime.createNative(f.source, name);
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var at = context.getAbsolutePos(new net.minecraft.util.math.BlockPos(MID, 2, MID));
                    f.player().refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    f.player().setVelocity(net.minecraft.util.math.Vec3d.ZERO);
                })
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing on the ground"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.BREAD, 8));
                    player.setHealth(8);
                    player.getHungerManager().setFoodLevel(12);
                    player.getHungerManager().setSaturationLevel(0);
                    context.getWorld().setTimeOfDay(6000);
                    // Two zombies after it, four and seven off: as the first fight ends, the second can still get at it,
                    // and the hurt player's meal is held back by it.
                    for (int off : new int[] {4, 7}) {
                        var zombie = EntityType.ZOMBIE.create(context.getWorld());
                        zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                        zombie.setPersistent();
                        zombie.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ() + off, 180, 0);
                        context.getWorld().spawnEntity(zombie);
                        zombie.setTarget(player);
                    }
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(6000);
                    boolean starts = lines.stream().anyMatch(l -> l.startsWith(name + " fight starts with zombie: health "));
                    boolean ends = lines.stream().anyMatch(l -> l.startsWith(name + " fight ends "));
                    boolean held = lines.stream().anyMatch(l -> l.startsWith(name + " meal held back: zombie "));
                    context.assertTrue(starts && ends && held, "Its lines so far: " + lines);
                })
                .createAndAdd(() -> {
                    String start = lines.stream().filter(l -> l.startsWith(name + " fight starts")).findFirst().orElseThrow();
                    for (String part : List.of("health ", "food ", "saturation ", "armour ", "weapon stone_sword", "mobs within 8 {zombie=", "light ", "at Y "))
                        context.assertTrue(start.contains(part), "The start line names " + part + ": " + start);
                    String end = lines.stream().filter(l -> l.startsWith(name + " fight ends")).findFirst().orElseThrow();
                    context.assertTrue(end.matches(name + " fight ends [A-Z]+.*: health .*"), "The end line says how it ended: " + end);
                    // Once a spell, not each tick it is held back.
                    long held = lines.stream().filter(l -> l.startsWith(name + " meal held back")).count();
                    context.assertTrue(held <= 3, "The meal line once a spell, not each tick: " + held + " " + lines);
                    KindredMod.LOGGER.info("{}: {}", name, lines);
                    // Its cost on the tick: the state line is built twice a fight, never per tick. Timed here, two hundred
                    // times with a zombie about, it must stay far under a millisecond a call.
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 200; i++) PlayerRuntime.fightState(f.player());
                    double perCall = (System.nanoTime() - t0) / 200.0 / 1000.0;
                    KindredMod.LOGGER.info("{}: the fight's state line built in {} us a call", name, String.format(java.util.Locale.ROOT, "%.1f", perCall));
                    context.assertTrue(perCall < 500, "The state line costs " + perCall + " us a call");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
