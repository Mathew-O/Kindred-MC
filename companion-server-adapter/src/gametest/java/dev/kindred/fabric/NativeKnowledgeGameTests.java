package dev.kindred.fabric;

import dev.kindred.core.knowledge.KnowledgeWeb;
import dev.kindred.core.record.ClaimLedger;
import dev.kindred.core.record.WorldEvent;
import dev.kindred.core.wire.Listener;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In plain terms: a GameTest of what everyone knows from the World Record: a faction claims two chunks and another
 * captures one; the world's web says who holds which, agreeing with the territory ledger; after a restart it is rebuilt
 * from the Record alone and says the same. A player's old atlas file is read into its own web once at start.
 *
 * Technical: F-32b's fixture. Events go in through {@code PlayerRuntime.record}, as the Factions mode writes them; the
 * web is fed as each is written and rebuilt by replaying the Record at start.
 */
public final class NativeKnowledgeGameTests implements FabricGameTest {
    private static Map<String, String> owners(KnowledgeWeb world, long tick) {
        var anyone = KnowledgeWeb.Viewer.of(new Listener(UUID.randomUUID(), "anyone", true, Set.of(), "", "", "", false, null, null));
        var out = new TreeMap<String, String>();
        for (var r : world.query(anyone, tick, r -> r.verb() == KnowledgeWeb.Verb.CLAIMED)) {
            out.put(r.object().substring("chunk:".length()), r.subject().substring("faction:".length()));
        }
        return out;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knowledge", tickLimit = 800)
    public void whoHoldsWhichChunkIsRebuiltFromTheRecordAlone(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 700);
        var server = context.getWorld().getServer();
        String a = ClaimLedger.chunk("minecraft:overworld", 70, 70), b = ClaimLedger.chunk("minecraft:overworld", 71, 70);
        @SuppressWarnings("unchecked") Map<String, String>[] before = new Map[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.webs().rebuilt(), "Ready, the web rebuilt"))
                .createAndAdd(() -> f.runtime.record(List.of(
                        WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM, null, "red", a, Map.of()),
                        WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CLAIM, null, "red", b, Map.of()),
                        WorldEvent.recorded(server.getTicks(), System.currentTimeMillis(), "minecraft:overworld", ClaimLedger.CAPTURE, null, "blue", b, Map.of("from", "red")))))
                .createAndAdd(() -> {
                    var claims = f.runtime.ledgers().claims();
                    context.assertTrue(claims.equals(Map.of(a, "red", b, "blue")), "Written: " + claims);
                    var known = owners(f.runtime.webs().world(), server.getTicks());
                    context.assertTrue(known.equals(new TreeMap<>(claims)), "The web agrees with the ledger: " + known + " against " + claims);
                    before[0] = known;
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.webs().rebuilt(), "Restarted, the web rebuilt"))
                .createAndAdd(() -> {
                    var known = owners(f.runtime.webs().world(), server.getTicks());
                    context.assertTrue(known.equals(before[0]), "The same from the Record alone: " + known + " against " + before[0]);
                    context.assertTrue(known.equals(new TreeMap<>(f.runtime.ledgers().claims())), "And with the ledger rebuilt");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .completeIfSuccessful();
    }

    /** Ada tells her faction she needs iron; Bram, of her faction, knows it, and still knows it after a restart. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knowledgeTold", tickLimit = 1000)
    public void whatAPlayerWasToldSurvivesARestart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 900);
        var server = context.getWorld().getServer();
        UUID[] ids = new UUID[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.webs().rebuilt(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(dev.kindred.core.Actor.console()).size() == 1, "Ada"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Bram"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(dev.kindred.core.Actor.console());
                    context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(st -> st.phase() == dev.kindred.persistence.StoredNativePlayer.Phase.ESTABLISHED
                            && st.connected()), "Both established");
                    for (var st : statuses) {
                        var body = server.getPlayerManager().getPlayer(st.nativeId());
                        ids[body.getName().getString().equals("Ada") ? 0 : 1] = f.runtime.wire().idOf(body);
                    }
                    f.runtime.setFactionOf(id -> id.equals(ids[0]) || id.equals(ids[1]) ? "oak" : "");
                    var d = f.runtime.wire().send(dev.kindred.core.wire.Message.of("faction.need", ids[0], dev.kindred.core.wire.Scope.faction("oak"),
                            Map.of("needs", dev.kindred.core.wire.Fact.of("iron", dev.kindred.core.wire.Visibility.FACTION)), List.of()));
                    context.assertTrue(d.sent() && d.told().size() == 1 && d.told().getFirst().receiver().equals(ids[1]), "Told to Bram: " + d);
                })
                .createAndAdd(() -> {
                    context.assertTrue(needs(f, ids), "Bram knows Ada needs iron");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> { f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer); f.runtime.start(); })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.webs().rebuilt(), "Restarted, the webs rebuilt"))
                .createAndAdd(() -> {
                    context.assertTrue(needs(f, ids), "Bram still knows it, from the Record alone");
                    var ada = f.runtime.webs().existing(ids[0]);
                    context.assertTrue(ada.map(w -> w.size() == 0).orElse(true), "Ada, the sender, was told nothing");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .completeIfSuccessful();
    }

    /**
     * Ada's old atlas file (F-30), as an earlier version of the mod kept it, holds a diamond she found and her bed; after
     * a restart both are in her web, hers alone, read once: a second look finds the web the same.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knowledgeOldFiles", tickLimit = 1000)
    public void aPlayersOldAtlasIsReadIntoItsWebOnceAtStart(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 900);
        var server = context.getWorld().getServer();
        UUID[] ada = new UUID[1];
        int[] held = new int[1];
        long[] at = new long[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady() && f.runtime.webs().rebuilt(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Ada"))
                .createAndAdd(() -> {
                    var statuses = f.runtime.nativeStatuses(dev.kindred.core.Actor.console());
                    context.assertTrue(statuses.size() == 1 && statuses.getFirst().phase() == dev.kindred.persistence.StoredNativePlayer.Phase.ESTABLISHED
                            && statuses.getFirst().connected(), "Ada established");
                    ada[0] = f.runtime.wire().idOf(server.getPlayerManager().getPlayer(statuses.getFirst().nativeId()));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> {
                    var diamond = new dev.kindred.core.atlas.Atlas.Place("minecraft:overworld", 905, 12, -411);
                    var bed = new dev.kindred.core.atlas.Atlas.Place("minecraft:overworld", 880, 70, -400);
                    var old = new dev.kindred.core.atlas.Atlas.Snapshot(List.of(new dev.kindred.core.atlas.Atlas.ChunkRow("minecraft:overworld", 56, -26, 1000, true, "minecraft:plains",
                            List.of(new dev.kindred.core.atlas.Atlas.Sighting(dev.kindred.core.atlas.Atlas.Resource.DIAMOND, diamond, 3, 1000, "Ada")))),
                            List.of(), List.of(new dev.kindred.core.atlas.Atlas.Shelter(bed, "bed")));
                    try {
                        var folder = f.directory.resolve("memory");
                        java.nio.file.Files.createDirectories(folder);
                        java.nio.file.Files.writeString(folder.resolve(ada[0] + ".atlas.json"), new com.google.gson.Gson().toJson(old));
                    } catch (java.io.IOException e) {
                        throw new AssertionError("The old atlas could not be written", e);
                    }
                    f.runtime = new PlayerRuntime(server, f.directory, NativePlayerSaves::read, writer -> writer);
                    f.runtime.start();
                })
                .createAndAdd(() -> {
                    f.runtime.onServerTick(server); // A fixture's runtime is ticked through its bodies; this one's is not back yet.
                    context.assertTrue(f.runtime.isReady() && f.runtime.webs().rebuilt(), "Restarted, the webs rebuilt");
                    var web = f.runtime.webs().existing(ada[0]);
                    context.assertTrue(web.isPresent() && web.get().all().stream().anyMatch(r -> r.subject().equals("resource:diamond")), "Her diamond, in her web");
                })
                .createAndAdd(() -> {
                    var web = f.runtime.webs().existing(ada[0]).orElseThrow();
                    long tick = server.getTicks();
                    var her = KnowledgeWeb.Viewer.of(new Listener(ada[0], "Ada", false, Set.of(KindredWire.TEAM), "", "", "", false, null, null));
                    var mate = KnowledgeWeb.Viewer.of(new Listener(UUID.randomUUID(), "Bram", false, Set.of(KindredWire.TEAM), "", "", "", false, null, null));
                    context.assertTrue(web.query(her, tick, r -> r.verb() == KnowledgeWeb.Verb.FOUND).size() == 1, "She knows where it is");
                    context.assertTrue(web.query(her, tick, r -> r.verb() == KnowledgeWeb.Verb.SHELTERS_AT).size() == 1, "And her bed");
                    context.assertTrue(web.query(mate, tick, r -> true).isEmpty(), "A teammate is told nothing of it");
                    context.assertTrue(f.runtime.webs().oldAdded() == web.size(), "What was added is what she holds: " + f.runtime.webs().oldAdded() + " of " + web.size());
                    held[0] = web.size();
                    at[0] = tick;
                })
                .createAndAdd(() -> {
                    f.runtime.onServerTick(server);
                    context.assertTrue(server.getTicks() >= at[0] + 60, "Three seconds on");
                })
                .createAndAdd(() -> {
                    context.assertTrue(f.runtime.webs().existing(ada[0]).orElseThrow().size() == held[0]
                            && f.runtime.webs().oldAdded() == held[0], "Read once: still " + held[0]);
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped again"))
                .completeIfSuccessful();
    }

    private static boolean needs(NativePaidSkillsGameTests.Fixture f, UUID[] ids) {
        var web = f.runtime.webs().existing(ids[1]);
        if (web.isEmpty()) return false;
        var bram = KnowledgeWeb.Viewer.of(new Listener(ids[1], "Bram", false, Set.of(), "oak", "", "", false, null, null));
        var known = web.get().about(bram, dev.kindred.core.knowledge.WebFeed.entityOf(ids[0], false), KnowledgeWeb.Verb.NEEDS, f.source.getServer().getTicks());
        return known.size() == 1 && known.getFirst().object().equals("iron");
    }
}
