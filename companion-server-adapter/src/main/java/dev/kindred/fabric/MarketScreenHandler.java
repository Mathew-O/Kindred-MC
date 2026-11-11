package dev.kindred.fabric;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.market.Market;
import dev.kindred.core.market.MarketTable;
import dev.kindred.core.market.Pricing;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * In plain terms: the market as a chest: what it sells, with what it pays and asks, a page at a time; and the player's own
 * stacks with what each would fetch. Click an item to buy one, sixteen or sixty-four of it, or one of your stacks to sell
 * it, then click again to confirm. The items shown are pictures: clicking, shift-clicking or a number key never moves an
 * item anywhere.
 *
 * Technical: M-06g1's screen, a 9x6 generic container beside {@link AuctionScreenHandler}, the same slot handling. Prices
 * come only from the market's own {@link Pricing} and book; every confirmed click is {@link MarketDesk#buy} or
 * {@link MarketDesk#sell} exactly as {@code /shop buy} and {@code /shop sell} call them, so the holds, the records and
 * the saves are theirs, rechecked at the mutation. The price is quoted again at the confirming click and a moved price
 * asks again. {@link #onSlotClick} never runs vanilla slot logic and every click resyncs the client. A trade settled
 * after the screen has closed is told in chat only.
 */
final class MarketScreenHandler extends GenericContainerScreenHandler {
    static final int PAGE = 45;
    static final int PREVIOUS = 45, BROWSE = 46, SELL = 47, NOTE = 49, CLOSE = 51, NEXT = 53;
    /** The confirm view: buy one, sixteen or sixty-four, or sell the stack; the item between; back. */
    static final int ONE = 20, SIXTEEN = 21, SIXTY_FOUR = 23, SELL_IT = 24, SHOWN = 22, BACK = 40;
    static final int[] COUNTS = {1, 16, 64};

    enum View { BROWSE, SELL, CONFIRM_BUY, CONFIRM_SELL }

    private final MarketDesk desk;
    private final ServerPlayerEntity viewer;
    private final BooleanSupplier open;
    /** What each shown slot stands for: an item id in the browse view, an inventory slot in the sell view. */
    private final List<String> items = new ArrayList<>();
    private final List<Integer> stacks = new ArrayList<>();
    private View view = View.BROWSE;
    private View back = View.BROWSE;
    private int page;
    private String chosenItem;
    private int chosenSlot = -1;
    /** What the confirm view offers, by count (buying) or for the stack (selling), so a moved price is caught. */
    private final long[] offered = new long[COUNTS.length];
    private long offeredSale;
    private String note = "";

    MarketScreenHandler(int syncId, PlayerInventory inventory, MarketDesk desk, ServerPlayerEntity viewer, BooleanSupplier open) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, inventory, new SimpleInventory(54), 6);
        this.desk = desk;
        this.viewer = viewer;
        this.open = open;
        redraw();
    }

    View view() { return view; }

    String note() { return note; }

    /** The items the browse view lists, in order (for the fixtures). */
    List<String> listed() { return List.copyOf(items); }

    /** Every click is a button press or nothing: no vanilla slot logic runs, and the client is resynced. */
    @Override
    public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
        if (player == viewer && action == SlotActionType.PICKUP && slot >= 0 && slot < 54) press(slot);
        redraw();
        syncState();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
        return false;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == viewer && open.getAsBoolean();
    }

    private void press(int slot) {
        if (view == View.CONFIRM_BUY) {
            switch (slot) {
                case ONE -> buy(0);
                case SIXTEEN -> buy(1);
                case SIXTY_FOUR -> buy(2);
                case BACK -> view = back;
                default -> { }
            }
            return;
        }
        if (view == View.CONFIRM_SELL) {
            switch (slot) {
                case SELL_IT -> sell();
                case BACK -> view = back;
                default -> { }
            }
            return;
        }
        switch (slot) {
            case PREVIOUS -> page = Math.max(0, page - 1);
            case NEXT -> page++;
            case BROWSE -> { view = View.BROWSE; page = 0; }
            case SELL -> { view = View.SELL; page = 0; }
            case CLOSE -> viewer.closeHandledScreen();
            default -> {
                if (slot >= PAGE) return;
                if (view == View.BROWSE && slot < items.size()) {
                    chosenItem = items.get(slot);
                    for (int i = 0; i < COUNTS.length; i++) offered[i] = askFor(chosenItem, COUNTS[i]);
                    back = view;
                    view = View.CONFIRM_BUY;
                    note = "";
                } else if (view == View.SELL && slot < stacks.size()) {
                    chosenSlot = stacks.get(slot);
                    offeredSale = payFor(chosenSlot);
                    back = view;
                    view = View.CONFIRM_SELL;
                    note = "";
                }
            }
        }
    }

    /** What {@code count} of {@code item} costs now, or -1 when the market will not sell them. */
    private long askFor(String item, int count) {
        var quote = Market.quoteBuy(desk.pricing(), desk.book(), item, count, System.currentTimeMillis());
        return quote.accepted() ? quote.cents() : -1;
    }

    /** What the stack in inventory slot {@code slot} would fetch now, or -1 when the market refuses it. */
    private long payFor(int slot) {
        var estimate = desk.estimate(viewer, List.of(slot));
        return estimate.quotes().isEmpty() ? -1 : estimate.cents();
    }

    /** The confirming click: the same call {@code /shop buy} makes, at the price quoted again now. */
    private void buy(int which) {
        if (!open.getAsBoolean()) { refuse("This world does not play Factions."); return; }
        long now = askFor(chosenItem, COUNTS[which]);
        if (now < 0) { refuse("The market does not sell that now"); return; }
        if (now != offered[which]) {
            offered[which] = now;
            refuse("The price moved: " + COUNTS[which] + " now cost " + Coins.show(now) + "; click again to buy");
            return;
        }
        String world = viewer.getServerWorld().getRegistryKey().getValue().toString();
        String refusal = desk.buy(viewer, chosenItem, COUNTS[which], world, this::told);
        if (refusal != null) refuse(refusal);
        else note = "Buying...";
    }

    /** The confirming click: the same call {@code /shop sell} makes for that one slot, at the price quoted again now. */
    private void sell() {
        if (!open.getAsBoolean()) { refuse("This world does not play Factions."); return; }
        long now = payFor(chosenSlot);
        if (now < 0) { refuse("The market does not take that stack"); view = back; return; }
        if (now != offeredSale) {
            offeredSale = now;
            refuse("The price moved: the stack now fetches " + Coins.show(now) + "; click again to sell");
            return;
        }
        String world = viewer.getServerWorld().getRegistryKey().getValue().toString();
        desk.sell(viewer, List.of(chosenSlot), world, this::told);
        if (note.isEmpty() || note.startsWith("The price moved")) note = "Selling...";
    }

    private void refuse(String reason) {
        note = reason;
        viewer.sendMessage(Text.literal(reason).formatted(Formatting.RED));
    }

    /** The desk's answer once settled; the screen is redrawn only if it is still open. */
    private void told(String line) {
        note = line;
        viewer.sendMessage(Text.literal(line));
        if (viewer.currentScreenHandler == this) {
            if (view == View.CONFIRM_BUY || view == View.CONFIRM_SELL) view = back;
            redraw();
            syncState();
        }
    }

    private void redraw() {
        items.clear();
        stacks.clear();
        for (int i = 0; i < 54; i++) getSlot(i).setStack(ItemStack.EMPTY);
        if (view == View.CONFIRM_BUY) { drawConfirmBuy(); return; }
        if (view == View.CONFIRM_SELL) { drawConfirmSell(); return; }
        if (view == View.SELL) {
            var inventory = viewer.getInventory();
            var held = new ArrayList<Integer>();
            for (int i = 0; i < inventory.main.size(); i++) if (!inventory.getStack(i).isEmpty()) held.add(i);
            page = Math.min(page, Math.max(0, (held.size() - 1) / PAGE));
            for (int i = 0; i < PAGE && page * PAGE + i < held.size(); i++) {
                int slot = held.get(page * PAGE + i);
                stacks.add(slot);
                long pay = payFor(slot);
                var shown = inventory.getStack(slot).copy();
                getSlot(i).setStack(labeled(shown, null, pay < 0 ? List.of("The market does not take this") : List.of("Fetches " + Coins.show(pay), "Click to sell")));
            }
            drawButtons(held.size());
            return;
        }
        var sold = sold();
        page = Math.min(page, Math.max(0, (sold.size() - 1) / PAGE));
        for (int i = 0; i < PAGE && page * PAGE + i < sold.size(); i++) {
            String item = sold.get(page * PAGE + i);
            items.add(item);
            getSlot(i).setStack(labeled(shownItem(item), null, List.of(desk.priceLine(item), "Click to buy")));
        }
        drawButtons(sold.size());
    }

    /** What the market sells now: its buyable raw materials with a price, by id. */
    private List<String> sold() {
        var out = new ArrayList<String>();
        long now = System.currentTimeMillis();
        for (var root : desk.pricing().table().roots().values()) {
            if (!root.buyable()) continue;
            String id = MarketTable.id(root.item());
            if (Registries.ITEM.get(Identifier.of(id)) == Items.AIR) continue;
            if (desk.pricing().price(desk.book(), id, now).isPresent()) out.add(id);
        }
        out.sort(String::compareTo);
        return out;
    }

    private void drawButtons(int count) {
        int pages = Math.max(1, (count + PAGE - 1) / PAGE);
        for (int i = PAGE; i < 54; i++) getSlot(i).setStack(labeled(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " ", List.of()));
        if (page > 0) getSlot(PREVIOUS).setStack(labeled(new ItemStack(Items.ARROW), "Previous page", List.of("Page " + page + " of " + pages)));
        if (page + 1 < pages) getSlot(NEXT).setStack(labeled(new ItemStack(Items.ARROW), "Next page", List.of("Page " + (page + 2) + " of " + pages)));
        getSlot(BROWSE).setStack(button(Items.CHEST, "What the market sells", view == View.BROWSE));
        getSlot(SELL).setStack(button(Items.GOLD_INGOT, "Sell your stacks", view == View.SELL));
        getSlot(NOTE).setStack(noteStack());
        getSlot(CLOSE).setStack(labeled(new ItemStack(Items.BARRIER), "Close", List.of()));
    }

    private void drawConfirmBuy() {
        for (int i = 0; i < 54; i++) getSlot(i).setStack(labeled(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " ", List.of()));
        getSlot(SHOWN).setStack(labeled(shownItem(chosenItem), null, List.of(desk.priceLine(chosenItem))));
        int[] at = {ONE, SIXTEEN, SIXTY_FOUR};
        for (int i = 0; i < COUNTS.length; i++) {
            getSlot(at[i]).setStack(offered[i] < 0
                    ? labeled(new ItemStack(Items.RED_STAINED_GLASS_PANE), "Not sold by " + COUNTS[i], List.of())
                    : labeled(new ItemStack(Items.LIME_CONCRETE, COUNTS[i]), "Buy " + COUNTS[i] + " for " + Coins.show(offered[i]), List.of("From your coins")));
        }
        getSlot(BACK).setStack(labeled(new ItemStack(Items.ARROW), "Back", List.of()));
        getSlot(NOTE).setStack(noteStack());
    }

    private void drawConfirmSell() {
        var stack = chosenSlot < 0 ? ItemStack.EMPTY : viewer.getInventory().getStack(chosenSlot);
        if (stack.isEmpty()) { view = back; redraw(); return; }
        for (int i = 0; i < 54; i++) getSlot(i).setStack(labeled(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " ", List.of()));
        getSlot(SHOWN).setStack(labeled(stack.copy(), null, List.of()));
        getSlot(SELL_IT).setStack(offeredSale < 0
                ? labeled(new ItemStack(Items.RED_STAINED_GLASS_PANE), "The market does not take this", List.of())
                : labeled(new ItemStack(Items.EMERALD), "Sell for " + Coins.show(offeredSale), List.of("The stack goes; the coins come once it is saved")));
        getSlot(BACK).setStack(labeled(new ItemStack(Items.ARROW), "Back", List.of()));
        getSlot(NOTE).setStack(noteStack());
    }

    private ItemStack noteStack() {
        return note.isEmpty() ? labeled(new ItemStack(Items.BOOK), "Market", List.of("Click an item to buy, or one of your stacks to sell"))
                : labeled(new ItemStack(Items.WRITABLE_BOOK), note, List.of());
    }

    private static ItemStack shownItem(String id) {
        Item item = Registries.ITEM.get(Identifier.of(id));
        return new ItemStack(item == Items.AIR ? Items.PAPER : item);
    }

    private static ItemStack button(Item item, String title, boolean active) {
        var stack = labeled(new ItemStack(item), title, List.of());
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, active);
        return stack;
    }

    /** A display stack with its title (null keeps the item's own name) and grey lore lines. */
    private static ItemStack labeled(ItemStack stack, String title, List<String> lore) {
        if (title != null) stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title));
        if (!lore.isEmpty()) stack.set(DataComponentTypes.LORE, new LoreComponent(lore.stream().<Text>map(t -> Text.literal(t).formatted(Formatting.GRAY)).toList()));
        return stack;
    }
}
