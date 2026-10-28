package dev.kindred.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kindred.core.CanonicalRosterSkins;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Keybind panel for spawning, starting, stopping and tuning the AI players this client may control. */
@Environment(EnvType.CLIENT)
final class KindredPanelScreen extends Screen {
    private static final int PANEL = 0xD0101418;
    private static final int CARD = 0xFF1C242C;
    private static final int BORDER = 0xFF3B4B5B;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF9AA7B4;
    private static final int GOOD = 0xFF6FD08C;
    private static final int WARN = 0xFFE3B341;
    private static final int BAD = 0xFFF0716A;
    private static final int REFRESH_TICKS = 20;

    private JsonObject state = new JsonObject();
    private String message = "";
    private String selectedId;
    private int ticks;
    private int left, right, top, bottom;

    KindredPanelScreen(String json, String message) {
        super(Text.literal("Kindred AI Players"));
        accept(json, message);
    }

    void accept(String json, String message) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            state = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException malformed) {
            state = new JsonObject();
        }
        if (message != null && !message.isBlank()) this.message = message;
        List<JsonObject> bots = bots();
        if (selectedId == null || bots.stream().noneMatch(bot -> str(bot, "id").equals(selectedId))) {
            selectedId = bots.isEmpty() ? null : str(bots.getFirst(), "id");
        }
        if (client != null) rebuild();
    }

    @Override
    protected void init() {
        rebuild();
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0 && ClientPlayNetworking.canSend(KindredPanelPayloads.Request.ID)) {
            ClientPlayNetworking.send(new KindredPanelPayloads.Request());
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int detailX, detailWidth, rowA, rowB, rowC, rosterButtonX, rosterButtonWidth;

    private void rebuild() {
        clearChildren();
        left = Math.max(6, width / 2 - 230);
        right = Math.min(width - 6, width / 2 + 230);
        top = 24;
        bottom = height - 8;
        rosterButtonX = left + 26;
        rosterButtonWidth = Math.min(96, Math.max(70, (right - left) / 5));
        detailX = rosterButtonX + rosterButtonWidth + 10;
        detailWidth = right - detailX - 6;
        rowC = bottom - 24;
        rowB = rowC - 23;
        rowA = rowB - 23;
        boolean operator = bool(state, "operator");

        int rowY = top + 18;
        for (JsonElement element : state.has("roster") ? state.getAsJsonArray("roster") : new com.google.gson.JsonArray()) {
            JsonObject slot = element.getAsJsonObject();
            String name = str(slot, "name");
            JsonObject bot = botNamed(name);
            ButtonWidget button;
            if (bot != null) {
                String id = str(bot, "id");
                button = ButtonWidget.builder(Text.literal((id.equals(selectedId) ? "> " : "") + name), b -> {
                    selectedId = id;
                    rebuild();
                }).dimensions(rosterButtonX, rowY, rosterButtonWidth, 20).build();
            } else {
                button = ButtonWidget.builder(Text.literal("Spawn " + name), b -> act("spawn", "", name))
                        .dimensions(rosterButtonX, rowY, rosterButtonWidth, 20).build();
                button.active = operator;
            }
            addDrawableChild(button);
            rowY += 26;
        }

        int gap = 3;
        JsonObject bot = selected();
        if (bot != null) {
            String id = str(bot, "id");
            boolean connected = bool(bot, "connected");
            boolean stopped = bool(bot, "stopped");
            boolean running = !stopped && !"NONE".equals(str(bot, "goal"));
            int startWidth = detailWidth / 2 - gap;
            int smallWidth = (detailWidth - startWidth - 2 * gap) / 2;
            var start = ButtonWidget.builder(Text.literal(running ? "Nether run active" : "Start Nether run"),
                    b -> {
                        if (!bool(state, "worldEdits") && operator) act("edits", "", "true");
                        act("goal", id, "NETHER_PORTAL");
                    }).dimensions(detailX, rowA, startWidth, 20).build();
            start.active = connected && !running && (bool(state, "worldEdits") || operator);
            addDrawableChild(start);
            var stop = ButtonWidget.builder(Text.literal("Stop"), b -> act("stop", id, ""))
                    .dimensions(detailX + startWidth + gap, rowA, smallWidth, 20).build();
            stop.active = connected && !stopped;
            addDrawableChild(stop);
            var resume = ButtonWidget.builder(Text.literal("Resume"), b -> act("resume", id, ""))
                    .dimensions(detailX + startWidth + smallWidth + 2 * gap, rowA, detailWidth - startWidth - smallWidth - 2 * gap, 20).build();
            resume.active = connected && stopped;
            addDrawableChild(resume);

            int modeWidth = detailWidth * 2 / 5;
            int teamWidth = detailWidth * 3 / 10;
            int capX = detailX + modeWidth + teamWidth + 2 * gap;
            int capWidth = detailX + detailWidth - capX;
            addDrawableChild(ButtonWidget.builder(Text.literal("Mode: " + playstyleLabel(str(bot, "playstyle"))),
                    b -> act("playstyle", id, nextPlaystyle(str(bot, "playstyle")))).dimensions(detailX, rowB, modeWidth, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("COOP_TEAM".equals(str(bot, "collaboration")) ? "Team: co-op" : "Team: solo"),
                    b -> act("collaboration", id, "")).dimensions(detailX + modeWidth + gap, rowB, teamWidth, 20).build());
            int cap = bot.has("capHours") ? bot.get("capHours").getAsInt() : -1;
            var less = ButtonWidget.builder(Text.literal("-"), b -> act("cap", id, String.valueOf(Math.max(1, cap - 1))))
                    .dimensions(capX, rowB, 16, 20).build();
            var more = ButtonWidget.builder(Text.literal("+"), b -> act("cap", id, String.valueOf(Math.min(24, cap + 1))))
                    .dimensions(capX + capWidth - 16, rowB, 16, 20).build();
            less.active = operator && cap > 1;
            more.active = operator && cap > 0 && cap < 24;
            addDrawableChild(less);
            addDrawableChild(more);

            addDrawableChild(ButtonWidget.builder(Text.literal(connected ? "Disconnect" : "Connect"),
                    b -> act(connected ? "disconnect" : "connect", id, "")).dimensions(detailX, rowC, detailWidth / 4, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Skin..."), b -> {
                if (client != null) client.setScreen(new KindredSkinScreen(this, id, str(bot, "name"), bodyId(bot), str(bot, "skin"), str(state, "skinsFolder")));
            }).dimensions(detailX + detailWidth / 4 + gap, rowC, detailWidth / 5, 20).build());
        }
        int editsX = detailX + detailWidth / 4 + detailWidth / 5 + 2 * gap;
        int closeWidth = detailWidth / 4;
        var edits = ButtonWidget.builder(Text.literal("Edits: " + (bool(state, "worldEdits") ? "ON" : "OFF")),
                b -> act("edits", "", String.valueOf(!bool(state, "worldEdits"))))
                .dimensions(editsX, rowC, detailX + detailWidth - closeWidth - gap - editsX, 20).build();
        edits.active = operator;
        addDrawableChild(edits);
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(detailX + detailWidth - closeWidth, rowC, closeWidth, 20).build());
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(left, top, right, bottom, PANEL);
        context.drawBorder(left, top, right - left, bottom - top, BORDER);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Background (blur, panel frame) and widgets first; text and heads draw on top of them.
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, TEXT);
        context.drawTextWithShadow(textRenderer, Text.literal("Roster"), left + 6, top + 6, MUTED);
        context.fill(detailX - 6, top + 4, detailX - 5, bottom - 4, BORDER);

        int rowY = top + 18;
        for (JsonElement element : state.has("roster") ? state.getAsJsonArray("roster") : new com.google.gson.JsonArray()) {
            String name = str(element.getAsJsonObject(), "name");
            int headY = rowY + 2;
            JsonObject bot = botNamed(name);
            var chosen = bot == null ? null : KindredClientSkins.forBody(bodyId(bot));
            if (chosen != null) {
                PlayerSkinDrawer.draw(context, chosen.texture(), left + 6, headY, 16);
            } else {
                CanonicalRosterSkins.forDisplayName(name).ifPresent(skin ->
                        PlayerSkinDrawer.draw(context, Identifier.of("kindred", skin.clientAssetPath()), left + 6, headY, 16));
            }
            if (bot != null) {
                int dot = !bool(bot, "connected") ? MUTED : bool(bot, "stopped") ? WARN : GOOD;
                context.fill(rosterButtonX + rosterButtonWidth + 2, rowY + 8, rosterButtonX + rosterButtonWidth + 6, rowY + 12, dot);
            }
            rowY += 26;
        }

        if (!bool(state, "ready")) {
            context.drawTextWithShadow(textRenderer, Text.literal("Kindred is loading saved records..."), detailX, top + 20, MUTED);
        } else {
            JsonObject bot = selected();
            int x0 = detailX;
            int textWidth = detailWidth;
            if (bot == null) {
                context.drawTextWrapped(textRenderer, Text.literal(bool(state, "operator")
                        ? "No AI players yet. Spawn Kindred, Sol or Vesp from the roster; they appear beside you."
                        : "No AI players you may control. Ask an operator to spawn one."), x0, top + 20, textWidth, MUTED);
            } else {
                int y = top + 8;
                context.drawTextWithShadow(textRenderer, Text.literal(str(bot, "name")), x0, y, TEXT);
                String state = !bool(bot, "connected") ? "offline" : bool(bot, "stopped") ? "stopped" : bool(bot, "busy") ? "working" : "ready";
                int stateColor = !bool(bot, "connected") ? MUTED : bool(bot, "stopped") ? WARN : GOOD;
                context.drawTextWithShadow(textRenderer, Text.literal(state), x0 + textRenderer.getWidth(str(bot, "name")) + 8, y, stateColor);
                y += 14;
                if (bool(bot, "connected")) {
                    float health = bot.get("health").getAsFloat();
                    context.drawTextWithShadow(textRenderer, Text.literal(String.format(Locale.ROOT, "Health %.0f/20   Food %d/20",
                            health, bot.get("food").getAsInt())), x0, y, health < 8 ? BAD : TEXT);
                    y += 11;
                    if (bot.has("x")) {
                        context.drawTextWithShadow(textRenderer, Text.literal("At " + bot.get("x").getAsInt() + ", " + bot.get("y").getAsInt()
                                + ", " + bot.get("z").getAsInt() + " (" + str(bot, "dimension") + ")"), x0, y, MUTED);
                        y += 11;
                    }
                }
                if (bot.has("capHours")) {
                    context.drawTextWithShadow(textRenderer, Text.literal("Daily activity cap: " + bot.get("capHours").getAsInt() + " h"), x0, y, MUTED);
                    y += 11;
                }
                y += 4;
                context.fill(x0, y, right - 10, y + 1, BORDER);
                y += 5;
                context.drawTextWithShadow(textRenderer, Text.literal("Goal: " + str(bot, "goalLabel")), x0, y, TEXT);
                y += 11;
                String progress = str(bot, "goalProgress");
                if (!progress.isBlank()) {
                    context.drawTextWrapped(textRenderer, Text.literal(progress), x0, y, textWidth, GOOD);
                    y += 11 * Math.min(2, textRenderer.wrapLines(Text.literal(progress), textWidth).size());
                }
                String task = str(bot, "task");
                if (!task.isBlank()) {
                    context.drawTextWithShadow(textRenderer, Text.literal("Doing: " + task.replace("native_", "")), x0, y, MUTED);
                    y += 11;
                }
                y += 3;
                // Status and the last outcome share the space above the buttons; never draw over them.
                int limit = rowA - 4;
                for (var line : textRenderer.wrapLines(Text.literal(str(bot, "status")), textWidth)) {
                    if (y + 9 > limit) break;
                    context.drawTextWithShadow(textRenderer, line, x0, y, MUTED);
                    y += 10;
                }
                if (!message.isBlank()) {
                    for (var line : textRenderer.wrapLines(Text.literal(message), textWidth)) {
                        if (y + 9 > limit) break;
                        context.drawTextWithShadow(textRenderer, line, x0, y, WARN);
                        y += 10;
                    }
                }
            }
        }
        JsonObject bot = selected();
        if (bot != null && bot.has("capHours")) {
            int capX = detailX + detailWidth * 2 / 5 + detailWidth * 3 / 10 + 6;
            int capWidth = detailX + detailWidth - capX;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(bot.get("capHours").getAsInt() + "h/day"),
                    capX + capWidth / 2, rowB + 6, TEXT);
        }
        if (bot == null && !message.isBlank()) {
            context.drawTextWrapped(textRenderer, Text.literal(message), detailX, rowA - 24, detailWidth, WARN);
        }
    }

    private void act(String action, String id, String argument) {
        if (!ClientPlayNetworking.canSend(KindredPanelPayloads.Action.ID)) {
            message = "This server does not run Kindred.";
            return;
        }
        ClientPlayNetworking.send(new KindredPanelPayloads.Action(action, id, argument));
    }

    private List<JsonObject> bots() {
        var list = new ArrayList<JsonObject>();
        if (state.has("bots")) for (JsonElement element : state.getAsJsonArray("bots")) list.add(element.getAsJsonObject());
        return list;
    }

    private JsonObject selected() {
        for (JsonObject bot : bots()) if (str(bot, "id").equals(selectedId)) return bot;
        return null;
    }

    private JsonObject botNamed(String name) {
        for (JsonObject bot : bots()) if (str(bot, "name").equalsIgnoreCase(name)) return bot;
        return null;
    }

    private static java.util.UUID bodyId(JsonObject bot) {
        try { return java.util.UUID.fromString(str(bot, "bodyId")); }
        catch (IllegalArgumentException missing) { return null; }
    }

    private static String playstyleLabel(String raw) {
        return switch (raw) {
            case "SPEEDRUN_ANY_PERCENT" -> "speedrun";
            case "IDLE_OBSERVING" -> "idle";
            default -> "survival";
        };
    }

    private static String nextPlaystyle(String raw) {
        return switch (raw) {
            case "OPTIMAL_SURVIVAL" -> "SPEEDRUN";
            case "SPEEDRUN_ANY_PERCENT" -> "IDLE";
            default -> "OPTIMAL";
        };
    }

    private static String str(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).getAsBoolean();
    }
}
