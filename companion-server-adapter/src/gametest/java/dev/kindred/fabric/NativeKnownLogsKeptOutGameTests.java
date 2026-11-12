package dev.kindred.fabric;

import dev.kindred.core.construction.BillOfMaterials;
import dev.kindred.core.construction.BuildSite;
import dev.kindred.core.construction.BuildStyle;
import dev.kindred.core.construction.Houses;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.TreeMap;

/**
 * In plain terms: a player building a cherry cabin, with only oak trees about, says once a trip out for logs why the
 * oak it knows was not for it, and the line changes nothing it does.
 *
 * Technical: the build's known-logs-kept-out line (F-27a; for capM-2 on #219's CI, where the cabin's log gather came
 * back empty with a tree 21 blocks off and nothing said why). STAGED: NativeBaseGameTests' ground, with its two oak
 * trunks; the player seeded with a cherry log (E-29a) and holding all any cherry cabin takes but its cherry blocks,
 * as though gathered, with stone tools, a table, dirt and bread. It goes out for cherry logs, its gather keeps the
 * oak out as not its wood, and it explores. Passes if a line names the oak as not its wood, and the build has logged
 * no more lines than it has made trips.
 */
public final class NativeKnownLogsKeptOutGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "knownLogsKeptOut", tickLimit = 12000)
    public void stagedOutForCherryLogsWithOnlyOakAboutItSaysOnceATripWhyTheOakWasKeptOut(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 11500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(40, 7, 4), o.add(40, 7, 40)};
        BlockPos home = o.add(16, 7, 16);
        PortalRunCampaign[] campaign = new PortalRunCampaign[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeBaseGameTests.ground(context, trunks);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    // As though gathered: all any cherry cabin takes but what is made of cherry, which it must cut.
                    var most = new TreeMap<String, Integer>();
                    for (int v = 1; v <= BuildStyle.VARIANTS; v++) {
                        var plan = Houses.of(Houses.Stage.CABIN, new BuildStyle("", "cherry", "cobblestone", v, 0));
                        BillOfMaterials.of(plan.blocks(), new BuildSite(Map.of()), Map.of()).needed().forEach((item, n) -> most.merge(item, n, Math::max));
                    }
                    most.keySet().removeIf(item -> item.startsWith("minecraft:cherry_"));
                    int slot = 0;
                    inventory.setStack(slot++, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(slot++, new ItemStack(Items.STONE_AXE));
                    for (var e : most.entrySet()) {
                        var item = Registries.ITEM.get(Identifier.of(e.getKey()));
                        for (int left = e.getValue(); left > 0; left -= item.getMaxCount()) {
                            inventory.setStack(slot++, new ItemStack(item, Math.min(left, item.getMaxCount())));
                        }
                    }
                    inventory.setStack(slot++, new ItemStack(Items.CRAFTING_TABLE, 1));
                    inventory.setStack(slot++, new ItemStack(Items.DIRT, 64));
                    inventory.setStack(slot, new ItemStack(Items.BREAD, 16));
                    KindredMod.LOGGER.info("Known logs kept out: Mason holds {}", most);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
                    campaign[0] = f.runtime.campaignForTest(f.id);
                    context.assertTrue(f.runtime.setSeedBlock(f.source, f.id, "minecraft:cherry_log") == 1, "Seeded");
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
                })
                .createAndAdd(() -> {
                    String last = campaign[0].knownLogsLastForTest();
                    context.assertTrue(last != null && last.contains("not its wood (minecraft:oak_log)"),
                            "No line naming the oak as not its wood yet: " + last + " / " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.baseNote(f.id));
                })
                .createAndAdd(() -> {
                    int lines = campaign[0].knownLogsLinesForTest(), trips = f.runtime.buildTripsForTest(f.id);
                    context.assertTrue(lines >= 1 && lines <= trips, lines + " lines for " + trips + " trips");
                    KindredMod.LOGGER.info("Known logs kept out: {} lines for {} trips; last: {}", lines, trips, campaign[0].knownLogsLastForTest());
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
