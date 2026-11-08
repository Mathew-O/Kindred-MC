package dev.kindred.fabric;

import dev.kindred.core.factions.Hud;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.DyeColor;
import org.lwjgl.glfw.GLFW;

/**
 * In plain terms: the Factions panel on the right of the screen, drawn by Kindred's client mod: a dark see-through card
 * in the banner's stone greys with the faction in its own dye colour, the state colours amber, red and green, and the
 * H key cycling it full, compact and hidden. Nothing in it is clickable, and it hides while a screen is open.
 *
 * Technical: The HUD's client side (M-06l). It draws {@link Hud#layout} of the last state the server sent; the server
 * decides what the player may know. The card is about 35% opaque with a lighter edge, a quarter of the screen wide,
 * Minecraft's own font, scaled with the GUI scale (the draw context's coordinates already are).
 */
final class KindredHud {
    /** The banner's stone grey at about 35% opacity, and its lighter edge. */
    static final int CARD = 0x59_3C_3C_3C, EDGE = 0x80_8A_8A_8A;
    static final int TEXT = 0xFF_E8_E8_E8, DIM = 0xFF_A8_A8_A8, AMBER = 0xFF_FF_B3_2E, RED = 0xFF_FF_55_55, GREEN = 0xFF_6C_E0_6C;

    private static KeyBinding cycle;
    private static Hud.State state;
    private static Hud.Mode mode = Hud.Mode.FULL;

    private KindredHud() {}

    static void register() {
        cycle = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kindred.factions_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.kindred"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (cycle.wasPressed()) mode = mode.next();
        });
        ClientPlayNetworking.registerGlobalReceiver(KindredPanelPayloads.Hud.ID, (payload, context) -> {
            try {
                state = Hud.decode(payload.text());
            } catch (RuntimeException unreadable) {
                state = null;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> state = null));
        HudRenderCallback.EVENT.register((context, tick) -> draw(context));
    }

    /** The colour a line is drawn in. */
    static int colour(Hud.Line line) {
        return switch (line.role()) {
            case FACTION -> 0xFF_00_00_00 | factionColour(line.colour());
            case TEXT -> TEXT;
            case DIM -> DIM;
            case AMBER -> AMBER;
            case RED -> RED;
            case GREEN -> GREEN;
        };
    }

    /** A faction's dye colour as Minecraft's own sign text shows it. */
    static int factionColour(String dye) {
        var colour = DyeColor.byName(dye, DyeColor.WHITE);
        return colour == DyeColor.BLACK ? 0x50_50_50 : colour.getSignColor();
    }

    private static void draw(DrawContext context) {
        var client = MinecraftClient.getInstance();
        if (state == null || client.player == null || client.currentScreen != null || client.options.hudHidden) return;
        var lines = Hud.layout(state, mode);
        if (lines.isEmpty()) return;
        var font = client.textRenderer;
        int width = Math.max(120, context.getScaledWindowWidth() / 4);
        int pad = 4, rowHeight = font.fontHeight + 2;
        int height = pad * 2 + lines.size() * rowHeight;
        int x = context.getScaledWindowWidth() - width - 4, y = Math.max(4, (context.getScaledWindowHeight() - height) / 3);
        // The card, its corners cut by a pixel, and its lighter edge.
        context.fill(x + 1, y, x + width - 1, y + height, CARD);
        context.fill(x, y + 1, x + 1, y + height - 1, CARD);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, CARD);
        context.fill(x + 1, y, x + width - 1, y + 1, EDGE);
        context.fill(x + 1, y + height - 1, x + width - 1, y + height, EDGE);
        int row = y + pad;
        for (var line : lines) {
            String text = font.trimToWidth(line.text(), width - pad * 2);
            context.drawTextWithShadow(font, text, x + pad, row, colour(line));
            row += rowHeight;
        }
    }
}
