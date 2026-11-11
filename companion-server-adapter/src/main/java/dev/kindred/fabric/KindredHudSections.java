package dev.kindred.fabric;

import dev.kindred.core.ui.HudPanel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * In plain terms: the list of who adds a section to a player's panel. Factions adds its own here, and a mod's integration
 * adds its own the same way; the panel is every section any of them gives for that player, in order.
 *
 * Technical: The HUD's section sources (the owner's mod-support decision 7, M-06l), on the server thread. A source is
 * registered once under its owner, and its sections' ids begin with that owner and a dot, so one owner's sections are never
 * another's. A source is asked for one viewer's sections only, so what reaches a client is what that viewer may know; a
 * source that fails, or names a section not its own, is logged and left out, and the rest of the panel is sent.
 */
final class KindredHudSections {
    /** A source of one viewer's sections: none when it has nothing for that viewer. */
    interface Source {
        List<HudPanel.Section> sections(ServerPlayerEntity viewer);
    }

    private final Map<String, Source> sources = new LinkedHashMap<>();

    /** Registers {@code source} for {@code owner}; an owner registered already is refused. */
    void register(String owner, Source source) {
        if (owner == null || !owner.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid section owner " + owner);
        if (sources.putIfAbsent(owner, java.util.Objects.requireNonNull(source, "source")) != null) {
            throw new IllegalStateException("A section source for " + owner + " is registered already");
        }
    }

    /** The owners with a source, in the order they registered. */
    List<String> owners() { return List.copyOf(sources.keySet()); }

    /** {@code viewer}'s panel: every source's sections for it, each source's own only; laid out by {@link HudPanel#layout}. */
    List<HudPanel.Section> panel(ServerPlayerEntity viewer) {
        var out = new ArrayList<HudPanel.Section>();
        for (var entry : sources.entrySet()) {
            List<HudPanel.Section> given;
            try {
                given = entry.getValue().sections(viewer);
            } catch (RuntimeException failure) {
                KindredMod.LOGGER.error("The panel's {} sections failed for {}; left out", entry.getKey(), viewer.getName().getString(), failure);
                continue;
            }
            if (given == null) continue;
            for (var section : given) {
                if (section.id().startsWith(entry.getKey() + ".")) out.add(section);
                else KindredMod.LOGGER.warn("Section {} is not {}'s; left out", section.id(), entry.getKey());
            }
        }
        return List.copyOf(out);
    }
}
