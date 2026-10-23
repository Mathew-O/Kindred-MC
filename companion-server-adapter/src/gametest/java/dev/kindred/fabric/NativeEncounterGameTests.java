package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kindred.core.Actor;
import dev.kindred.core.tactics.CombatRuleset;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A fight's record read back (E-41a). The player fights a zombie with its sword; when the fight ends, its record is
 * written off the server thread, and read here from the file. It must name the ruleset, keep the frames in tick order,
 * hold the hits the player saw land as confirmed events, and show the zombie's health as the player believed it,
 * falling from its kind's twenty.
 */
public final class NativeEncounterGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "encounterRecord", tickLimit = 600)
    public void aFightIsRecordedAndReadBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        net.minecraft.entity.mob.ZombieEntity[] zombie = new net.minecraft.entity.mob.ZombieEntity[1];
        Path[] file = new Path[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Recorded"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var player = f.player();
                    player.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    player.getInventory().selectedSlot = 0;
                    // Three and a half blocks off, out of a strike's reach; a helmet, so the sun does not finish it first.
                    zombie[0] = context.spawnMob(EntityType.ZOMBIE, new Vec3d(3.5, 2.0, 7.0));
                    zombie[0].equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                    context.assertTrue(f.runtime.nativeFight(f.source, f.id, zombie[0].getUuid(), 0) == 1, "Fight requested");
                })
                .createAndAdd(() -> context.assertTrue(!zombie[0].isAlive(), "The zombie fought and killed"))
                .createAndAdd(() -> {
                    Path directory = EncounterLog.directory(context.getWorld().getServer(), f.player().getUuid());
                    context.assertTrue(Files.isDirectory(directory), "The record's folder written");
                    try (var listing = Files.list(directory)) {
                        file[0] = listing.filter(p -> p.getFileName().toString().endsWith(".jsonl")).findFirst().orElse(null);
                    } catch (java.io.IOException failure) {
                        throw new RuntimeException(failure);
                    }
                    context.assertTrue(file[0] != null, "The record written");
                })
                .createAndAdd(() -> {
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file[0]);
                    } catch (java.io.IOException failure) {
                        throw new RuntimeException(failure);
                    }
                    JsonObject header = JsonParser.parseString(lines.getFirst()).getAsJsonObject();
                    context.assertTrue(CombatRuleset.ID.equals(header.get("ruleset").getAsString()), "The ruleset named: " + header);
                    context.assertTrue(header.get("frames").getAsInt() == lines.size() - 1 && lines.size() > 5, "Every frame a line: " + header);
                    context.assertTrue(header.get("outcome").getAsString().contains("defeated"), "How it ended: " + header);
                    long lastTick = Long.MIN_VALUE;
                    int landed = 0;
                    double lastBelief = Double.MAX_VALUE, firstBelief = -1;
                    var decisions = new java.util.LinkedHashSet<String>();
                    for (String line : lines.subList(1, lines.size())) {
                        JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
                        long tick = frame.get("tick").getAsLong();
                        context.assertTrue(tick > lastTick, "Frames in tick order");
                        lastTick = tick;
                        decisions.add(frame.get("decision").getAsString());
                        for (var event : frame.getAsJsonArray("events")) if (event.getAsString().startsWith("hit-landed")) landed++;
                        var seen = frame.getAsJsonArray("seen");
                        context.assertTrue(seen.size() == 1, "The zombie in every frame");
                        double belief = seen.get(0).getAsJsonObject().get("believedHealth").getAsDouble();
                        if (firstBelief < 0) firstBelief = belief;
                        context.assertTrue(belief <= lastBelief + 1e-9, "The belief only falls: " + belief + " after " + lastBelief);
                        lastBelief = belief;
                    }
                    KindredMod.LOGGER.info("Recorded: {} frames, {} hits seen to land, believed health {} to {}, decisions {}",
                            lines.size() - 1, landed, firstBelief, lastBelief, decisions);
                    context.assertTrue(firstBelief == 20, "Believed whole at first: " + firstBelief);
                    context.assertTrue(landed >= 3 && lastBelief < 20, "The hits it saw land: " + landed + ", believed " + lastBelief);
                    context.assertTrue(decisions.contains("close in") && decisions.stream().anyMatch(d -> d.startsWith("strike")), "What it chose: " + decisions);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
