package dev.kindred.fabric.mixin.client;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * In plain terms: lets the Factions panel see whether a title (a match countdown, say) is on screen, so it can step
 * aside while it shows.
 *
 * Technical: Read-only access to the in-game HUD's title and its remaining ticks (M-06l, the HUD's "hidden during
 * titles" setting). Nothing is changed.
 */
@Mixin(InGameHud.class)
public interface InGameHudTitleAccessor {
    @Accessor("title")
    Text kindred$title();

    @Accessor("titleRemainTicks")
    int kindred$titleRemainTicks();
}
