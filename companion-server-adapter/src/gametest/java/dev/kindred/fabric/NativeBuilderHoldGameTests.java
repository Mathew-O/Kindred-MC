package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.BuildBattle;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

/**
 * In plain terms: a build battle's free blocks never leave Builder, and a builder never loses its own things to it
 * (M-06k3). Each builder's own things, ender chest and game mode are set aside when the battle starts; however it ends
 * (the match over, the server stopped, the builder away at the end, or a crash) the builder gets exactly those back,
 * and whatever creative made or the match gave is gone: from its inventory, its ender chest, the chests on the plots
 * and the ground of the arena. The same for an AI builder and a person.
 */
public final class NativeBuilderHoldGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "builderHoldEnd", tickLimit = 1600)
    public void atTheMatchsEndEachBuilderGetsExactlyItsOwnThingsBackAndTheArenaKeepsNothingCreativeMade(TestContext context) {
        var b = new Battle(context);
        boolean[] once = {false};
        b.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    if (once[0]) return;
                    once[0] = true;
                    // Creative's things: in Mira's inventory and ender chest, a chest on her plot, an item on the ground.
                    var mira = b.mira[0];
                    mira.getInventory().setStack(20, new ItemStack(Items.DIAMOND_BLOCK, 64));
                    mira.getEnderChestInventory().setStack(5, new ItemStack(Items.NETHERITE_INGOT, 3));
                    var world = context.getWorld();
                    BlockPos chestAt = b.plotSpot(mira.getUuid(), 4, 1, 4);
                    world.setBlockState(chestAt, Blocks.CHEST.getDefaultState());
                    ((ChestBlockEntity) world.getBlockEntity(chestAt)).setStack(0, new ItemStack(Items.DIAMOND, 10));
                    b.chest[0] = chestAt;
                    BlockPos dropAt = b.plotSpot(mira.getUuid(), 7, 1, 7);
                    b.dropped[0] = new ItemEntity(world, dropAt.getX() + 0.5, dropAt.getY() + 0.2, dropAt.getZ() + 0.5, new ItemStack(Items.GOLD_BLOCK, 16));
                    b.dropped[0].setPickupDelayInfinite();
                    world.spawnEntity(b.dropped[0]);
                    b.stopped[0] = b.f.runtime.stopMatch(b.operator(), b.match[0]);
                })
                .createAndAdd(() -> context.assertTrue(b.stopped[0] == 1, "The match stopped"))
                .createAndAdd(() -> context.assertTrue(b.over(), "The match over"))
                .createAndAdd(() -> {
                    b.ownThingsBack(b.wren(), true);
                    b.ownThingsBack(b.mira[0], false);
                    var chest = (Inventory) context.getWorld().getBlockEntity(b.chest[0]);
                    context.assertTrue(chest == null || chest.isEmpty(), "The chest on the plot emptied");
                    context.assertTrue(b.dropped[0].isRemoved(), "The item on the ground cleared");
                })
                .createAndAdd(() -> context.assertTrue(b.holdsGone(), "Both holds forgotten"))
                .createAndAdd(b::end)
                .createAndAdd(() -> context.assertTrue(b.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "builderHoldStop", tickLimit = 1600)
    public void whenTheServerStopsMidBattleEachBuilderGetsItsOwnThingsBack(TestContext context) {
        var b = new Battle(context);
        b.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    b.mira[0].getInventory().setStack(20, new ItemStack(Items.DIAMOND_BLOCK, 64));
                    b.f.runtime.stop();
                })
                .createAndAdd(() -> { })
                .createAndAdd(() -> context.assertTrue(b.f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> {
                    b.ownThingsBack(b.mira[0], false);
                    context.assertTrue(b.holdsGone(), "Both holds forgotten");
                    context.getWorld().getServer().getPlayerManager().remove(b.mira[0]);
                })
                // Wren's body went with the stop: started again and connected, it holds its own things, saved at the stop.
                .createAndAdd(() -> { b.f.runtime = new PlayerRuntime(context.getWorld().getServer(), b.f.directory, NativePlayerSaves::read, writer -> writer); b.f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(b.f.runtime.isReady(), "Started again"))
                .createAndAdd(() -> context.assertTrue(b.f.runtime.nativeLifecycle(b.f.source, "connect", b.wrenId[0]) == 1, "Wren back"))
                .createAndAdd(() -> b.ownThingsBack(b.wren(), true))
                .createAndAdd(() -> {
                    context.assertTrue(b.f.runtime.nativeLifecycle(b.f.source, "stop", b.wrenId[0]) == 1, "Wren stopped");
                    b.f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(b.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "builderHoldLeave", tickLimit = 2000)
    public void aBuilderThatLeavesMidBattleGetsItsOwnThingsBackAsItGoesAndStillHasThemWhenItComesBack(TestContext context) {
        var b = new Battle(context);
        b.start(context.createTimedTaskRunner())
                .createAndAdd(() -> context.assertTrue(b.f.runtime.nativeLifecycle(b.f.source, "disconnect", b.wrenId[0]) == 1, "Wren away"))
                .createAndAdd(() -> context.assertTrue(b.wren() == null, "Wren gone from the world"))
                .createAndAdd(() -> context.assertTrue(b.f.runtime.stopMatch(b.operator(), b.match[0]) == 1, "The match stopped while Wren was away"))
                .createAndAdd(() -> context.assertTrue(b.over(), "The match over"))
                .createAndAdd(() -> {
                    b.ownThingsBack(b.mira[0], false);
                    // Wren was given its own things back as it left the battle, before its body went: nothing kept for later.
                    context.assertTrue(!Files.exists(b.holdFile(b.wren[0])), "Wren's hold forgotten as it left");
                })
                .createAndAdd(() -> context.assertTrue(b.f.runtime.nativeLifecycle(b.f.source, "connect", b.wrenId[0]) == 1, "Wren back"))
                .createAndAdd(() -> {
                    context.assertTrue(b.wren() != null, "Wren in the world");
                    b.ownThingsBack(b.wren(), true);
                })
                .createAndAdd(() -> context.assertTrue(b.holdsGone(), "Both holds forgotten"))
                .createAndAdd(b::end)
                .createAndAdd(() -> context.assertTrue(b.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "builderHoldCrash", tickLimit = 1600)
    public void afterACrashMidBattleABuilderGetsItsOwnThingsBackWhenItComesBack(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
        var server = context.getWorld().getServer();
        @SuppressWarnings("unchecked") java.util.concurrent.CompletableFuture<Boolean>[] written = new java.util.concurrent.CompletableFuture[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var wren = f.player();
                    ownThings(wren, true);
                    // What a crash mid-battle leaves: the hold on disk, and the body saved in creative with the match's
                    // stock in place of its own things.
                    var hold = BuilderHolds.capture(wren);
                    written[0] = new BuilderHolds(f.directory).write(wren.getUuid(), hold);
                    wren.getInventory().clear();
                    wren.getEnderChestInventory().clear();
                    wren.getInventory().setStack(0, new ItemStack(Items.DIRT, 64));
                    wren.getInventory().setStack(1, new ItemStack(Items.OAK_PLANKS, 64));
                    wren.changeGameMode(GameMode.CREATIVE);
                })
                .createAndAdd(() -> context.assertTrue(written[0].isDone() && written[0].join(), "The hold on disk"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Wren stopped");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped: saved in creative with the stock"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Started again"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "connect", f.id) == 1, "Wren back"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> checkOwnThings(context, f.player(), true))
                .createAndAdd(() -> context.assertTrue(!Files.exists(new BuilderHolds(f.directory).file(f.player().getUuid())), "The hold forgotten"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", f.id) == 1, "Wren stopped");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A builder's own things: an AI builder's iron and pickaxe and an emerald in its ender chest; a person's diamonds and gold. */
    private static void ownThings(ServerPlayerEntity player, boolean ai) {
        var inventory = player.getInventory();
        inventory.clear();
        player.getEnderChestInventory().clear();
        if (ai) {
            inventory.setStack(3, new ItemStack(Items.IRON_INGOT, 5));
            inventory.setStack(4, new ItemStack(Items.STONE_PICKAXE));
            player.getEnderChestInventory().setStack(0, new ItemStack(Items.EMERALD));
        } else {
            inventory.setStack(2, new ItemStack(Items.DIAMOND, 3));
            player.getEnderChestInventory().setStack(0, new ItemStack(Items.GOLD_INGOT));
        }
    }

    private static void checkOwnThings(TestContext context, ServerPlayerEntity player, boolean ai) {
        context.assertTrue(player != null, "The builder in the world");
        String who = player.getName().getString();
        var inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) total += inventory.getStack(i).getCount();
        int ender = 0;
        for (int i = 0; i < player.getEnderChestInventory().size(); i++) ender += player.getEnderChestInventory().getStack(i).getCount();
        if (ai) {
            context.assertTrue(count(inventory, Items.IRON_INGOT) == 5 && count(inventory, Items.STONE_PICKAXE) == 1 && total == 6,
                    who + " holds exactly its own iron and pickaxe: " + total + " items");
            context.assertTrue(count(player.getEnderChestInventory(), Items.EMERALD) == 1 && ender == 1, who + "'s ender chest as it was");
        } else {
            context.assertTrue(count(inventory, Items.DIAMOND) == 3 && total == 3, who + " holds exactly its own diamonds: " + total + " items");
            context.assertTrue(count(player.getEnderChestInventory(), Items.GOLD_INGOT) == 1 && ender == 1, who + "'s ender chest as it was");
        }
        context.assertTrue(player.interactionManager.getGameMode() == GameMode.SURVIVAL, who + " back in survival");
    }

    private static int count(Inventory inventory, Item item) {
        int n = 0;
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) n += inventory.getStack(i).getCount();
        return n;
    }

    /** A build battle: Wren, an AI builder, and Mira, a person, each with its own things, building in creative. */
    private static final class Battle {
        final NativePaidSkillsGameTests.Fixture f;
        final TestContext context;
        final UUID[] wrenId = new UUID[1], wren = new UUID[1], match = new UUID[1];
        final ServerPlayerEntity[] mira = new ServerPlayerEntity[1];
        final BlockPos[] chest = new BlockPos[1];
        final ItemEntity[] dropped = new ItemEntity[1];
        final int[] proposed = new int[1], joined = new int[1], started = new int[1], stopped = new int[1];
        final BlockPos o;

        Battle(TestContext context) {
            this.context = context;
            this.f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1500);
            this.o = context.getAbsolutePos(BlockPos.ORIGIN);
        }

        TimedTaskRunner start(TimedTaskRunner runner) {
            return runner
                    .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                    .createAndAdd(() -> {
                        var world = context.getWorld();
                        for (int x = -10; x <= 40; x++) for (int z = -4; z <= 26; z++) {
                            world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                            for (int y = 2; y <= 20; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                        }
                        f.runtime.createNative(f.source, "Wren");
                    })
                    .createAndAdd(() -> {
                        var statuses = f.runtime.nativeStatuses(Actor.console());
                        context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED && statuses.getFirst().connected(), "Wren established");
                        wrenId[0] = statuses.getFirst().id();
                        wren[0] = statuses.getFirst().nativeId();
                    })
                    .createAndAdd(() -> {
                        // Once: a person joins, and both arrive with nothing, as a build battle asks.
                        if (mira[0] == null) {
                            wren().getInventory().clear();
                            mira[0] = MockPlayers.survival(context, "Mira", new BlockPos(1, 2, 1));
                            mira[0].getInventory().clear();
                            proposed[0] = f.runtime.proposeBuildBattle(operator(), java.util.List.of(mira[0].getUuid(), wren[0]), 20 * 60, BuildBattle.Theme.COTTAGE);
                        }
                        context.assertTrue(proposed[0] == 1, "Proposed");
                    })
                    .createAndAdd(() -> {
                        if (match[0] == null) {
                            match[0] = f.runtime.matches().getFirst().id();
                            joined[0] = f.runtime.joinMatch(mira[0].getCommandSource(), match[0]);
                            // Between the proposal and the start each comes by things of its own, its ender chest's too.
                            ownThings(wren(), true);
                            ownThings(mira[0], false);
                            started[0] = f.runtime.startMatch(mira[0].getCommandSource(), match[0]);
                        }
                        context.assertTrue(joined[0] == 1 && started[0] == 1, "Mira joined and started it");
                    })
                    .createAndAdd(() -> {
                        context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Building");
                        context.assertTrue(Files.exists(holdFile(wren[0])) && Files.exists(holdFile(mira[0].getUuid())), "Both builders' own things set aside");
                        context.assertTrue(wren().interactionManager.getGameMode() == GameMode.CREATIVE && mira[0].interactionManager.getGameMode() == GameMode.CREATIVE,
                                "Both in creative");
                        context.assertTrue(count(wren().getInventory(), Items.IRON_INGOT) == 0 && count(mira[0].getInventory(), Items.DIAMOND) == 0,
                                "Their own things set aside, not held");
                    });
        }

        net.minecraft.server.command.ServerCommandSource operator() { return f.source.withPosition(net.minecraft.util.math.Vec3d.of(o.add(0, 2, 0))); }

        ServerPlayerEntity wren() { return context.getWorld().getServer().getPlayerManager().getPlayer(wren[0]); }

        java.nio.file.Path holdFile(UUID player) { return new BuilderHolds(f.directory).file(player); }

        boolean over() { return f.runtime.matches().stream().noneMatch(m -> m.id().equals(match[0]) && m.state() != MatchRegistry.State.ENDED); }

        boolean holdsGone() { return !Files.exists(holdFile(wren[0])) && !Files.exists(holdFile(mira[0].getUuid())); }

        /** A spot on {@code builder}'s plot, {@code dy} over its floor. */
        BlockPos plotSpot(UUID builder, int dx, int dy, int dz) {
            // Mira's plot is the first, its floor at y 1 from x 2 and z 2; Wren's the next, eighteen east.
            int index = builder.equals(mira[0].getUuid()) ? 0 : 1;
            return o.add(2 + index * (BuildBattle.PLOT + BuildBattle.GAP) + dx, 1 + dy, 2 + dz);
        }

        void ownThingsBack(ServerPlayerEntity player, boolean ai) { checkOwnThings(context, player, ai); }

        void end() {
            context.getWorld().getServer().getPlayerManager().remove(mira[0]);
            f.runtime.stop();
        }
    }
}
