package dev.kindred.fabric;

import dev.kindred.core.CanonicalRosterSkins;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Optional client enhancements: the AI player panel on a key (K by default), bundled roster skins for native
 * bodies, and the legacy mob renderer. Servers work with vanilla clients; these only add presentation.
 */
public final class KindredClient implements ClientModInitializer {
    private static KeyBinding openPanel;
    private static boolean panelRequested;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(KindredMod.COMPANION, CompanionRenderer::new);
        openPanel = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kindred.panel", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K, "key.categories.kindred"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPanel.wasPressed()) {
                if (client.player == null) return;
                if (client.currentScreen instanceof KindredPanelScreen) continue;
                if (ClientPlayNetworking.canSend(KindredPanelPayloads.Request.ID)) {
                    panelRequested = true;
                    ClientPlayNetworking.send(new KindredPanelPayloads.Request());
                } else {
                    client.player.sendMessage(Text.literal("This server does not run Kindred, so there are no AI players to manage."), true);
                }
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(KindredPanelPayloads.State.ID, (payload, context) -> {
            var client = context.client();
            if (client.currentScreen instanceof KindredPanelScreen screen) {
                screen.accept(payload.json(), payload.message());
            } else if (panelRequested) {
                panelRequested = false;
                client.setScreen(new KindredPanelScreen(payload.json(), payload.message()));
            }
        });
    }

    private static final class CompanionRenderer
            extends MobEntityRenderer<CompanionEntity, VillagerResemblingModel<CompanionEntity>> {
        private CompanionRenderer(EntityRendererFactory.Context context) {
            super(context, new VillagerResemblingModel<>(context.getPart(EntityModelLayers.VILLAGER)), 0.5f);
        }

        private static final Identifier FALLBACK = Identifier.ofVanilla("textures/entity/villager/villager.png");

        @Override
        public Identifier getTexture(CompanionEntity entity) {
            Text custom = entity.getCustomName();
            if (custom != null) {
                return CanonicalRosterSkins.forDisplayName(custom.getString())
                        .map(skin -> Identifier.of("kindred", skin.clientAssetPath()))
                        .orElse(FALLBACK);
            }
            return FALLBACK;
        }
    }
}
