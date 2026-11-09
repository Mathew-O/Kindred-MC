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
 * from the Record alone and says the same.
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

    private static boolean needs(NativePaidSkillsGameTests.Fixture f, UUID[] ids) {
        var web = f.runtime.webs().existing(ids[1]);
        if (web.isEmpty()) return false;
        var bram = KnowledgeWeb.Viewer.of(new Listener(ids[1], "Bram", false, Set.of(), "oak", "", "", false, null, null));
        var known = web.get().about(bram, dev.kindred.core.knowledge.WebFeed.entityOf(ids[0], false), KnowledgeWeb.Verb.NEEDS, f.source.getServer().getTicks());
        return known.size() == 1 && known.getFirst().object().equals("iron");
    }
}
