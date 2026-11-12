package dev.kindred.fabric;

import dev.kindred.core.factions.FactionSettings;
import dev.kindred.core.factions.SafeZones;
import dev.kindred.core.factions.Territory;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: in a faction world, a founder claims the ground its first base will stand on (M-06i with M-01c), not
 * whatever chunk it happens to stand in. Standing on a floor of planks (another's build, no ground for a base), with flat
 * open ground beyond it, it walks to where its
 * cabin would go, claims the chunk there (a claim is always the chunk the claimer stands in, as a person's is), and its
 * cabin, once it has stone tools, is built wholly on its own land. With no flat open ground anywhere round it, it claims
 * where it stands, as before.
 *
 * Technical: no goal, bases on, AI world edits on; without stone tools at first its base waits ("Stone tools first"), so
 * its faction play sites the base before its base decision does. The claimed chunks are the Record's ({@code claims}).
 * The cabin's footprint is its origin and its plan's span (the first plan, {@code BuildStyle.CLASSIC}).
 */
public final class NativeFactionBaseSiteGameTests implements FabricGameTest {
    private static final FactionSettings WIDE = FactionSettings.parse(Map.of("border", "60000000"));
    private static final String W = Territory.OVERWORLD;

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionBaseSite", tickLimit = 12000)
    public void aFounderClaimsTheGroundItsFirstBaseWillStandOnAndBuildsItThere(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 11500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos home = o.add(16, 7, 16);
        String[] standingChunk = new String[1];
        Set<String> claimed = new TreeSet<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, new BlockPos[0]);
                    // A floor of planks over the whole chunk it stands in: another's build, no ground to build on there.
                    int cx = home.getX() >> 4 << 4, cz = home.getZ() >> 4 << 4;
                    for (int x = cx; x < cx + 16; x++) for (int z = cz; z < cz + 16; z++) {
                        var top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).down();
                        world.setBlockState(top, Blocks.OAK_PLANKS.getDefaultState());
                    }
                    standingChunk[0] = SafeZones.chunkAt(W, home.getX(), home.getZ());
                    stage(context, f, home);
                })
                .createAndAdd(() -> {
                    String faction = f.runtime.factions().book().of(f.nativeId).map(x -> x.id().toString()).orElse("");
                    var mine = f.runtime.projected().claims().entrySet().stream().filter(e -> e.getValue().equals(faction)).map(Map.Entry::getKey).toList();
                    context.assertTrue(!mine.isEmpty(), "Its first claim: " + f.runtime.nativeLastResult(f.id));
                    claimed.addAll(mine);
                    context.assertTrue(!claimed.contains(standingChunk[0]),
                            "The base's ground claimed, not the planks it stood on (" + standingChunk[0] + "): " + claimed);
                    var player = f.player();
                    context.assertTrue(claimed.contains(SafeZones.chunkAt(W, player.getBlockX(), player.getBlockZ())),
                            "Claimed where it walked to and stands: " + player.getBlockPos().toShortString() + " in " + claimed);
                    // Now stone tools and its cabin's makings: its base is decided.
                    var inventory = player.getInventory();
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_AXE));
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.currentBase(f.id).isPresent(), "Its cabin decided: " + f.runtime.baseNote(f.id)))
                .createAndAdd(() -> {
                    // Its land as it stands now (a second claim may have joined the first).
                    String faction = f.runtime.factions().book().of(f.nativeId).map(x -> x.id().toString()).orElse("");
                    f.runtime.projected().claims().forEach((chunk, owner) -> { if (owner.equals(faction)) claimed.add(chunk); });
                    var base = f.runtime.currentBase(f.id).orElseThrow();
                    var shape = dev.kindred.core.construction.Houses.shape(dev.kindred.core.construction.Houses.Stage.CABIN, base.variant());
                    int[] span = dev.kindred.core.construction.Houses.span(shape, base.turns());
                    // The plan's origin turns with it; its ground is the span from the north-west corner it covers.
                    var corner = cornerOf(base, shape);
                    for (int[] c : new int[][] {{0, 0}, {span[0] - 1, 0}, {0, span[1] - 1}, {span[0] - 1, span[1] - 1}}) {
                        String chunk = SafeZones.chunkAt(W, corner.getX() + c[0], corner.getZ() + c[1]);
                        context.assertTrue(claimed.contains(chunk), "The cabin wholly on its own land: corner " + (corner.getX() + c[0]) + ", "
                                + (corner.getZ() + c[1]) + " in " + chunk + ", its land " + claimed);
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    @GameTest(templateName = FixtureGround.WIDE, skyAccess = true, batchId = "factionBaseNoSite", tickLimit = 6000)
    public void withNoGroundForABaseAFounderClaimsWhereItStands(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 5500);
        BlockPos o = NativeBaseGameTests.origin(context);
        BlockPos home = o.add(16, 7, 16);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Mason"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    NativeBaseGameTests.ground(context, new BlockPos[0]);
                    // Planks over the ground as far as it would look for a site and a cabin's width more: no ground to build
                    // on anywhere round it.
                    for (int x = -16; x <= 48; x++) for (int z = -16; z <= 48; z++) {
                        var top = world.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, o.add(x, 0, z)).down();
                        world.setBlockState(top, Blocks.OAK_PLANKS.getDefaultState());
                    }
                    stage(context, f, home);
                })
                .createAndAdd(() -> {
                    String faction = f.runtime.factions().book().of(f.nativeId).map(x -> x.id().toString()).orElse("");
                    var mine = f.runtime.projected().claims().entrySet().stream().filter(e -> e.getValue().equals(faction)).map(Map.Entry::getKey).toList();
                    context.assertTrue(mine.size() == 1, "Its first claim: " + mine + " / " + f.runtime.nativeLastResult(f.id));
                    context.assertTrue(mine.getFirst().equals(SafeZones.chunkAt(W, home.getX(), home.getZ())), "No site anywhere: claimed where it stands: " + mine);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /** A faction world, by day, bases on, the player at home, playing, with food and no stone tools yet. */
    private static void stage(TestContext context, NativePaidSkillsGameTests.Fixture f, BlockPos home) {
        context.getWorld().setTimeOfDay(1000);
        f.runtime.factionsWorldForTest(WIDE);
        var player = f.player();
        var inventory = player.getInventory();
        inventory.clear();
        inventory.setStack(0, new ItemStack(Items.BREAD, 16));
        player.setHealth(20);
        player.getHungerManager().setFoodLevel(20);
        player.refreshPositionAndAngles(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0, 0);
        context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.OPTIMAL_SURVIVAL) == 1, "Playing");
        context.assertTrue(f.runtime.setBases(f.source, true) == 1, "Bases on");
        // No goal of its own, as its base is only decided without one; a faction world's standing goal would take it.
        context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NONE) == 1, "No goal");
        f.runtime.fixBaseStyle(f.id, dev.kindred.core.construction.BuildStyle.CLASSIC);
        f.runtime.factionsTickForTest(); // Its starting coins, as for anyone first seen in a faction world.
    }

    /** The north-west corner of the ground a base covers: its origin turned back ({@code Houses.originFor}'s inverse). */
    private static BlockPos cornerOf(PlayerMemoryStore.Base base, dev.kindred.core.construction.Houses.Shape shape) {
        return switch (Math.floorMod(base.turns(), 4)) {
            case 1 -> new BlockPos(base.x() - (shape.depth() - 1), base.y(), base.z());
            case 2 -> new BlockPos(base.x() - (shape.width() - 1), base.y(), base.z() - (shape.depth() - 1));
            case 3 -> new BlockPos(base.x(), base.y(), base.z() - (shape.width() - 1));
            default -> new BlockPos(base.x(), base.y(), base.z());
        };
    }
}
