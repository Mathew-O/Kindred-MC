package dev.kindred.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A waking kept as a corpus scene (F-27a4): the scene round where a player wakes after a death and, within reach, where it
 * died and what it dropped there. The corpus's scenes were cut at deaths and stuck spells only: e40a23's 101 Sol woke 9
 * times at its bed at −376, 31, −145 and died each time in the caves round it, and none of #370's 10 scenes holds the bed,
 * the waking spot or its drops, so nothing it does on waking could be measured.
 */
public final class ReplayWakingGameTests implements FabricGameTest {

    /**
     * In plain terms: the box kept round a player's waking takes in where it died and what it dropped when that is near
     * enough, and says so when it is not.
     *
     * Technical: {@link DeathReplays#wakeBox} on e40a23's 101 Sol. Passes if its first waking (at −376, 31, −145, its death at
     * −372, 26, −192, 47 blocks off in z) is one box of at most 64 across with both inside, a near death (−372, 33, −154) is
     * inside at the usual 16 round, a death 57 blocks off (−402, 37, −88) is outside with the usual box round the waking, and
     * no death is the usual box.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "wakingBox", tickLimit = 20)
    public void aWakingsBoxHoldsTheDeathItFollowsWhenNearAndSaysWhenNot(TestContext context) {
        BlockPos woke = new BlockPos(-376, 31, -145);
        java.util.function.BiPredicate<DeathReplays.WakeBox, BlockPos> holds = (box, at) -> {
            for (int axis = 0; axis < 3; axis++) {
                int v = axis == 0 ? at.getX() : axis == 1 ? at.getY() : at.getZ();
                int o = axis == 0 ? box.origin().getX() : axis == 1 ? box.origin().getY() : box.origin().getZ();
                if (v < o || v >= o + box.size()[axis]) return false;
            }
            return true;
        };
        var first = DeathReplays.wakeBox(woke, new BlockPos(-372, 26, -192));
        context.assertTrue(first.deathInside() && holds.test(first, woke) && holds.test(first, new BlockPos(-372, 26, -192)),
                "Sol's first waking and its death not both in its box: " + first.origin() + " " + java.util.Arrays.toString(first.size()));
        context.assertTrue(first.size()[0] <= DeathReplays.WAKE_MAX && first.size()[2] <= DeathReplays.WAKE_MAX, "Wider than " + DeathReplays.WAKE_MAX + ": "
                + java.util.Arrays.toString(first.size()));
        var near = DeathReplays.wakeBox(woke, new BlockPos(-372, 33, -154));
        context.assertTrue(near.deathInside() && holds.test(near, new BlockPos(-372, 33, -154)) && holds.test(near, woke), "A near death not in its box");
        var far = DeathReplays.wakeBox(woke, new BlockPos(-402, 37, -88));
        context.assertTrue(!far.deathInside() && holds.test(far, woke) && far.size()[0] == DeathReplays.WIDTH, "A far death not left outside: "
                + java.util.Arrays.toString(far.size()));
        var none = DeathReplays.wakeBox(woke, null);
        context.assertTrue(!none.deathInside() && none.size()[0] == DeathReplays.WIDTH && none.size()[1] == DeathReplays.HEIGHT, "No death: not the usual box");
        context.complete();
    }

    /**
     * In plain terms: when an AI player wakes after dying, the scene kept of it holds where it woke and what it dropped
     * where it died, each item as it lies with how long it has lain, so the corpus can play its waking again.
     *
     * Technical: F-27a4, through the runtime's own respawn. STAGED: a native player with a stone pickaxe in hand and an
     * iron chestplate on, its spawn point twenty blocks east in this test's own ground; replays on; killed by the fixture,
     * its things dropped by vanilla where it died. Passes if the latest capture is a {@code "respawn"} scene round where it
     * woke, its death inside its box and recorded, its drops listed (the pickaxe and the chestplate, each with its age), its
     * budget, pass and reported check written, and its structure the size its record says with the item entities in it.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "wakingCapture", tickLimit = 600)
    public void aWakingIsKeptWithWhereItDiedAndWhatItDropped(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        var replays = new DeathReplays(f.directory.resolve("replays"), null);
        net.minecraft.server.network.ServerPlayerEntity[] dead = {null};
        BlockPos[] spawn = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Woken"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.player().isOnGround(), "Standing"))
                .createAndAdd(() -> {
                    var player = f.player();
                    var inventory = player.getInventory();
                    inventory.clear();
                    inventory.setStack(0, new ItemStack(Items.STONE_PICKAXE));
                    inventory.selectedSlot = 0;
                    player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    spawn[0] = player.getBlockPos().east(20);
                    context.getWorld().setBlockState(spawn[0].down(), net.minecraft.block.Blocks.STONE.getDefaultState());
                    context.getWorld().setBlockState(spawn[0], net.minecraft.block.Blocks.AIR.getDefaultState());
                    context.getWorld().setBlockState(spawn[0].up(), net.minecraft.block.Blocks.AIR.getDefaultState());
                    player.setSpawnPoint(context.getWorld().getRegistryKey(), spawn[0], 0, true, false);
                    f.runtime.captureReplays(replays);
                    replays.note("Woken", player, context.getWorld().getServer().getTicks());
                    dead[0] = player;
                    player.kill();
                    context.assertFalse(player.isAlive(), "Killed");
                })
                .createAndAdd(() -> {
                    var fresh = f.player();
                    context.assertTrue(fresh != null && fresh != dead[0] && fresh.isAlive(), "Respawned");
                    var capture = replays.latestForTest();
                    context.assertTrue(capture.isPresent() && capture.get().folder().getFileName().toString().startsWith("Woken-"), "No capture");
                    double cpu = capture.get().cpuNanosOnThread() / 1e6;
                    context.assertTrue(cpu <= 50, "The waking's capture took " + cpu + " ms of the server thread's time, more than a tick");
                    try {
                        capture.get().written().get(10, TimeUnit.SECONDS);
                    } catch (Exception late) {
                        throw new net.minecraft.test.GameTestException("The replay's files not written: " + late);
                    }
                    Path folder = capture.get().folder();
                    try {
                        JsonObject state = JsonParser.parseString(Files.readString(folder.resolve("replay.json"))).getAsJsonObject();
                        context.assertTrue("respawn".equals(state.get("kind").getAsString()), "Its kind: " + state.get("kind"));
                        context.assertTrue(state.get("deathInside").getAsBoolean(), "Its death not inside its box: " + state);
                        context.assertTrue(state.has("death") && state.getAsJsonArray("death").size() == 3, "Its death not recorded: " + state.get("death"));
                        context.assertTrue(state.get("cause").getAsString().startsWith("respawned after its death at"), "Its cause: " + state.get("cause"));
                        context.assertTrue(state.get("ticks").getAsInt() == DeathReplays.WAKE_TICKS && DeathReplays.WAKE_PASS.equals(state.get("pass").getAsString())
                                && DeathReplays.WAKE_REPORTED.equals(state.get("reported").getAsString()), "Its budget, pass and report: " + state);
                        String drops = state.getAsJsonArray("drops").toString();
                        context.assertTrue(drops.contains("minecraft:stone_pickaxe") && drops.contains("minecraft:iron_chestplate") && drops.contains("\"age\""),
                                "Its drops: " + drops);
                        var size = state.getAsJsonArray("size");
                        var template = new StructureTemplate();
                        var nbt = NbtHelper.fromNbtProviderString(Files.readString(folder.resolve("replay.snbt")));
                        template.readNbt(context.getWorld().getRegistryManager().getWrapperOrThrow(RegistryKeys.BLOCK), nbt);
                        context.assertTrue(template.getSize().equals(new Vec3i(size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt())),
                                "Its structure's size " + template.getSize() + ", its record's " + size);
                        context.assertTrue(nbt.getList("entities", 10).toString().contains("minecraft:item"), "Its drops not in its structure");
                        KindredMod.LOGGER.info("Waking capture fixture: size {}, its death at {}, {} ms of the server thread's time, drops {}", size, state.get("death"),
                                String.format(java.util.Locale.ROOT, "%.1f", cpu), drops);
                    } catch (java.io.IOException | com.mojang.brigadier.exceptions.CommandSyntaxException unreadable) {
                        throw new net.minecraft.test.GameTestException("The replay does not read back: " + unreadable);
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }

    /**
     * In plain terms: a waking played again from the corpus knows where it died, as the player knew it in the run, and
     * nothing more.
     *
     * Technical: F-27a5, {@link ReplayGameTests#tellOfItsDeath}. STAGED: a native player on the Nether portal goal; a
     * waking's record made here, its death inside its box, 20 ticks before, by a zombie. Passes if its campaign's last death
     * is that death at its place in the staged scene, with its cause; a record whose death lies outside its box tells it
     * nothing; and the cause's words are read as a mob's kill or not.
     */
    @GameTest(templateName = FixtureGround.FIELD, batchId = "wakingDeath", tickLimit = 400)
    public void aWakingsCampaignIsToldOfTheDeathItRecords(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true);
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> f.runtime.createNative(f.source, "Told"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> context.assertTrue(f.runtime.startGoal(f.source, f.id, dev.kindred.core.survival.SurvivalGoal.NETHER_PORTAL) == 1, "Goal accepted"))
                .createAndAdd(() -> {
                    var campaign = f.runtime.campaignForTest(f.id);
                    context.assertTrue(campaign != null, "No campaign");
                    BlockPos staged = context.getAbsolutePos(new BlockPos(2, 2, 2));
                    var outside = JsonParser.parseString("{\"deathInside\":false,\"death\":[110,60,210],\"origin\":[100,50,200],"
                            + "\"cause\":\"respawned after its death at 110, 60, 210 (Told was slain by Zombie), the death outside its box\"}").getAsJsonObject();
                    ReplayGameTests.tellOfItsDeath(f.runtime, f.id, outside, staged, context.getWorld(), context.getWorld().getServer().getTicks(), 1, "Told");
                    context.assertTrue(campaign.lastDeath().isEmpty(), "Told of a death outside its box: " + campaign.lastDeath());
                    var inside = JsonParser.parseString("{\"deathInside\":true,\"death\":[110,60,210],\"origin\":[100,50,200],\"deathAgo\":20,"
                            + "\"deathByMob\":true,\"cause\":\"respawned after its death at 110, 60, 210 (Told was slain by Zombie)\"}").getAsJsonObject();
                    ReplayGameTests.tellOfItsDeath(f.runtime, f.id, inside, staged, context.getWorld(), context.getWorld().getServer().getTicks(), 1, "Told");
                    var death = campaign.lastDeath();
                    context.assertTrue(death.isPresent() && death.get().pos().equals(staged.add(10, 10, 10)), "Its death not where the scene put it: " + death);
                    context.assertTrue(death.get().cause().equals("Told was slain by Zombie"), "Its cause: " + death.get().cause());
                    context.assertTrue(ReplayGameTests.byAMob("Sol was slain by Zombie") && ReplayGameTests.byAMob("Sol was killed by Witch using magic")
                            && !ReplayGameTests.byAMob("Sol fell from a high place") && !ReplayGameTests.byAMob("Sol drowned"), "A mob's kill misread");
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> FixtureGround.restore(context))
                .completeIfSuccessful();
    }
}
