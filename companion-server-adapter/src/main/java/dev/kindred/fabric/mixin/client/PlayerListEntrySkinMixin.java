package dev.kindred.fabric.mixin.client;

import com.mojang.authlib.GameProfile;
import dev.kindred.core.CanonicalRosterSkins;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Renders the skin the server chose for a native body from its skins folder, else the bundled roster skin for
 * bodies whose profile carries the Kindred skin marker.
 */
@Mixin(PlayerListEntry.class)
abstract class PlayerListEntrySkinMixin {
    @Shadow @Final private GameProfile profile;

    @Inject(method = "getSkinTextures", at = @At("HEAD"), cancellable = true)
    private void kindred$rosterSkin(CallbackInfoReturnable<SkinTextures> cir) {
        var chosen = dev.kindred.fabric.KindredClientSkins.texturesFor(profile.getId());
        if (chosen != null) {
            cir.setReturnValue(chosen);
            return;
        }
        var markers = profile.getProperties().get(CanonicalRosterSkins.PROFILE_PROPERTY);
        if (markers.isEmpty()) return;
        CanonicalRosterSkins.forKey(markers.iterator().next().value()).ifPresent(skin -> cir.setReturnValue(new SkinTextures(
                Identifier.of("kindred", skin.clientAssetPath()), null, null, null,
                skin.slim() ? SkinTextures.Model.SLIM : SkinTextures.Model.WIDE, true)));
    }
}
