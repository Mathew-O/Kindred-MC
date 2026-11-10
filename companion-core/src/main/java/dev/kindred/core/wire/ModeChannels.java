package dev.kindred.core.wire;

import dev.kindred.core.match.GameModes;

import java.util.List;
import java.util.Set;

/**
 * In plain terms: which channels each way of playing uses: Co-op its team and private messages; Manhunt each side's
 * own channel; Speedrun only public posts; Builder public plots and private designs; Factions all of them. The
 * operators' channel is open in every world, and every match's own channel (its start, its result) in every world that
 * plays matches, as a duel may be played in any.
 *
 * Technical: F-34's per-mode table for the built-in channels; a registered channel is open in the modes it names (none
 * named: every mode). A world with no mode chosen keeps every channel, as before modes existed.
 * {@link Channel#PUBLIC} in Co-op and Manhunt carries the mode's own announcements and earshot notices.
 */
public final class ModeChannels {
    private ModeChannels() { }

    public static Set<Channel> of(GameModes.WorldMode mode) {
        var out = new java.util.LinkedHashSet<Channel>();
        if (mode == null || mode == GameModes.WorldMode.FACTIONS) {
            out.addAll(Channel.values()); // Every channel, as before modes existed, and every one in Factions.
            return java.util.Collections.unmodifiableSet(out);
        }
        out.addAll(switch (mode) {
            case COOP_SURVIVAL -> List.of(Channel.TEAM, Channel.PRIVATE, Channel.MATCH, Channel.PUBLIC, Channel.OPERATOR);
            case MANHUNT -> List.of(Channel.SIDE, Channel.MATCH, Channel.PRIVATE, Channel.PUBLIC, Channel.OPERATOR);
            case SPEEDRUN -> List.of(Channel.MATCH, Channel.PUBLIC, Channel.OPERATOR);
            case BUILDER -> List.of(Channel.MATCH, Channel.PUBLIC, Channel.PRIVATE, Channel.OPERATOR);
            case FACTIONS -> List.of();
        });
        // A registered channel is open in the modes it named, or in every mode when it named none.
        for (Channel c : Channel.values()) if (!c.builtIn() && (c.modes().isEmpty() || c.modes().contains(mode))) out.add(c);
        return java.util.Collections.unmodifiableSet(out);
    }

    public static boolean allows(GameModes.WorldMode mode, Channel channel) { return of(mode).contains(channel); }
}
