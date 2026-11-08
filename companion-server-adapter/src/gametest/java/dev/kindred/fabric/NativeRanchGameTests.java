package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.survival.Ranch;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, a player with fourteen oak logs and eight wheat is set to
 * start a cow ranch. The test checks it crafts and builds a fenced pen with a gate, leads two cows in from
 * across the field with the wheat, breeds them and leaves with the gate shut: the pen complete, two wheat
 * used, both cows and a calf inside, and still inside ten seconds later.
 *
 * Technical: A ranch in Co-op Survival (M-01a). An AI player with fourteen oak logs it cut and eight wheat it
 * grew crafts a pen's fences and gate, builds the pen, leads two cows from across a field into it with the
 * wheat in hand, shuts them in, feeds them, and leaves with the gate shut once the calf is born: two wheat
 * spent, the cows still penned after.
 */
public final class NativeRanchGameTests implements FabricGameTest {
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "ranch", tickLimit = 6000)
    public void anAiPlayerPensAPairOfCowsWithItsOwnWoodAndBreedsThem(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5800);
        UUID[] id = new UUID[1], player = new UUID[1], cows = new UUID[2];
        long[] doneAt = {-1};
        BlockPos o = context.getAbsolutePos(new BlockPos(4, 0, 4));
        // The pen's north-west post; its gate in the middle of the south side.
        BlockPos pen = o.add(4, 2, 2), gate = pen.add(3, 0, 6);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    // A field of grass walled in, so the cows wander only so far.
                    var world = context.getWorld();
                    forceChunks(world, o, true);
                    FixtureGround.keep(context, o.add(-4, 0, -4), o.add(34, 12, 24));
                    for (int x = -4; x <= 34; x++) for (int z = -4; z <= 24; z++) {
                        boolean edge = x == -4 || x == 34 || z == -4 || z == 24;
                        world.setBlockState(o.add(x, 1, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 2; y <= 12; y++) {
                            world.setBlockState(o.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    f.runtime.createNative(f.source, "Wren");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Wren established");
                    id[0] = statuses.getFirst().id();
                    player[0] = statuses.getFirst().nativeId();
                })
                .createAndAdd(() -> {
                    var wren = player(f, player[0]);
                    wren.refreshPositionAndAngles(o.getX() + 7.5, o.getY() + 2, o.getZ() + 14.5, 0, 0);
                    wren.getInventory().clear();
                    // As though cut and grown: fourteen logs and eight wheat.
                    wren.getInventory().insertStack(new ItemStack(Items.OAK_LOG, 14));
                    wren.getInventory().insertStack(new ItemStack(Items.WHEAT, 8));
                    var daisy = context.spawnEntity(EntityType.COW, new BlockPos(28, 2, 10));
                    var bess = context.spawnEntity(EntityType.COW, new BlockPos(29, 2, 13));
                    cows[0] = daisy.getUuid();
                    cows[1] = bess.getUuid();
                    context.assertTrue(f.runtime.startRanch(f.source, id[0], pen, Ranch.Kind.COW) == 1, "Ranch started");
                })
                .createAndAdd(() -> {
                    // Until the ranch is done: the pen built, the pair in, a calf, the gate shut behind.
                    var status = f.runtime.ranchStatus(id[0]);
                    context.assertTrue(status.isEmpty(), "Ranch done: " + status.orElse("") + "; " + f.runtime.buildStatus(id[0]).orElse(""));
                    doneAt[0] = context.getWorld().getTime();
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int fences = 0;
                    for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
                        if (x != 0 && x != 6 && z != 0 && z != 6) continue;
                        if (world.getBlockState(pen.add(x, 0, z)).isOf(Blocks.OAK_FENCE)) fences++;
                    }
                    var gateState = world.getBlockState(gate);
                    context.assertTrue(fences == 23 && gateState.isOf(Blocks.OAK_FENCE_GATE), "The pen built: " + fences + " fences, " + gateState);
                    context.assertTrue(!gateState.get(FenceGateBlock.OPEN), "The gate shut");
                    var wren = player(f, player[0]);
                    BlockPos feet = wren.getBlockPos();
                    context.assertTrue(!inside(pen, feet), "Wren out of the pen: " + feet.toShortString());
                    var where = new StringBuilder();
                    for (var cow : world.getEntitiesByClass(CowEntity.class, new Box(pen).expand(40), c -> true)) {
                        where.append(cow.getBlockPos().subtract(pen).toShortString()).append(cow.isBaby() ? " young" : "").append("; ");
                    }
                    context.assertTrue(wren.getInventory().count(Items.WHEAT) == 6, "Two wheat fed: " + wren.getInventory().count(Items.WHEAT) + " left; cows from the pen's post: " + where);
                    var penned = world.getEntitiesByClass(CowEntity.class, new Box(pen).expand(8), c -> inside(pen, c.getBlockPos()));
                    long adults = penned.stream().filter(c -> !c.isBaby()).count(), calves = penned.stream().filter(CowEntity::isBaby).count();
                    KindredMod.LOGGER.info("Ranch: {} fences, gate {}, {} cows and {} calves penned, Wren at {}", fences, gateState, adults, calves, feet.toShortString());
                    context.assertTrue(adults == 2 && penned.stream().filter(c -> !c.isBaby()).allMatch(c -> c.getUuid().equals(cows[0]) || c.getUuid().equals(cows[1])),
                            "Both cows penned: " + adults);
                    context.assertTrue(calves == 1, "A calf born in the pen: " + calves);
                })
                .createAndAdd(() -> {
                    // Ten seconds on, the gate holds them.
                    var world = context.getWorld();
                    context.assertTrue(world.getTime() - doneAt[0] >= 200, "Ten seconds on");
                    var penned = world.getEntitiesByClass(CowEntity.class, new Box(pen).expand(8), c -> inside(pen, c.getBlockPos()));
                    context.assertTrue(penned.size() == 3, "Still penned: " + penned.size());
                    forceChunks(world, o, false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * One of the pair stands down in a sunken patch three deep past the pen, in sight, with no way up a cow or a player
     * can walk (the wheat fixture: a cow past the field's edge, the player down after it and there for good). It does not
     * go down after it: it pens the one it can bring and leaves, the other given up, the gate shut behind; its wheat kept,
     * there being no pair to breed. Staged: the field walled in as above, the patch dug in it, one cow put in it.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "ranchSunken", tickLimit = 4000)
    public void oneOfThePairDownWhereNoneCanWalkUpIsNotFollowedDown(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 3800);
        UUID[] id = new UUID[1], player = new UUID[1], cows = new UUID[2];
        int[] lowest = {Integer.MAX_VALUE};
        BlockPos o = context.getAbsolutePos(new BlockPos(4, 0, 4));
        BlockPos pen = o.add(4, 2, 2);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    forceChunks(world, o, true);
                    FixtureGround.keep(context, o.add(-4, -3, -4), o.add(34, 12, 24));
                    for (int x = -4; x <= 34; x++) for (int z = -4; z <= 24; z++) {
                        boolean edge = x == -4 || x == 34 || z == -4 || z == 24;
                        for (int y = -3; y <= 0; y++) world.setBlockState(o.add(x, y, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(o.add(x, 1, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 2; y <= 12; y++) {
                            world.setBlockState(o.add(x, y, z), edge && y <= 3 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                        }
                    }
                    // The sunken patch, three below the field, its sides sheer.
                    for (int x = 25; x <= 32; x++) for (int z = 6; z <= 16; z++) {
                        world.setBlockState(o.add(x, -2, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = -1; y <= 1; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    f.runtime.createNative(f.source, "Wren");
                })
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Wren established");
                    id[0] = statuses.getFirst().id();
                    player[0] = statuses.getFirst().nativeId();
                })
                .createAndAdd(() -> {
                    var wren = player(f, player[0]);
                    wren.refreshPositionAndAngles(o.getX() + 7.5, o.getY() + 2, o.getZ() + 14.5, 0, 0);
                    wren.getInventory().clear();
                    wren.getInventory().insertStack(new ItemStack(Items.OAK_LOG, 14));
                    wren.getInventory().insertStack(new ItemStack(Items.WHEAT, 8));
                    // Daisy on the field south of the pen, well west of the patch, so that she does not wander into it as
                    // she grazes (once in five runs, nearer it, she did); Bess down in the patch.
                    cows[0] = context.spawnEntity(EntityType.COW, new BlockPos(4 + 10, 2, 4 + 18)).getUuid();
                    cows[1] = context.spawnEntity(EntityType.COW, new BlockPos(4 + 29, -1, 4 + 11)).getUuid();
                    context.assertTrue(f.runtime.startRanch(f.source, id[0], pen, Ranch.Kind.COW) == 1, "Ranch started");
                })
                .createAndAdd(() -> {
                    var wren = player(f, player[0]);
                    if (wren != null) lowest[0] = Math.min(lowest[0], wren.getBlockY());
                    context.assertTrue(lowest[0] >= o.getY() + 2, "Never down off the field: " + (lowest[0] - o.getY() - 2) + " below it, at "
                            + (wren == null ? "-" : wren.getBlockPos().subtract(pen).toShortString()) + " from the pen's post");
                    var status = f.runtime.ranchStatus(id[0]);
                    context.assertTrue(status.isEmpty(), "Ranch done: " + status.orElse("") + "; " + f.runtime.buildStatus(id[0]).orElse(""));
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var wren = player(f, player[0]);
                    var gateState = world.getBlockState(pen.add(3, 0, 6));
                    context.assertTrue(gateState.isOf(Blocks.OAK_FENCE_GATE) && !gateState.get(FenceGateBlock.OPEN), "The gate shut: " + gateState);
                    context.assertTrue(!inside(pen, wren.getBlockPos()) && wren.getBlockY() >= o.getY() + 2, "Wren out of the pen, on the field: "
                            + wren.getBlockPos().subtract(pen).toShortString());
                    var penned = world.getEntitiesByClass(CowEntity.class, new Box(pen).expand(8), c -> inside(pen, c.getBlockPos()));
                    var where = new StringBuilder();
                    for (var cow : world.getEntitiesByClass(CowEntity.class, new Box(pen).expand(40), c -> true)) {
                        where.append(cow.getUuid().equals(cows[0]) ? "Daisy " : cow.getUuid().equals(cows[1]) ? "Bess " : "other ")
                                .append(cow.getBlockPos().subtract(pen).toShortString()).append("; ");
                    }
                    KindredMod.LOGGER.info("Ranch, one sunken: {} penned; cows from the pen's post: {}", penned.size(), where);
                    context.assertTrue(penned.size() == 1 && penned.getFirst().getUuid().equals(cows[0]), "Daisy penned alone: " + where);
                    context.assertTrue(wren.getInventory().count(Items.WHEAT) == 8, "No pair to breed, its wheat kept: " + wren.getInventory().count(Items.WHEAT));
                    forceChunks(world, o, false);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * Pressed against the pen's fence, a player's middle can be in the fence's block, clear of its post: in the pen from
     * inside, out of it from outside, and the same at the gate shut. Read by that block alone, a player its cows had pushed
     * against the fence from inside was out of the pen, and the ranch was done with it shut in (the cattle fixture, once in
     * ten, M-06a1). A chicken, narrow enough to stand so, the same. In the gateway with the gate open, it is in neither.
     */
    @GameTest(templateName = FixtureGround.FIELD, skyAccess = true, batchId = "ranchFence", tickLimit = 200)
    public void againstThePensFenceOneIsReckonedOnItsOwnSideOfIt(TestContext context) {
        BlockPos o = context.getAbsolutePos(new BlockPos(4, 0, 4));
        BlockPos pen = o.add(4, 2, 2), gate = pen.add(3, 0, 6);
        RanchRunner runner = new RanchRunner(pen, Ranch.Kind.CHICKEN, "oak");
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    // The pen staged on grass, its gate shut, and a chicken against its south fence from inside, its AI off.
                    var world = context.getWorld();
                    FixtureGround.keep(context, o.add(-2, 0, -2), o.add(14, 6, 14));
                    for (int x = -2; x <= 14; x++) for (int z = -2; z <= 14; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.GRASS_BLOCK.getDefaultState());
                        for (int y = 2; y <= 6; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
                        if (x != 0 && x != 6 && z != 0 && z != 6) continue;
                        world.setBlockState(pen.add(x, 0, z), (x == 3 && z == 6 ? Blocks.OAK_FENCE_GATE : Blocks.OAK_FENCE).getDefaultState());
                    }
                    var hen = EntityType.CHICKEN.create(world);
                    hen.refreshPositionAndAngles(pen.getX() + 1.5, pen.getY(), pen.getZ() + 6.1, 0, 0);
                    hen.setAiDisabled(true);
                    world.spawnEntity(hen);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    var player = net.fabricmc.fabric.api.entity.FakePlayer.get(world);
                    // Its middle in the fence's block east of the gate, just inside the post, then just outside it.
                    player.setPosition(pen.getX() + 4.5, pen.getY(), pen.getZ() + 6.05);
                    var seen = runner.observe(player, world);
                    context.assertTrue(player.getBlockPos().getZ() == pen.getZ() + 6, "Its middle in the fence's block: " + player.getPos());
                    context.assertTrue(runner.ranch.inside(seen.feet()), "Against the fence from inside, in the pen: " + seen.feet());
                    var hen = seen.animals().stream().filter(a -> a.kind() == Ranch.Kind.CHICKEN).findFirst();
                    context.assertTrue(hen.isPresent() && runner.ranch.inside(hen.get().at()), "The chicken against it from inside, in the pen: " + hen);
                    player.setPosition(pen.getX() + 4.5, pen.getY(), pen.getZ() + 6.95);
                    seen = runner.observe(player, world);
                    context.assertTrue(!runner.ranch.inside(seen.feet()), "Against it from outside, out of the pen: " + seen.feet());
                    player.setPosition(gate.getX() + 0.5, pen.getY(), gate.getZ() + 0.05);
                    seen = runner.observe(player, world);
                    context.assertTrue(runner.ranch.inside(seen.feet()), "Against the gate shut from inside, in the pen: " + seen.feet());
                    world.setBlockState(gate, world.getBlockState(gate).with(FenceGateBlock.OPEN, true));
                    player.setPosition(gate.getX() + 0.5, pen.getY(), gate.getZ() + 0.5);
                    seen = runner.observe(player, world);
                    context.assertTrue(!runner.ranch.inside(seen.feet()) && seen.feet().z() == gate.getZ(), "In the gateway, the gate open: " + seen.feet());
                })
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** The test server ticks no mob outside the test's own chunks (it sets no simulation distance); a live one does. */
    private static void forceChunks(net.minecraft.server.world.ServerWorld world, BlockPos o, boolean forced) {
        for (int x = o.getX() - 16; x <= o.getX() + 48; x += 16) for (int z = o.getZ() - 16; z <= o.getZ() + 32; z += 16) {
            world.setChunkForced(x >> 4, z >> 4, forced);
        }
    }

    private static boolean inside(BlockPos pen, BlockPos at) {
        return at.getX() > pen.getX() && at.getX() < pen.getX() + 6 && at.getZ() > pen.getZ() && at.getZ() < pen.getZ() + 6
                && Math.abs(at.getY() - pen.getY()) <= 2;
    }

    private static ServerPlayerEntity player(NativePaidSkillsGameTests.Fixture f, UUID id) {
        return f.source.getServer().getPlayerManager().getPlayer(id);
    }
}
