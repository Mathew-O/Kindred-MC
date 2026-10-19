package dev.kindred.fabric;

import dev.kindred.core.CanonicalRoster;
import dev.kindred.core.progression.PlaystyleProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

/**
 * Virtual 9x6 control deck (GUI-01). Clicks never move items into player inventories (PLY-04).
 */
final class KindredControlScreenHandler extends GenericContainerScreenHandler {

    static final int SLOT_SPEEDRUN = 19;
    static final int SLOT_COLLAB = 21;
    static final int SLOT_SEED_LOCATOR = 22;
    static final int SLOT_IDLE_RADIUS = 34;
    static final int SLOT_CLOSE = 53;

    private final PlayerRuntime runtime;
    private final ServerPlayerEntity operator;
    private UUID selectedBotId;

    KindredControlScreenHandler(int syncId, PlayerInventory playerInventory, PlayerRuntime runtime, ServerPlayerEntity operator) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, new SimpleInventory(54), 6);
        this.runtime = runtime;
        this.operator = operator;
        this.selectedBotId = runtime.firstConnectedBotId().orElse(null);
        refreshVirtualSlots();
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || slotIndex < 0 || slotIndex >= 54) {
            return;
        }
        if (slotIndex == SLOT_CLOSE) {
            serverPlayer.closeHandledScreen();
            return;
        }
        if (selectedBotId == null) {
            return;
        }
        switch (slotIndex) {
            case SLOT_SPEEDRUN -> runtime.togglePlaystyleForBot(operator.getCommandSource(), selectedBotId);
            case SLOT_COLLAB -> runtime.toggleCollaborationForBot(operator.getCommandSource(), selectedBotId);
            case SLOT_SEED_LOCATOR -> runtime.toggleSeedLocatorForBot(operator.getCommandSource(), selectedBotId);
            case SLOT_IDLE_RADIUS -> runtime.adjustIdleRadiusForBot(operator.getCommandSource(), selectedBotId, button == 0 ? 4 : -4);
            case 2, 4, 6 -> selectedBotId = runtime.canonicalBotIdForControlSlot(slotIndex).orElse(selectedBotId);
            default -> { }
        }
        refreshVirtualSlots();
        sendContentUpdates();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    private void refreshVirtualSlots() {
        for (int i = 0; i < 54; i++) {
            getSlot(i).setStack(decorativeStack(i));
        }
    }

    private ItemStack decorativeStack(int slot) {
        if (selectedBotId == null) {
            return labeled(Items.GRAY_STAINED_GLASS_PANE, "No AI player connected", List.of("Spawn Kindred, Sol, or Vesp first."));
        }
        var settings = runtime.runtimeSettings(selectedBotId);
        return switch (slot) {
            case 2 -> rosterStack(CanonicalRoster.KINDRED_NAME, settings, slot);
            case 4 -> rosterStack(CanonicalRoster.SOL_NAME, settings, slot);
            case 6 -> rosterStack(CanonicalRoster.VESP_NAME, settings, slot);
            case SLOT_SPEEDRUN -> toggleStack(
                    settings.playstyleProfile() == PlaystyleProfile.SPEEDRUN_ANY_PERCENT,
                    Items.CLOCK,
                    "Speedrun Mode",
                    settings.playstyleProfile().name());
            case SLOT_COLLAB -> toggleStack(
                    settings.collaborationMode() == dev.kindred.core.team.CollaborationMode.COOP_TEAM,
                    Items.PLAYER_HEAD,
                    "Collaboration",
                    settings.collaborationMode().name());
            case SLOT_SEED_LOCATOR -> toggleStack(
                    settings.seedLocatorEnabled(),
                    Items.MAP,
                    "Seed Structure Locator",
                    settings.seedLocatorEnabled() ? "ON (assisted)" : "OFF (unassisted)");
            case SLOT_IDLE_RADIUS -> labeled(Items.COMPASS, "Idle Radius: " + settings.idleRadiusBlocks() + "m",
                    List.of("Left-click +4, right-click -4", "Mode: " + settings.playstyleProfile()));
            case SLOT_CLOSE -> labeled(Items.BARRIER, "Close", List.of());
            default -> labeled(Items.GRAY_STAINED_GLASS_PANE, " ", List.of());
        };
    }

    private ItemStack rosterStack(String name, CanonicalRoster.RuntimeSettings settings, int slot) {
        boolean selected = runtime.canonicalBotIdForControlSlot(slot)
                .map(id -> id.equals(selectedBotId))
                .orElse(false);
        return toggleStack(selected, Items.NAME_TAG, name + (selected ? " (selected)" : ""), settings.playstyleProfile().name());
    }

    private static ItemStack toggleStack(boolean active, net.minecraft.item.Item item, String title, String detail) {
        var stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title).formatted(active ? Formatting.GREEN : Formatting.GRAY));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(detail).formatted(Formatting.DARK_GRAY))));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, active);
        return stack;
    }

    private static ItemStack labeled(net.minecraft.item.Item item, String title, List<String> loreLines) {
        var stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title));
        if (!loreLines.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(loreLines.stream()
                    .<Text>map(t -> Text.literal(t).formatted(Formatting.DARK_GRAY))
                    .toList()));
        }
        return stack;
    }
}
