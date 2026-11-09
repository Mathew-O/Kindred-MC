package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.persistence.StoredNativePlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.EntityType;
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
 * In plain terms: GameTests that a faction's land holds against everything that could reach in from outside it: a piston
 * pushing a block over the border, water poured beside it, a hopper under the outside half of a double chest, a
 * creeper's blast, a person breaking or opening things, and an AI player told to break a block there; each with a
 * control showing the same thing works in the wilderness or for the land's own faction.
 *
 * Technical: M-06d's native fixtures. A claim is written to the World Record directly (the claim rules, the spawn
 * exclusion and the border are JUnit's, and test positions lie far outside an 8,000 border). Each test is its own
 * batch, so only one faction world is active at a time.
 */
public final class NativeTerritoryGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";

    /** The local x at which a new chunk begins, between 4 and 20, so x - 1 is the wilderness and x the claim. */
    private static int border(TestContext context) {
        for (int x = 4; x <= 20; x++) if (Math.floorMod(context.getAbsolutePos(new BlockPos(x, 0, 0)).getX(), 16) == 0) return x;
        throw new IllegalStateException("No chunk border");
    }

    private static void claim(PlayerRuntime runtime, TestContext context, BlockPos local, String owner) {
        var at = context.getAbsolutePos(local);
        runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, owner,
                ClaimLedger.chunk(W, at.getX() >> 4, at.getZ() >> 4), Map.of()));
    }

    /** The chunk column from local x {@code b} east claimed, across every chunk row from local z 0 to 8. */
    private static void claimEast(PlayerRuntime runtime, TestContext context, int b, String owner) {
        var rows = new java.util.LinkedHashSet<Integer>();
        for (int z = 0; z <= 8; z++) rows.add(context.getAbsolutePos(new BlockPos(b, 0, z)).getZ() >> 4);
        int x = context.getAbsolutePos(new BlockPos(b, 0, 0)).getX() >> 4;
        for (int row : rows) runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, owner, ClaimLedger.chunk(W, x, row), Map.of()));
    }

    /** The ground from {@code fromX} to {@code toX} cleared on stone, its chunks both sides of the border kept simulated. */
    private static void floor(TestContext context, int fromX, int toX, int fromZ, int toZ) {
        FixtureGround.simulated(context, context.getAbsolutePos(new BlockPos(fromX, 0, fromZ)), context.getAbsolutePos(new BlockPos(toX, 0, toZ)));
        for (int x = fromX; x <= toX; x++) for (int z = fromZ; z <= toZ; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 5; y++) context.setBlockState(x, y, z, Blocks.AIR);
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryPiston", tickLimit = 400)
    public void aPistonCannotPushABlockIntoAnotherFactionsLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 6, b + 2, 0, 8);
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b, UUID.randomUUID().toString());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    // Across the border: the piston and its block in the wilderness, the block's next place on the claim.
                    context.setBlockState(b - 2, 2, 3, Blocks.PISTON.getDefaultState().with(PistonBlock.FACING, Direction.EAST));
                    context.setBlockState(b - 1, 2, 3, Blocks.OAK_PLANKS);
                    // The control, all in the wilderness: the same push one chunk-width short of the border.
                    context.setBlockState(b - 5, 2, 6, Blocks.PISTON.getDefaultState().with(PistonBlock.FACING, Direction.EAST));
                    context.setBlockState(b - 4, 2, 6, Blocks.OAK_PLANKS);
                    context.setBlockState(b - 2, 3, 3, Blocks.REDSTONE_BLOCK);
                    context.setBlockState(b - 5, 3, 6, Blocks.REDSTONE_BLOCK);
                })
                .createAndAdd(() -> {
                    context.expectBlock(Blocks.OAK_PLANKS, new BlockPos(b - 3, 2, 6));
                    context.expectBlock(Blocks.OAK_PLANKS, new BlockPos(b - 1, 2, 3));
                    context.expectBlock(Blocks.AIR, new BlockPos(b, 2, 3));
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryWater", tickLimit = 400)
    public void waterPouredAtTheBorderDoesNotFlowIn(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        long[] poured = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // A walled pool on both sides of the border, so the water has no drop to make for and spreads every way.
                    floor(context, b - 5, b + 4, -1, 7);
                    for (int x = b - 5; x <= b + 4; x++) for (int z = -1; z <= 7; z++) {
                        if (x == b - 5 || x == b + 4 || z == -1 || z == 7) context.setBlockState(x, 2, z, Blocks.STONE);
                    }
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b, UUID.randomUUID().toString());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    context.setBlockState(b - 1, 2, 3, Blocks.WATER);
                    poured[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getTick() - poured[0] >= 40, "Forty ticks to flow");
                    context.assertTrue(!context.getWorld().getFluidState(context.getAbsolutePos(new BlockPos(b - 2, 2, 3))).isEmpty(), "Flowed west, into the wilderness: source "
                            + context.getBlockState(new BlockPos(b - 1, 2, 3)) + ", west " + context.getBlockState(new BlockPos(b - 2, 2, 3)) + ", below " + context.getBlockState(new BlockPos(b - 2, 1, 3)) + ", west ok " + TerritoryGuard.mayCross(context.getWorld(), context.getAbsolutePos(new BlockPos(b - 1, 2, 3)), context.getAbsolutePos(new BlockPos(b - 2, 2, 3)))
                            + ", east ok " + TerritoryGuard.mayCross(context.getWorld(), context.getAbsolutePos(new BlockPos(b - 1, 2, 3)), context.getAbsolutePos(new BlockPos(b, 2, 3))) + ", b " + b);
                    for (int x = b; x <= b + 3; x++) for (int z = 0; z <= 6; z++) {
                        context.assertTrue(context.getWorld().getFluidState(context.getAbsolutePos(new BlockPos(x, 2, z))).isEmpty(), "No water on the claim at " + x + "," + z);
                    }
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryHopper", tickLimit = 400)
    public void aHopperDoesNotPullFromTheProtectedHalfOfADoubleChest(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        long[] set = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 4, b + 1, 0, 6);
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b, UUID.randomUUID().toString());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    // A double chest across the border: the west half in the wilderness, the east half (with the loot) claimed.
                    var west = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH).with(ChestBlock.CHEST_TYPE, ChestType.LEFT);
                    var east = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH).with(ChestBlock.CHEST_TYPE, ChestType.RIGHT);
                    context.setBlockState(b - 1, 3, 3, west);
                    context.setBlockState(b, 3, 3, east);
                    context.setBlockState(b - 1, 2, 3, Blocks.HOPPER);
                    ((ChestBlockEntity) context.getBlockEntity(new BlockPos(b, 3, 3))).setStack(0, new ItemStack(Items.DIAMOND, 5));
                    // The control: a single chest and a hopper, both in the wilderness.
                    context.setBlockState(b - 3, 3, 5, Blocks.CHEST);
                    context.setBlockState(b - 3, 2, 5, Blocks.HOPPER);
                    ((ChestBlockEntity) context.getBlockEntity(new BlockPos(b - 3, 3, 5))).setStack(0, new ItemStack(Items.COAL, 5));
                    set[0] = context.getTick();
                })
                .createAndAdd(() -> {
                    context.assertTrue(context.getTick() - set[0] >= 60, "Sixty ticks of hopping");
                    var control = (HopperBlockEntity) context.getBlockEntity(new BlockPos(b - 3, 2, 5));
                    context.assertTrue(!control.isEmpty(), "The wilderness hopper pulled its chest's coal");
                    var hopper = (HopperBlockEntity) context.getBlockEntity(new BlockPos(b - 1, 2, 3));
                    context.assertTrue(hopper.isEmpty(), "The border hopper pulled nothing: west " + context.getBlockState(new BlockPos(b - 1, 3, 3))
                            + ", east " + context.getBlockState(new BlockPos(b, 3, 3)) + ", hopper " + hopper.getStack(0) + ", claims " + f.runtime.ledgers().claims()
                            + ", reach " + TerritoryGuard.mayReachContainer(context.getWorld(), context.getAbsolutePos(new BlockPos(b - 1, 2, 3)), context.getAbsolutePos(new BlockPos(b - 1, 3, 3)))
                            + ", other " + TerritoryGuard.otherHalf(context.getWorld(), context.getAbsolutePos(new BlockPos(b - 1, 3, 3))) + " of " + context.getAbsolutePos(new BlockPos(b - 1, 3, 3))
                            + ", here " + ClaimLedger.chunk(W, context.getAbsolutePos(new BlockPos(b, 3, 3)).getX() >> 4, context.getAbsolutePos(new BlockPos(b, 3, 3)).getZ() >> 4));
                    var loot = (ChestBlockEntity) context.getBlockEntity(new BlockPos(b, 3, 3));
                    context.assertTrue(loot.getStack(0).isOf(Items.DIAMOND) && loot.getStack(0).getCount() == 5, "Five diamonds still in the claimed half");
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryCreeper", tickLimit = 400)
    public void aCreepersBlastLeavesProtectedBlocksStanding(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        boolean[] lit = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 4, b + 3, 0, 6);
                    for (int x = b - 3; x <= b + 2; x++) for (int y = 2; y <= 3; y++) context.setBlockState(x, y, 5, Blocks.OAK_PLANKS);
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b, UUID.randomUUID().toString());
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    if (!lit[0]) {
                        // A creeper's own blast: what its explode() does, at once rather than after its fuse.
                        var creeper = context.spawnEntity(EntityType.CREEPER, new Vec3d(b, 2, 4));
                        var at = creeper.getPos();
                        context.getWorld().createExplosion(creeper, at.x, at.y, at.z, 3.0f, net.minecraft.world.World.ExplosionSourceType.MOB);
                        creeper.discard();
                        lit[0] = true;
                    }
                })
                .createAndAdd(() -> {
                    int wild = 0;
                    for (int x = b - 3; x < b; x++) for (int y = 2; y <= 3; y++) if (context.getBlockState(new BlockPos(x, y, 5)).isAir()) wild++;
                    context.assertTrue(wild > 0, "The blast broke wilderness planks");
                    for (int x = b; x <= b + 2; x++) for (int y = 2; y <= 3; y++) context.expectBlock(Blocks.OAK_PLANKS, new BlockPos(x, y, 5));
                    context.expectBlock(Blocks.STONE, new BlockPos(b, 1, 4));
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "territoryPlayers", tickLimit = 1200)
    public void onlyTheLandsOwnFactionChangesItPeopleAndAiPlayersAlike(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 1100);
        var server = context.getWorld().getServer();
        UUID[] players = new UUID[2], ids = new UUID[2];
        ServerPlayerEntity[] person = new ServerPlayerEntity[1];
        BlockPos target = new BlockPos(5, 2, 5), chest = new BlockPos(2, 2, 6);
        FixtureGround.whenDone(context, () -> { if (person[0] != null && server.getPlayerManager().getPlayer(person[0].getUuid()) != null) server.getPlayerManager().remove(person[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    f.runtime.createNative(f.source, "Ada");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).size() == 1, "Ada allocated"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.phase() == StoredNativePlayer.Phase.ESTABLISHED && s.connected()),
                            "Both established");
                    for (var status : statuses) {
                        int i = player(f, status.nativeId()).getName().getString().equals("Ada") ? 0 : 1;
                        players[i] = status.nativeId();
                        ids[i] = status.id();
                    }
                    person[0] = MockPlayers.survival(context, "Cleo", new BlockPos(4, 2, 4));
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.faction(f.source, players[0], "create", List.of("Red", "RED")).accepted(), "Ada founds Red");
                    context.assertTrue(f.runtime.faction(f.source, players[1], "create", List.of("Blue", "BLU")).accepted(), "Bram founds Blue");
                })
                .createAndAdd(() -> {
                    String red = f.runtime.factions().book().named("Red").orElseThrow().id().toString();
                    for (var corner : List.of(new BlockPos(0, 0, 0), new BlockPos(8, 0, 0), new BlockPos(0, 0, 8), new BlockPos(8, 0, 8))) claim(f.runtime, context, corner, red);
                    context.setBlockState(target, Blocks.OAK_PLANKS);
                    context.setBlockState(chest, Blocks.CHEST);
                    ((ChestBlockEntity) context.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 3));
                    var bram = player(f, players[1]);
                    var at = context.getAbsolutePos(new BlockPos(5, 2, 3));
                    bram.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    // A person in no faction: neither breaks a block nor opens the chest on Red's land.
                    var cleo = person[0];
                    context.assertFalse(cleo.interactionManager.tryBreakBlock(context.getAbsolutePos(target)), "Cleo's break refused");
                    var hit = new BlockHitResult(Vec3d.ofCenter(context.getAbsolutePos(chest)), Direction.UP, context.getAbsolutePos(chest), false);
                    cleo.interactionManager.interactBlock(cleo, context.getWorld(), ItemStack.EMPTY, Hand.MAIN_HAND, hit);
                    context.assertTrue(cleo.currentScreenHandler == cleo.playerScreenHandler, "Cleo did not open Red's chest");
                    // Bram, an enemy AI player, is told to break the planks: refused at admission, nothing moved.
                    f.runtime.nativeLifecycle(f.source, "resume", ids[1]);
                    f.runtime.nativeBreak(f.source, ids[1], context.getAbsolutePos(target));
                })
                .createAndAdd(() -> {
                    String line = f.runtime.nativeStatusLine(ids[1]);
                    context.assertTrue(line.contains("Another faction's land"), "Bram refused: " + line);
                    context.expectBlock(Blocks.OAK_PLANKS, target);
                    var loot = (ChestBlockEntity) context.getBlockEntity(chest);
                    context.assertTrue(loot.getStack(0).getCount() == 3, "Three diamonds untouched");
                    context.assertTrue(person[0].getInventory().count(Items.DIAMOND) == 0 && player(f, players[1]).getInventory().count(Items.OAK_PLANKS) == 0, "Nothing moved");
                    // Red's own member may: the control.
                    context.assertTrue(TerritoryGuard.mayChange(player(f, players[0]), context.getAbsolutePos(target)), "Ada may change her own land");
                    context.assertTrue(!TerritoryGuard.mayChange(player(f, players[1]), context.getAbsolutePos(target)), "Bram may not");
                })
                .completeIfSuccessful();
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
