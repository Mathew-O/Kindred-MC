package dev.kindred.fabric;

import dev.kindred.core.customs.ServerCustoms;
import dev.kindred.core.survival.SurvivalGoal;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In plain terms: a person who opts in teaches the AI players the depth they mine their iron at, and the players lean
 * on it a little; someone who has not opted in teaches them nothing, however much they mine; what anyone says or
 * carries teaches nothing; and opting out takes back everything learned from that person, from the server's file too.
 *
 * Technical: E-47 under D10. Two mock people: the first runs {@code /aiplayer watch on} as herself and mines twenty
 * iron ore at y 12; the second, who never opts in, mines twenty at y -40. The breaks go through the server's own break
 * event, which the mod's customs keeper hears. An AI player on the Nether goal digs toward y 16 for iron of its own;
 * with her custom (y 14, the middle of the four blocks from 12) it digs toward 15, three tenths of the way and no
 * further. Her diamonds and what she says change nothing. {@code /aiplayer watch off} as her: her counts are gone,
 * the AI player is back at 16, and the customs file no longer holds her. The second person's ore at y -40, if counted,
 * would have moved the depth to 8.
 */
public final class NativeCustomsGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, batchId = "customs", tickLimit = 3600)
    public void aConsentingMinersDepthMovesTheOrePlanWithinTheBlendAndOptingOutTakesItBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        BlockPos o = context.getAbsolutePos(new BlockPos(10, 0, 10));
        ServerPlayerEntity[] people = new ServerPlayerEntity[2];
        Path[] file = new Path[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> context.assertTrue(KindredMod.customs() != null, "The server's customs kept"))
                .createAndAdd(() -> {
                    f.runtime.customs(KindredMod.customs().customs());
                    f.runtime.createNative(f.source, "Prospector");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var inventory = f.player().getInventory();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(1, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(2, new ItemStack(Items.BREAD, 8));
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.oreDepthForFixtures(f.id, ServerCustoms.Ore.IRON) == 16,
                        "Its own depth for iron first: " + f.runtime.oreDepthForFixtures(f.id, ServerCustoms.Ore.IRON)))
                .createAndAdd(() -> {
                    var server = context.getWorld().getServer();
                    people[0] = MockPlayers.creative(context);
                    people[1] = MockPlayers.creative(context);
                    server.getCommandManager().executeWithPrefix(people[0].getCommandSource(), "aiplayer watch on");
                    // Run as the second person by the server, as an operator might: refused, for it is not that person's own say.
                    server.getCommandManager().executeWithPrefix(server.getCommandSource().withEntity(people[1]), "aiplayer watch on");
                    context.assertTrue(KindredMod.customs().consents(people[0]) && !KindredMod.customs().consents(people[1]),
                            "The first opted in, for herself alone; the second not opted in by anyone else");
                    // What a person says, and what they carry, is no part of it.
                    people[0].getInventory().insertStack(new ItemStack(Items.DIAMOND, 64));
                    f.runtime.chat(people[0].getUuid(), "I mine my diamonds down at y -59, by the lava");
                    var world = context.getWorld();
                    FixtureGround.keep(context, new BlockPos(o.getX() + 10, -40, o.getZ() + 10), new BlockPos(o.getX() + 29, 12, o.getZ() + 12));
                    for (int i = 0; i < 20; i++) {
                        BlockPos hers = new BlockPos(o.getX() + 10 + i, 12, o.getZ() + 10), his = new BlockPos(o.getX() + 10 + i, -40, o.getZ() + 12);
                        world.setBlockState(hers, Blocks.IRON_ORE.getDefaultState());
                        context.assertTrue(people[0].interactionManager.tryBreakBlock(hers), "Her ore broken");
                        world.setBlockState(his, Blocks.IRON_ORE.getDefaultState());
                        context.assertTrue(people[1].interactionManager.tryBreakBlock(his), "His ore broken");
                    }
                })
                .createAndAdd(() -> {
                    var customs = KindredMod.customs().customs();
                    context.assertTrue(customs.minedCount(ServerCustoms.Ore.IRON) == 20,
                            "Her twenty learned, his none: " + customs.minedCount(ServerCustoms.Ore.IRON));
                    context.assertTrue(customs.miningHeight(ServerCustoms.Ore.IRON).orElse(Integer.MIN_VALUE) == 14,
                            "Her custom: " + customs.miningHeight(ServerCustoms.Ore.IRON));
                    context.assertTrue(customs.miningHeight(ServerCustoms.Ore.DIAMOND).isEmpty() && customs.minedCount(ServerCustoms.Ore.DIAMOND) == 0,
                            "Nothing from her diamonds or her words");
                    int depth = f.runtime.oreDepthForFixtures(f.id, ServerCustoms.Ore.IRON);
                    context.assertTrue(depth == 15, "Its depth for iron within the blend, three tenths of the way from 16 to 14: " + depth);
                    file[0] = context.getWorld().getServer().getSavePath(WorldSavePath.ROOT).resolve("kindred").resolve("customs.json");
                })
                // Written off the server thread at the next tick, and read back as it was: her consent and her counts.
                .createAndAdd(() -> {
                    String saved = read(file[0]);
                    context.assertTrue(saved.contains(people[0].getUuidAsString()) && saved.contains("IRON"), "Written with her counts");
                    try (var again = ServerCustomsKeeper.open(file[0].getParent())) {
                        context.assertTrue(again.consents(people[0]) && again.customs().minedCount(ServerCustoms.Ore.IRON) == 20
                                && again.customs().miningHeight(ServerCustoms.Ore.IRON).orElse(Integer.MIN_VALUE) == 14, "Read back the same");
                    }
                })
                .createAndAdd(() -> {
                    var server = context.getWorld().getServer();
                    server.getCommandManager().executeWithPrefix(people[0].getCommandSource(), "aiplayer watch off");
                    context.assertTrue(!KindredMod.customs().consents(people[0]), "She opted out");
                    context.assertTrue(KindredMod.customs().customs().minedCount(ServerCustoms.Ore.IRON) == 0, "Everything learned from her deleted");
                    context.assertTrue(f.runtime.oreDepthForFixtures(f.id, ServerCustoms.Ore.IRON) == 16, "Back to its own depth");
                })
                // Looked at until the opt-out's write is there.
                .createAndAdd(() -> {
                    String saved = read(file[0]);
                    context.assertTrue(saved.contains("consenting") && !saved.contains(people[0].getUuidAsString())
                            && !saved.contains(people[1].getUuidAsString()), "The file written without her, and never with him: " + saved);
                })
                .createAndAdd(() -> {
                    for (var person : people) context.getWorld().getServer().getPlayerManager().remove(person);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    private static String read(Path file) {
        try {
            return file != null && Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (java.io.IOException unreadable) {
            return "";
        }
    }
}
