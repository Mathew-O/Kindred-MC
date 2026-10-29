package dev.kindred.fabric;

import dev.kindred.core.ActionRecord;
import dev.kindred.core.Actor;
import dev.kindred.core.match.MatchRegistry;
import dev.kindred.core.survival.StrategyAdvice;
import dev.kindred.model.Strategist;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In plain terms: a GameTest: in a real test world, during a manhunt a hunter sees the runner use a chest
 * from too far away to strike it, and the runner then moves off. The test checks the hunter, by its own rule,
 * takes what is worth taking from the chest (leaving the dirt) and tells its side what it took; that it
 * leaves the chest alone when a test stand-in adviser says to; that outside a match a chest it sees used
 * means nothing to it; and that it never raids a fellow hunter's chest.
 *
 * Technical: Raiding an opponent's storage in a match (E-44): in a manhunt, the hunter Hal sees the runner
 * Rue use a chest, out of the hunter's striking range; Rue goes on its way. With no model, Hal's own rule
 * raids the chest of what is worth taking and tells its own side; a model's LEAVE is followed and nothing
 * taken; outside a match nothing Rue does at a chest is anything to Hal; and a fellow hunter's chest is never
 * its to raid.
 */
public final class NativeRaidGameTests implements FabricGameTest {
    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID nativeId) {
        return f.source.getServer().getPlayerManager().getPlayer(nativeId);
    }

    /** A strategist that answers every question as told, and records it; it gives no advice. */
    private static final class Stub implements Strategist {
        final String choice;
        final List<String> asked = new java.util.ArrayList<>();

        Stub(String choice) { this.choice = choice; }

        @Override
        public CompletableFuture<Outcome> advise(String report, List<StrategyAdvice.Place> reported) {
            return CompletableFuture.completedFuture(new Outcome(Optional.empty(), 0, 0, 0, 0, Optional.of("a fixture's stub gives no advice")));
        }

        @Override
        public CompletableFuture<Answer> ask(Question question) {
            asked.add(question.text());
            return CompletableFuture.completedFuture(new Answer(Optional.of(choice), Optional.empty(), "Rue may be watching from the trees.",
                    40, 0, 12, 5, Optional.empty()));
        }

        @Override public String provider() { return "Fixture"; }
        @Override public String model() { return "stub"; }
        @Override public String pace() { return "at once"; }
        @Override public void close() { }
    }

    /** The scene's places, from the test's origin: Hal's, Rue's, and Rue's chest beside it. */
    private static final BlockPos HAL = new BlockPos(4, 2, 2), RUE = new BlockPos(4, 2, 30), CHEST = new BlockPos(6, 2, 32);

    /**
     * Open ground, Hal and Rue on it 28 blocks apart, Rue's chest beside it with what Rue keeps; in a manhunt when
     * {@code hunt}, Hal hunting and Rue its runner. {@code stub} answers Hal's questions, if given.
     */
    private static void scene(TestContext context, NativePaidSkillsGameTests.Fixture f, boolean hunt, Stub stub, Runnable checks,
                              java.util.function.Consumer<UUID[]> after) {
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[2], players = new UUID[2], match = new UUID[1];
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 40; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    if (stub != null) f.runtime.strategistForFixtures(stub);
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
                    after.accept(new UUID[] {ids[0], ids[1], players[0], players[1]});
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    var rue = player(f, players[1]);
                    BlockPos a = context.getAbsolutePos(HAL), b = context.getAbsolutePos(RUE);
                    hal.refreshPositionAndAngles(a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
                    rue.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 180, 0);
                    hal.getInventory().clear();
                    rue.getInventory().clear();
                    var world = context.getWorld();
                    BlockPos chest = context.getAbsolutePos(CHEST);
                    world.setBlockState(chest, Blocks.CHEST.getDefaultState());
                    var inside = (ChestBlockEntity) world.getBlockEntity(chest);
                    inside.setStack(0, new ItemStack(Items.DIAMOND, 4));
                    inside.setStack(1, new ItemStack(Items.GOLD_INGOT, 10));
                    inside.setStack(2, new ItemStack(Items.BREAD, 6));
                    inside.setStack(3, new ItemStack(Items.DIRT, 20));
                    world.setTimeOfDay(1000);
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                    // Both at a fresh start, as a manhunt has it.
                    if (hunt) context.assertTrue(f.runtime.proposeManhunt(f.source, List.of(players[1]), List.of(players[0])) == 1, "Proposed");
                })
                .createAndAdd(() -> {
                    // Past the fresh start: the hunter's kit staged here, as earning it would take the whole run's iron.
                    var hal = player(f, players[0]);
                    hal.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    hal.getInventory().setStack(1, new ItemStack(Items.BREAD, 8));
                    hal.getInventory().selectedSlot = 0;
                    hal.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                })
                .createAndAdd(() -> {
                    if (!hunt) return;
                    match[0] = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.matches().getFirst().joined().size() == 2, "Both entered by their administrator");
                    context.assertTrue(f.runtime.startMatch(f.source, match[0]) == 1, "Started");
                })
                .createAndAdd(() -> {
                    if (hunt) context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    // The runner keeps still for this fixture: what it does is the fixture's.
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1, "Rue held");
                })
                .createAndAdd(() -> {
                    // Rue opens its chest, in Hal's sight but out of its striking range.
                    var rue = player(f, players[1]);
                    var hal = player(f, players[0]);
                    context.assertTrue(hal.canSee(rue) && hal.squaredDistanceTo(rue) > 24 * 24, "Hal sees Rue, out of reach");
                    rue.openHandledScreen((ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(CHEST)));
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "Rue at its chest for two seconds"))
                .createAndAdd(() -> {
                    // Then on its way, far off and out of sight.
                    var rue = player(f, players[1]);
                    rue.closeHandledScreen();
                    BlockPos away = context.getAbsolutePos(new BlockPos(4, 2, 30)).add(0, 0, 200);
                    rue.refreshPositionAndAngles(away.getX() + 0.5, away.getY() + 1, away.getZ() + 0.5, 0, 0);
                    waited[0] = 0;
                })
                .createAndAdd(checks)
                .createAndAdd(() -> {
                    var record = f.runtime.nativeActionStatus(Actor.console(), ids[0]).record();
                    context.assertTrue(record == null || record.state() != ActionRecord.State.UNCERTAIN, "No uncertain action: " + record);
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    private static ChestBlockEntity chest(TestContext context) {
        return (ChestBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(CHEST));
    }

    private static int count(net.minecraft.inventory.Inventory inventory, net.minecraft.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) if (inventory.getStack(slot).isOf(item)) total += inventory.getStack(slot).getCount();
        return total;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raidOwn", tickLimit = 2400)
    public void aHunterRaidsTheChestItSawTheRunnerUseByItsOwnRuleAndTellsItsSide(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        UUID[] hal = new UUID[2];
        scene(context, f, true, null, () -> {
            var halBody = player(f, hal[1]);
            context.assertTrue(halBody != null, "Hal online");
            var inside = chest(context);
            context.assertTrue("RAID (its own rule)".equals(f.runtime.raidDecision(hal[0], context.getAbsolutePos(CHEST))),
                    "Its own rule's call: " + f.runtime.raidDecision(hal[0], context.getAbsolutePos(CHEST)));
            context.assertTrue(count(inside, Items.DIAMOND) == 0 && count(inside, Items.GOLD_INGOT) == 0 && count(inside, Items.BREAD) == 0,
                    "The chest raided of what is worth taking: " + count(inside, Items.DIAMOND) + " diamonds left");
            context.assertTrue(count(inside, Items.DIRT) == 20, "Its dirt left");
            var held = halBody.getInventory();
            // Its own bread it may have eaten some of meanwhile; the chest's six came out with the rest.
            context.assertTrue(count(held, Items.DIAMOND) == 4 && count(held, Items.GOLD_INGOT) == 10 && count(held, Items.BREAD) > 8,
                    "Hal holds what it took");
            context.assertTrue("I raided Rue's chest: 6 bread, 4 diamonds and 10 gold ingots.".equals(f.runtime.lastSaid(hal[0])),
                    "Told its side what it took: " + f.runtime.lastSaid(hal[0]));
        }, ids -> { hal[0] = ids[0]; hal[1] = ids[2]; });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raidModel", tickLimit = 2400)
    public void theModelsCallToLeaveTheRunnersChestIsFollowed(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var stub = new Stub("LEAVE");
        UUID[] hal = new UUID[1];
        int[] after = {0};
        scene(context, f, true, stub, () -> {
            context.assertTrue("LEAVE (the model's call)".equals(f.runtime.raidDecision(hal[0], context.getAbsolutePos(CHEST))),
                    "The model's call: " + f.runtime.raidDecision(hal[0], context.getAbsolutePos(CHEST)));
            context.assertTrue(stub.asked.size() == 1 && stub.asked.getFirst().startsWith("You are Hal, a hunter in a Manhunt match. You saw Rue, a runner and your opponent, use a chest"),
                    "Asked once, with what Hal saw: " + stub.asked);
            context.assertTrue(++after[0] >= 200, "Ten seconds more");
            context.assertTrue(count(chest(context), Items.DIAMOND) == 4 && count(chest(context), Items.GOLD_INGOT) == 10, "Nothing taken");
        }, ids -> hal[0] = ids[0]);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raidCoop", tickLimit = 2400)
    public void outsideAMatchAChestItSeesUsedIsNothingToIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        UUID[] hal = new UUID[1];
        int[] after = {0};
        scene(context, f, false, null, () -> {
            context.assertTrue(++after[0] >= 200, "Ten seconds after");
            context.assertTrue(f.runtime.raidDecision(hal[0], context.getAbsolutePos(CHEST)) == null, "No question, no decision");
            context.assertTrue(count(chest(context), Items.DIAMOND) == 4 && count(chest(context), Items.GOLD_INGOT) == 10, "Nothing taken");
        }, ids -> hal[0] = ids[0]);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "raidMate", tickLimit = 2400)
    public void aTeammatesChestItSeesUsedIsNeverItsToRaid(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 2300);
        var server = context.getWorld().getServer();
        boolean pvp = server.isPvpEnabled();
        UUID[] ids = new UUID[3], players = new UUID[3];
        int[] waited = {0};
        String[] names = {"Hal", "Wren", "Rue"};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -4; x <= 20; x++) for (int z = -4; z <= 40; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Hal");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Hal allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wren"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 2, "Wren allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Rue"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 3 && statuses.stream().allMatch(st -> st.phase() == StoredNativePlayer.Phase.ESTABLISHED && st.connected()),
                            "All three established");
                    for (var status : statuses) {
                        int i = java.util.Arrays.asList(names).indexOf(player(f, status.nativeId()).getName().getString());
                        ids[i] = status.id();
                        players[i] = status.nativeId();
                    }
                })
                .createAndAdd(() -> {
                    BlockPos hal = context.getAbsolutePos(HAL), wren = context.getAbsolutePos(RUE), rue = context.getAbsolutePos(RUE).add(0, 0, 200);
                    player(f, players[0]).refreshPositionAndAngles(hal.getX() + 0.5, hal.getY(), hal.getZ() + 0.5, 0, 0);
                    player(f, players[1]).refreshPositionAndAngles(wren.getX() + 0.5, wren.getY(), wren.getZ() + 0.5, 180, 0);
                    player(f, players[2]).refreshPositionAndAngles(rue.getX() + 0.5, rue.getY() + 1, rue.getZ() + 0.5, 0, 0);
                    for (UUID each : players) player(f, each).getInventory().clear();
                    var world = context.getWorld();
                    BlockPos chest = context.getAbsolutePos(CHEST);
                    world.setBlockState(chest, Blocks.CHEST.getDefaultState());
                    var inside = (ChestBlockEntity) world.getBlockEntity(chest);
                    inside.setStack(0, new ItemStack(Items.DIAMOND, 4));
                    inside.setStack(1, new ItemStack(Items.GOLD_INGOT, 10));
                    world.setTimeOfDay(1000);
                    server.setPvpEnabled(true);
                    f.runtime.setDragonFightForTest(true);
                    context.assertTrue(f.runtime.proposeManhunt(f.source, List.of(players[2]), List.of(players[0], players[1])) == 1, "Proposed");
                })
                .createAndAdd(() -> {
                    var hal = player(f, players[0]);
                    hal.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                    hal.getInventory().setStack(1, new ItemStack(Items.BREAD, 8));
                    hal.getInventory().selectedSlot = 0;
                    hal.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    var match = f.runtime.matches().getFirst().id();
                    context.assertTrue(f.runtime.startMatch(f.source, match) == 1, "Started");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.matches().getFirst().state() == MatchRegistry.State.RUNNING, "Running");
                    context.assertTrue(f.runtime.nativeLifecycle(f.source, "stop", ids[1]) == 1 && f.runtime.nativeLifecycle(f.source, "stop", ids[2]) == 1,
                            "Wren and Rue held");
                })
                .createAndAdd(() -> {
                    // Wren, Hal's fellow hunter, opens its chest in Hal's sight.
                    context.assertTrue(player(f, players[0]).canSee(player(f, players[1])), "Hal sees Wren");
                    player(f, players[1]).openHandledScreen(chest(context));
                })
                .createAndAdd(() -> context.assertTrue(++waited[0] >= 40, "Wren at its chest for two seconds"))
                .createAndAdd(() -> {
                    player(f, players[1]).closeHandledScreen();
                    waited[0] = 0;
                })
                .createAndAdd(() -> {
                    context.assertTrue(++waited[0] >= 200, "Ten seconds after");
                    context.assertTrue(f.runtime.raidDecision(ids[0], context.getAbsolutePos(CHEST)) == null, "No question, no decision: a teammate's");
                    context.assertTrue(count(chest(context), Items.DIAMOND) == 4 && count(chest(context), Items.GOLD_INGOT) == 10, "Nothing taken");
                })
                .createAndAdd(() -> {
                    server.setPvpEnabled(pvp);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
