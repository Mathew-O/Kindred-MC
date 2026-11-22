package dev.kindred.fabric;

import dev.kindred.core.factions.FactionPlay;
import dev.kindred.core.factions.Territory;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * In plain terms: a place it once set aside as open water it saw, chosen again once its wait is over and refused from
 * where it cannot see the water, is set aside again; its next choice then comes at the decision interval, as after any
 * place set aside as water (F-35j1), not at once (M-06m1). A dry place it could find no way to, set aside after its third
 * refusal, is still chosen without at once (F-35i).
 *
 * Technical: on #465's CI (run 45056595996, attempt 2) the paced open-water test failed with a choice 595 ticks after the
 * last: the place chosen at 390382 had been set aside as water before, its wait over; asked from a cave at 390972, its walk
 * was refused ("the goal standable false, on water"), which set it aside again (its fourth), and the live-move set-aside
 * branch weighed again at once, 5 ticks later. Staged in F-35i2's box of bedrock ({@link NativeFactionSurfaceGameTests}),
 * every faction walk refused and the walks waiting after the run of refusals; the move set to a chunk three east, its
 * record (written before the faction world) as each case has it; the box's east side opened, the sky's light at its feet, so that the wait ends (F-35i2's
 * came-up release, logged with where it stands, the sky's light and the wait); its walk asked and refused; then the gap to
 * its next choice.
 */
public final class NativeFactionWaterRefusalGameTests implements FabricGameTest {
    /** How long after its walk's refusal its next choice is watched for. */
    private static final int WATCH = FactionsPlayer.DECIDE_TICKS + 100;
    /** "At once": the next choice within this many ticks of the refusal. */
    private static final int AT_ONCE = 40;

    /** Its record from open water it saw: set aside again by the refusal, the next choice at the decision interval. */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionWaterRefusal", tickLimit = 7000)
    public void aPlaceOnceSetAsideAsWaterAndRefusedAgainIsFollowedByAChoiceAtTheDecisionInterval(TestContext context) {
        run(context, true);
    }

    /** The control: a dry place refused twice before, set aside by its third refusal, chosen without at once (F-35i). */
    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true, batchId = "factionDryRefusal", tickLimit = 7000)
    public void aDryPlaceSetAsideByItsThirdRefusalIsFollowedByAChoiceAtOnce(TestContext context) {
        run(context, false);
    }

    private static void run(TestContext context, boolean water) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 6900);
        var server = context.getWorld().getServer();
        var world = context.getWorld();
        ServerPlayerEntity[] ben = new ServerPlayerEntity[1];
        BlockPos[] feet = new BlockPos[1];
        String[] target = new String[1];
        long[] refusedAt = {-1};
        FixtureGround.whenDone(context, () -> { if (ben[0] != null && server.getPlayerManager().getPlayer(ben[0].getUuid()) != null) server.getPlayerManager().remove(ben[0]); });
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    // Its record of the place, from well before, its wait over, written before the faction world, so that it
                    // takes no part in the run of refusals that starts the wait: set aside as water it saw, or (the control)
                    // refused twice. The place: three chunks east of where its box will be, beyond Blue's land, a place its
                    // faction play does not choose by itself in this staging.
                    long now = server.getTicks();
                    BlockPos box = f.player().getBlockPos().up(30);
                    target[0] = Territory.Chunk.at(Territory.OVERWORLD, box.getX(), box.getZ()).offset(3, 0).key();
                    if (water) f.runtime.factionTargetRecordForTest(f.id, target[0], now - 2000, true);
                    else {
                        f.runtime.factionTargetRecordForTest(f.id, target[0], now - 3000, false);
                        f.runtime.factionTargetRecordForTest(f.id, target[0], now - 2000, false);
                    }
                    feet[0] = NativeFactionSurfaceGameTests.staged(context, f, ben, false, false);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.factionPausedForTest(f.id), "Its faction walks waiting after a run of refusals: "
                        + f.runtime.factionBlockedForTest(f.id)[0] + " refused"))
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.factionWalksEndedForTest(f.id).stream().noneMatch(e -> e.getKey().equals(target[0])),
                            "The place three east not chosen by itself before");
                    f.runtime.factionMoveSetForTest(f.id, new FactionPlay.Choice(FactionPlay.Kind.SCOUT, target[0], 0, "the fixture's move east"));
                    // Its east side opened: the sky's light at its feet, rock still over it; the wait ends, and the walk is asked.
                    for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) world.setBlockState(feet[0].add(1, dy, dz), Blocks.AIR.getDefaultState());
                })
                .createAndAdd(() -> {
                    var ended = f.runtime.factionWalksEndedForTest(f.id).stream().filter(e -> e.getKey().equals(target[0])).toList();
                    context.assertTrue(!ended.isEmpty(), "Its walk east asked and ended: move " + f.runtime.factionMoveForTest(f.id)
                            + ", waiting " + f.runtime.factionPausedForTest(f.id));
                    refusedAt[0] = ended.get(ended.size() - 1).getValue();
                    var player = f.player();
                    KindredMod.LOGGER.info("Water-refusal fixture ({}): its walk east refused at {}, standing {}, the sky's light at its feet {}, waiting {}",
                            water ? "water" : "dry", refusedAt[0], player.getBlockPos().toShortString(),
                            world.getLightLevel(net.minecraft.world.LightType.SKY, player.getBlockPos()), f.runtime.factionPausedForTest(f.id));
                })
                .createAndAdd(() -> {
                    var next = f.runtime.factionDecisionsForTest(f.id).stream().filter(t -> t > refusedAt[0]).findFirst();
                    long since = server.getTicks() - refusedAt[0];
                    if (water) {
                        if (next.isPresent()) {
                            context.assertTrue(next.get() - refusedAt[0] >= FactionsPlayer.DECIDE_TICKS,
                                    "Its next choice " + (next.get() - refusedAt[0]) + " ticks after its walk east was refused, before the decision interval");
                        } else {
                            context.assertTrue(since >= WATCH, "Watched " + WATCH + " ticks after the refusal");
                        }
                    } else {
                        context.assertTrue(next.isPresent() || since > AT_ONCE, "Its next choice after the refusal");
                        context.assertTrue(next.isPresent() && next.get() - refusedAt[0] <= AT_ONCE,
                                "Its next choice at once after a dry place set aside: " + next.map(t -> (t - refusedAt[0]) + " ticks").orElse("none in " + since));
                    }
                })
                .completeIfSuccessful();
    }
}
