package dev.kindred.fabric;

import dev.kindred.core.Actor;
import dev.kindred.core.social.PeerLedger;
import dev.kindred.core.survival.SurvivalGoal;
import dev.kindred.persistence.StoredNativePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * In plain terms: a GameTest: in a real test world, what an AI player knows of the others. Sol, on its way to the End
 * fight without a bow, needs string and feathers; Echo comes to know it through the team with nothing said by either, and
 * asked "what does Sol need?" answers from that, and its voice is told it too. A person's "I'm off to the Nether" is kept
 * as what they said, never as a fact, and "forget that" takes it back for good, with who said it kept. And what Echo knows
 * is there after the server restarts, apart from what was said.
 *
 * Technical: E-50 through the whole runtime: the ledger fed by {@link PeerRadio} (sightings, teammates' updates, claims
 * heard), read by the fixed replies and the voice (a stub stands in for the model, as in {@link NativeVoiceGameTests}),
 * kept in {@code <uuid>.peers.json}. The fixtures stage what they need and say so: Sol's kit and a lit portal, as the
 * follow-up fixtures stage them, so that its End-fight needs are its own planner's.
 */
public final class NativePeerLedgerGameTests implements FabricGameTest {
    /** Both of the owner's players made and established: Echo first (the Talk fixture's), then Sol. */
    private static final class Two {
        final NativeVoiceGameTests.Talk t;
        UUID echo, sol;
        ServerPlayerEntity echoBody, solBody;
        ServerCommandSource owner;
        boolean solMade;

        Two(TestContext context, boolean edits) {
            t = new NativeVoiceGameTests.Talk(context, edits);
        }

        void makeSol(TestContext context) {
            if (!solMade) {
                owner = t.owner.getCommandSource().withWorld(context.getWorld()).withPosition(context.getAbsolute(new Vec3d(1.5, 2, 6.5)));
                t.f.runtime.createNative(owner, "Sol");
                solMade = true;
            }
        }

        void established(TestContext context) {
            var statuses = t.f.runtime.nativeStatuses(Actor.console());
            context.assertTrue(statuses.size() == 2 && statuses.stream().allMatch(s -> s.connected()
                    && s.phase() == StoredNativePlayer.Phase.ESTABLISHED), "Both established: " + statuses);
            for (var s : statuses) {
                var body = context.getWorld().getServer().getPlayerManager().getPlayer(s.nativeId());
                if (s.name().equals("Echo")) { echo = s.id(); echoBody = body; }
                if (s.name().equals("Sol")) { sol = s.id(); solBody = body; }
            }
            context.assertTrue(echo != null && sol != null && echoBody != null && solBody != null, "Both found");
        }

        PeerLedger echoKnows(TestContext context) {
            var ledger = t.f.runtime.peersForFixtures(echo);
            context.assertTrue(ledger != null, "Echo's ledger read");
            return ledger;
        }

        static void place(ServerPlayerEntity body, TestContext context, Vec3d at, float yaw) {
            Vec3d stand = context.getAbsolute(at);
            body.refreshPositionAndAngles(stand.x, stand.y, stand.z, yaw, 0);
        }
    }

    /**
     * Staged: a lit portal, Sol stocked for the Nether as the follow-up fixtures stock it (sticks, a flint, no string, no
     * feathers, no bow), on the End goal. Its own planner wants string and feathers for the End fight's bow and arrows;
     * Echo's ledger has that from Sol's update, by Sol, with no line said to Echo or by Sol about it. Its owner asks Echo
     * "what does Sol need?": the voice is asked and told what the ledger says (E-50a, the owner's decision of 2 November);
     * when it gives no line, the ledger's own words are said; when it does, its line is. Asked something open, the voice is
     * told it too.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peers", tickLimit = 1600)
    public void aTeammatesNeedsReachItsLedgerWithNothingSaidAndItAnswersFromThem(TestContext context) {
        var two = new Two(context, true);
        var t = two.t;
        var f = t.f;
        boolean[] done = new boolean[4];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> two.makeSol(context))
                .createAndAdd(() -> two.established(context))
                // Sol's remarks off, so that it says nothing first (E-53 lets a player ask people for what it needs): the
                // team's channel alone is tested, and "nothing said" below holds for every line.
                .createAndAdd(() -> context.assertTrue(f.runtime.socialSetting(two.owner, two.sol, "ambient", false, null, 0) == 1, "Sol's remarks off"))
                .createAndAdd(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    NativeStrongholdGameTests.litPortal(context);
                    context.getWorld().setTimeOfDay(1000);
                    Two.place(two.solBody, context, new Vec3d(1.5, 2, 6.5), 180);
                    Two.place(two.echoBody, context, new Vec3d(6.5, 2, 6.5), 270);
                    var inventory = two.solBody.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_SWORD));
                    inventory.setStack(1, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(2, new ItemStack(Items.STONE_PICKAXE));
                    inventory.setStack(3, new ItemStack(Items.BREAD, 16));
                    inventory.setStack(4, new ItemStack(Items.COBBLESTONE, 64));
                    inventory.setStack(5, new ItemStack(Items.STICK, 4));
                    inventory.setStack(6, new ItemStack(Items.FLINT, 1));
                    t.listen();
                    t.stub.answer = conversation -> NativeVoiceGameTests.Stub.says("Everyone's busy.");
                    context.assertTrue(f.runtime.startGoal(two.owner, two.sol, SurvivalGoal.STRONGHOLD) == 1, "Sol's goal accepted");
                })
                .createAndAdd(() -> {
                    var needs = two.echoKnows(context).best(two.sol, PeerLedger.Topic.NEEDS, context.getWorld().getServer().getTicks());
                    context.assertTrue(needs.isPresent() && needs.get().text().contains("string") && needs.get().text().contains("feather"),
                            "Sol's End-fight needs in Echo's ledger: " + needs);
                    context.assertTrue(needs.get().source() == PeerLedger.Source.TEAM && two.sol.equals(needs.get().by()), "From Sol's own update: " + needs.get());
                    context.assertTrue(t.heard.stream().noneMatch(h -> h.to().equals(two.echoBody.getUuid())), "Nothing said to Echo: " + t.heard);
                    context.assertTrue(t.heard.stream().noneMatch(h -> h.text().contains("string") || h.text().contains("feather")), "Nobody said it: " + t.heard);
                })
                .createAndAdd(() -> {
                    if (!done[1]) {
                        t.stub.answer = conversation -> NativeVoiceGameTests.Stub.fails("a fixture's spent budget");
                        f.runtime.chat(t.owner.getUuid(), "Echo, what does Sol need?");
                        done[1] = true;
                    }
                    context.assertTrue(t.stub.asked.size() == 1, "The voice asked to phrase it: " + t.stub.asked.size());
                    String told = t.stub.asked.getFirst().text();
                    context.assertTrue(told.contains("You know of others: Sol needs ") && told.contains("string"), "The voice is told the ledger: " + told);
                    var answer = t.heardBy(t.owner).stream().filter(h -> h.text().startsWith("[Kindred] Echo: Sol needs")).findFirst();
                    context.assertTrue(answer.isPresent() && answer.get().text().contains("string") && answer.get().text().contains("Sol's own update"),
                            "No line from the voice: the ledger's own words: " + t.heardBy(t.owner));
                })
                .createAndAdd(() -> {
                    if (!done[2]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100); // The lane's three seconds between one speaker's replies.
                        t.stub.answer = conversation -> NativeVoiceGameTests.Stub.says("Sol's after string and feathers for a bow.");
                        f.runtime.chat(t.owner.getUuid(), "Echo, what does Sol need?");
                        done[2] = true;
                    }
                    context.assertTrue(t.stub.asked.size() == 2, "The voice asked again: " + t.stub.asked.size());
                    context.assertTrue(t.heardBy(t.owner).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: Sol's after string and feathers for a bow.")),
                            "The voice's line, phrased from the ledger: " + t.heardBy(t.owner));
                })
                .createAndAdd(() -> {
                    if (!done[3]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(t.owner.getUuid(), "Echo, how is everyone getting on?");
                        done[3] = true;
                    }
                    context.assertTrue(t.stub.asked.size() == 3, "The voice asked a third time: " + t.stub.asked.size());
                    String told = t.stub.asked.get(2).text();
                    context.assertTrue(told.contains("You know of others: Sol needs ") && told.contains("string"), "The voice is told: " + told);
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * F-34c: a teammate's update travels on the Wire. Sol of oak and Echo of birch: no update of Sol's reaches Echo, through
     * all the seconds it is sent; both of oak, Echo has Sol's update from the team, by Sol. Never in Echo's inbox, never
     * a word to anyone: the receiver's ledger alone keeps it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peers-wire", tickLimit = 1600)
    public void aTeammatesUpdateTravelsOnTheWireAndCrossesNoFaction(TestContext context) {
        var two = new Two(context, false);
        var t = two.t;
        var f = t.f;
        long[] at = new long[3];
        String[] oakOf = {""};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> two.makeSol(context))
                .createAndAdd(() -> two.established(context))
                .createAndAdd(() -> {
                    if (at[0] != 0) return;
                    t.listen();
                    // Twenty blocks apart, out of each other's sight: what Echo knows of Sol can come only through the team.
                    Two.place(two.solBody, context, new Vec3d(1.5, 2, 1.5), 180);
                    Two.place(two.echoBody, context, new Vec3d(1.5, 2, 21.5), 0);
                    f.runtime.setFactionOf(id -> id.equals(two.sol) ? "oak" : id.equals(two.echo) ? oakOf[0].isEmpty() ? "birch" : "oak" : "");
                    at[0] = context.getWorld().getServer().getTicks();
                    at[1] = f.runtime.wire().stateTold();
                })
                .createAndAdd(() -> {
                    long now = context.getWorld().getServer().getTicks();
                    context.assertTrue(now - at[0] >= 3 * PeerRadio.UPDATE_TICKS, "Three updates' worth of seconds: " + (now - at[0]));
                    var doing = two.echoKnows(context).best(two.sol, PeerLedger.Topic.DOING, now);
                    context.assertTrue(doing.isEmpty() || doing.get().source() != PeerLedger.Source.TEAM || doing.get().at() < at[0],
                            "Nothing of Sol's, of oak, reaches Echo, of birch: " + doing);
                })
                .createAndAdd(() -> {
                    if (at[2] == 0) {
                        oakOf[0] = "oak";
                        at[2] = context.getWorld().getServer().getTicks();
                    }
                    long now = context.getWorld().getServer().getTicks();
                    var doing = two.echoKnows(context).best(two.sol, PeerLedger.Topic.DOING, now);
                    context.assertTrue(doing.isPresent() && doing.get().source() == PeerLedger.Source.TEAM && two.sol.equals(doing.get().by())
                            && doing.get().at() >= at[2], "Both of oak: Sol's update in Echo's ledger, from the team: " + doing);
                    context.assertTrue(f.runtime.wire().stateTold() > at[1], "Told through the Wire: " + f.runtime.wire().stateTold());
                    context.assertTrue(f.runtime.wire().inbox(two.echo, dev.kindred.core.wire.TeamState.KIND).isEmpty(), "Never in Echo's inbox");
                    context.assertTrue(t.heard.stream().noneMatch(h -> h.to().equals(two.echoBody.getUuid()) || h.to().equals(two.solBody.getUuid())), "No word to either: " + t.heard);
                })
                .createAndAdd(() -> {
                    f.runtime.setFactionOf(id -> "");
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Its owner tells Echo "I'm off to the Nether": a claim, by them, never a fact, and the voice is told it only as what
     * they said. "Forget that" takes it back, answered in Echo's own words without the model; the voice is not told it
     * again; and who said it and when are kept with the correction.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peers", tickLimit = 1600)
    public void aPersonsClaimStaysAClaimAndForgetThatTakesItBack(TestContext context) {
        var t = new NativeVoiceGameTests.Talk(context, false);
        var f = t.f;
        boolean[] done = new boolean[4];
        long[] said = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.peersForFixtures(f.id) != null, "Echo's ledger read"))
                .createAndAdd(() -> {
                    if (!done[0]) {
                        t.listen();
                        t.stub.answer = conversation -> NativeVoiceGameTests.Stub.says("Have fun.");
                        f.runtime.chat(t.owner.getUuid(), "Echo, I'm off to the Nether!");
                        said[0] = context.getWorld().getServer().getTicks();
                        done[0] = true;
                    }
                    var ledger = f.runtime.peersForFixtures(f.id);
                    long now = context.getWorld().getServer().getTicks();
                    var claims = ledger.claims(t.owner.getUuid(), PeerLedger.Topic.DOING, now);
                    context.assertTrue(claims.size() == 1 && claims.getFirst().text().equals("going to the nether")
                            && t.owner.getUuid().equals(claims.getFirst().by()), "Kept as their claim: " + claims);
                    context.assertTrue(ledger.best(t.owner.getUuid(), PeerLedger.Topic.DOING, now).isEmpty(), "Not a fact");
                    context.assertTrue(!t.heardBy(t.owner).isEmpty(), "Answered");
                })
                .createAndAdd(() -> {
                    if (!done[1]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(t.owner.getUuid(), "Echo, nice day for it");
                        done[1] = true;
                    }
                    context.assertTrue(t.stub.asked.size() == 2, "The voice asked twice: " + t.stub.asked.size());
                    String told = t.stub.asked.get(1).text();
                    context.assertTrue(told.contains("said, just now, that they were going to the nether (said, not seen)"), "Told as said, not seen: " + told);
                    context.assertTrue(!told.contains("is going to the nether"), "Never as a fact: " + told);
                })
                .createAndAdd(() -> {
                    if (!done[2]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(t.owner.getUuid(), "Echo, forget that");
                        done[2] = true;
                    }
                    context.assertTrue(t.heardBy(t.owner).stream().anyMatch(h -> h.text().equals("[Kindred] Echo: All right, I've forgotten it.")),
                            "Taken back, in its own words: " + t.heardBy(t.owner));
                    context.assertTrue(t.stub.asked.size() == 2, "Without the model");
                    var kept = f.runtime.peersForFixtures(f.id).claimsKept(t.owner.getUuid());
                    context.assertTrue(kept.size() == 1 && kept.getFirst().correction() == PeerLedger.Correction.FORGOTTEN
                            && t.owner.getUuid().equals(kept.getFirst().by()) && kept.getFirst().at() == said[0]
                            && t.owner.getUuid().equals(kept.getFirst().correctedBy()), "Corrected, who said it and when kept: " + kept);
                })
                .createAndAdd(() -> {
                    if (!done[3]) {
                        NativeVoiceGameTests.Talk.waitFor(3_100);
                        f.runtime.chat(t.owner.getUuid(), "Echo, see you later");
                        done[3] = true;
                    }
                    context.assertTrue(t.stub.asked.size() == 3, "The voice asked again: " + t.stub.asked.size());
                    context.assertTrue(!t.stub.asked.get(2).text().contains("nether"), "No longer told: " + t.stub.asked.get(2).text());
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }

    /**
     * Echo knows Sol (from Sol's updates) and its owner (seen), and the owner has said "I need iron". The runtime stops and
     * a new one starts on the same save, with Sol left out of the world: Echo still knows Sol's note from before the stop,
     * and its owner, from its file; what was said is not kept. An operator's {@code memory forget} of Sol removes Sol.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "peers", tickLimit = 1600)
    public void whatItKnowsOfTheOthersSurvivesARestartButNothingSaid(TestContext context) {
        var two = new Two(context, false);
        var t = two.t;
        var f = t.f;
        boolean[] done = new boolean[2];
        long[] stopped = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Runtime ready"))
                .createAndAdd(() -> t.createOwned(context))
                .createAndAdd(() -> two.makeSol(context))
                .createAndAdd(() -> two.established(context))
                .createAndAdd(() -> two.echoKnows(context)) // Read from its file (none yet) before anything is said to it.
                .createAndAdd(() -> {
                    if (!done[0]) {
                        Two.place(two.solBody, context, new Vec3d(1.5, 2, 3.5), 90);
                        Two.place(two.echoBody, context, new Vec3d(4.5, 2, 3.5), 270);
                        f.runtime.chat(t.owner.getUuid(), "Echo, I need iron");
                        done[0] = true;
                    }
                    var ledger = two.echoKnows(context);
                    long now = context.getWorld().getServer().getTicks();
                    context.assertTrue(ledger.best(two.sol, PeerLedger.Topic.DOING, now).isPresent(), "Sol's update in");
                    context.assertTrue(ledger.seen(t.owner.getUuid()).isPresent(), "Its owner seen");
                    context.assertTrue(ledger.claimsKept(t.owner.getUuid()).size() == 1, "The owner's claim noted");
                })
                .createAndAdd(() -> {
                    stopped[0] = context.getWorld().getServer().getTicks();
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Stopped"))
                .createAndAdd(() -> context.assertTrue(java.nio.file.Files.isRegularFile(f.directory.resolve("memory").resolve(two.echo + ".peers.json")),
                        "Kept in its file"))
                .createAndAdd(() -> {
                    f.runtime = new PlayerRuntime(context.getWorld().getServer(), f.directory);
                    f.runtime.start();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Started again on the same save"))
                .createAndAdd(() -> f.runtime.nativeLifecycle(f.source, "connect", two.echo))
                .createAndAdd(() -> context.assertTrue(f.runtime.nativeStatuses(Actor.console()).stream()
                        .anyMatch(s -> s.id().equals(two.echo) && s.connected()), "Echo connected; Sol left out"))
                .createAndAdd(() -> {
                    var ledger = f.runtime.peersForFixtures(two.echo);
                    context.assertTrue(ledger != null, "Echo's ledger read from its file");
                    long now = context.getWorld().getServer().getTicks();
                    var doing = ledger.best(two.sol, PeerLedger.Topic.DOING, now);
                    context.assertTrue(doing.isPresent() && doing.get().at() <= stopped[0] && doing.get().source() == PeerLedger.Source.TEAM,
                            "Sol's note from before the stop (tick " + stopped[0] + "): " + doing);
                    context.assertTrue(ledger.name(two.sol).orElse("").equals("Sol"), "Known by name");
                    context.assertTrue(ledger.knows(t.owner.getUuid()), "Its owner known");
                    context.assertTrue(ledger.claimsKept(t.owner.getUuid()).isEmpty(), "Nothing said kept");
                })
                .createAndAdd(() -> {
                    if (!done[1]) {
                        context.assertTrue(f.runtime.forgetMemory(f.source, two.echo, two.sol) == 1, "An operator's memory forget");
                        done[1] = true;
                    }
                    context.assertTrue(!f.runtime.peersForFixtures(two.echo).knows(two.sol), "Sol removed");
                    t.cleanup(context);
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
