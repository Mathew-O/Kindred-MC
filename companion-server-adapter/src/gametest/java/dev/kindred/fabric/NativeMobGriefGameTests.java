package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: GameTests that a faction's land holds against mobs' own griefing as it does against explosions
 * (M-06d1): an enderman taking or setting down a block, a ravager trampling leaves, a silverfish hiding in stone, a
 * wither tearing through, and a zombie breaking a door. Each test has a control beside it: the same mob in the
 * wilderness, a few blocks west across the chunk border, does it.
 *
 * Technical: M-06d1's native fixtures. A claim is written to the World Record directly, as in
 * {@link NativeTerritoryGameTests}. The wilderness pen lies west of a chunk border and the claimed pen east of it.
 * Endermen and silverfish act at random, so their own vanilla goals are found in their goal selector and ticked many
 * times: the same code their AI runs, made certain. The ravager is pushed into leaves; the wither is hurt, which starts
 * its block-breaking cooldown; the zombie, able to break doors on Hard, has a villager behind the door. Each test is its
 * own batch, so only one faction world is active at a time.
 */
public final class NativeMobGriefGameTests implements FabricGameTest {
    private static final String W = "minecraft:overworld";

    /** The local x at which a new chunk begins, between 6 and 21 (sixteen, so always one), so x - 1 is the wilderness and x the claim. */
    private static int border(TestContext context) {
        for (int x = 6; x <= 21; x++) if (Math.floorMod(context.getAbsolutePos(new BlockPos(x, 0, 0)).getX(), 16) == 0) return x;
        throw new IllegalStateException("No chunk border");
    }

    /** The chunk column from local x {@code b} east claimed, across every chunk row from local z 0 to 10. */
    private static void claimEast(PlayerRuntime runtime, TestContext context, int b) {
        String owner = UUID.randomUUID().toString();
        var rows = new java.util.LinkedHashSet<Integer>();
        for (int z = 0; z <= 10; z++) rows.add(context.getAbsolutePos(new BlockPos(b, 0, z)).getZ() >> 4);
        int x = context.getAbsolutePos(new BlockPos(b, 0, 0)).getX() >> 4;
        for (int row : rows) runtime.record(WorldEvent.recorded(0, System.currentTimeMillis(), W, ClaimLedger.CLAIM, null, owner, ClaimLedger.chunk(W, x, row), Map.of()));
    }

    /** The ground from {@code fromX} to {@code toX} cleared on stone, open to height 8, kept simulated; a stone roof over it. */
    private static void floor(TestContext context, int fromX, int toX, int fromZ, int toZ) {
        FixtureGround.simulated(context, context.getAbsolutePos(new BlockPos(fromX, 0, fromZ)), context.getAbsolutePos(new BlockPos(toX, 0, toZ)));
        for (int x = fromX; x <= toX; x++) for (int z = fromZ; z <= toZ; z++) {
            context.setBlockState(x, 1, z, Blocks.STONE);
            for (int y = 2; y <= 8; y++) context.setBlockState(x, y, z, Blocks.AIR);
            context.setBlockState(x, 9, z, Blocks.STONE);
        }
    }

    /** The mob's own vanilla goal whose class name ends {@code name} (an enderman's or a silverfish's inner goals). */
    private static Goal goal(MobEntity mob, String name) {
        try {
            var field = MobEntity.class.getDeclaredField("goalSelector");
            field.setAccessible(true);
            var selector = (net.minecraft.entity.ai.goal.GoalSelector) field.get(mob);
            for (var prioritized : selector.getGoals()) if (prioritized.getGoal().getClass().getName().endsWith(name)) return prioritized.getGoal();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The goal selector could not be read", e);
        }
        throw new IllegalStateException("No " + name + " on " + mob);
    }

    private static int count(TestContext context, Block block, int fromX, int toX, int fromY, int toY, int fromZ, int toZ) {
        int n = 0;
        for (int x = fromX; x <= toX; x++) for (int y = fromY; y <= toY; y++) for (int z = fromZ; z <= toZ; z++) {
            if (context.getBlockState(new BlockPos(x, y, z)).isOf(block)) n++;
        }
        return n;
    }

    /** Dirt all about the cell at {@code x, z} (two to the side, three up), the cell itself open for an enderman. */
    private static void dirtAbout(TestContext context, int cx, int cz) {
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) for (int y = 2; y <= 5; y++) {
            context.setBlockState(x, y, z, x == cx && z == cz ? Blocks.AIR : Blocks.DIRT);
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "griefEndermanTake", tickLimit = 400)
    public void anEndermanTakesNoBlockFromClaimedLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        int wild = b - 4, claimed = b + 3;
        EndermanEntity[] e = new EndermanEntity[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 7, b + 6, 0, 8);
                    dirtAbout(context, wild, 4);
                    dirtAbout(context, claimed, 4);
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    for (int i = 0; i < 2; i++) {
                        int x = i == 0 ? wild : claimed;
                        e[i] = context.spawnEntity(EntityType.ENDERMAN, new Vec3d(x + 0.5, 2, 4.5));
                        e[i].setAiDisabled(true);
                    }
                })
                .createAndAdd(() -> {
                    for (int i = 0; i < 2; i++) {
                        var take = goal(e[i], "PickUpBlockGoal");
                        for (int n = 0; n < 400 && e[i].getCarriedBlock() == null; n++) take.tick();
                    }
                    context.assertTrue(e[0].getCarriedBlock() != null, "In the wilderness the enderman took a block");
                    context.assertTrue(count(context, Blocks.DIRT, wild - 2, wild + 2, 2, 5, 2, 6) == 24 * 4 - 1, "One dirt gone from the wilderness pen");
                    context.assertTrue(e[1].getCarriedBlock() == null, "On the claim it carries nothing: " + e[1].getCarriedBlock());
                    context.assertTrue(count(context, Blocks.DIRT, claimed - 2, claimed + 2, 2, 5, 2, 6) == 24 * 4, "Every dirt on the claim where it was");
                    for (var x : e) x.discard();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "griefEndermanPlace", tickLimit = 400)
    public void anEndermanSetsNoBlockDownOnClaimedLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        int wild = b - 4, claimed = b + 3;
        EndermanEntity[] e = new EndermanEntity[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 7, b + 6, 0, 8);
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    for (int i = 0; i < 2; i++) {
                        int x = i == 0 ? wild : claimed;
                        e[i] = context.spawnEntity(EntityType.ENDERMAN, new Vec3d(x + 0.5, 2, 4.5));
                        e[i].setAiDisabled(true);
                        e[i].setCarriedBlock(Blocks.MELON.getDefaultState());
                    }
                })
                .createAndAdd(() -> {
                    for (int i = 0; i < 2; i++) {
                        var place = goal(e[i], "PlaceBlockGoal");
                        for (int n = 0; n < 400 && e[i].getCarriedBlock() != null; n++) place.tick();
                    }
                    context.assertTrue(e[0].getCarriedBlock() == null, "In the wilderness the enderman set its melon down");
                    context.assertTrue(count(context, Blocks.MELON, b - 7, b - 1, 2, 5, 0, 8) == 1, "The melon in the wilderness");
                    context.assertTrue(e[1].getCarriedBlock() != null, "On the claim it still carries its melon");
                    context.assertTrue(count(context, Blocks.MELON, b, b + 6, 2, 5, 0, 8) == 0, "No melon on the claim");
                    for (var x : e) x.discard();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "griefRavager", tickLimit = 400)
    public void aRavagerTramplesNoLeavesOnClaimedLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        int wild = b - 4, claimed = b + 3;
        MobEntity[] r = new MobEntity[2];
        int[] pushed = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 7, b + 6, 0, 8);
                    // A wall of leaves north of each pen, two high, across the pen's width.
                    for (int x : new int[] {wild, claimed}) for (int dx = -2; dx <= 2; dx++) for (int y = 2; y <= 4; y++) {
                        context.setBlockState(x + dx, y, 6, Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true));
                    }
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    for (int i = 0; i < 2; i++) r[i] = context.spawnEntity(EntityType.RAVAGER, new Vec3d((i == 0 ? wild : claimed) + 0.5, 2, 3.5));
                })
                .createAndAdd(() -> {
                    // Driven into the leaves, as a ravager charging through them is.
                    for (var x : r) x.setVelocity(0, x.getVelocity().y, 0.4);
                    context.assertTrue(++pushed[0] > 60, "Pushed for three seconds");
                    context.assertTrue(count(context, Blocks.OAK_LEAVES, wild - 2, wild + 2, 2, 4, 6, 6) < 15, "In the wilderness the ravager broke through the leaves");
                    context.assertTrue(count(context, Blocks.OAK_LEAVES, claimed - 2, claimed + 2, 2, 4, 6, 6) == 15, "Every leaf on the claim where it was");
                    for (var x : r) x.discard();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "griefSilverfish", tickLimit = 400)
    public void aSilverfishHidesInNoStoneOnClaimedLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        int wild = b - 4, claimed = b + 3;
        MobEntity[] s = new MobEntity[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 7, b + 6, 0, 8);
                    // Each silverfish in a cell of stone: stone on every side, above and below.
                    for (int x : new int[] {wild, claimed}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 2; y <= 4; y++) {
                        context.setBlockState(x + dx, y, 4 + dz, dx == 0 && dz == 0 && y == 3 ? Blocks.AIR : Blocks.STONE);
                    }
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    for (int i = 0; i < 2; i++) {
                        s[i] = context.spawnEntity(EntityType.SILVERFISH, new Vec3d((i == 0 ? wild : claimed) + 0.5, 3, 4.5));
                        s[i].setAiDisabled(true);
                    }
                })
                .createAndAdd(() -> {
                    for (int i = 0; i < 2; i++) {
                        var hide = goal(s[i], "WanderAndInfestGoal");
                        for (int n = 0; n < 2000 && s[i].isAlive(); n++) if (hide.canStart()) hide.start();
                    }
                    context.assertTrue(!s[0].isAlive(), "In the wilderness the silverfish hid in the stone");
                    context.assertTrue(count(context, Blocks.INFESTED_STONE, wild - 1, wild + 1, 2, 4, 3, 5) == 1, "One infested stone in the wilderness");
                    context.assertTrue(s[1].isAlive(), "On the claim it is still out");
                    context.assertTrue(count(context, Blocks.INFESTED_STONE, claimed - 1, claimed + 1, 2, 4, 3, 5) == 0, "No infested stone on the claim");
                    s[1].discard();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "griefWither", tickLimit = 400)
    public void aWitherTearsThroughNoBlockOnClaimedLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 380);
        int b = border(context);
        int wild = b - 4, claimed = b + 3;
        MobEntity[] w = new MobEntity[2];
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 7, b + 6, 0, 8);
                    // Each wither hemmed in by planks where a hurt wither tears through (a block either side of it, from its feet
                    // up four), its own column open.
                    for (int x : new int[] {wild, claimed}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 2; y <= 5; y++) {
                        context.setBlockState(x + dx, y, 4 + dz, dx == 0 && dz == 0 ? Blocks.AIR : Blocks.OAK_PLANKS);
                    }
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    for (int i = 0; i < 2; i++) {
                        w[i] = context.spawnEntity(EntityType.WITHER, new Vec3d((i == 0 ? wild : claimed) + 0.5, 2, 4.5));
                        w[i].damage(context.getWorld().getDamageSources().generic(), 1);
                    }
                })
                .createAndAdd(() -> {
                    context.assertTrue(++waited[0] > 40, "Two seconds for the cooldown hurt starts");
                    context.assertTrue(count(context, Blocks.OAK_PLANKS, wild - 1, wild + 1, 2, 5, 3, 5) < 32, "In the wilderness the wither tore through planks");
                    context.assertTrue(count(context, Blocks.OAK_PLANKS, claimed - 1, claimed + 1, 2, 5, 3, 5) == 32, "Every plank on the claim where it was");
                    for (var x : w) x.discard();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "griefZombieDoor", tickLimit = 1600)
    public void aZombieBreaksNoDoorOnClaimedLand(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, false, 1580);
        int b = border(context);
        int wild = b - 4, claimed = b + 3;
        var server = context.getWorld().getServer();
        var difficulty = server.getSaveProperties().getDifficulty();
        FixtureGround.whenDone(context, () -> server.setDifficulty(difficulty, true));
        MobEntity[] z = new MobEntity[2];
        MobEntity[] v = new MobEntity[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    floor(context, b - 7, b + 6, 0, 8);
                    // Each pen walled in stone, split by a wall with an oak door; the zombie south, a villager north.
                    for (int x : new int[] {wild, claimed}) {
                        for (int dx = -2; dx <= 2; dx++) for (int dz = 0; dz <= 8; dz++) for (int y = 2; y <= 4; y++) {
                            boolean edge = Math.abs(dx) == 2 || dz == 0 || dz == 8 || dz == 4;
                            context.setBlockState(x + dx, y, dz, edge ? Blocks.STONE : Blocks.AIR);
                        }
                        var door = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, net.minecraft.util.math.Direction.SOUTH);
                        context.setBlockState(new BlockPos(x, 2, 4), door);
                        context.setBlockState(new BlockPos(x, 3, 4), door.with(DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER));
                    }
                    server.setDifficulty(net.minecraft.world.Difficulty.HARD, true);
                    f.runtime.factionsWorldForTest(FactionSettings.DEFAULTS);
                    claimEast(f.runtime, context, b);
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.recordWritten(), "Claimed");
                    for (int i = 0; i < 2; i++) {
                        int x = i == 0 ? wild : claimed;
                        v[i] = context.spawnEntity(EntityType.VILLAGER, new Vec3d(x + 0.5, 2, 6.5));
                        v[i].setAiDisabled(true);
                        v[i].setInvulnerable(true);
                        var zombie = context.spawnEntity(EntityType.ZOMBIE, new Vec3d(x + 0.5, 2, 2.5));
                        zombie.setCanBreakDoors(true);
                        zombie.setPersistent();
                        z[i] = zombie;
                    }
                })
                .createAndAdd(() -> {
                    // Kept after the villager: a zombie that loses sight of it through the door is told again.
                    for (int i = 0; i < 2; i++) if (z[i].getTarget() == null) z[i].setTarget(v[i]);
                    context.assertTrue(!(context.getBlockState(new BlockPos(wild, 2, 4)).getBlock() instanceof DoorBlock), "In the wilderness the zombie broke the door down");
                    context.assertTrue(context.getBlockState(new BlockPos(claimed, 2, 4)).getBlock() instanceof DoorBlock
                            && context.getBlockState(new BlockPos(claimed, 3, 4)).getBlock() instanceof DoorBlock, "The claim's door standing");
                    for (var x : z) x.discard();
                    for (var x : v) x.discard();
                })
                .completeIfSuccessful();
    }
}
