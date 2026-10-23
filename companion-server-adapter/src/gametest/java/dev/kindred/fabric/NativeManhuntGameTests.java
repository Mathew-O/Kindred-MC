package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * A manhunt (M-02a, D6, D7): a runner and a hunter, both AI players. The hunter is handed the announced tracking
 * compass, follows its needle round a wall it cannot see through, finds the runner and fights it; the runner's fall ends
 * the hunt for the hunters, and the compass is taken back.
 */
public final class NativeManhuntGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "manhunt", tickLimit = 3000)
    public void aHunterFollowsItsCompassToTheRunnerAndTheHuntersWinAtItsFall(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2800);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        String[] pace = new String[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // Open ground, and a wall between them with a way round its east end.
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -4; x <= 12; x++) for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, 8), Blocks.STONE.getDefaultState());
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    BlockPos a = context.getAbsolutePos(new BlockPos(4, 2, 2)), b = context.getAbsolutePos(new BlockPos(4, 2, 14));
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    rue.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    context.assertTrue(!hal.canSee(rue), "The wall hides the runner");
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                    pace[0] = bot(f, ids[0]).playstyle();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().joined().size() == 2, "Both entered by their administrator");
                    // Past the fresh start: the hunter's kit staged here, as earning it would take the whole run's iron.
                    var hal = player(f, players[0]);
                    hal.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    hal.getInventory().selectedSlot = 0;
                    hal.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    // The runner holds still for this fixture, so the hunt is the hunter's alone.
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Rue held");
                })
                .createAndAdd(() -> {
                    // The announced rule's one item: a tracking compass, cursed to vanish, pointing at the runner.
                    var hal = player(f, players[0]);
                    var compass = compass(hal, match[0]);
                    context.assertTrue(compass != null, "Hal holds a tracking compass");
                    var curse = hal.getServerWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.VANISHING_CURSE).orElseThrow();
                    context.assertTrue(compass.getEnchantments().getLevel(curse) == 1, "It vanishes on death");
                    var target = compass.get(DataComponentTypes.LODESTONE_TRACKER).target().orElseThrow();
                    context.assertTrue(target.pos().isWithinDistance(player(f, players[1]).getBlockPos(), 2), "It points at Rue: " + target.pos());
                })
                .createAndAdd(() -> {
                    var hunt = f.runtime.matches().getFirst();
                    context.assertTrue(hunt.state() == MatchRegistry.State.ENDED, "Hunted down: " + hunt.state() + "; Rue " + player(f, players[1]).getHealth()
                            + ", Hal at " + player(f, players[0]).getBlockPos().subtract(context.getAbsolutePos(BlockPos.ORIGIN)).toShortString());
                })
                .createAndAdd(() -> {
                    var hunt = f.runtime.matches().getFirst();
                    KindredMod.LOGGER.info("Manhunt: {} ({}); the hunters won {}", hunt.state(), hunt.result(), hunt.won(players[0]));
                    context.assertTrue("hunted".equals(hunt.result()) && hunt.won(players[0]) && !hunt.won(players[1]), "The hunters won: " + hunt.result());
                    context.assertTrue(compass(player(f, players[0]), match[0]) == null, "The compass taken back");
                    var bot = bot(f, ids[0]);
                    context.assertTrue(bot.goal() == dev.kindred.core.survival.SurvivalGoal.NONE && bot.playstyle().equals(pace[0]),
                            "The hunt's goal over, its own pace back: " + bot.goal() + " " + bot.playstyle());
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * A hunter makes its kit before it hunts (M-02a): with the iron it has (here three ingots, staged past the fresh
     * start as though mined), an iron sword and a shield are crafted at a table of its own, and only then does it go
     * after the runner.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "manhuntKit", tickLimit = 3600)
    public void aHunterMakesItsKitFromItsIronBeforeItHunts(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3400);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        boolean[] huntedUnready = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 22; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = -4; x <= 12; x++) for (int y = 2; y <= 4; y++) world.setBlockState(o.add(x, y, 8), Blocks.STONE.getDefaultState());
                    f.runtime.createNative(f.source, "Smith");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Smith allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Fox"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Smith") ? 0 : 1;
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    var fox = player(f, players[1]);
                    BlockPos a = context.getAbsolutePos(new BlockPos(4, 2, 2)), b = context.getAbsolutePos(new BlockPos(4, 2, 14));
                    smith.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    fox.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    smith.getInventory().clear();
                    fox.getInventory().clear();
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                    match[0] = f.runtime.matches().getFirst().id();
                })
                .createAndAdd(() -> {
                    // Past the fresh start: iron as though mined and smelted, sticks and planks; no sword, no shield.
                    var inventory = player(f, players[0]).getInventory();
                    inventory.setStack(0, new ItemStack(Items.IRON_INGOT, 3));
                    inventory.setStack(1, new ItemStack(Items.STICK, 4));
                    inventory.setStack(2, new ItemStack(Items.OAK_PLANKS, 16));
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Fox held");
                })
                .createAndAdd(() -> {
                    // Kept: whether it ever struck at the runner without its kit.
                    var smith = player(f, players[0]);
                    var fox = player(f, players[1]);
                    if (fox.getHealth() < 20 && !PlayerRuntime.huntReady(smith)) huntedUnready[0] = true;
                    context.assertTrue(PlayerRuntime.huntReady(smith), "The kit made: " + f.runtime.panelBots(Actor.console()).stream()
                            .filter(b -> b.id().equals(ids[0])).findFirst().orElseThrow().goalProgress());
                })
                .createAndAdd(() -> {
                    var smith = player(f, players[0]);
                    context.assertTrue(smith.getInventory().count(Items.IRON_SWORD) == 1 && (smith.getInventory().count(Items.SHIELD) == 1
                            || smith.getOffHandStack().isOf(Items.SHIELD)), "An iron sword and a shield of its own making");
                    context.assertTrue(smith.getInventory().count(Items.IRON_INGOT) == 0, "Three ingots: two for the sword, one for the shield");
                    context.assertTrue(!huntedUnready[0], "No strike before the kit");
                })
                .createAndAdd(() -> {
                    var hunt = f.runtime.matches().getFirst();
                    context.assertTrue(hunt.state() == MatchRegistry.State.ENDED && "hunted".equals(hunt.result()), "Hunted down after: " + hunt.state());
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ItemStack compass(ServerPlayerEntity player, UUID match) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) if (HuntCompass.of(inventory.getStack(slot), match)) return inventory.getStack(slot);
        return null;
    }

    private static PlayerRuntime.PanelBot bot(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
