package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.SurvivalGoal;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * One portal for the team (E-40a): two players set the Nether-portal goal together. Mason, with a diamond pickaxe and
 * the frame's obsidian, takes the portal and says so; Wren, with iron, says it will get iron, food and wood, and with
 * its iron in and stocked for the run it waits for the team's portal rather than building one of its own. Mason builds
 * and lights it; Wren takes it as its own, and says the team's portal is lit, claiming none of it.
 */
public final class NativeTeamPortalGameTests implements FabricGameTest {
    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID nativeId) {
        return f.source.getServer().getPlayerManager().getPlayer(nativeId);
    }

    private static String progress(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(id)).findFirst().map(PlayerRuntime.PanelBot::goalProgress).orElse("");
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "teamPortal", tickLimit = 1600)
    public void theBestPickaxeBuildsTheTeamsOnePortalWhileTheOtherStocksTheRunAndWaitsForIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        UUID[] ids = new UUID[2], players = new UUID[2]; // Mason, Wren
        boolean[] waited = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) context.setBlockState(x, 7, z, Blocks.STONE);
                    f.runtime.createNative(f.source, "Mason");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Mason allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Mason") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var mason = player(f, players[0]);
                    var wren = player(f, players[1]);
                    context.assertTrue(mason.isOnGround() && wren.isOnGround(), "Both standing");
                    var m = mason.getInventory();
                    m.clear();
                    m.setStack(0, new ItemStack(Items.DIAMOND_PICKAXE));
                    m.setStack(1, new ItemStack(Items.OBSIDIAN, 10));
                    m.setStack(2, new ItemStack(Items.COBBLESTONE, 4));
                    m.setStack(3, new ItemStack(Items.FLINT_AND_STEEL));
                    m.setStack(4, new ItemStack(Items.BREAD, 5));
                    m.setStack(5, new ItemStack(Items.WATER_BUCKET));
                    m.setStack(6, new ItemStack(Items.IRON_SWORD));
                    // Wren: its iron in (an iron pickaxe, chestplate, two buckets) and stocked for the run, so nothing of
                    // its own route is left but the portal.
                    var w = wren.getInventory();
                    w.clear();
                    w.setStack(0, new ItemStack(Items.IRON_PICKAXE));
                    w.setStack(1, new ItemStack(Items.IRON_SWORD));
                    w.setStack(2, new ItemStack(Items.BUCKET, 2));
                    w.setStack(3, new ItemStack(Items.BREAD, 12));
                    w.setStack(4, new ItemStack(Items.OAK_LOG, 8));
                    w.setStack(40, new ItemStack(Items.SHIELD));
                    wren.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    BlockPos a = context.getAbsolutePos(new BlockPos(2, 2, 2)), b = context.getAbsolutePos(new BlockPos(6, 2, 6));
                    mason.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    wren.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    context.assertTrue(f.runtime.startGoal(f.source, ids[0], SurvivalGoal.NETHER_PORTAL) == 1
                            && f.runtime.startGoal(f.source, ids[1], SurvivalGoal.NETHER_PORTAL) == 1, "Both on the goal");
                })
                .createAndAdd(() -> {
                    // Mason says it has the best pickaxe, or, its site already chosen before Wren was on the goal, that it is building it.
                    var masons = f.runtime.partsTaken(ids[0]);
                    context.assertTrue(masons.size() == 1 && (masons.getFirst().equals("PORTAL: I've got the best pickaxe; I'll dig the obsidian for the portal.")
                                    || masons.getFirst().equals("PORTAL: I'm building the portal."))
                            && f.runtime.partsTaken(ids[1]).equals(java.util.List.of("PROVISIONS: I'll get iron, food and wood.")),
                            "Each took up its part, once: " + masons + " / " + f.runtime.partsTaken(ids[1]));
                })
                .createAndAdd(() -> {
                    if (progress(f, ids[1]).contains("Wait for the team's portal")) waited[0] = true;
                    long portal = BlockPos.streamOutwards(player(f, players[0]).getBlockPos(), 6, 6, 6)
                            .filter(pos -> context.getWorld().getBlockState(pos).isOf(Blocks.NETHER_PORTAL)).count();
                    context.assertTrue(portal == 6, "Mason's portal lit: " + portal + " / " + progress(f, ids[0]) + " / " + progress(f, ids[1]));
                })
                .createAndAdd(() -> {
                    context.assertTrue(waited[0], "Wren waited for the team's portal");
                    context.assertTrue("The team's Nether portal is lit.".equals(f.runtime.lastSaid(ids[1])),
                            "Wren's goal done by the team's portal, and it claims none of it: " + f.runtime.lastSaid(ids[1]) + " / " + progress(f, ids[1]));
                    context.assertTrue("I built and lit a Nether portal!".equals(f.runtime.lastSaid(ids[0])), "Mason's: " + f.runtime.lastSaid(ids[0]));
                })
                .createAndAdd(() -> {
                    // Wren built nothing of a portal of its own: nothing but its own kit placed or spent.
                    var w = player(f, players[1]).getInventory();
                    context.assertTrue(ItemMoves.count(w, Items.IRON_PICKAXE) == 1 && ItemMoves.count(w, Items.BUCKET) == 2,
                            "Wren's kit untouched by a portal of its own");
                    context.assertTrue(ItemMoves.count(player(f, players[0]).getInventory(), Items.OBSIDIAN) == 0, "Mason's ten obsidian in the frame");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
