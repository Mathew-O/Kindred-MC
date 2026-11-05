package dev.kindred.fabric;

import dev.kindred.core.construction.Houses;
import dev.kindred.core.match.GameModes;
import dev.kindred.core.match.GroupPace;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;

import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: in a Co-op Survival world an AI player with its cabin, iron tools and days behind it would move on to
 * a house. While the only human has mined stone but not smelted iron, it waits, and says why. Once the human has
 * smelted iron, it begins the house.
 *
 * Technical: M-01e, the group's pace. Staged, as the fixture says: the ground laid; the cabin's blocks set and recorded
 * as the player's own complete cabin; its iron tools given; the clock set three days on; the human a mock survival
 * player whose advancements the fixture grants, as though earned. What is under test is the decision: the house held
 * while the group's furthest step is short of iron, and begun once it is not.
 */
public final class NativeCoopPaceGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "coopPace", tickLimit = 6000)
    public void aHouseWaitsUntilAHumanHasSmeltedIron(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos[] trunks = {o.add(34, 7, 10), o.add(34, 7, 22)};
        BlockPos home = o.add(16, 7, 16);
        BlockPos cabinAt = home.add(-3, 0, -3);
        ServerPlayerEntity[] human = new ServerPlayerEntity[1];
        var server = context.getWorld().getServer();
        long[] heldFrom = {-1};
        boolean[] begunEarly = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Hearth"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, trunks);
                    // Staged: three days on, by day; its cabin standing, block for block on the first plan in oak.
                    world.setTimeOfDay(3 * 24000L + 1000);
                    var cabin = Houses.of(Houses.Stage.CABIN, "oak", "cobblestone");
                    for (var b : cabin.placed(BuildRunner.pos(cabinAt), 0)) {
                        try {
                            var state = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), b.spec().toString(), false).blockState();
                            world.setBlockState(BuildRunner.blockPos(b.at()), state);
                        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                            throw new RuntimeException(failure);
                        }
                    }
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    ItemStack[] held = {new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_AXE), new ItemStack(Items.BREAD, 16),
                            new ItemStack(Items.DIRT, 32)};
                    for (int slot = 0; slot < held.length; slot++) inventory.setStack(slot, held[slot]);
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    BlockPos stand = home.add(8, 0, 0);
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0, 0);
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
                    f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.COOP_SURVIVAL) == 1, "Co-op Survival");
                    // Staged: a human, who has mined stone and no more (granted by the fixture, as though earned).
                    human[0] = MockPlayers.survival(context, "Wren", o.add(16, 7, 24).subtract(context.getAbsolutePos(BlockPos.ORIGIN)));
                    grant(server, human[0], GroupPace.Step.STONE);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.stageBaseForTest(f.id,
                        new PlayerMemoryStore.Base("cabin", "minecraft:overworld", cabinAt.getX(), cabinAt.getY(), cabinAt.getZ(), true)), "Its cabin recorded"))
                .createAndAdd(() -> context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on"))
                .createAndAdd(() -> {
                    if (f.runtime.buildStatus(f.id).isPresent()) begunEarly[0] = true;
                    context.assertTrue(!begunEarly[0], "Begun before the group smelted iron: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.currentBase(f.id));
                    context.assertTrue(f.runtime.groupStep().equals(java.util.Optional.of(GroupPace.Step.STONE)), "The group's step read: " + f.runtime.groupStep());
                    context.assertTrue(f.runtime.baseNote(f.id).startsWith("Keeping to the group's pace"), "Held for the group: " + f.runtime.baseNote(f.id));
                    heldFrom[0] = server.getTicks();
                })
                // Held: nothing begun for ten seconds, the cabin still its base.
                .createAndAdd(() -> {
                    if (f.runtime.buildStatus(f.id).isPresent() || !f.runtime.currentBase(f.id).orElseThrow().stage().equals("cabin")) begunEarly[0] = true;
                    context.assertTrue(!begunEarly[0], "Begun before the group smelted iron: " + f.runtime.buildStatus(f.id).orElse(""));
                    context.assertTrue(server.getTicks() - heldFrom[0] >= 200, "Holding");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.baseNote(f.id).contains("smelted iron"), "Still held for iron: " + f.runtime.baseNote(f.id));
                    grant(server, human[0], GroupPace.Step.IRON);
                })
                .createAndAdd(() -> {
                    var base = f.runtime.currentBase(f.id);
                    context.assertTrue(f.runtime.groupStep().equals(java.util.Optional.of(GroupPace.Step.IRON)), "Iron seen: " + f.runtime.groupStep());
                    context.assertTrue(base.isPresent() && base.get().stage().equals("house") && f.runtime.buildStatus(f.id).isPresent(),
                            "A house begun once a human smelted iron: " + base + " / " + f.runtime.baseNote(f.id));
                    KindredMod.LOGGER.info("Coop pace: held ({}), then a house begun: {}", "smelted iron", f.runtime.buildStatus(f.id).orElse(""));
                    f.runtime.stop();
                    server.getPlayerManager().remove(human[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * A trip waits too: in Co-op Survival the End goal, stocked for the trip beside a lit Nether portal, stays on this
     * side while the only human has smelted iron but not entered the Nether, saying why; once the human has entered the
     * Nether, it goes through. Staged as the stronghold fixture is (the portal lit, the trip's stock given), and the
     * human's advancements granted by the fixture, as though earned.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "coopPaceTrip", tickLimit = 2400)
    public void theNetherWaitsUntilAHumanHasEnteredIt(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        ServerPlayerEntity[] human = new ServerPlayerEntity[1];
        var server = context.getWorld().getServer();
        long[] heldFrom = {-1};
        boolean[] crossedEarly = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Wayfarer"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    NativeStrongholdGameTests.litPortal(context);
                    context.getWorld().setTimeOfDay(1000);
                    var player = f.player();
                    BlockPos stand = context.getAbsolutePos(new BlockPos(3, 2, 7));
                    player.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 180, 0);
                    NativeStrongholdGameTests.stockedForTheNether(player);
                    context.assertTrue(f.runtime.setWorldMode(f.source, GameModes.WorldMode.COOP_SURVIVAL) == 1, "Co-op Survival");
                    // Staged: a human who has smelted iron and no more (granted by the fixture, as though earned).
                    human[0] = MockPlayers.survival(context, "Wren", new BlockPos(6, 2, 7));
                    grant(server, human[0], GroupPace.Step.STONE);
                    grant(server, human[0], GroupPace.Step.IRON);
                    context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.STRONGHOLD) == 1, "Goal accepted");
                })
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    if (f.player().getServerWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) crossedEarly[0] = true;
                    context.assertTrue(!crossedEarly[0], "Through before the group entered the Nether");
                    String progress = f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress();
                    context.assertTrue(progress.startsWith("Keeping to the group's pace: into the Nether"), "Held at the portal: " + progress);
                    heldFrom[0] = server.getTicks();
                })
                // Held: still on this side ten seconds on.
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    if (f.player().getServerWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) crossedEarly[0] = true;
                    context.assertTrue(!crossedEarly[0], "Through before the group entered the Nether");
                    context.assertTrue(server.getTicks() - heldFrom[0] >= 200, "Holding");
                })
                .createAndAdd(() -> grant(server, human[0], GroupPace.Step.NETHER))
                .createAndAdd(() -> {
                    context.getWorld().setTimeOfDay(1000);
                    context.assertTrue(f.player().getServerWorld().getRegistryKey() == net.minecraft.world.World.NETHER,
                            "Through once a human entered the Nether: " + f.runtime.panelBots(dev.kindred.core.Actor.console()).getFirst().goalProgress());
                    KindredMod.LOGGER.info("Coop pace: held at the portal, then through once someone entered the Nether");
                    f.runtime.stop();
                    server.getPlayerManager().remove(human[0]);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /** Every criterion of the step's advancement granted to the player (staged, as though earned). */
    private static void grant(net.minecraft.server.MinecraftServer server, ServerPlayerEntity player, GroupPace.Step step) {
        var advancement = server.getAdvancementLoader().get(Identifier.of(step.advancement));
        if (advancement == null) throw new IllegalStateException("No advancement " + step.advancement);
        for (String criterion : advancement.value().criteria().keySet()) player.getAdvancementTracker().grantCriterion(advancement, criterion);
    }
}
