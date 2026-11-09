package dev.kindred.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kindred.core.factions.Hud;
import dev.kindred.core.ui.HudOptions;
import dev.kindred.fabric.mixin.client.InGameHudTitleAccessor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import org.lwjgl.glfw.GLFW;

/**
 * In plain terms: the Factions panel on the right of the screen, drawn by Kindred's client mod: a dark see-through card
 * in the banner's stone greys with the faction in its own dye colour, the state colours amber, red and green, and the
 * H key cycling it full, compact and hidden. Nothing in it is clickable, and it hides while a screen is open. Each
 * player sets on their own computer how see-through and how big it is, which side it sits on, and whether it steps
 * aside while a title shows ({@code /kindredhud}).
 *
 * Technical: The HUD's client side (M-06l). It draws {@link Hud#layout} of the last state the server sent; the server
 * decides what the player may know. The card is about 35% opaque with a lighter edge, a quarter of the screen wide,
 * Minecraft's own font, scaled with the GUI scale (the draw context's coordinates already are). The player's own
 * settings ({@link HudOptions}, kept by {@link KindredHudOptions}) set its opacity (10 to 100%), its scale (0.5 to 2)
 * on top of the GUI scale, its side, and whether it is hidden while a title or subtitle shows; none of them changes the
 * H key's cycle, and the server's vanilla sidebar fallback is untouched.
 */
final class KindredHud {
    /** The banner's stone grey at about 35% opacity, and its lighter edge: the default settings' card. */
    static final int CARD = 0x59_3C_3C_3C, EDGE = 0x80_8A_8A_8A;
    static final int TEXT = 0xFF_E8_E8_E8, DIM = 0xFF_A8_A8_A8, AMBER = 0xFF_FF_B3_2E, RED = 0xFF_FF_55_55, GREEN = 0xFF_6C_E0_6C;

    private static KeyBinding cycle;
    private static Hud.State state;
    private static Hud.Mode mode = Hud.Mode.FULL;

    private KindredHud() {}

    static void register() {
        KindredHudOptions.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(ClientCommandManager.literal("kindredhud")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.translatable("command.kindred.hud.now", KindredHudOptions.current().describe()));
                    context.getSource().sendFeedback(Text.translatable("command.kindred.hud.usage"));
                    return 1;
                })
                .then(ClientCommandManager.literal("reset").executes(context -> {
                    KindredHudOptions.set(HudOptions.DEFAULT);
                    context.getSource().sendFeedback(Text.translatable("command.kindred.hud.reset", HudOptions.DEFAULT.describe()));
                    return 1;
                }))
                .then(ClientCommandManager.argument("setting", StringArgumentType.word())
                        .then(ClientCommandManager.argument("value", StringArgumentType.word()).executes(context -> {
                            var changed = KindredHudOptions.current().with(StringArgumentType.getString(context, "setting"),
                                    StringArgumentType.getString(context, "value"));
                            if (changed.options().isEmpty()) {
                                context.getSource().sendError(Text.translatable("command.kindred.hud.refused", changed.error()));
                                return 0;
                            }
                            KindredHudOptions.set(changed.options().get());
                            context.getSource().sendFeedback(Text.translatable("command.kindred.hud.set", changed.options().get().describe()));
                            return 1;
                        })))));
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

    /** Whether a title or subtitle is on screen now: a match countdown, say. */
    static boolean titleShowing(MinecraftClient client) {
        var hud = (InGameHudTitleAccessor) client.inGameHud;
        return hud.kindred$title() != null && hud.kindred$titleRemainTicks() > 0;
    }

    private static void draw(DrawContext context) {
        var client = MinecraftClient.getInstance();
        if (state == null || client.player == null || client.currentScreen != null || client.options.hudHidden) return;
        var options = KindredHudOptions.current();
        if (options.hideForTitles() && titleShowing(client)) return;
        var lines = Hud.layout(state, mode);
        if (lines.isEmpty()) return;
        var font = client.textRenderer;
        // Laid out in the card's own scale, on top of the GUI scale: the screen as the card sees it.
        float scale = (float) options.scale();
        var place = options.place(context.getScaledWindowWidth(), context.getScaledWindowHeight(), lines.size(), font.fontHeight);
        int pad = 4, rowHeight = font.fontHeight + 2;
        int x = place.x(), y = place.y(), width = place.width(), height = place.height();
        int card = options.card(), edge = options.edge();
        var matrices = context.getMatrices();
        matrices.push();
        matrices.scale(scale, scale, 1);
        // The card, its corners cut by a pixel, and its lighter edge.
        context.fill(x + 1, y, x + width - 1, y + height, card);
        context.fill(x, y + 1, x + 1, y + height - 1, card);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, card);
        context.fill(x + 1, y, x + width - 1, y + 1, edge);
        context.fill(x + 1, y + height - 1, x + width - 1, y + height, edge);
        int row = y + pad;
        for (var line : lines) {
            String text = font.trimToWidth(line.text(), width - pad * 2);
            context.drawTextWithShadow(font, text, x + pad, row, colour(line));
            row += rowHeight;
        }
        matrices.pop();
    }
}
