package dev.kindred.fabric;

import dev.kindred.core.CanonicalRosterSkins;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Optional local renderer. Networked companions use the vanilla fallback on every client. */
public final class KindredClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(KindredMod.COMPANION, CompanionRenderer::new);
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
