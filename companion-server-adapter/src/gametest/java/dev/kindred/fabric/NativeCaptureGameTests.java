package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.PowerRules;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest of a raid by the rule. Blue holds five chunks in a plus with the power for one. Red's leader
 * cannot open Blue's chest while Blue's land is only exposed; cannot take the chunk in the middle; takes the border chunk
 * the chest stands in, then opens the chest and finds its five diamonds as they were; cannot take another chunk while
 * Blue's thirty-minute shield holds; and, once Red's own power falls an hour later, Blue takes the chunk back.
 *
 * Technical: M-06e's native fixture with two stand-in people. Claims and power are written to the World Record directly
 * (the claim and power rules are JUnit's); captures go through {@code /faction capture}'s own method. The factions are
 * founded with the clock 50 hours back, past the new-faction shield, and the clock moved on for the hour's limit. The
 * border setting is at its widest, since test positions lie millions of blocks from spawn.
 */
public final class NativeCaptureGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "capture", tickLimit = 800)
    public void aBorderChunkIsCapturedByTheRuleAndItsChestChangesHandsUnmoved(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 780);
        var server = context.getWorld().getServer();
        ServerPlayerEntity[] p = new ServerPlayerEntity[2];
        BlockPos chest = new BlockPos(2, 2, 2);
        int[] t = new int[2];
        FixtureGround.whenDone(context, () -> { for (var x : p) if (x != null && server.getPlayerManager().getPlayer(x.getUuid()) != null) server.getPlayerManager().remove(x); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Test positions lie millions of blocks out: the border at its widest, so the rules apply where the test stands.
                    f.runtime.factionsWorldForTest(WIDE);
                    p[0] = MockPlayers.survival(context, "Ana", chest.up());
                    p[1] = MockPlayers.survival(context, "Ben", chest.up());
                    f.runtime.factionsTickForTest();
                    f.runtime.factions().clockForTest(-50 * 3_600_000L);
                    context.assertTrue(f.runtime.faction(f.source, p[0].getUuid(), "create", List.of("Red", "RED")).accepted(), "Ana founds Red");
                    context.assertTrue(f.runtime.faction(f.source, p[1].getUuid(), "create", List.of("Blue", "BLU")).accepted(), "Ben founds Blue");
                    f.runtime.factions().clockForTest(0);
                })
                .createAndAdd(() -> {
                    var abs = context.getAbsolutePos(chest);
                    t[0] = abs.getX() >> 4;
                    t[1] = abs.getZ() >> 4;
                    String blue = f.runtime.projected().factions().named("Blue").orElseThrow().id().toString();
                    // Blue's plus: the chest's chunk T on its west arm, the centre one east of it.
                    for (int[] c : new int[][] {{0, 0}, {1, 0}, {2, 0}, {1, 1}, {1, -1}}) {
                        f.runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, blue, ClaimLedger.chunk(W, t[0] + c[0], t[1] + c[1]), Map.of()));
                    }
                    f.runtime.record(PowerRules.set(p[1].getUuid(), 1, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                    context.setBlockState(chest, Blocks.CHEST);
                    ((ChestBlockEntity) context.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 5));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Blue's land written, its power 1 for five chunks");
                    var ana = p[0];
                    // Exposed, not captured: Red cannot open the chest or break a block.
                    open(context, ana, chest);
                    context.assertTrue(ana.currentScreenHandler == ana.playerScreenHandler, "Ana cannot open Blue's chest before a capture");
                    context.assertFalse(ana.interactionManager.tryBreakBlock(context.getAbsolutePos(chest)), "Nor break it");
                    // The centre chunk is interior: refused.
                    var home = ana.getPos();
                    ana.refreshPositionAndAngles((t[0] + 1) * 16 + 8, home.y, t[1] * 16 + 8, 0, 0);
                    var interior = f.runtime.faction(f.source, ana.getUuid(), "capture", List.of());
                    context.assertTrue(!interior.accepted() && interior.lines().getFirst().startsWith("Not on the border"), "The centre refused: " + interior.lines());
                    ana.refreshPositionAndAngles(home.x, home.y, home.z, 0, 0);
                    var taken = f.runtime.faction(f.source, ana.getUuid(), "capture", List.of());
                    context.assertTrue(taken.accepted(), "The border chunk taken: " + taken.lines());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Captured");
                    var ana = p[0];
                    String red = f.runtime.projected().factions().named("Red").orElseThrow().id().toString();
                    context.assertTrue(red.equals(f.runtime.projected().claims().get(ClaimLedger.chunk(W, t[0], t[1]))), "T is Red's");
                    open(context, ana, chest);
                    context.assertTrue(ana.currentScreenHandler != ana.playerScreenHandler, "Ana opens the chest now hers");
                    ana.closeHandledScreen();
                    var loot = (ChestBlockEntity) context.getBlockEntity(chest);
                    context.assertTrue(loot.getStack(0).isOf(Items.DIAMOND) && loot.getStack(0).getCount() == 5, "Its five diamonds as they were, nothing moved");
                    open(context, p[1], chest);
                    context.assertTrue(p[1].currentScreenHandler == p[1].playerScreenHandler, "Ben no longer opens it");
                    // Blue's 30-minute shield: another border chunk refused.
                    var home = ana.getPos();
                    ana.refreshPositionAndAngles((t[0] + 2) * 16 + 8, home.y, t[1] * 16 + 8, 0, 0);
                    var shielded = f.runtime.faction(f.source, ana.getUuid(), "capture", List.of());
                    context.assertTrue(!shielded.accepted() && shielded.lines().getFirst().startsWith("Blue lost land lately"), "Shielded: " + shielded.lines());
                    ana.refreshPositionAndAngles(home.x, home.y, home.z, 0, 0);
                    // Red's power falls; an hour on, Blue (recovered) takes T back.
                    f.runtime.record(PowerRules.set(ana.getUuid(), 0, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                    f.runtime.record(PowerRules.set(p[1].getUuid(), 10, null, FactionSettings.DEFAULTS, server.getTicks(), System.currentTimeMillis()));
                    f.runtime.factions().clockForTest(61 * 60_000L);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Powers written");
                    // Ben standing in T, where the chest is.
                    var at = context.getAbsolutePos(chest.up());
                    p[1].refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                    var back = f.runtime.faction(f.source, p[1].getUuid(), "capture", List.of());
                    context.assertTrue(back.accepted(), "Blue recaptures T: " + back.lines());
                })
                .createAndAdd(() -> {
                    String blue = f.runtime.projected().factions().named("Blue").orElseThrow().id().toString();
                    context.assertTrue(blue.equals(f.runtime.projected().claims().get(ClaimLedger.chunk(W, t[0], t[1]))), "T is Blue's again");
                    var loot = (ChestBlockEntity) context.getBlockEntity(chest);
                    context.assertTrue(loot.getStack(0).getCount() == 5, "Still the five diamonds");
                })
                .completeIfSuccessful();
    }

    private static void open(TestContext context, ServerPlayerEntity player, BlockPos chest) {
        var at = context.getAbsolutePos(chest);
        player.interactionManager.interactBlock(player, context.getWorld(), ItemStack.EMPTY, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(at), Direction.UP, at, false));
    }
}
