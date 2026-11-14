package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;

/**
 * In plain terms: a hunt's tracking compass is issued once to each hunter (M-06k2, D6). Put away in a chest, by the
 * hunter or anyone, no second one appears: the hunter has to fetch it, and an AI hunter never puts its own away. Burnt
 * in lava, it is replaced. Lost to its holder's death by the curse of vanishing, it is replaced once it is up again;
 * with keepInventory on, the same one is kept and no second appears. In each, the hunt's compasses are counted: never
 * more than one a hunter.
 */
public final class NativeHuntCompassGameTests implements FabricGameTest {
    private static final BlockPos CHEST = new BlockPos(6, 2, 2);
    private static final BlockPos LAVA = new BlockPos(10, 1, 10);

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "huntCompassStored", tickLimit = 2400)
    public void aCompassPutInAChestIsNotReplacedAndOnceTheHuntIsOverIsTakenBackFromWhoeverTakesItOut(TestContext context) {
        var h = new Hunt(context);
        int[] waited = {0};
        h.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    context.assertTrue(h.held(h.hal()) == 1, "Hal issued one compass");
                    // A person (or anyone) puts it in the chest instead: set down, not gone.
                    var hal = h.hal();
                    for (int i = 0; i < hal.getInventory().size(); i++) {
                        if (HuntCompass.of(hal.getInventory().getStack(i), h.match[0])) {
                            chest(context).setStack(1, hal.getInventory().removeStack(i));
                            break;
                        }
                    }
                    context.assertTrue(h.held(hal) == 0 && h.inChest(context) == 1, "The compass in the chest");
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 100, "Five seconds of the hunt"))
                .createAndAdd(() -> {
                    context.assertTrue(h.held(h.hal()) == 0, "No second compass handed while its own lies in the chest");
                    context.assertTrue(h.inChest(context) == 1, "One compass of the hunt in all");
                    // The hunt over: the one left in the chest outlives it, until a player takes it out.
                    context.assertTrue(h.f.runtime.stopMatch(h.f.source, h.match[0]) == 1, "The hunt stopped");
                })
                .createAndAdd(() -> context.assertTrue(h.f.runtime.matches().stream().noneMatch(m -> m.id().equals(h.match[0])
                        && m.state() != MatchRegistry.State.ENDED), "The hunt over"))
                .createAndAdd(() -> {
                    var chest = chest(context);
                    for (int i = 0; i < chest.size(); i++) {
                        if (HuntCompass.of(chest.getStack(i), h.match[0])) h.hal().getInventory().insertStack(chest.removeStack(i));
                    }
                    context.assertTrue(h.inChest(context) == 0, "Taken out of the chest");
                })
                .createAndAdd(() -> context.assertTrue(h.held(h.hal()) == 0, "Taken back once a player holds it, the hunt over"))
                .createAndAdd(h::end)
                .createAndAdd(() -> context.assertTrue(h.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "huntCompassLava", tickLimit = 2400)
    public void aCompassBurntInLavaIsReplacedOnce(TestContext context) {
        var h = new Hunt(context);
        UUID[] first = new UUID[1];
        ItemEntity[] dropped = new ItemEntity[1];
        h.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    var hal = h.hal();
                    context.assertTrue(h.held(hal) == 1, "Hal issued one compass");
                    var world = context.getWorld();
                    world.setBlockState(context.getAbsolutePos(LAVA), Blocks.LAVA.getDefaultState());
                    for (int i = 0; i < hal.getInventory().size(); i++) {
                        var stack = hal.getInventory().getStack(i);
                        if (!HuntCompass.of(stack, h.match[0])) continue;
                        first[0] = HuntCompass.issue(stack).orElseThrow();
                        var at = context.getAbsolutePos(LAVA);
                        dropped[0] = new ItemEntity(world, at.getX() + 0.5, at.getY() + 0.6, at.getZ() + 0.5, hal.getInventory().removeStack(i));
                        world.spawnEntity(dropped[0]);
                        break;
                    }
                    context.assertTrue(h.held(hal) == 0, "Thrown into the lava");
                })
                .createAndAdd(() -> context.assertTrue(dropped[0].isRemoved(), "Burnt"))
                .createAndAdd(() -> {
                    var hal = h.hal();
                    context.assertTrue(h.held(hal) == 1, "One new compass handed");
                    var now = h.issueHeld(hal);
                    context.assertTrue(now != null && !now.equals(first[0]), "A new issue, not the burnt one: " + now);
                })
                .createAndAdd(() -> {
                    context.getWorld().setBlockState(context.getAbsolutePos(LAVA), Blocks.STONE.getDefaultState());
                    context.assertTrue(h.held(h.hal()) == 1, "Still just one");
                })
                .createAndAdd(h::end)
                .createAndAdd(() -> context.assertTrue(h.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "huntCompassDeath", tickLimit = 3000)
    public void aCompassLostToADeathIsReplacedAndOneKeptThroughADeathIsNot(TestContext context) {
        var h = new Hunt(context);
        var rules = context.getWorld().getGameRules();
        boolean keep = rules.getBoolean(GameRules.KEEP_INVENTORY);
        UUID[] first = new UUID[1];
        h.start(context.createTimedTaskRunner())
                .createAndAdd(() -> {
                    var hal = h.hal();
                    context.assertTrue(h.held(hal) == 1, "Hal issued one compass");
                    first[0] = h.issueHeld(hal);
                    // keepInventory off: the curse of vanishing takes it at the death.
                    rules.get(GameRules.KEEP_INVENTORY).set(false, context.getWorld().getServer());
                    hal.kill();
                })
                .createAndAdd(() -> {
                    var hal = h.hal();
                    context.assertTrue(hal != null && hal.isAlive(), "Hal up again");
                    context.assertTrue(h.held(hal) == 1, "One new compass once it is up");
                    var now = h.issueHeld(hal);
                    context.assertTrue(now != null && !now.equals(first[0]), "A new issue: " + now);
                    first[0] = now;
                    // keepInventory on: it keeps what it held, compass and all.
                    rules.get(GameRules.KEEP_INVENTORY).set(true, context.getWorld().getServer());
                    hal.kill();
                })
                .createAndAdd(() -> context.assertTrue(h.hal() != null && h.hal().isAlive(), "Hal up again"))
                .createAndAdd(() -> {
                    var hal = h.hal();
                    context.assertTrue(h.held(hal) == 1 && first[0].equals(h.issueHeld(hal)), "The same compass kept, no second: " + h.issueHeld(hal));
                    rules.get(GameRules.KEEP_INVENTORY).set(keep, context.getWorld().getServer());
                })
                .createAndAdd(h::end)
                .createAndAdd(() -> context.assertTrue(h.f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "huntCompassKept", tickLimit = 2400)
    public void anAIPlayerToldToPutItsCompassAwayKeepsItAndPutsTheRestAway(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        BlockPos chest = context.getAbsolutePos(CHEST);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hal"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -2; x <= 10; x++) for (int z = -2; z <= 10; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    world.setBlockState(chest, Blocks.CHEST.getDefaultState());
                    var hal = f.player();
                    BlockPos a = context.getAbsolutePos(new BlockPos(5, 2, 3));
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    hal.getInventory().clear();
                    hal.getInventory().setStack(0, new ItemStack(Items.BREAD, 8));
                    hal.getInventory().setStack(4, HuntCompass.make(hal, UUID.randomUUID(), UUID.randomUUID()));
                    hal.getInventory().setStack(5, new ItemStack(Items.SADDLE));
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.runtime.nativeStorage(f.source, f.id, chest) == 1, "Its own chest assigned");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeLifecycle(f.source, "resume", f.id) == 1, "Hal playing"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeTaskForFixtures(f.source, f.id, "native_store", Map.of(
                        "x", String.valueOf(chest.getX()), "y", String.valueOf(chest.getY()), "z", String.valueOf(chest.getZ()),
                        "items", "minecraft:saddle,minecraft:compass")) == 1, "Told to put its saddle and its compass away"))
                .createAndAdd(() -> context.assertTrue(count(chest(context), Items.SADDLE) == 1, "The saddle put away / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    int held = 0;
                    for (int i = 0; i < f.player().getInventory().size(); i++) if (HuntCompass.handed(f.player().getInventory().getStack(i))) held++;
                    context.assertTrue(held == 1 && count(chest(context), Items.COMPASS) == 0, "Its compass kept on it, none in the chest");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** A running manhunt: Hal hunts, Rue runs and is held still; the ground flat, a chest by Hal. */
    private static final class Hunt {
        final NativePaidSkillsGameTests.Fixture f;
        final TestContext context;
        final UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        final boolean pvp;

        Hunt(TestContext context) {
            this.context = context;
            this.f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
            this.pvp = context.getWorld().getServer().isPvpEnabled();
        }

        net.minecraft.test.TimedTaskRunner start(net.minecraft.test.TimedTaskRunner runner) {
            var server = context.getWorld().getServer();
            return runner
                    .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                    .createAndAdd(() -> {
                        var world = context.getWorld();
                        BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                        for (int x = -2; x <= 14; x++) for (int z = -2; z <= 14; z++) {
                            world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                            for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                        }
                        world.setBlockState(context.getAbsolutePos(CHEST), Blocks.CHEST.getDefaultState());
                        f.runtime.createNative(f.source, "Hal");
                    })
                    .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                    .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                    .createAndAdd(() -> {
                        var statuses = f.runtime.nativeStatuses(Actor.console());
                        context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                                "Both established");
                        for (var status : statuses) {
                            int i = player(status.nativeId()).getName().getString().equals("Hal") ? 0 : 1;
                            ids[i] = status.id();
                            players[i] = status.nativeId();
                        }
                    })
                    .createAndAdd(() -> {
                        var hal = player(players[0]);
                        var rue = player(players[1]);
                        BlockPos a = context.getAbsolutePos(new BlockPos(5, 2, 3)), b = context.getAbsolutePos(new BlockPos(12, 2, 12));
                        hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                        rue.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                        hal.getInventory().clear();
                        rue.getInventory().clear();
                        server.setPvpEnabled(true);
                        context.getWorld().setTimeOfDay(1000);
                        f.runtime.setDragonFightForTest(true);
                    })
                    .createAndAdd(() -> {
                        context.assertTrue(f.runtime.proposeManhunt(f.source, java.util.List.of(players[1]), java.util.List.of(players[0])) == 1, "Proposed");
                        match[0] = f.runtime.matches().getFirst().id();
                    })
                    .createAndAdd(() -> {
                        context.assertTrue(f.runtime.matches().getFirst().joined().size() == 2, "Both entered");
                        context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                    })
                    .createAndAdd(() -> {
                        context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                        // Both held still: the compass's rule is what is tested, not the chase.
                        context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[0]) == 1, "Hal held");
                        context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Rue held");
                    });
        }

        ServerPlayerEntity hal() { return player(players[0]); }

        ServerPlayerEntity player(UUID id) { return context.getWorld().getServer().getPlayerManager().getPlayer(id); }

        /** How many of this hunt's compasses a player holds. */
        int held(ServerPlayerEntity player) {
            int n = 0;
            if (player == null) return 0;
            for (int i = 0; i < player.getInventory().size(); i++) if (HuntCompass.of(player.getInventory().getStack(i), match[0])) n++;
            return n;
        }

        UUID issueHeld(ServerPlayerEntity player) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                var stack = player.getInventory().getStack(i);
                if (HuntCompass.of(stack, match[0])) return HuntCompass.issue(stack).orElse(null);
            }
            return null;
        }

        int inChest(TestContext context) {
            int n = 0;
            var chest = chest(context);
            for (int i = 0; i < chest.size(); i++) if (HuntCompass.of(chest.getStack(i), match[0])) n++;
            return n;
        }

        void end() {
            context.getWorld().getServer().setPvpEnabled(pvp);
            f.runtime.stop();
        }
    }

    private static Inventory chest(TestContext context) {
        return (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(CHEST));
    }

    private static int count(Inventory inventory, net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) n += inventory.getStack(i).getCount();
        return n;
    }
}
