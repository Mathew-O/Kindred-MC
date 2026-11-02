package dev.kindred.fabric;

import dev.kindred.core.atlas.Atlas;
import dev.kindred.core.outing.Outing;
import dev.kindred.core.social.Rapport;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * In plain terms: a GameTest: Echo, with nothing to do and its owner about, has seen a lake twenty blocks off; it asks
 * whether anyone wants to come and see it. Its owner says "I'm in"; it walks there, says so, stays, and walks back home.
 * Its owner was there with it, so it remembers going to the lake together, a good memory with them; and nothing moved
 * before the "I'm in".
 *
 * Technical: E-27a, through the whole runtime: {@link Outings} (the invitation in the social lane, the walks as Echo's own
 * {@code native_goto} candidates in its decision loop), the ledger's episode and the friendship's {@code OUTING} (E-24a). Staged, and said where: the
 * lake as an atlas sighting on a stone causeway laid for the walk; daytime; a shorter wait before asking; the stay held until the
 * arrival is heard and then ended (this test server runs ticks faster than a line is typed); the owner standing at the lake
 * when Echo gets there (a mock player does not walk).
 */
public final class NativeOutingGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "outing", tickLimit = 12000)
    public void askedToSeeALakeItsOwnerSaysImInAndTheyGoTogetherAndItRemembers(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        BlockPos[] lake = {null};
        BlockPos[] home = {null};
        boolean[] done = new boolean[3];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(f.source, f.id, "public", true, null, 0) == 1, "Public chat on"))
                .createAndAdd(() -> context.assertTrue(f.runtime.atlasForFixtures(f.id) != null, "Its atlas read"))
                .createAndAdd(() -> context.assertTrue(f.runtime.peersForFixtures(f.id) != null && f.runtime.rapportForFixtures(f.id).isPresent(),
                        "Its ledger and friendships read"))
                .createAndAdd(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    // Staged: a stone causeway east, twenty blocks long, and the lake seen at its end.
                    for (int x = 0; x <= 24; x++) for (int z = 2; z <= 5; z++) {
                        context.setBlockState(x, 1, z, Blocks.STONE);
                        for (int y = 2; y <= 4; y++) context.setBlockState(x, y, z, Blocks.AIR);
                    }
                    lake[0] = context.getAbsolutePos(new BlockPos(23, 2, 3));
                    home[0] = f.player().getBlockPos();
                    var world = context.getWorld();
                    f.runtime.atlasForFixtures(f.id).sighted(Atlas.Resource.WATER, PortalRunCampaign.place(world, lake[0]), world.getTime(), null);
                    world.setTimeOfDay(1000); // Staged: day, as an outing is not asked at night.
                    // Staged: a stay held until the fixture has heard it arrive (this server runs ticks faster than the line is typed).
                    f.runtime.outingsForFixtures().shorterForFixtures(1_000_000, 20);
                    f.runtime.outingsForFixtures().readyForFixtures(f.id);
                    t.listen();
                })
                .createAndAdd(() -> context.assertTrue(t.heardBy(t.owner).stream().anyMatch(h -> h.text().startsWith("[Kindred] Echo: I found a lake not far off.")),
                        "Asked: " + t.heardBy(t.owner)))
                .createAndAdd(() -> {
                    NativeVoiceGameTests.Talk.waitFor(1_000);
                    context.assertTrue(f.player() != null && f.player().getBlockPos().getSquaredDistance(home[0]) <= 4, "Nothing moved before the yes");
                })
                .createAndAdd(() -> {
                    if (!done[1]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(t.owner.getUuid(), "Echo, I'm in");
                        done[1] = true;
                    }
                    var outing = f.runtime.outingsForFixtures().outingForFixtures(f.id).orElse(null);
                    context.assertTrue(outing != null && outing.state() == Outing.State.GOING, "Off: " + f.runtime.outingsForFixtures().logForFixtures());
                })
                .createAndAdd(() -> {
                    // Staged: its owner walked along (a mock player does not), standing at the lake.
                    if (!done[2]) {
                        done[2] = true;
                        var at = Vec3d.ofBottomCenter(lake[0]).add(0, 0, 1);
                        t.owner.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
                    }
                    var at = f.player() == null ? null : f.player().getBlockPos();
                    context.assertTrue(at != null && at.getSquaredDistance(lake[0]) <= 16,
                            "Walked to the lake: at " + at + "; " + f.runtime.outingsForFixtures().logForFixtures());
                })
                .createAndAdd(() -> context.assertTrue(t.heardBy(t.owner).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: Here we are: a lake.")),
                        "Said so: " + t.heardBy(t.owner)))
                .createAndAdd(() -> {
                    var outing = f.runtime.outingsForFixtures().outingForFixtures(f.id).orElse(null);
                    context.assertTrue(outing != null && outing.state() == Outing.State.THERE && outing.there().contains(t.owner.getUuid()),
                            "There, with its owner: " + f.runtime.outingsForFixtures().logForFixtures());
                    f.runtime.outingsForFixtures().shorterForFixtures(0, 20); // Staged: the stay over.
                })
                .createAndAdd(() -> {
                    var at = f.player() == null ? null : f.player().getBlockPos();
                    context.assertTrue(at != null && at.getSquaredDistance(home[0]) <= 16,
                            "Walked back home: at " + at + "; " + f.runtime.outingsForFixtures().logForFixtures());
                })
                .createAndAdd(() -> {
                    var outing = f.runtime.outingsForFixtures().outingForFixtures(f.id);
                    context.assertTrue(outing.isEmpty() || outing.get().state() == Outing.State.DONE, "Done: " + f.runtime.outingsForFixtures().logForFixtures());
                    var standing = f.runtime.rapportForFixtures(f.id).orElseThrow().standing(t.owner.getUuid());
                    context.assertTrue(standing.friendship() == Rapport.Kind.OUTING.worth, "A good memory with its owner: " + standing);
                    var ledger = f.runtime.peersForFixtures(f.id);
                    context.assertTrue(ledger.episodes(t.owner.getUuid()).stream().anyMatch(e -> e.text().equals("went to a lake together")),
                            "In its ledger: " + ledger.episodes(t.owner.getUuid()));
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
