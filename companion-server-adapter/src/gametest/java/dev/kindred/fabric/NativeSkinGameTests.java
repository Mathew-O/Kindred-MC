package dev.kindred.fabric;

import dev.kindred.core.Actor;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * In plain terms: a GameTest: it runs inside a real Minecraft test world and checks that a skin put in the
 * skins folder can be chosen for an AI player and is remembered after a restart.
 *
 * Technical: The skin selector's server half: a skin dropped into the skins folder is put on a native body by
 * someone allowed to adjust it, kept across restarts in the choices file, and taken off again with {@code
 * default}. The payload that carries the PNG to clients survives its codec.
 */
public final class NativeSkinGameTests implements FabricGameTest {
    private static byte[] png(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) image.setRGB(x, y, 0xFF000000 | (x * 4 << 16) | (y * 4 << 8));
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String choicesFile() throws Exception {
        Path file = KindredSkins.FOLDER.getParent().resolve("skin-choices.properties");
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "skins", tickLimit = 400)
    public void aSkinFromTheFolderIsWornKeptAndTakenOff(TestContext context) {
        var f = new NativePaidSkillsGameTests.Fixture(context);
        Path knight = KindredSkins.FOLDER.resolve("Gametest Knight.png");
        Path tall = KindredSkins.FOLDER.resolve("Gametest Tall.png");
        UUID[] body = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> context.assertTrue(f.runtime.isReady(), "Ready"))
                .createAndAdd(() -> {
                    try {
                        Files.createDirectories(KindredSkins.FOLDER);
                        Files.write(knight, png(64, 64));
                        Files.write(tall, png(64, 128));
                    } catch (Exception failure) {
                        throw new AssertionError("Skins folder not writable: " + failure);
                    }
                    f.runtime.createNative(f.source, "Skinner");
                })
                .createAndAdd(() -> f.established(context))
                .createAndAdd(() -> {
                    body[0] = f.player().getUuid();
                    context.assertTrue(f.runtime.setSkin(f.source.withLevel(0), f.id, "gametest_knight") == 0,
                            "Someone who may not adjust the player cannot dress it");
                    context.assertTrue(KindredSkins.choiceOf(body[0]).isEmpty(), "Still its own skin");
                    context.assertTrue(f.runtime.setSkin(f.source, f.id, "gametest_tall") == 1, "Asked for the tall image");
                })
                .createAndAdd(() -> context.assertTrue(KindredSkins.keys().contains("gametest_knight"), "The folder was read"))
                .createAndAdd(() -> {
                    context.assertTrue(!KindredSkins.keys().contains("gametest_tall"), "A 64x128 image is not a skin");
                    context.assertTrue(KindredSkins.choiceOf(body[0]).isEmpty(), "Nothing put on for an unusable image");
                    context.assertTrue(f.runtime.setSkin(f.source, f.id, "gametest_knight") == 1, "Chosen");
                    context.assertTrue(KindredSkins.choiceOf(body[0]).equals("gametest_knight"), "Known from the scan, so worn at once");
                    var bot = f.runtime.panelBots(Actor.console()).stream().filter(b -> b.id().equals(f.id)).findFirst().orElseThrow();
                    context.assertTrue("gametest_knight".equals(bot.skin()) && body[0].equals(bot.bodyId()), "The panel shows it: " + bot.skin());
                })
                .createAndAdd(() -> {
                    String saved;
                    try { saved = choicesFile(); } catch (Exception failure) { throw new AssertionError(failure.toString()); }
                    context.assertTrue(saved.contains(body[0] + "=gametest_knight"), "Kept in the choices file: " + saved);
                })
                .createAndAdd(() -> {
                    // The payload carrying the PNG to clients, through its codec.
                    byte[] bytes;
                    try { bytes = png(64, 64); } catch (Exception failure) { throw new AssertionError(failure.toString()); }
                    var buffer = new RegistryByteBuf(Unpooled.buffer(), context.getWorld().getRegistryManager());
                    KindredSkinPayloads.Binding.CODEC.encode(buffer, new KindredSkinPayloads.Binding(body[0], "gametest_knight", true, bytes));
                    var back = KindredSkinPayloads.Binding.CODEC.decode(buffer);
                    context.assertTrue(back.body().equals(body[0]) && back.key().equals("gametest_knight") && back.slim()
                            && Arrays.equals(back.png(), bytes), "The binding survives its codec");
                    context.assertTrue(f.runtime.setSkin(f.source, f.id, "default") == 1, "Own skin again");
                    context.assertTrue(KindredSkins.choiceOf(body[0]).isEmpty(), "Taken off at once");
                })
                .createAndAdd(() -> {
                    String saved;
                    try { saved = choicesFile(); } catch (Exception failure) { throw new AssertionError(failure.toString()); }
                    context.assertTrue(!saved.contains(body[0].toString()), "Gone from the choices file");
                    try {
                        Files.deleteIfExists(knight);
                        Files.deleteIfExists(tall);
                    } catch (Exception ignored) {
                        // Left for the next scan to read again; harmless.
                    }
                    f.runtime.stop();
                })
                .createAndAdd(() -> context.assertTrue(f.runtime.shutdownComplete(), "Cleanup"))
                .completeIfSuccessful();
    }
}
