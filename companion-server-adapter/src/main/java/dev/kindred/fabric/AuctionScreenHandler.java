package dev.kindred.fabric;

import dev.kindred.core.factions.Coins;
import dev.kindred.core.market.AuctionBook;
import dev.kindred.core.market.Auctions;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * In plain terms: the auction house as a chest: the open listings with their price, buy-now and time left, a page at a
 * time; click one to bid the least the house takes or to buy it now, after a second click to confirm. The items shown
 * are pictures of the listed items: clicking, shift-clicking or a number key never moves an item anywhere.
 *
 * Technical: M-06h's screen, a 9x6 generic container of display copies decoded from each listing's escrowed stack.
 * Every action goes through {@link AuctionHouse}'s public calls ({@code bid}, {@code cancel}, {@code book}), the same
 * as {@code /ah}, so a confirmed click is exactly the bid the command would make; the price is read again at the
 * confirming click, and a moved price asks again. {@link #onSlotClick} never calls the vanilla slot logic and every
 * click resyncs the client, so no stack moves between slots, the cursor or the player's inventory. Refusals (not a
 * faction world, {@link MarketDesk#HELD}, the house's reasons) are shown on the note button and told in chat.
 */
final class AuctionScreenHandler extends GenericContainerScreenHandler {
    static final int PAGE = 45;
    static final int PREVIOUS = 45, ALL = 46, MINE = 47, ORDERS = 48, NOTE = 49, CLOSE = 51, NEXT = 53;
    /** The confirm view's buttons and the item between them. */
    static final int BID = 20, SHOWN = 22, BUY_NOW = 24, BACK = 40;

    enum View { ALL, MINE, ORDERS, CONFIRM }

    private final AuctionHouse house;
    private final ServerPlayerEntity viewer;
    private final BooleanSupplier open;
    private final List<String> ids = new ArrayList<>();
    private View view = View.ALL;
    private View back = View.ALL;
    private int page;
    private String chosen;
    /** The bid the confirm view offers, so a moved price is caught at the confirming click. */
    private long offered;
    private String note = "";

    AuctionScreenHandler(int syncId, PlayerInventory inventory, AuctionHouse house, ServerPlayerEntity viewer, BooleanSupplier open) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, inventory, new SimpleInventory(54), 6);
        this.house = house;
        this.viewer = viewer;
        this.open = open;
        redraw();
    }

    View view() { return view; }

    String note() { return note; }

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
        if (view == View.CONFIRM) {
            switch (slot) {
                case BID, BUY_NOW -> act(slot == BUY_NOW);
                case BACK -> view = back;
                default -> { }
            }
            return;
        }
        switch (slot) {
            case PREVIOUS -> page = Math.max(0, page - 1);
            case NEXT -> page++;
            case ALL -> { view = View.ALL; page = 0; }
            case MINE -> { view = View.MINE; page = 0; }
            case ORDERS -> { view = View.ORDERS; page = 0; }
            case CLOSE -> viewer.closeHandledScreen();
            default -> {
                if (slot < PAGE && view != View.ORDERS && slot < ids.size() && house.book().listings().containsKey(ids.get(slot))) {
                    chosen = ids.get(slot);
                    back = view;
                    view = View.CONFIRM;
                    offered = Auctions.least(house.book().listings().get(chosen));
                    note = "";
                }
            }
        }
    }

    /** The confirming click: the same call {@code /ah bid} or {@code /ah buy} makes, at the price read again now. */
    private void act(boolean buyNow) {
        if (!open.getAsBoolean()) { refuse("This world does not play Factions."); return; }
        var listing = house.book().listings().get(chosen);
        if (listing == null) { refuse("That listing has closed"); view = back; return; }
        String world = viewer.getServerWorld().getRegistryKey().getValue().toString();
        if (listing.seller().equals(viewer.getUuid())) {
            if (buyNow) return;
            String refusal = house.cancel(viewer, chosen, world, this::told);
            if (refusal != null) refuse(refusal);
            else note = "Cancelling...";
            return;
        }
        if (buyNow && listing.buyNow() <= 0) return;
        long amount = buyNow ? listing.buyNow() : Auctions.least(listing);
        if (!buyNow && amount != offered) {
            offered = amount;
            refuse("The price moved: the least bid is now " + Coins.show(amount) + "; click again to bid it");
            return;
        }
        String refusal = house.bid(viewer, chosen, amount, world, this::told);
        if (refusal != null) refuse(refusal);
        else note = "Sending...";
    }

    private void refuse(String reason) {
        note = reason;
        viewer.sendMessage(Text.literal(reason).formatted(Formatting.RED));
    }

    /** The house's answer once written; the screen is redrawn if it is still open. */
    private void told(String line) {
        note = line;
        viewer.sendMessage(Text.literal(line));
        if (viewer.currentScreenHandler == this) {
            if (view == View.CONFIRM) view = back;
            redraw();
            syncState();
        }
    }

    private void redraw() {
        ids.clear();
        for (int i = 0; i < 54; i++) getSlot(i).setStack(ItemStack.EMPTY);
        if (view == View.CONFIRM) { drawConfirm(); return; }
        var book = house.book();
        if (view == View.ORDERS) {
            var orders = book.orders().values().stream().sorted(Comparator.comparing(AuctionBook.Order::id)).toList();
            page = Math.min(page, Math.max(0, (orders.size() - 1) / PAGE));
            for (int i = 0; i < PAGE && page * PAGE + i < orders.size(); i++) {
                var o = orders.get(page * PAGE + i);
                var shown = shownItem(o.key());
                ids.add(o.id());
                getSlot(i).setStack(labeled(shown, o.count() + " wanted",
                        List.of("At " + Coins.show(o.each()) + " each", o.buyer().equals(viewer.getUuid()) ? "Your order (/ah unorder " + o.id() + ")" : "Fill it: list one with /ah sell")));
            }
            drawButtons(orders.size());
            return;
        }
        var listings = book.listings().values().stream()
                .filter(l -> view != View.MINE || l.seller().equals(viewer.getUuid()))
                .sorted(Comparator.comparingLong(AuctionBook.Listing::closesAt).thenComparing(AuctionBook.Listing::id))
                .toList();
        page = Math.min(page, Math.max(0, (listings.size() - 1) / PAGE));
        for (int i = 0; i < PAGE && page * PAGE + i < listings.size(); i++) {
            var l = listings.get(page * PAGE + i);
            ids.add(l.id());
            getSlot(i).setStack(listed(l));
        }
        drawButtons(listings.size());
    }

    private void drawButtons(int count) {
        int pages = Math.max(1, (count + PAGE - 1) / PAGE);
        for (int i = PAGE; i < 54; i++) getSlot(i).setStack(labeled(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " ", List.of()));
        if (page > 0) getSlot(PREVIOUS).setStack(labeled(new ItemStack(Items.ARROW), "Previous page", List.of("Page " + page + " of " + pages)));
        if (page + 1 < pages) getSlot(NEXT).setStack(labeled(new ItemStack(Items.ARROW), "Next page", List.of("Page " + (page + 2) + " of " + pages)));
        getSlot(ALL).setStack(button(Items.CHEST, "All listings", view == View.ALL));
        getSlot(MINE).setStack(button(Items.PLAYER_HEAD, "Mine", view == View.MINE));
        getSlot(ORDERS).setStack(button(Items.PAPER, "Buy orders", view == View.ORDERS));
        getSlot(NOTE).setStack(noteStack());
        getSlot(CLOSE).setStack(labeled(new ItemStack(Items.BARRIER), "Close", List.of()));
    }

    private void drawConfirm() {
        var listing = house.book().listings().get(chosen);
        if (listing == null) { view = back; redraw(); return; }
        for (int i = 0; i < 54; i++) getSlot(i).setStack(labeled(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), " ", List.of()));
        getSlot(SHOWN).setStack(listed(listing));
        if (listing.seller().equals(viewer.getUuid())) {
            getSlot(BID).setStack(labeled(new ItemStack(Items.RED_CONCRETE), "Cancel this listing",
                    List.of(listing.bidder() == null ? "The item comes back to you" : "It has a bid: the house refuses")));
        } else {
            getSlot(BID).setStack(labeled(new ItemStack(Items.LIME_CONCRETE), "Bid " + Coins.show(offered), List.of("The least the house takes now", "Held from your coins until outbid")));
            if (listing.buyNow() > 0) getSlot(BUY_NOW).setStack(labeled(new ItemStack(Items.EMERALD), "Buy now for " + Coins.show(listing.buyNow()), List.of("Wins it at once")));
        }
        getSlot(BACK).setStack(labeled(new ItemStack(Items.ARROW), "Back", List.of()));
        getSlot(NOTE).setStack(noteStack());
    }

    private ItemStack noteStack() {
        return note.isEmpty() ? labeled(new ItemStack(Items.BOOK), "Auction house", List.of("Click a listing to bid or buy"))
                : labeled(new ItemStack(Items.WRITABLE_BOOK), note, List.of());
    }

    /** A display copy of the listed stack, with its price, buy-now, time left and seller. */
    private ItemStack listed(AuctionBook.Listing l) {
        ItemStack shown;
        try {
            shown = ItemStack.fromNbt(viewer.getRegistryManager(), StringNbtReader.parse(l.item())).map(ItemStack::copy).orElse(null);
        } catch (Exception unreadable) {
            shown = null;
        }
        if (shown == null) shown = shownItem(l.key());
        var lore = new ArrayList<Text>();
        lore.add(Text.literal(l.bidder() == null ? "From " + Coins.show(l.start()) : "Bid " + Coins.show(l.bid())).formatted(Formatting.GOLD));
        if (l.buyNow() > 0) lore.add(Text.literal("Buy now " + Coins.show(l.buyNow())).formatted(Formatting.GREEN));
        lore.add(Text.literal(Math.max(0, (l.closesAt() - house.now()) / 60_000) + " min left").formatted(Formatting.GRAY));
        var seller = viewer.getServer().getUserCache() == null ? null : viewer.getServer().getUserCache().getByUuid(l.seller()).orElse(null);
        lore.add(Text.literal("Seller: " + (l.seller().equals(viewer.getUuid()) ? "you" : seller != null ? seller.getName() : l.seller().toString().substring(0, 8))).formatted(Formatting.GRAY));
        lore.add(Text.literal(l.id()).formatted(Formatting.DARK_GRAY));
        shown.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return shown;
    }

    private static ItemStack shownItem(String key) {
        var id = net.minecraft.util.Identifier.tryParse(key.contains("[") ? key.substring(0, key.indexOf('[')) : key);
        Item item = id == null ? Items.PAPER : net.minecraft.registry.Registries.ITEM.get(id);
        return new ItemStack(item == Items.AIR ? Items.PAPER : item);
    }

    private static ItemStack button(Item item, String title, boolean active) {
        var stack = labeled(new ItemStack(item), title, List.of());
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, active);
        return stack;
    }

    private static ItemStack labeled(ItemStack stack, String title, List<String> lore) {
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title));
        if (!lore.isEmpty()) stack.set(DataComponentTypes.LORE, new LoreComponent(lore.stream().<Text>map(t -> Text.literal(t).formatted(Formatting.GRAY)).toList()));
        return stack;
    }
}
