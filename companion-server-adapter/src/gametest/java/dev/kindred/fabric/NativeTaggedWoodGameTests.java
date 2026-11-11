package dev.kindred.fabric;

import dev.kindred.core.Capabilities;
import dev.kindred.core.construction.BuildStyle;
import dev.kindred.core.construction.Generators;
import dev.kindred.core.construction.Woods;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.AfterBatch;
import net.minecraft.test.BeforeBatch;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

/**
 * F-38: a wood the game is told about by data alone, with no code change, is gathered, made into planks and built
 * with. The fixture's datapack is STAGED: the fixture writes it into the test world's datapacks folder and reloads, and
 * removes it and reloads again at the end. A datapack cannot add blocks, so its "wood" is made of vanilla blocks that
 * no code calls wood: {@code minecraft:shroomlight} tagged into {@code #minecraft:logs}, {@code minecraft:bamboo_mosaic}
 * tagged into {@code #minecraft:planks}, and a recipe that makes eight of the one from two of the other. (Shroomlight:
 * it drops itself to a hand and no vanilla recipe takes it, so only the staged recipe can make anything of it; a bamboo
 * block has vanilla's own bamboo planks, hay its wheat.) The player starts with nothing of either, only bread and a
 * wooden axe (a tool, as other gather fixtures give): resumed, its own decision loop cuts the six logs from its work
 * patch and crafts planks of them by the staged recipe, nothing asked (on 10 November it made eight and a crafting
 * table of four of them). It is then quieted to observing, so its progression spends no more; the logs it left are
 * planked when asked; and in its work area, where the logs stood, it builds a wall of six whose material is the
 * planks of the wood it holds, found by the server's tags and recipes ({@link KindredCapabilities}, {@link Woods}).
 */
public final class NativeTaggedWoodGameTests implements FabricGameTest {

    static final String PACK = "kindred-f38-tagged-wood";
    static final String LOG = "minecraft:shroomlight";
    static final String PLANKS = "minecraft:bamboo_mosaic";
    static final String RECIPE = "kindred_f38:bamboo_mosaic_from_shroomlight";

    /** A pack left by an earlier run that stopped mid-fixture is taken away before this one stages its own. */
    @BeforeBatch(batchId = "taggedWood")
    public void noPackLeftFromBefore(ServerWorld world) {
        if (unstage(world.getServer())) world.getServer().getCommandManager().executeWithPrefix(world.getServer().getCommandSource(), "reload");
    }

    /** Runs when the batch ends however its test ended, so no later test sees the staged wood. */
    @AfterBatch(batchId = "taggedWood")
    public void packTakenAway(ServerWorld world) {
        if (unstage(world.getServer())) world.getServer().getCommandManager().executeWithPrefix(world.getServer().getCommandSource(), "reload");
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "taggedWood", tickLimit = 9000)
    public void aWoodTaggedByADatapackIsGatheredPlankedAndBuiltWith(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context, writer -> writer, true, 8500);
        MinecraftServer server = context.getWorld().getServer();
        BlockPos base = context.getAbsolutePos(new BlockPos(6, 2, 6));
        BlockPos[] origin = {null};
        String[] wood = {null};
        boolean[] reloaded = {false};
        int[] held = {0};
        String[] seen = {null};
        dev.kindred.core.construction.Blueprint[] plan = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    stage(server);
                    server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
                })
                .createAndAdd(() -> context.assertTrue(Capabilities.current().wood(PLANKS).isPresent(),
                        "The staged wood known after the reload: " + Capabilities.current().woods().stream().map(Capabilities.Wood::name).toList()))
                .createAndAdd(() -> {
                    var w = Capabilities.current().wood(PLANKS).orElseThrow();
                    context.assertTrue(w.log().equals(LOG), "Its log read from the staged recipe: " + w);
                })
                .createAndAdd(() -> f.runtime.createNative(f.source, "Joiner"))
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    BlockPos o = context.getAbsolutePos(BlockPos.ORIGIN);
                    for (int x = -6; x <= 14; x++) for (int z = -6; z <= 14; z++) {
                        world.setBlockState(o.add(x, 1, z), Blocks.STONE.getDefaultState());
                        for (int y = 2; y <= 8; y++) world.setBlockState(o.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) world.setBlockState(base.add(x, y, 0), Blocks.SHROOMLIGHT.getDefaultState());
                    var player = f.player();
                    player.getInventory().clear();
                    player.getInventory().setStack(8, new ItemStack(Items.BREAD, 8));
                    player.setHealth(20);
                    player.getHungerManager().setFoodLevel(20);
                    f.runtime.nativePatch(f.source, f.id, base, base.add(1, 2, 0), LOG);
                })
                .createAndAdd(() -> context.assertTrue(!f.runtime.nativeSetupStatus(dev.kindred.core.Actor.console(), f.id).pending(), "Patch acknowledged"))
                // Resumed, its own decision loop cuts the patch (a log by the server's #minecraft:logs) and makes
                // planks of what it cut (planks by #minecraft:planks, the recipe the staged pack gave).
                .createAndAdd(() -> {
                    f.player().getInventory().setStack(0, new ItemStack(Items.WOODEN_AXE));
                    f.runtime.nativeLifecycle(f.source, "resume", f.id);
                })
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int standing = 0;
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) if (world.getBlockState(base.add(x, y, 0)).isOf(Blocks.SHROOMLIGHT)) standing++;
                    int planks = count(f, Items.BAMBOO_MOSAIC);
                    log(f, seen, standing);
                    context.assertTrue(standing == 0 && planks > 0,
                            "The tagged logs cut, and planks made of them, by its own loop: " + standing + " standing; held logs "
                                    + count(f, Items.SHROOMLIGHT) + ", planks " + planks + "; "
                                    + f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id).work());
                    // Its own loop goes on to spend planks on its progression (a table, sticks); quieted now, so the
                    // rest are planked and built with as asked.
                    context.assertTrue(f.runtime.setPlaystyleMode(f.source, f.id, dev.kindred.core.progression.PlaystyleProfile.IDLE_OBSERVING) == 1,
                            "Quieted to observing");
                })
                .createAndAdd(() -> {
                    log(f, seen, 0);
                    if (count(f, Items.SHROOMLIGHT) >= 2 && idle(f) && server.getTicks() % 10 == 0) f.runtime.nativeCraft(f.source, f.id, RECIPE);
                    context.assertTrue(count(f, Items.SHROOMLIGHT) < 2 && count(f, Items.BAMBOO_MOSAIC) >= 6 && idle(f),
                            "The rest planked by the staged recipe: planks " + count(f, Items.BAMBOO_MOSAIC) + ", logs left " + count(f, Items.SHROOMLIGHT));
                })
                .createAndAdd(() -> {
                    int planks = count(f, Items.BAMBOO_MOSAIC);
                    // The build's wood is the wood it holds, read off its planks by the server's tags and recipes.
                    var style = BuildStyle.grown(PLANKS, null, 0L);
                    wood[0] = style.wood();
                    context.assertTrue(PLANKS.equals(wood[0]), "The style's wood is the held wood: " + wood[0]);
                    // A wall of the held wood's planks, two wide and three high, where the logs stood: its work area.
                    var wall = new java.util.ArrayList<dev.kindred.core.construction.Blueprint.Block>();
                    var plank = dev.kindred.core.construction.BlockSpec.of(Woods.planks(wood[0]));
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++) {
                        wall.add(new dev.kindred.core.construction.Blueprint.Block(new dev.kindred.core.construction.Pos(x, y, 0), plank));
                    }
                    var floorPlan = new dev.kindred.core.construction.Blueprint(style.wood() + " wall", Generators.SOURCE + "wall?wood=" + style.wood(),
                            "Kindred", "Kindred's own", wall);
                    origin[0] = base;
                    held[0] = planks;
                    KindredMod.LOGGER.info("Joiner: patch cut and planked by its own loop; {} planks, {} logs left; building", planks,
                            count(f, Items.SHROOMLIGHT));
                    // Started the tick it holds enough, before its own progression spends them; the build holds the body.
                    plan[0] = floorPlan;
                    context.assertTrue(f.runtime.startBuild(f.source, f.id, floorPlan, origin[0], 0) == 1, "Build started");
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.buildStatus(f.id).isEmpty(),
                        "Built: " + f.runtime.buildStatus(f.id).orElse("") + " / " + f.runtime.nativeLastResult(f.id)))
                .createAndAdd(() -> {
                    var world = context.getWorld();
                    int placed = 0;
                    for (var b : plan[0].placed(BuildRunner.pos(origin[0]), 0)) {
                        if (world.getBlockState(BuildRunner.blockPos(b.at())).isOf(Blocks.BAMBOO_MOSAIC)) placed++;
                    }
                    context.assertTrue(placed == 6, "The wall's six blocks are the tagged wood's planks: " + placed);
                    context.assertTrue(count(f, Items.BAMBOO_MOSAIC) + placed <= held[0],
                            "Its own planks went into it, none from nowhere: held " + held[0] + ", left " + count(f, Items.BAMBOO_MOSAIC));
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .createAndAdd(() -> {
                    if (!reloaded[0]) {
                        if (unstage(server)) server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
                        reloaded[0] = true;
                    }
                })
                .createAndAdd(() -> context.assertTrue(Capabilities.current().wood(PLANKS).isEmpty(), "The staged wood gone after the pack is removed"))
                .completeIfSuccessful();
    }

    /** Logs the player's holding whenever it changes, so a failure shows what it did. */
    private static void log(NativePaidSkillsGameTests.Fixture f, String[] seen, int standing) {
        String state = standing + " standing, " + (f.player() == null ? "no player" : f.player().getInventory().main.stream()
                .filter(st -> !st.isEmpty()).map(st -> st.getCount() + " " + net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath()).toList());
        if (!state.equals(seen[0])) KindredMod.LOGGER.info("Joiner (tagged wood): {}", seen[0] = state);
    }

    private static boolean idle(NativePaidSkillsGameTests.Fixture f) {
        return f.runtime.nativeActivityStatus(dev.kindred.core.Actor.console(), f.id).phase().equals("IDLE");
    }

    private static int count(NativePaidSkillsGameTests.Fixture f, net.minecraft.item.Item item) {
        return f.player() == null ? 0 : f.player().getInventory().count(item);
    }

    /** Writes the fixture's datapack into the world's datapacks folder; the reload after picks it up as a new pack. */
    static void stage(MinecraftServer server) {
        Path root = server.getSavePath(WorldSavePath.DATAPACKS).resolve(PACK);
        try {
            write(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"Kindred F-38 fixture: a wood by tags alone (staged)\"}}");
            for (String kind : new String[] {"block", "item"}) {
                write(root.resolve("data/minecraft/tags/" + kind + "/logs.json"), "{\"values\":[\"" + LOG + "\"]}");
                write(root.resolve("data/minecraft/tags/" + kind + "/planks.json"), "{\"values\":[\"" + PLANKS + "\"]}");
            }
            write(root.resolve("data/kindred_f38/recipe/bamboo_mosaic_from_shroomlight.json"),
                    "{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"" + LOG + "\"},{\"item\":\"" + LOG
                            + "\"}],\"result\":{\"id\":\"" + PLANKS + "\",\"count\":8}}");
        } catch (IOException e) {
            throw new IllegalStateException("Could not stage the fixture's datapack", e);
        }
    }

    /** Removes the staged datapack, if it is there; the reload after drops it. Whether there was one. */
    static boolean unstage(MinecraftServer server) {
        Path root = server.getSavePath(WorldSavePath.DATAPACKS).resolve(PACK);
        if (!Files.exists(root)) return false;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        } catch (IOException e) {
            throw new IllegalStateException("Could not remove the fixture's datapack", e);
        }
        return true;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
