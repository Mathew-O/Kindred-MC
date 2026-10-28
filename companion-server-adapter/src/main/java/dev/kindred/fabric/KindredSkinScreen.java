package dev.kindred.fabric;

import dev.kindred.core.CanonicalRosterSkins;
import dev.kindred.core.SkinFiles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * In plain terms: the skin picker screen in the Kindred client: shows each skin in the server's skins folder
 * from the front, so you can choose what an AI player wears. The first card puts its own skin back.
 *
 * Technical: Picks an AI player's skin from the server's skins folder, each shown from the front as it will
 * look in the world. The first card brings back the player's own skin (its bundled roster skin, or the
 * default one).
 */
@Environment(EnvType.CLIENT)
final class KindredSkinScreen extends Screen {
    private static final int PANEL = 0xD0101418;
    private static final int BORDER = 0xFF3B4B5B;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF9AA7B4;
    private static final int GOOD = 0xFF6FD08C;
    private static final int UNIT = 2;
    private static final int CARD_W = 16 * UNIT + 40, CARD_H = 32 * UNIT + 22, GAP = 6;

    private final Screen parent;
    private final String botId, botName, folder;
    private final UUID body;
    private String current;
    private int page, seen = -2;
    private int left, right, top, bottom, columns, rows;

    KindredSkinScreen(Screen parent, String botId, String botName, UUID body, String current, String folder) {
        super(Text.literal("Skin for " + botName));
        this.parent = parent;
        this.botId = botId;
        this.botName = botName;
        this.body = body;
        this.current = current == null ? "" : current;
        this.folder = folder == null || folder.isBlank() ? "config/kindred/skins" : folder;
        request();
    }

    private void request() {
        KindredClientSkins.clearCatalog();
        if (ClientPlayNetworking.canSend(KindredSkinPayloads.CatalogRequest.ID)) {
            ClientPlayNetworking.send(new KindredSkinPayloads.CatalogRequest());
        }
    }

    /** One card's choice: a skin's key, or the player's own skin. */
    private record Card(String key, Identifier texture, boolean slim, boolean legacy, String label) { }

    private List<Card> cards() {
        var cards = new java.util.ArrayList<Card>();
        var roster = CanonicalRosterSkins.forDisplayName(botName);
        if (roster.isPresent()) {
            cards.add(new Card(SkinFiles.DEFAULT_KEY, Identifier.of("kindred", roster.get().clientAssetPath()), roster.get().slim(), false, "Own skin"));
        } else {
            var fallback = DefaultSkinHelper.getSkinTextures(body == null ? UUID.randomUUID() : body);
            cards.add(new Card(SkinFiles.DEFAULT_KEY, fallback.texture(), fallback.model() == net.minecraft.client.util.SkinTextures.Model.SLIM, false, "Default"));
        }
        for (var held : KindredClientSkins.catalog()) cards.add(new Card(held.key(), held.texture(), held.slim(), held.legacy(), label(held.key())));
        return cards;
    }

    /** A key as a name: {@code frost_ranger_slim} reads "Frost Ranger". */
    static String label(String key) {
        String name = key.replaceAll("[_-](slim|wide|classic)$", "").replace('_', ' ').replace('-', ' ').trim();
        var words = new StringBuilder();
        for (String word : name.split(" +")) {
            if (word.isEmpty()) continue;
            if (!words.isEmpty()) words.append(' ');
            words.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return words.isEmpty() ? key : words.toString();
    }

    @Override
    protected void init() {
        rebuild();
    }

    @Override
    public void tick() {
        int count = KindredClientSkins.catalog().size() + (KindredClientSkins.catalogTotal() < 0 ? -1 : 0);
        if (count != seen) rebuild();
    }

    private void rebuild() {
        clearChildren();
        seen = KindredClientSkins.catalog().size() + (KindredClientSkins.catalogTotal() < 0 ? -1 : 0);
        left = Math.max(6, width / 2 - 230);
        right = Math.min(width - 6, width / 2 + 230);
        top = 24;
        bottom = height - 8;
        columns = Math.max(1, (right - left - 12 + GAP) / (CARD_W + GAP));
        rows = Math.max(1, (bottom - 30 - (top + 30) + GAP) / (CARD_H + GAP));
        List<Card> cards = cards();
        int perPage = columns * rows;
        int pages = Math.max(1, (cards.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        int gridWidth = columns * (CARD_W + GAP) - GAP;
        int x0 = left + (right - left - gridWidth) / 2;
        int y0 = top + 30;
        for (int i = page * perPage; i < Math.min(cards.size(), (page + 1) * perPage); i++) {
            Card card = cards.get(i);
            int slot = i - page * perPage;
            int x = x0 + (slot % columns) * (CARD_W + GAP);
            int y = y0 + (slot / columns) * (CARD_H + GAP);
            addDrawableChild(ButtonWidget.builder(Text.empty(), b -> choose(card.key())).dimensions(x, y, CARD_W, CARD_H).build());
        }
        int buttonY = bottom - 24;
        var previous = ButtonWidget.builder(Text.literal("<"), b -> { page--; rebuild(); }).dimensions(left + 6, buttonY, 20, 20).build();
        previous.active = page > 0;
        addDrawableChild(previous);
        var next = ButtonWidget.builder(Text.literal(">"), b -> { page++; rebuild(); }).dimensions(left + 30, buttonY, 20, 20).build();
        next.active = page < pages - 1;
        addDrawableChild(next);
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload"), b -> request()).dimensions(right - 140, buttonY, 64, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close()).dimensions(right - 72, buttonY, 66, 20).build());
    }

    private void choose(String key) {
        if (ClientPlayNetworking.canSend(KindredPanelPayloads.Action.ID)) {
            ClientPlayNetworking.send(new KindredPanelPayloads.Action("skin", botId, key));
        }
        current = SkinFiles.DEFAULT_KEY.equals(key) ? "" : key;
        close();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(left, top, right, bottom, PANEL);
        context.drawBorder(left, top, right - left, bottom - top, BORDER);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, TEXT);
        int total = KindredClientSkins.catalogTotal();
        String hint = total < 0 ? "Asking the server for its skins..."
                : total == 0 ? "No skins yet: put 64x64 skin PNGs in " + folder + " on the server, then Reload."
                : total + " skin" + (total == 1 ? "" : "s") + " in " + folder + ". Pick one to put it on " + botName + ".";
        context.drawTextWithShadow(textRenderer, Text.literal(hint), left + 6, top + 8, MUTED);
        List<Card> cards = cards();
        int perPage = columns * rows;
        int gridWidth = columns * (CARD_W + GAP) - GAP;
        int x0 = left + (right - left - gridWidth) / 2;
        int y0 = top + 30;
        for (int i = page * perPage; i < Math.min(cards.size(), (page + 1) * perPage); i++) {
            Card card = cards.get(i);
            int slot = i - page * perPage;
            int x = x0 + (slot % columns) * (CARD_W + GAP);
            int y = y0 + (slot / columns) * (CARD_H + GAP);
            boolean worn = card.key().equals(SkinFiles.DEFAULT_KEY) ? current.isEmpty() : card.key().equals(current);
            if (worn) context.drawBorder(x - 1, y - 1, CARD_W + 2, CARD_H + 2, GOOD);
            KindredClientSkins.drawFront(context, card.texture(), card.slim(), card.legacy(), x + (CARD_W - 16 * UNIT) / 2, y + 4, UNIT);
            String label = textRenderer.trimToWidth(card.label(), CARD_W - 4);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + CARD_W / 2, y + CARD_H - 12, worn ? GOOD : TEXT);
        }
        int pages = Math.max(1, (cards.size() + perPage - 1) / perPage);
        if (pages > 1) context.drawTextWithShadow(textRenderer, Text.literal((page + 1) + "/" + pages), left + 56, bottom - 18, MUTED);
    }
}
